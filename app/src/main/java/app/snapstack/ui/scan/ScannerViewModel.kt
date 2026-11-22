package app.snapstack.ui.scan

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.snapstack.AppGraph
import app.snapstack.data.ScanMode
import app.snapstack.data.ScanSpeed
import app.snapstack.model.Game
import app.snapstack.model.Languages
import app.snapstack.model.RipSession
import app.snapstack.model.Card
import app.snapstack.scan.Box
import app.snapstack.scan.Detection
import app.snapstack.scan.Grading
import app.snapstack.scan.SlabReader
import app.snapstack.scan.OnePieceDonKey
import app.snapstack.scan.TextMatch
import app.snapstack.scan.CardTextParser
import app.snapstack.scan.FrameVoter
import app.snapstack.scan.ItemStatus
import app.snapstack.scan.OcrFrame
import app.snapstack.scan.ScanKey
import app.snapstack.scan.TextAnalyzer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

data class LiveDetection(
    val label: String, val box: Box?, val frameW: Int, val frameH: Int, val game: Game,
    /** The read matches a card on the phone and its printed name: accepted after a single frame. */
    val verified: Boolean = false,
)

data class ScanUi(
    val mode: ScanMode = ScanMode.RAPID,
    val game: Game? = null,
    val speed: ScanSpeed = ScanSpeed.BALANCED,
    val torch: Boolean = false,
    val detection: LiveDetection? = null,
    val ocrMs: Long = 0,
    val fps: Float = 0f,
    val message: String? = null,
    val snapping: Boolean = false,
    val singleUid: Long? = null,
    val binderRows: Int = 3,
    val binderCols: Int = 3,
    val binderLive: Int = 0,
    val lastUndo: List<Long> = emptyList(),
    val flash: Long = 0,
    /** The camera image is blurry (card moving or lens refocusing): those frames are skipped. */
    val blurry: Boolean = false,
    /** Slab mode: what the label says so far ("PSA 10"), shown while reading. */
    val slab: String? = null,
)

class ScannerViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = AppGraph.instance
    private val settings = graph.settings
    val session = graph.session
    val items = session.items
    val sticky = session.sticky
    val showStats = settings.showOcrStats.flow

    private val _ui = MutableStateFlow(
        ScanUi(
            mode = settings.mode(),
            game = Game.fromId(settings.scanGame.value),
            speed = settings.speed(),
        ).withGrid(settings.binderGrid.value)
    )
    val ui: StateFlow<ScanUi> = _ui.asStateFlow()

    private val voter = FrameVoter(requiredVotes = settings.speed().votes)
    private val parseExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var viewW = 0
    @Volatile private var viewH = 0
    val analyzer = TextAnalyzer(
        parseExecutor,
        roiProvider = { w, h ->
            val kind = guideKind()
            Guide.inFrame(viewW, viewH, w, h, kind, if (kind == GuideKind.CARD) 0.08f else 0.04f)
        },
        onBlur = ::onBlur,
        cardProvider = { w, h ->
            when (guideKind()) {
                GuideKind.BINDER -> null
                GuideKind.SLAB -> Guide.inFrame(viewW, viewH, w, h, GuideKind.SLAB, 0f)?.let { Guide.cardInSlab(it) }
                GuideKind.CARD -> Guide.inFrame(viewW, viewH, w, h, GuideKind.CARD, 0f)
            }
        },
        onFrame = ::onFrame,
    )
    private var lastFrameAt = 0L

    /** Asks the camera to refocus on the guide when the picture stays blurry. */
    private val _focusRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val focusRequests: SharedFlow<Unit> = _focusRequests
    @Volatile private var blurSince = 0L
    @Volatile private var lastFocusRequest = 0L

    /** Offline lookups of recent reads (key id → cards on the phone), so each frame costs ~nothing. */
    private val peekCache = object : LinkedHashMap<String, Pair<Long, List<Card>>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<Card>>>?) = size > 64
    }

    /** The preview's size on screen, so the scanner can ignore text outside the guide. */
    fun setViewSize(w: Int, h: Int) {
        viewW = w; viewH = h
    }

    private fun region(frame: OcrFrame, binder: Boolean, grow: Float) =
        Guide.inFrame(viewW, viewH, frame.width, frame.height, binder, grow)

    private fun guideKind() = when (_ui.value.mode) {
        ScanMode.BINDER -> GuideKind.BINDER
        ScanMode.SLAB -> GuideKind.SLAB
        else -> GuideKind.CARD
    }
    private var lastCommittedId: String? = null
    private var tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 55) }.getOrNull()

    init {
        analyzer.paused = false
        warmUp(_ui.value.game)
    }

    /** Set lists the resolver needs are fetched as soon as the scanner opens. */
    private fun warmUp(game: Game?) {
        graph.cards.warmUp(
            game?.let { listOf(it) } ?: listOf(Game.POKEMON, Game.ONE_PIECE, Game.DRAGON_BALL, Game.RIFTBOUND)
        )
    }

    private fun onBlur(blurry: Boolean) {
        val now = SystemClock.elapsedRealtime()
        blurSince = if (blurry) (if (blurSince == 0L) now else blurSince) else 0L
        _ui.update { it.copy(blurry = blurry) }
    }

    private fun maybeRefocus(now: Long) {
        if (blurSince != 0L && now - blurSince > 900 && now - lastFocusRequest > 2500) {
            lastFocusRequest = now
            _focusRequests.tryEmit(Unit)
        }
    }

    private fun peek(key: ScanKey, now: Long): List<Card> {
        peekCache[key.id]?.let { (at, cards) -> if (now - at < 3000) return cards }
        val cards = graph.cards.peek(key)
        peekCache[key.id] = now to cards
        return cards
    }

    // ------------------------------------------------------------------ live frames (background thread)

    private fun onFrame(frame: OcrFrame) {
        val now = SystemClock.elapsedRealtime()
        val dt = now - lastFrameAt
        lastFrameAt = now
        val state = _ui.value
        val fps = if (dt in 1..2000) state.fps * 0.8f + (1000f / dt) * 0.2f else state.fps
        val ocr = if (state.ocrMs == 0L) frame.tookMs else (state.ocrMs * 4 + frame.tookMs) / 5

        if (state.mode == ScanMode.BINDER) {
            val readable = CardTextParser.parseGrid(frame, state.binderRows, state.binderCols, state.game, region(frame, true, 0.04f))
                .count { it != null }
            _ui.update { it.copy(fps = fps, ocrMs = ocr, binderLive = readable, detection = null) }
            return
        }

        maybeRefocus(now)
        if (state.mode == ScanMode.SLAB) {
            onSlabFrame(frame, state, now, fps, ocr); return
        }
        val inside = region(frame, false, 0.1f)
        val candidates = CardTextParser.parse(frame, state.game)
            .filter { it.score >= MIN_SCORE && Guide.contains(inside, it.box) }
            .take(3)
        val hints = frame.lines.map { it.text }
        val nameLines = hints.count { line -> line.count { it.isLetter() } >= 3 }

        // Check the reads against cards already on the phone: a number whose card name is also printed
        // here is almost certainly right, so it needn't wait for more frames. A number that points at a
        // different card than the printed name is a likely misread, so it must hold for longer.
        var best: Detection? = null
        var verified = false
        var suspicious = false
        for (d in candidates) {
            val known = peek(d.key, now)
            val match = known.isNotEmpty() && known.any { TextMatch.cardNameScore(it.name, hints) >= 0.75 }
            if (match) { best = d; verified = true; break }
            if (best == null) {
                best = d
                suspicious = known.isNotEmpty() && nameLines >= 3 && d.key !is OnePieceDonKey
            }
        }
        val pick = best
        _ui.update {
            it.copy(
                fps = fps, ocrMs = ocr,
                detection = pick?.let { d -> LiveDetection(d.key.label, d.box, frame.width, frame.height, d.key.game, verified) },
            )
        }
        val votes = state.speed.votes
        val required = when {
            verified -> (votes - 1).coerceAtLeast(1)
            suspicious -> votes + 1
            else -> votes
        }
        val commitId = voter.offer(pick?.key?.id, now, required) ?: return
        if (pick != null && pick.key.id == commitId) commit(pick.key, hints, frame.thumb)
    }

    /**
     * Slab mode: the label gives grader, grade and cert; the card is read from its own number through the
     * case when possible (most precise), otherwise from the label's "#number" and name.
     */
    private fun onSlabFrame(frame: OcrFrame, state: ScanUi, now: Long, fps: Float, ocr: Long) {
        val inside = Guide.inFrame(viewW, viewH, frame.width, frame.height, GuideKind.SLAB, 0.06f)
        val lines = frame.lines.filter { Guide.contains(inside, it.box) }
        val slab = SlabReader.read(lines)
        val cardKey = slab?.let {
            CardTextParser.parseLines(lines, state.game ?: it.game).firstOrNull { d -> d.score >= MIN_SCORE }?.key
                ?: SlabReader.labelKey(it, state.game)
        }
        _ui.update {
            it.copy(
                fps = fps, ocrMs = ocr, slab = slab?.display,
                detection = if (slab != null && cardKey != null) {
                    LiveDetection("${slab.display} · ${cardKey.label}", null, frame.width, frame.height, cardKey.game, slab.cert != null)
                } else null,
            )
        }
        if (slab == null || cardKey == null || slab.grade == null) { voter.offer(null, now); return }
        // A slab is a big-ticket item: always want two agreeing reads of grade, cert and card.
        val commitId = voter.offer("${slab.id}|${cardKey.id}", now, maxOf(2, state.speed.votes)) ?: return
        if (commitId.isNotEmpty()) commit(cardKey, lines.map { it.text }, frame.thumb, Grading.from(slab))
    }

    private fun commit(
        scanned: ScanKey, hints: List<String> = emptyList(), thumb: app.snapstack.scan.CardThumb? = null,
        grading: Grading? = null,
    ) {
        val key = if (scanned is app.snapstack.scan.OnePieceDonKey && scanned.setHint == null) {
            // DON!! cards don't say which set they're from: assume the pack/deck being scanned.
            scanned.copy(setHint = session.items.value.firstOrNull { it.card?.game == Game.ONE_PIECE && it.card.number != "DON!!" }?.card?.setCode)
        } else scanned
        lastCommittedId = key.id
        feedback()
        val uid = session.add(key, hints, thumb, grading = grading)
        _ui.update { it.copy(flash = SystemClock.elapsedRealtime(), message = null) }
        if (_ui.value.mode == ScanMode.SINGLE || _ui.value.mode == ScanMode.SLAB) {
            analyzer.paused = true
            _ui.update { it.copy(singleUid = uid) }
        }
    }

    // ------------------------------------------------------------------ user actions

    fun setMode(mode: ScanMode) {
        settings.scanMode.value = mode.name
        analyzer.paused = false
        voter.reset()
        _ui.update { it.copy(mode = mode, singleUid = null, detection = null, message = null) }
    }

    fun setGame(game: Game?) {
        settings.scanGame.value = game?.id ?: "auto"
        voter.reset()
        warmUp(game)
        _ui.update { it.copy(game = game, detection = null) }
    }

    fun setSpeed(speed: ScanSpeed) {
        settings.scanSpeed.value = speed.name
        voter.requiredVotes = speed.votes
        _ui.update { it.copy(speed = speed) }
    }

    fun setGrid(spec: String) {
        settings.binderGrid.value = spec
        _ui.update { it.withGrid(spec) }
    }

    fun setTorch(on: Boolean) = _ui.update { it.copy(torch = on) }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    /** Closes the single-card sheet and resumes scanning without re-reading the card still in view. */
    fun nextCard() {
        lastCommittedId?.let { voter.markCommitted(it, SystemClock.elapsedRealtime()) }
        _ui.update { it.copy(singleUid = null) }
        analyzer.paused = false
    }

    fun typed(text: String): Boolean {
        val key = CardTextParser.parseTyped(text, _ui.value.game) ?: run {
            _ui.update { it.copy(message = "Pick a game first to look up a card by name") }
            return false
        }
        commit(key)
        return true
    }

    fun undoBinder() {
        session.removeAll(_ui.value.lastUndo)
        _ui.update { it.copy(lastUndo = emptyList(), message = null) }
    }

    /** Takes a full-resolution photo: one stubborn card (Rapid/Single) or a whole binder page. */
    fun snap(capture: ImageCapture?) {
        if (capture == null || _ui.value.snapping) return
        _ui.update { it.copy(snapping = true, message = null) }
        capture.takePicture(parseExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bitmap = runCatching { image.toBitmap() }.getOrNull()
                image.close()
                if (bitmap == null) {
                    _ui.update { it.copy(snapping = false, message = "Couldn't read the photo") }
                    return
                }
                viewModelScope.launch {
                    val frame = runCatching { TextAnalyzer.recognize(bitmap, rotation) }.getOrNull()
                    val thumb = frame?.let { f ->
                        val kind = guideKind()
                        Guide.inFrame(viewW, viewH, f.width, f.height, kind, 0f)
                            ?.let { if (kind == GuideKind.SLAB) Guide.cardInSlab(it) else it }
                            ?.takeIf { kind != GuideKind.BINDER }?.let { guide ->
                            runCatching {
                                app.snapstack.scan.ArtMatcher.thumbFromBitmap(
                                    bitmap, rotation, app.snapstack.scan.ArtMatcher.captureArea(guide, f.width, f.height),
                                )
                            }.getOrNull()
                        }
                    }
                    bitmap.recycle()
                    handleStill(frame?.copy(thumb = thumb))
                }
            }

            override fun onError(exception: ImageCaptureException) {
                _ui.update { it.copy(snapping = false, message = "Camera error: ${exception.message}") }
            }
        })
    }

    private fun handleStill(frame: OcrFrame?) {
        val state = _ui.value
        if (frame == null) {
            _ui.update { it.copy(snapping = false, message = "Text recognition failed") }; return
        }
        if (state.mode == ScanMode.BINDER) {
            val keys = CardTextParser.parseGrid(frame, state.binderRows, state.binderCols, state.game, region(frame, true, 0.04f))
                .mapNotNull { it?.takeIf { d -> d.score >= MIN_SCORE - 0.1 }?.key }
            if (keys.isEmpty()) {
                _ui.update { it.copy(snapping = false, message = "No card numbers found. Fill the frame with the page and try again.") }
                return
            }
            feedback()
            val uids = keys.map { session.add(it) }
            _ui.update {
                it.copy(
                    snapping = false, lastUndo = uids, flash = SystemClock.elapsedRealtime(),
                    message = "Added ${keys.size} of ${state.binderRows * state.binderCols} pockets",
                )
            }
            return
        }
        if (state.mode == ScanMode.SLAB) {
            val box = Guide.inFrame(viewW, viewH, frame.width, frame.height, GuideKind.SLAB, 0.1f)
            val lines = frame.lines.filter { Guide.contains(box, it.box) }
            val slab = SlabReader.read(lines)
            val key = slab?.let {
                CardTextParser.parseLines(lines, state.game ?: it.game).firstOrNull { d -> d.score >= 0.4 }?.key
                    ?: SlabReader.labelKey(it, state.game)
            }
            _ui.update { it.copy(snapping = false) }
            if (slab == null || key == null) {
                _ui.update { it.copy(message = if (slab == null) "Couldn't read a grading label. Fit the whole slab in the frame." else "Read ${slab.display}, but not the card. Try Type #.") }
                return
            }
            commit(key, lines.map { it.text }, frame.thumb, Grading.from(slab))
            return
        }
        val inside = region(frame, false, 0.2f)
        val best = CardTextParser.parse(frame, state.game).firstOrNull { it.score >= 0.4 && Guide.contains(inside, it.box) }
        _ui.update { it.copy(snapping = false) }
        if (best == null) {
            _ui.update { it.copy(message = "Couldn't find a card number. Move closer or use Type #.") }
            return
        }
        voter.markCommitted(best.key.id, SystemClock.elapsedRealtime())
        commit(best.key, frame.lines.map { it.text }, frame.thumb)
    }

    fun saveSession(onDone: (Int) -> Unit) {
        val items = session.readyItems()
        if (items.isEmpty()) return
        val cost = session.packCost.value
        val label = session.label.value.trim()
        val rip = if (cost != null || label.isNotEmpty()) RipSession(
            startedAt = session.startedAt,
            label = label.ifEmpty { "Pack opening" },
            packCost = cost,
            cardCount = items.sumOf { it.qty },
            value = items.sumOf { it.total },
            game = items.groupingBy { it.card!!.game }.eachCount().maxByOrNull { it.value }?.key,
        ) else null
        val collectionItems = session.toCollectionItems()
        viewModelScope.launch {
            graph.collection.saveSession(collectionItems, rip)
            session.clear()
            val n = collectionItems.sumOf { it.qty }
            _ui.update { it.copy(message = "Added $n cards to your collection", lastUndo = emptyList()) }
            onDone(n)
        }
    }

    fun pendingCount(): Int = items.value.count { it.status == ItemStatus.RESOLVING }

    private fun feedback() {
        if (settings.haptics.value) vibrate()
        if (settings.beep.value) runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60) }
    }

    private fun vibrate() {
        val ctx = getApplication<Application>()
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            else vibrator.vibrate(VibrationEffect.createOneShot(25, 120))
        }
    }

    override fun onCleared() {
        analyzer.close()
        parseExecutor.shutdown()
        tone?.release(); tone = null
    }

    companion object {
        const val MIN_SCORE = 0.5
        val grids = listOf("2x2", "3x3", "3x4", "4x4")
    }
}

private fun ScanUi.withGrid(spec: String): ScanUi {
    val parts = spec.split('x').mapNotNull { it.toIntOrNull() }
    return if (parts.size == 2) copy(binderCols = parts[0], binderRows = parts[1]) else this
}
