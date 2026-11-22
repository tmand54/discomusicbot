package app.snapstack.data.importer

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import app.snapstack.data.CardRepository
import app.snapstack.data.CollectionRepository
import app.snapstack.data.db.Database
import app.snapstack.data.source.YugiohSource
import app.snapstack.model.Card
import app.snapstack.model.CardSet
import app.snapstack.model.CollectionItem
import app.snapstack.model.Game
import app.snapstack.model.normalizeNumber
import app.snapstack.scan.LorcanaKey
import app.snapstack.scan.MagicKey
import app.snapstack.scan.NameKey
import app.snapstack.scan.OnePieceDonKey
import app.snapstack.scan.OnePieceKey
import app.snapstack.scan.PokemonKey
import app.snapstack.scan.RiftboundKey
import app.snapstack.scan.ScanKey
import app.snapstack.scan.TextMatch
import app.snapstack.scan.YugiohKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

data class ImportReport(
    val format: ImportFormat,
    val fileName: String?,
    val rowsInFile: Int,
    val importedRows: Int,
    val importedCopies: Int,
    val unmatched: List<String>,
    val unmatchedRows: Int,
    val skipped: Map<String, Int>,
    val replaced: Boolean = false,
)

sealed interface ImportUi {
    data class Reading(val fileName: String?) : ImportUi
    data class ConfirmReplace(val uri: Uri, val fileName: String?) : ImportUi
    data class Running(val phase: String, val done: Int, val total: Int) : ImportUi
    data class Done(val report: ImportReport) : ImportUi
    data class Failed(val message: String) : ImportUi
}

/**
 * Brings a collection in from a file: a SnapStack backup (replaces), or a Ripfolio backup / CSV
 * (adds). Other apps' cards are matched by game + set + number + name against the same sources
 * the scanner uses, then priced, then saved in one go. Runs in the app scope so it keeps going
 * if you switch tabs; progress and the result are shown by [app.snapstack.ui.importer.ImportDialogs].
 */
class CollectionImporter(
    private val app: Application,
    private val db: Database,
    private val cards: CardRepository,
    private val collection: CollectionRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ImportUi?>(null)
    val state: StateFlow<ImportUi?> = _state.asStateFlow()

    fun dismiss() {
        if (_state.value !is ImportUi.Running) _state.value = null
    }

    /** Reads the picked file and either asks to replace (SnapStack backup) or starts importing. */
    /** Imports pasted text: a list (one card per line) or CSV. [game] is used for lines that don't say. */
    fun startText(text: String, game: Game?) {
        if (_state.value is ImportUi.Running || text.isBlank()) return
        _state.value = ImportUi.Running("Reading list", 0, 0)
        scope.launch {
            try {
                runImport(ImportParsing.parsePasted(text, game), null)
            } catch (e: ImportException) {
                _state.value = ImportUi.Failed(e.message ?: "Import failed")
            } catch (e: Exception) {
                _state.value = ImportUi.Failed("Import failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun start(uri: Uri) {
        if (_state.value is ImportUi.Running) return
        val name = displayName(uri)
        _state.value = ImportUi.Reading(name)
        scope.launch {
            try {
                val text = readText(uri)
                when (ImportParsing.detect(text)) {
                    ImportFormat.SNAPSTACK -> _state.value = ImportUi.ConfirmReplace(uri, name)
                    // A plain text file: one card per line.
                    ImportFormat.UNKNOWN -> if (!text.trimStart().startsWith("{") && !text.trimStart().startsWith("[") && text.none { it == '\u0000' }) {
                        runImport(ImportParsing.parseList(text, null), name)
                    } else runImport(ImportParsing.parse(text), name)
                    else -> runImport(ImportParsing.parse(text), name)
                }
            } catch (e: ImportException) {
                _state.value = ImportUi.Failed(e.message ?: "Import failed")
            } catch (e: Exception) {
                _state.value = ImportUi.Failed("Couldn't read that file: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun confirmReplace(uri: Uri) {
        val name = displayName(uri)
        _state.value = ImportUi.Running("Restoring backup", 0, 0)
        scope.launch {
            try {
                val count = app.contentResolver.openInputStream(uri)!!.use { collection.restore(it) }
                _state.value = ImportUi.Done(
                    ImportReport(ImportFormat.SNAPSTACK, name, count, count, count, emptyList(), 0, emptyMap(), replaced = true)
                )
            } catch (e: Exception) {
                _state.value = ImportUi.Failed("Restore failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        app.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > 60 * 1024 * 1024) throw ImportException("That file is too large to import (over 60 MB).")
            String(bytes, Charsets.UTF_8)
        } ?: throw ImportException("Couldn't open that file.")
    }

    private fun displayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    // ------------------------------------------------------------------ import pipeline

    private suspend fun runImport(parsed: ParsedImport, fileName: String?) {
        val skipped = LinkedHashMap(parsed.skipped)
        val (supported, unsupported) = parsed.rows.partition { it.game != null }
        unsupported.groupBy { it.gameLabel ?: "unknown game" }.forEach { (label, rows) ->
            val what = when (label) {
                ImportParsing.NO_GAME -> label
                "unknown game" -> "cards with no game named (add a Game column, or pick a game when pasting)"
                else -> "cards from $label (not supported yet)"
            }
            skipped[what] = rows.sumOf { it.qty }
        }
        if (supported.isEmpty()) {
            _state.value = ImportUi.Done(
                ImportReport(parsed.format, fileName, parsed.rows.size, 0, 0, emptyList(), 0, skipped)
            )
            return
        }

        // Identical cards (same game/set/number/name) are looked up once.
        val groups = supported.groupBy { listOf(it.game!!.id, it.setName, it.number, it.name).joinToString("|") }
        val total = groups.size
        val done = AtomicInteger(0)
        _state.value = ImportUi.Running("Matching cards", 0, total)
        val matched = ConcurrentHashMap<String, Card>()
        coroutineScope {
            val gate = Semaphore(4)
            groups.map { (key, rows) ->
                async {
                    gate.withPermit {
                        runCatching { match(rows.first()) }.getOrNull()?.let { matched[key] = it }
                    }
                    _state.value = ImportUi.Running("Matching cards", done.incrementAndGet(), total)
                }
            }.awaitAll()
        }

        // Price everything we found (set lists can arrive without prices).
        val unique = matched.values.distinctBy { it.id }
        _state.value = ImportUi.Running("Fetching prices", 0, unique.size)
        val fresh = runCatching {
            cards.refreshPrices(unique) { d, t -> _state.value = ImportUi.Running("Fetching prices", d, t) }
        }.getOrDefault(emptyList()).associateBy { it.id }

        _state.value = ImportUi.Running("Saving", 0, 0)
        val now = System.currentTimeMillis()
        val items = ArrayList<CollectionItem>()
        val unmatched = ArrayList<String>()
        var copies = 0
        var importedRows = 0
        var unmatchedRows = 0
        for ((key, rows) in groups) {
            val found = matched[key]
            if (found == null) {
                unmatchedRows += rows.size
                val r = rows.first()
                unmatched += buildString {
                    append(r.name)
                    r.number?.let { append(" #").append(it) }
                    r.setName?.let { append(" · ").append(it) }
                    append(" (").append(r.game!!.label).append(")")
                    val q = rows.sumOf { it.qty }
                    if (q > 1) append(" ×").append(q)
                }
                continue
            }
            val card = fresh[found.id] ?: withContext(Dispatchers.IO) { db.getCard(found.id) } ?: found
            for (r in rows) {
                val variant = pickVariant(card, r)
                items += CollectionItem(
                    cardId = card.id, variant = variant, condition = r.condition, qty = r.qty,
                    costEach = r.costEach, grader = r.grader, grade = r.grade, notes = r.notes,
                    valueOverride = r.gradedValue, addedAt = r.addedAt ?: now,
                    priceAtAdd = r.priceAtAdd ?: card.price(variant),
                )
                copies += r.qty
                importedRows++
            }
        }
        if (items.isNotEmpty()) collection.add(items)
        _state.value = ImportUi.Done(
            ImportReport(parsed.format, fileName, parsed.rows.size, importedRows, copies, unmatched.sorted(), unmatchedRows, skipped)
        )
    }

    // ------------------------------------------------------------------ matching

    private val setCache = ConcurrentHashMap<String, CardSet?>()

    private suspend fun bestSet(game: Game, setName: String?): CardSet? {
        if (setName.isNullOrBlank()) return null
        val cacheKey = "${game.id}|$setName"
        if (setCache.containsKey(cacheKey)) return setCache[cacheKey]
        runCatching { cards.ensureSetList(game) }
        val sets = withContext(Dispatchers.IO) { db.getSets(game) }
        val result = ImportParsing.bestSetMatch(setName, sets)
        if (result != null) setCache[cacheKey] = result
        return result
    }

    private suspend fun match(row: ImportRow): Card? {
        val game = row.game ?: return null
        // Pasted lines were already parsed into a key ("123/193", "OP01-001", "Lightning Bolt"…).
        row.key?.let { key ->
            return runCatching { cards.resolve(key).card }.getOrNull()
        }
        val number = row.number ?: ImportParsing.numberFromName(row.name)
        val base = ImportParsing.baseName(row.name)
        val candidates = LinkedHashMap<String, Card>()

        suspend fun tryKey(key: ScanKey) {
            val r = runCatching { cards.resolve(key) }.getOrNull() ?: return
            r.card?.let { candidates[it.id] = it }
            r.alternatives.forEach { candidates.putIfAbsent(it.id, it) }
        }

        suspend fun fromSet(set: CardSet, n: String) {
            if (set.cachedAt == 0L) runCatching { cards.ensureSetCached(set) }
            withContext(Dispatchers.IO) { db.findBySetNumber(game, set.code, n) }.forEach { candidates.putIfAbsent(it.id, it) }
        }

        /** Catalog games use TCGplayer names, as Ripfolio does: look the name up inside the matched set. */
        suspend fun fromSetByName(set: CardSet) {
            if (set.cachedAt == 0L) runCatching { cards.ensureSetCached(set) }
            withContext(Dispatchers.IO) { db.cardsInSet(game, set.code) }
                .map { it to TextMatch.nameScore(it.name, listOf(row.name)) }
                .filter { it.second >= 0.75 }
                .sortedByDescending { it.second }
                .take(8)
                .forEach { candidates.putIfAbsent(it.first.id, it.first) }
        }

        when (game) {
            Game.POKEMON -> if (number != null) {
                val n = ImportParsing.numberOnly(number)
                val totalPart = ImportParsing.totalOf(number)
                bestSet(game, row.setName)?.let { fromSet(it, n) }
                if (totalPart != null) tryKey(PokemonKey(n, totalPart, null, listOf(base)))
            }
            Game.MAGIC -> {
                val set = bestSet(game, row.setName)
                if (set != null && number != null) tryKey(MagicKey(set.code, ImportParsing.numberOnly(number).trimStart('0').ifEmpty { "0" }))
            }
            Game.YUGIOH -> number?.uppercase(Locale.US)?.let { code ->
                if (YugiohSource.setCodeRegex.matches(code)) tryKey(YugiohKey(code))
            }
            Game.LORCANA -> {
                val set = bestSet(game, row.setName)
                if (set != null && number != null) tryKey(LorcanaKey(set.code, ImportParsing.numberOnly(number).trimStart('0').ifEmpty { "0" }))
            }
            Game.ONE_PIECE -> {
                val set = bestSet(game, row.setName)
                when {
                    row.name.trim().startsWith("DON", ignoreCase = true) -> tryKey(OnePieceDonKey(set?.code))
                    number != null && Regex("^(OP|ST|EB|PRB)\\d{2}-\\d{3}|^P-\\d{3}").containsMatchIn(number.uppercase(Locale.US)) ->
                        tryKey(OnePieceKey(number.uppercase(Locale.US).substringBefore(' ')))
                }
                set?.let { fromSetByName(it) }
            }
            Game.RIFTBOUND -> {
                val set = bestSet(game, row.setName)
                number?.let { num -> tryKey(RiftboundKey(set?.abbreviation, ImportParsing.numberOnly(num), ImportParsing.totalOf(num))) }
                set?.let { fromSetByName(it) }
            }
        }

        choose(row, candidates.values)?.let { return it }
        // Fall back to a name search (filtered by number when we have one), then name only.
        val n = number?.let { ImportParsing.numberOnly(it) }
        if (n != null) {
            tryKey(NameKey(game, base, n))
            choose(row, candidates.values)?.let { return it }
        }
        tryKey(NameKey(game, base))
        return choose(row, candidates.values, requireNumber = false)
    }

    /** Best candidate by name (and set / number as tie-breakers), or null if nothing is close enough. */
    private fun choose(row: ImportRow, candidates: Collection<Card>, requireNumber: Boolean = true): Card? {
        if (candidates.isEmpty()) return null
        val game = row.game
        val exactNames = game == Game.ONE_PIECE || game == Game.RIFTBOUND // parallels differ only by "(…)"
        val base = ImportParsing.baseName(row.name)
        val wantNumber = (row.number ?: ImportParsing.numberFromName(row.name))?.let { normalizeNumber(ImportParsing.numberOnly(it)) }
        val scored = candidates.map { c ->
            val nameScore = if (exactNames) {
                max(TextMatch.nameScore(c.name, listOf(row.name)) * 1.0, TextMatch.nameScore(ImportParsing.baseName(c.name), listOf(base)) * 0.85)
            } else {
                maxOf(
                    TextMatch.nameScore(c.title, listOf(base)),
                    TextMatch.nameScore(c.name, listOf(base)),
                    TextMatch.nameScore(ImportParsing.baseName(c.name), listOf(base)),
                )
            }
            val numberOk = wantNumber != null && (
                normalizeNumber(ImportParsing.numberOnly(c.number)) == wantNumber ||
                    normalizeNumber(c.number) == wantNumber
                )
            val setScore = ImportParsing.setScore(c.setName, row.setName)
            Triple(c, nameScore, nameScore + 0.35 * setScore + if (numberOk) 0.25 else 0.0)
        }
        val best = scored.maxBy { it.third }
        val minName = if (requireNumber) 0.6 else 0.8
        return best.first.takeIf { best.second >= minName }
    }

    private fun pickVariant(card: Card, row: ImportRow): String? {
        val all = card.allVariants
        ImportParsing.printingHint(row)?.let { hint ->
            if (hint in all) return hint
            if (hint == "holofoil" && "foil" in all) return "foil"
            if (hint == "foil" && "holofoil" in all) return "holofoil"
        }
        if (row.foil) listOf("foil", "holofoil", "reverseHolofoil").firstOrNull { it in all }?.let { return it }
        return if ("normal" in all) "normal" else card.defaultVariant
    }
}
