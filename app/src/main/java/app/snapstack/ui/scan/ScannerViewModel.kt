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
import app.snapstack.model.RipSession
import app.snapstack.scan.Box
import app.snapstack.scan.CardTextParser
import app.snapstack.scan.FrameVoter
import app.snapstack.scan.ItemStatus
import app.snapstack.scan.OcrFrame
import app.snapstack.scan.ScanKey
import app.snapstack.scan.TextAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

data class LiveDetection(val label: String, val box: Box?, val frameW: Int, val frameH: Int, val game: Game)

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
    val analyzer = TextAnalyzer(parseExecutor, ::onFrame)
    private var lastFrameAt = 0L
    @Volatile private var viewW = 0
    @Volatile private var viewH = 0

    /** The preview's size on screen, so the scanner can ignore text outside the guide. */
    fun setViewSize(w: Int, h: Int) {
        viewW = w; viewH = h
    }

    private fun region(frame: OcrFrame, binder: Boolean, grow: Float) =
        Guide.inFrame(viewW, viewH, frame.width, frame.height, binder, grow)
    private var lastCommittedId: String? = null
    private var tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 55) }.getOrNull()

    init {
        analyzer.paused = false
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

        val inside = region(frame, false, 0.1f)
        val best = CardTextParser.parse(frame, state.game)
            .firstOrNull { it.score >= MIN_SCORE && Guide.contains(inside, it.box) }
        _ui.update {
            it.copy(
                fps = fps, ocrMs = ocr,
                detection = best?.let { d -> LiveDetection(d.key.label, d.box, frame.width, frame.height, d.key.game) },
            )
        }
        val commitId = voter.offer(best?.key?.id, now) ?: return
        if (best != null && best.key.id == commitId) commit(best.key)
    }

    private fun commit(scanned: ScanKey) {
        val key = if (scanned is app.snapstack.scan.OnePieceDonKey && scanned.setHint == null) {
            // DON!! cards don't say which set they're from: assume the pack/deck being scanned.
            scanned.copy(setHint = session.items.value.firstOrNull { it.card?.game == Game.ONE_PIECE && it.card.number != "DON!!" }?.card?.setCode)
        } else scanned
        lastCommittedId = key.id
        feedback()
        val uid = session.add(key)
        _ui.update { it.copy(flash = SystemClock.elapsedRealtime(), message = null) }
        if (_ui.value.mode == ScanMode.SINGLE) {
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
                    bitmap.recycle()
                    handleStill(frame)
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
        val inside = region(frame, false, 0.2f)
        val best = CardTextParser.parse(frame, state.game).firstOrNull { it.score >= 0.4 && Guide.contains(inside, it.box) }
        _ui.update { it.copy(snapping = false) }
        if (best == null) {
            _ui.update { it.copy(message = "Couldn't find a card number. Move closer or use Type #.") }
            return
        }
        voter.markCommitted(best.key.id, SystemClock.elapsedRealtime())
        commit(best.key)
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
