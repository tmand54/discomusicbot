package app.snapstack.data

import android.util.LruCache
import app.snapstack.data.db.Database
import app.snapstack.data.source.CardSource
import app.snapstack.data.source.MagicSource
import app.snapstack.data.source.PokemonSource
import app.snapstack.data.source.YugiohSource
import app.snapstack.model.Card
import app.snapstack.model.CardSet
import app.snapstack.model.Game
import app.snapstack.model.normalizeNumber
import app.snapstack.scan.LorcanaKey
import app.snapstack.scan.MagicKey
import app.snapstack.scan.NameKey
import app.snapstack.scan.PokemonKey
import app.snapstack.scan.PokemonPromoKey
import app.snapstack.scan.ScanKey
import app.snapstack.scan.TextMatch
import app.snapstack.scan.YugiohKey
import app.snapstack.scan.YugiohPasscodeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

enum class ResolvedFrom { MEMORY, DEVICE, NETWORK }

data class Resolution(
    val card: Card?,
    val alternatives: List<Card> = emptyList(),
    val confident: Boolean = card != null,
    val from: ResolvedFrom = ResolvedFrom.NETWORK,
    val error: String? = null,
)

/**
 * Card lookups, layered for speed: memory → on-device catalog → network. Network hits
 * trigger a background download of the whole set, so the next card from the same pack or
 * binder resolves on the phone with no network round trip.
 */
class CardRepository(
    private val db: Database,
    private val settings: Settings,
    private val scope: CoroutineScope,
    val sources: Map<Game, CardSource>,
) {
    private val memory = LruCache<String, Resolution>(1000)
    private val inflight = ConcurrentHashMap<String, Deferred<Resolution>>()
    private val setJobs = ConcurrentHashMap<String, Deferred<Int>>()
    private val setListJobs = ConcurrentHashMap<Game, Deferred<Unit>>()
    private val priceJobs = ConcurrentHashMap<String, Deferred<Card?>>()

    fun source(game: Game) = sources.getValue(game)

    fun getCached(id: String): Card? = db.getCard(id)

    // ------------------------------------------------------------------ resolve (scanner hot path)

    suspend fun resolve(key: ScanKey): Resolution {
        memory.get(key.id)?.let { return it.copy(from = ResolvedFrom.MEMORY) }
        val job = inflight.computeIfAbsent(key.id) {
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    val r = runCatching { doResolve(key) }.getOrElse { e ->
                        Resolution(null, error = e.message ?: "Lookup failed")
                    }
                    if (r.card != null && r.confident) memory.put(key.id, r)
                    r
                } finally {
                    inflight.remove(key.id)
                }
            }
        }
        return job.await()
    }

    private suspend fun doResolve(key: ScanKey): Resolution = when (key) {
        is MagicKey -> bySetNumber(Game.MAGIC, key.set, key.number)
        is LorcanaKey -> bySetNumber(Game.LORCANA, key.set, key.number)
        is YugiohKey -> resolveYugioh(key)
        is YugiohPasscodeKey -> {
            db.getCard("ygo:pc:${key.passcode}")?.let { Resolution(it, from = ResolvedFrom.DEVICE) }
                ?: (source(Game.YUGIOH) as YugiohSource).fetchByPasscode(key.passcode)?.let {
                    db.upsertCards(listOf(it)); Resolution(it)
                } ?: Resolution(null)
        }
        is PokemonKey -> resolvePokemon(key)
        is PokemonPromoKey -> resolvePokemonPromo(key)
        is NameKey -> resolveName(key)
    }

    private suspend fun bySetNumber(game: Game, setCode: String, number: String): Resolution {
        db.findBySetNumber(game, setCode, number).firstOrNull()?.let {
            return Resolution(it, from = ResolvedFrom.DEVICE)
        }
        val card = source(game).fetchBySetNumber(setCode, number) ?: return Resolution(null)
        db.upsertCards(listOf(card))
        prefetchSetAsync(game, card.setCode)
        return Resolution(card)
    }

    private suspend fun resolveYugioh(key: YugiohKey): Resolution {
        val code = key.code.uppercase()
        db.getCard("ygo:$code")?.let { return Resolution(it, from = ResolvedFrom.DEVICE) }
        val card = source(Game.YUGIOH).fetchBySetNumber("", code)
            ?: if (!code.contains("-EN")) {
                // Old prints without a language letter, or OCR dropped it: try the English code.
                val alt = code.replaceFirst("-", "-EN")
                source(Game.YUGIOH).fetchBySetNumber("", alt)
            } else null
        card ?: return Resolution(null)
        db.upsertCards(listOf(card))
        prefetchSetAsync(Game.YUGIOH, card.setCode)
        return Resolution(card)
    }

    private suspend fun resolvePokemon(key: PokemonKey): Resolution {
        ensureSetList(Game.POKEMON)
        val candidates = LinkedHashMap<String, CardSet>()
        key.abbrev?.let { a -> db.setsByAbbrev(Game.POKEMON, a).forEach { candidates[it.code] = it } }
        val totalDigits = key.total?.filter { it.isDigit() }?.toIntOrNull()
        if (totalDigits != null) db.setsByPrintedTotal(Game.POKEMON, totalDigits).forEach { candidates.putIfAbsent(it.code, it) }

        val sets = candidates.values.take(8)
        var matches = findInSets(sets, key.number)
        if (matches.isEmpty() && key.number.any { it.isLetter() }) {
            // Subset numbering ("TG05", "GG12"): those cards live in sibling sets with their own totals.
            matches = db.findByNumberInSets(Game.POKEMON, db.getSets(Game.POKEMON).map { it.code }, key.number)
        }
        if (matches.isEmpty()) {
            // Last resort: search by the most name-like OCR line and filter on the number.
            val name = key.nameHints.firstOrNull { it.length in 3..24 } ?: return Resolution(null)
            val found = runCatching { source(Game.POKEMON).search(name) }.getOrDefault(emptyList())
            matches = found.filter { normalizeNumber(it.number) == normalizeNumber(key.number) }
            if (matches.isNotEmpty()) db.upsertCards(matches)
        }
        if (matches.isEmpty()) return Resolution(null)
        return rankByName(matches, key.nameHints)
    }

    private suspend fun resolvePokemonPromo(key: PokemonPromoKey): Resolution {
        ensureSetList(Game.POKEMON)
        val setCode = PokemonSource.promoSets[key.prefix] ?: return Resolution(null)
        val set = db.getSet(Game.POKEMON, setCode) ?: CardSet(Game.POKEMON, setCode, "${key.prefix} Promos", null, null)
        var matches = findInSets(listOf(set), key.prefix + key.number)
        if (matches.isEmpty()) matches = db.findByNumberInSets(Game.POKEMON, listOf(setCode), key.number)
        if (matches.isEmpty()) return Resolution(null)
        return rankByName(matches, key.nameHints)
    }

    private suspend fun findInSets(sets: List<CardSet>, number: String): List<Card> = coroutineScope {
        if (sets.isEmpty()) return@coroutineScope emptyList()
        // Download any candidate set we don't have yet, in parallel (one request each, names + images).
        sets.filter { it.cachedAt == 0L }.map { s -> async { runCatching { ensureSetCached(s) } } }.awaitAll()
        db.findByNumberInSets(sets.first().game, sets.map { it.code }, number)
    }

    private fun rankByName(matches: List<Card>, hints: List<String>): Resolution {
        val scored = matches.map { it to TextMatch.nameScore(it.name, hints) }.sortedByDescending { it.second }
        val (best, score) = scored.first()
        val confident = score >= 0.72 || (scored.size == 1 && (score >= 0.45 || hints.isEmpty()))
        val from = if (best.pricedAt > 0 || best.imageSmall != null) ResolvedFrom.DEVICE else ResolvedFrom.NETWORK
        return Resolution(best, scored.drop(1).map { it.first }, confident, from)
    }

    private suspend fun resolveName(key: NameKey): Resolution {
        if (key.game == Game.MAGIC && key.number == null) {
            val card = (source(Game.MAGIC) as MagicSource).fetchByName(key.name) ?: return Resolution(null)
            db.upsertCards(listOf(card))
            return Resolution(card)
        }
        val local = db.searchLocal(key.game, key.name, 40)
        val remote = if (local.size < 3 || key.number != null) {
            runCatching { source(key.game).search(key.name) }.getOrDefault(emptyList()).also { db.upsertCards(it) }
        } else emptyList()
        var all = (local + remote).distinctBy { it.id }
        if (key.number != null) {
            val n = normalizeNumber(key.number)
            all = all.filter { normalizeNumber(it.number) == n || normalizeNumber(it.number.substringAfterLast('-').filter { c -> c.isDigit() }) == n }
        }
        if (all.isEmpty()) return Resolution(null)
        val scored = all.sortedByDescending { TextMatch.nameScore(it.name, listOf(key.name)) }
        return Resolution(scored.first(), scored.drop(1).take(12), confident = scored.size == 1)
    }

    // ------------------------------------------------------------------ sets / offline catalog

    suspend fun ensureSetList(game: Game, force: Boolean = false) {
        if (!force && db.setCount(game) > 0) {
            // New sets come out all the time: refresh the list weekly, in the background.
            val key = "set_list_${game.id}"
            if (System.currentTimeMillis() - settings.getLong(key) > 7 * 24 * 3600_000L) {
                settings.putLong(key, System.currentTimeMillis())
                scope.launch { runCatching { ensureSetList(game, force = true) } }
            }
            return
        }
        val job = setListJobs.computeIfAbsent(game) {
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    db.upsertSets(source(game).fetchSets())
                    settings.putLong("set_list_${game.id}", System.currentTimeMillis())
                } finally {
                    setListJobs.remove(game)
                }
            }
        }
        job.await()
    }

    /** Downloads every card in a set into the on-device catalog. Returns the number of cards. */
    suspend fun ensureSetCached(set: CardSet): Int {
        val jobKey = "${set.game.id}|${set.code}"
        val job = setJobs.computeIfAbsent(jobKey) {
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    val dl = source(set.game).fetchSetCards(set)
                    db.upsertCards(dl.cards)
                    db.markSetCached(set.game, set.code, dl.cards.size, dl.abbreviation)
                    dl.cards.size
                } finally {
                    setJobs.remove(jobKey)
                }
            }
        }
        return job.await()
    }

    fun prefetchSetAsync(game: Game, setCode: String) {
        if (!settings.autoPrefetch.value) return
        scope.launch {
            runCatching {
                ensureSetList(game)
                val set = db.getSet(game, setCode) ?: CardSet(game, setCode, setCode, null, null)
                if (set.cachedAt == 0L) ensureSetCached(set)
            }
        }
    }

    // ------------------------------------------------------------------ prices

    /** Returns the card with prices no older than [maxAgeMs], fetching them if needed. */
    suspend fun ensurePriced(card: Card, maxAgeMs: Long = 12 * 3600_000L): Card {
        if (card.pricedAt > System.currentTimeMillis() - maxAgeMs) return card
        db.getCard(card.id)?.let { if (it.pricedAt > System.currentTimeMillis() - maxAgeMs) return it }
        val job = priceJobs.computeIfAbsent(card.id) {
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    runCatching { source(card.game).fetchPrices(listOf(card)).firstOrNull() }.getOrNull()?.also {
                        db.upsertCards(listOf(it), keepPrices = false)
                    }
                } finally {
                    priceJobs.remove(card.id)
                }
            }
        }
        return job.await() ?: card
    }

    /** Refreshes prices for many cards (collection/wantlist) and records today's price history. */
    suspend fun refreshPrices(cards: List<Card>, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Card> {
        val updated = ArrayList<Card>()
        var done = 0
        for ((game, list) in cards.groupBy { it.game }) {
            for (chunk in list.chunked(60)) {
                val fresh = runCatching { source(game).fetchPrices(chunk) }.getOrDefault(emptyList())
                db.upsertCards(fresh, keepPrices = false)
                updated += fresh
                done += chunk.size
                onProgress(done, cards.size)
            }
        }
        db.recordPrices(updated, LocalDate.now().toEpochDay())
        updated.forEach { memory.remove(it.id) }
        return updated
    }

    // ------------------------------------------------------------------ search

    suspend fun search(game: Game, query: String): List<Card> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val results = source(game).search(q)
        if (game == Game.POKEMON && results.any { it.setName == it.setCode }) {
            runCatching { ensureSetList(Game.POKEMON) }
        }
        val named = results.map { c ->
            if (c.game == Game.POKEMON && c.setName == c.setCode) {
                db.getSet(Game.POKEMON, c.setCode)?.let { c.copy(setName = it.name) } ?: c
            } else c
        }
        db.upsertCards(named)
        return named
    }

    fun clearMemory() = memory.evictAll()
}
