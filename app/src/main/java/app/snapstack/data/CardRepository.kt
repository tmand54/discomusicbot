package app.snapstack.data

import android.util.LruCache
import app.snapstack.data.db.Database
import app.snapstack.data.source.CardSource
import app.snapstack.data.source.MagicSource
import app.snapstack.data.source.PokemonSource
import app.snapstack.data.source.OptcgApi
import app.snapstack.data.source.TcgCsvSource
import app.snapstack.data.source.TcgPlayerFallback
import app.snapstack.data.source.YugiohSource
import app.snapstack.model.Card
import app.snapstack.model.CardSet
import app.snapstack.model.Game
import app.snapstack.model.normalizeNumber
import app.snapstack.scan.CardTextParser
import app.snapstack.scan.DragonBallKey
import app.snapstack.scan.LorcanaKey
import app.snapstack.scan.MagicKey
import app.snapstack.scan.NameKey
import app.snapstack.scan.OnePieceDonKey
import app.snapstack.scan.OnePieceKey
import app.snapstack.scan.PokemonKey
import app.snapstack.scan.PokemonPromoKey
import app.snapstack.scan.RiftboundKey
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.util.Locale
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
    /** Second source for Pokémon / Magic / Yu-Gi-Oh! / Lorcana prices, and Pokémon lookups (TCGplayer catalog). */
    private val tcgplayer: TcgPlayerFallback? = null,
    /** Second source for One Piece: online name search and backup prices. */
    private val optcg: OptcgApi? = null,
) {
    private val memory = LruCache<String, Resolution>(1000)
    private val inflight = ConcurrentHashMap<String, Deferred<Resolution>>()
    private val setJobs = ConcurrentHashMap<String, Deferred<Int>>()
    private val setListJobs = ConcurrentHashMap<Game, Deferred<Unit>>()
    private val priceJobs = ConcurrentHashMap<String, Deferred<Card?>>()

    fun source(game: Game) = sources.getValue(game)

    /** Games whose names are searched in the on-device catalog (no search API). */
    fun isCatalogGame(game: Game) = source(game) is TcgCsvSource

    init {
        // Teach the parser every Riftbound set code we already know about.
        scope.launch { runCatching { registerRiftboundCodes() } }
        // One Piece sets downloaded before DON!! support lack their DON!! cards: fetch them again on demand.
        if (settings.getLong("op_don_v1") == 0L) scope.launch {
            runCatching { db.resetSetsCached(Game.ONE_PIECE); settings.putLong("op_don_v1", 1L) }
        }
    }

    private fun registerRiftboundCodes() =
        CardTextParser.registerRiftboundCodes(db.getSets(Game.RIFTBOUND).mapNotNull { it.abbreviation })

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
        is OnePieceKey -> resolveOnePiece(key)
        is OnePieceDonKey -> resolveDon(key)
        is DragonBallKey -> resolveDragonBall(key)
        is RiftboundKey -> resolveRiftbound(key)
    }

    /**
     * DON!! cards carry no number or name, so every DON!! card is a candidate: the scanned pack's set
     * first (from [OnePieceDonKey.setHint]), then promos and recent sets. Always offered for review.
     */
    private suspend fun resolveDon(key: OnePieceDonKey): Resolution {
        val hint = key.setHint
        var dons = db.findByNumber(Game.ONE_PIECE, TcgCsvSource.DON_NUMBER)
        var from = ResolvedFrom.DEVICE
        if (dons.isEmpty() || (hint != null && dons.none { it.setCode == hint })) {
            from = ResolvedFrom.NETWORK
            ensureSetList(Game.ONE_PIECE)
            val sets = db.getSets(Game.ONE_PIECE)
            val wanted = buildList {
                hint?.let { h -> sets.firstOrNull { it.code == h }?.let(::add) }
                addAll(sets.filter { it.name.contains("Promotion", true) })
                addAll(
                    sets.filter { !it.name.contains("Release Event", true) && !it.name.contains("Promo", true) }
                        .sortedByDescending { it.releaseDate ?: "" }.take(4)
                )
            }.distinctBy { it.code }
            downloadSets(wanted)
            dons = db.findByNumber(Game.ONE_PIECE, TcgCsvSource.DON_NUMBER)
        }
        if (dons.isEmpty()) return Resolution(null)
        val sorted = dons.sortedWith(
            compareBy<Card>(
                { if (hint != null && it.setCode == hint) 0 else 1 },
                { TcgCsvSource.specialness(it.name) },
                { it.price(null) ?: Double.MAX_VALUE },
            )
        )
        return Resolution(sorted.first(), sorted.drop(1), confident = false, from = from)
    }

    private fun abbrevKey(a: String?) = a?.uppercase(Locale.US)?.filter { it.isLetterOrDigit() }

    /** "OP01-001": the set prefix names the TCGplayer group (OP01, ST-01, EB01, PRB01; P = promos). */
    private suspend fun resolveOnePiece(key: OnePieceKey): Resolution {
        val code = key.code.uppercase(Locale.US)
        var matches = db.findByNumber(Game.ONE_PIECE, code)
        var from = ResolvedFrom.DEVICE
        val prefix = code.substringBefore('-')
        if (matches.isEmpty()) {
            from = ResolvedFrom.NETWORK
            ensureSetList(Game.ONE_PIECE)
            val sets = db.getSets(Game.ONE_PIECE)
            val primary = sets.filter { abbrevKey(it.abbreviation) == prefix }
            val candidates = primary.ifEmpty {
                if (prefix == "P") sets.filter { it.name.contains("Promo", true) }.take(6) else emptyList()
            }
            downloadSets(candidates)
            matches = db.findByNumber(Game.ONE_PIECE, code)
        }
        if (matches.isEmpty()) return Resolution(null)
        return rankPrintings(matches, prefix, from)
    }

    /**
     * Dragon Ball codes → TCGplayer sets. Fusion World and recent Super Card Game boosters use the printed
     * code as the set abbreviation ("FB01", "BT22"); older ones are "DBS-B05", tournament packs "DBS-TB03".
     * Starter-deck codes (SD/XD) live inside booster sets and resolve once that set is on the phone.
     */
    private suspend fun resolveDragonBall(key: DragonBallKey): Resolution {
        val code = key.code.uppercase(Locale.US)
        var matches = db.findByNumber(Game.DRAGON_BALL, code)
        var from = ResolvedFrom.DEVICE
        var preferred: String? = null
        if (matches.isEmpty()) {
            from = ResolvedFrom.NETWORK
            ensureSetList(Game.DRAGON_BALL)
            val groups = TcgCsvSource.dragonBallGroups(code, db.getSets(Game.DRAGON_BALL))
            preferred = groups.firstOrNull()?.abbreviation
            downloadSets(groups.take(6))
            matches = db.findByNumber(Game.DRAGON_BALL, code)
        }
        if (matches.isEmpty()) return Resolution(null)
        return rankPrintings(matches, preferred, from)
    }

    /** "OGN • 179/298": set code (when readable) → TCGplayer group; the number/total picks the card. */
    private suspend fun resolveRiftbound(key: RiftboundKey): Resolution {
        ensureSetList(Game.RIFTBOUND)
        val number = if (key.total != null) "${key.number}/${key.total}" else key.number
        val prefixMatch = key.total == null
        val sets = db.getSets(Game.RIFTBOUND)
        val inSet = key.set?.let { code -> sets.filter { it.abbreviation.equals(code, true) } }.orEmpty()
        var from = ResolvedFrom.DEVICE
        var matches = if (inSet.isNotEmpty()) {
            if (inSet.any { it.cachedAt == 0L }) from = ResolvedFrom.NETWORK
            downloadSets(inSet)
            db.findByNumber(Game.RIFTBOUND, number, prefixMatch, inSet.map { it.code })
        } else emptyList()
        if (matches.isEmpty()) matches = db.findByNumber(Game.RIFTBOUND, number, prefixMatch)
        if (matches.isEmpty() && inSet.isEmpty()) {
            // Set code unreadable: fetch the main sets (promo groups are skipped) and look again.
            from = ResolvedFrom.NETWORK
            val main = sets.filter { it.cachedAt == 0L && !Regex("promo|bundle|judge", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
            downloadSets(main.take(10))
            matches = db.findByNumber(Game.RIFTBOUND, number, prefixMatch)
        }
        if (matches.isEmpty()) return Resolution(null)
        return rankPrintings(matches, key.set, from)
    }

    private suspend fun downloadSets(sets: List<CardSet>) = coroutineScope {
        val gate = Semaphore(4)
        sets.filter { it.cachedAt == 0L }.map { s -> async { gate.withPermit { runCatching { ensureSetCached(s) } } } }.awaitAll()
    }

    /**
     * Several printings share one number (regular, parallel, alt art, promo reprints): pick the regular
     * one from the expected set and offer the rest. Flag it when a pricier printing could be the real one.
     */
    private fun rankPrintings(matches: List<Card>, setAbbrev: String?, from: ResolvedFrom): Resolution {
        val abbrevBySet = db.getSets(matches.first().game).associate { it.code to abbrevKey(it.abbreviation) }
        val want = abbrevKey(setAbbrev)
        val sorted = matches.distinctBy { it.id }.sortedWith(
            compareBy<Card>(
                { TcgCsvSource.specialness(it.name) },
                { if (want != null && abbrevBySet[it.setCode] == want) 0 else 1 },
                { it.price(null) ?: Double.MAX_VALUE },
            )
        )
        val best = sorted.first()
        val others = sorted.drop(1)
        val basePrice = best.price(null) ?: 0.0
        val confident = others.none { (it.price(null) ?: 0.0) > basePrice * 2 + 1.0 }
        return Resolution(best, others.take(12), confident, from)
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

    /** TCGdex first; if it fails or doesn't know the card (e.g. a set released this week), TCGplayer's catalog. */
    private suspend fun resolvePokemon(key: PokemonKey): Resolution {
        val primary = runCatching { resolvePokemonPrimary(key) }
        primary.getOrNull()?.takeIf { it.card != null }?.let { return it }
        val fallback = tcgplayer ?: return primary.getOrThrow()
        val setNames = runCatching {
            val total = key.total?.filter(Char::isDigit)?.toIntOrNull()
            (key.abbrev?.let { db.setsByAbbrev(Game.POKEMON, it) }.orEmpty() +
                total?.let { db.setsByPrintedTotal(Game.POKEMON, it) }.orEmpty()).map { it.name }.distinct()
        }.getOrDefault(emptyList())
        if (key.abbrev == null && setNames.isEmpty()) return primary.getOrThrow()
        val found = runCatching { fallback.findPokemon(key.abbrev, setNames, key.number, key.total) }.getOrDefault(emptyList())
        if (found.isEmpty()) return primary.getOrThrow()
        db.upsertCards(found)
        return rankByName(found, key.nameHints).copy(from = ResolvedFrom.NETWORK)
    }

    private suspend fun resolvePokemonPrimary(key: PokemonKey): Resolution {
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
                    if (game == Game.RIFTBOUND) registerRiftboundCodes()
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
                    pricesFor(card.game, listOf(card)).firstOrNull()?.also {
                        db.upsertCards(listOf(it), keepPrices = false)
                    }
                } finally {
                    priceJobs.remove(card.id)
                }
            }
        }
        return job.await() ?: card
    }

    /**
     * Prices from the game's main source; cards it can't price (no price listed, or the source is down)
     * are tried against the second source. Cards that came from TCGplayer's catalog are priced there.
     */
    private suspend fun pricesFor(game: Game, cards: List<Card>): List<Card> {
        val (own, regular) = cards.partition { it.id.contains(":tcgp:") }
        val out = ArrayList<Card>()
        if (own.isNotEmpty()) out += runCatching { tcgplayer?.refreshOwn(own) }.getOrNull().orEmpty()
        if (regular.isNotEmpty()) {
            val primary = runCatching { source(game).fetchPrices(regular) }.getOrDefault(emptyList())
            val priced = primary.filter { it.prices.isNotEmpty() }
            out += priced
            val missing = regular.filter { c -> priced.none { it.id == c.id } }
            if (missing.isNotEmpty()) out += backupPrices(missing, primary)
        }
        return out
    }

    private suspend fun backupPrices(missing: List<Card>, primaryResults: List<Card>): List<Card> = coroutineScope {
        val gate = Semaphore(4)
        val now = System.currentTimeMillis()
        missing.map { c ->
            async {
                gate.withPermit {
                    val card = primaryResults.firstOrNull { it.id == c.id } ?: c
                    val prices = when {
                        tcgplayer?.supports(card.game) == true -> runCatching { tcgplayer.pricesFor(card) }.getOrNull()
                        card.game == Game.ONE_PIECE && optcg != null && card.number != TcgCsvSource.DON_NUMBER ->
                            runCatching { optcgPrice(card) }.getOrNull()
                        else -> null
                    }
                    prices?.let { card.copy(prices = it, variants = (card.variants + it.keys).distinct(), pricedAt = now) }
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** One Piece backup price: the optcgapi printing with this code whose name matches ("(Parallel)" etc.). */
    private suspend fun optcgPrice(card: Card): Map<String, Double>? {
        val hit = optcg?.versions(card.number)?.filter { it.price != null }
            ?.maxByOrNull { TextMatch.nameScore(it.name, listOf(card.name)) }
            ?.takeIf { TextMatch.nameScore(it.name, listOf(card.name)) >= 0.75 } ?: return null
        return mapOf((card.defaultVariant ?: "normal") to hit.price!!)
    }

    /** Refreshes prices for many cards (collection/wantlist) and records today's price history. */
    suspend fun refreshPrices(cards: List<Card>, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Card> {
        val updated = ArrayList<Card>()
        var done = 0
        for ((game, list) in cards.groupBy { it.game }) {
            for (chunk in list.chunked(60)) {
                val fresh = pricesFor(game, chunk)
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
        if (isCatalogGame(game)) {
            val byNumber = db.findByNumber(game, q, prefix = true)
            val local = (byNumber + db.searchLocal(game, q, 150)).distinctBy { it.id }
            val api = optcg
            if (game != Game.ONE_PIECE || api == null) return local
            // Online name search: optcgapi gives the card codes, the TCGplayer catalog gives the printings.
            val codes = runCatching { api.search(q) }.getOrDefault(emptyList())
                .map { it.code }.filter { Regex("^((OP|ST|EB|PRB)\\d{2}|P)-\\d{3}$").matches(it) }.distinct().take(16)
            val online = coroutineScope {
                val gate = Semaphore(3)
                codes.map { code ->
                    async {
                        gate.withPermit {
                            runCatching { resolve(OnePieceKey(code)) }.getOrNull()
                                ?.let { r -> listOfNotNull(r.card) + r.alternatives }.orEmpty()
                        }
                    }
                }.awaitAll().flatten()
            }.filter { c -> c.name.contains(q, ignoreCase = true) || TextMatch.nameScore(c.name, listOf(q)) >= 0.8 }
            return (local + online).distinctBy { it.id }
        }
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

    /** (sets on device, all sets) for a game's catalog. */
    suspend fun catalogStatus(game: Game): Pair<Int, Int> {
        runCatching { ensureSetList(game) }
        val sets = db.getSets(game)
        return sets.count { it.cachedAt > 0 } to sets.size
    }

    /** Downloads every set of a game (used for name search in catalog games, and offline use). */
    suspend fun downloadCatalog(game: Game, onProgress: (Int, Int) -> Unit) = coroutineScope {
        ensureSetList(game)
        val sets = db.getSets(game)
        val todo = sets.filter { it.cachedAt == 0L }
        var done = sets.size - todo.size
        onProgress(done, sets.size)
        val gate = Semaphore(4)
        todo.map { s ->
            async {
                gate.withPermit { runCatching { ensureSetCached(s) } }
                synchronized(this@CardRepository) { done++; onProgress(done, sets.size) }
            }
        }.awaitAll()
    }

    fun clearMemory() = memory.evictAll()
}
