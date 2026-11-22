package app.tcgportfolio.data.source

import app.tcgportfolio.data.importer.ImportParsing
import app.tcgportfolio.data.net.Http
import app.tcgportfolio.model.Card
import app.tcgportfolio.model.CardSet
import app.tcgportfolio.model.Game
import app.tcgportfolio.model.normalizeNumber
import app.tcgportfolio.scan.TextMatch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Second source for Pokémon, Magic, Yu-Gi-Oh! and Lorcana: TCGplayer's own catalog (via tcgcsv.com,
 * updated daily). Used when a game's main source has no price for a card, is unreachable, or
 * doesn't know a brand-new set yet. Cards are matched by set name + number + name; Pokémon cards can
 * also be identified directly from the set code printed on modern cards (TCGplayer uses the same codes).
 */
class TcgPlayerFallback(http: Http) {
    private val catalogs: Map<Game, TcgCsvSource> = mapOf(
        Game.POKEMON to TcgCsvSource(http, Game.POKEMON, 3, "pkm:tcgp"),
        Game.MAGIC to TcgCsvSource(http, Game.MAGIC, 1, "mtg:tcgp"),
        Game.YUGIOH to TcgCsvSource(http, Game.YUGIOH, 2, "ygo:tcgp"),
        Game.LORCANA to TcgCsvSource(http, Game.LORCANA, 71, "lor:tcgp"),
    )
    private val groups = ConcurrentHashMap<Game, Pair<Long, List<CardSet>>>()
    private val groupCards = object : LinkedHashMap<String, List<Card>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Card>>?) = size > 12
    }

    fun supports(game: Game) = game in catalogs

    private suspend fun groups(game: Game): List<CardSet> {
        val cached = groups[game]
        if (cached != null && System.currentTimeMillis() - cached.first < 12 * 3600_000L) return cached.second
        val fresh = catalogs.getValue(game).fetchSets()
        groups[game] = System.currentTimeMillis() to fresh
        return fresh
    }

    private suspend fun cards(game: Game, group: CardSet): List<Card> {
        val key = "${game.id}|${group.code}"
        synchronized(groupCards) { groupCards[key] }?.let { return it }
        val list = catalogs.getValue(game).fetchSetCards(group).cards
        synchronized(groupCards) { groupCards[key] = list }
        return list
    }

    /** TCGplayer market prices for one of our cards, keyed the way that game's main source keys them. */
    suspend fun pricesFor(card: Card): Map<String, Double>? {
        if (!supports(card.game) || card.id.contains(":tcgp:")) return null
        val group = ImportParsing.bestSetMatch(card.setName, groups(card.game)) ?: return null
        val product = bestProduct(card, cards(card.game, group)) ?: return null
        return adaptPrices(card, product.prices).takeIf { it.isNotEmpty() }
    }

    /** Fresh prices for cards that were identified from TCGplayer in the first place (ids "…:tcgp:…"). */
    suspend fun refreshOwn(cards: List<Card>): List<Card> =
        cards.groupBy { it.game }.flatMap { (game, list) -> catalogs[game]?.fetchPrices(list).orEmpty() }

    /**
     * Pokémon lookup without TCGdex: by the printed set code ("PAL", "DLR") and/or the names of sets
     * with the right printed total, then by number. Returned cards have ids "pkm:tcgp:<productId>".
     */
    suspend fun findPokemon(abbrev: String?, setNames: List<String>, number: String, total: String?): List<Card> {
        val all = groups(Game.POKEMON)
        val chosen = LinkedHashMap<String, CardSet>()
        abbrev?.let { a -> all.filter { it.abbreviation.equals(a, true) }.forEach { chosen[it.code] = it } }
        setNames.take(4).forEach { n -> ImportParsing.bestSetMatch(n, all)?.let { chosen.putIfAbsent(it.code, it) } }
        val want = normalizeNumber(number)
        return chosen.values.take(4).flatMap { g ->
            cards(Game.POKEMON, g).filter { c ->
                normalizeNumber(ImportParsing.numberOnly(c.number)) == want &&
                    (total == null || ImportParsing.totalOf(c.number)?.let { t -> normalizeNumber(t) == normalizeNumber(total) } != false)
            }.map { c ->
                c.copy(name = ImportParsing.baseName(c.name), number = ImportParsing.numberOnly(c.number))
            }
        }
    }

    /**
     * A Black Star promo from TCGplayer's promo set for its era ("MEP" + "117"), for promos TCGdex
     * hasn't added yet. Every printing of that number is returned (regular, Pokémon Center…).
     */
    suspend fun findPokemonPromo(prefix: String, number: String): List<Card> {
        val group = promoGroup(prefix) ?: return emptyList()
        val want = promoDigits(number)
        return cards(Game.POKEMON, group).filter { promoDigits(ImportParsing.numberOnly(it.number)) == want }
    }

    /** Name search in the current promo sets (MEP, SVP): the promos most likely missing from TCGdex. */
    suspend fun searchPokemonPromos(query: String, prefixes: List<String> = listOf("MEP", "SVP")): List<Pair<String, Card>> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return prefixes.flatMap { prefix ->
            val group = runCatching { promoGroup(prefix) }.getOrNull() ?: return@flatMap emptyList()
            runCatching { cards(Game.POKEMON, group) }.getOrDefault(emptyList())
                .filter { it.name.contains(q, ignoreCase = true) }
                .map { prefix to it }
        }
    }

    private suspend fun promoGroup(prefix: String): CardSet? {
        val name = PROMO_GROUPS[prefix] ?: return null
        return groups(Game.POKEMON).firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    companion object {
        /** TCGplayer's set for each era's Black Star promos, by the prefix printed on the card. */
        val PROMO_GROUPS = mapOf(
            "MEP" to "ME: Mega Evolution Promo",
            "SVP" to "SV: Scarlet & Violet Promo Cards",
            "SWSH" to "SWSH: Sword & Shield Promo Cards",
            "SM" to "SM Promos",
            "XY" to "XY Promos",
            "BW" to "Black and White Promos",
            "HGSS" to "HGSS Promos",
            "DP" to "Diamond and Pearl Promos",
        )

        /** "SWSH045", "SVP EN 178", "117", "MEP 117" → "45", "178", "117", "117". */
        fun promoDigits(number: String): String = normalizeNumber(number.filter { it.isDigit() })

        /** The TCGplayer product that is this card: same number, and a matching name. */
        fun bestProduct(card: Card, products: List<Card>): Card? {
            val want = normalizeNumber(ImportParsing.numberOnly(card.number))
            val names = listOf(card.title, card.name, ImportParsing.baseName(card.name))
            return products
                .filter { normalizeNumber(ImportParsing.numberOnly(it.number)) == want || normalizeNumber(it.number) == normalizeNumber(card.number) }
                .map { p -> p to names.maxOf { n -> TextMatch.nameScore(ImportParsing.baseName(p.name), listOf(ImportParsing.baseName(n))) } }
                .filter { it.second >= 0.7 }
                .sortedWith(compareByDescending<Pair<Card, Double>> { it.second }.thenBy { TcgCsvSource.specialness(it.first.name) })
                .firstOrNull()?.first
        }

        /** TCGplayer printing names → the keys each game's main source uses. */
        fun adaptPrices(card: Card, tcg: Map<String, Double>): Map<String, Double> {
            if (tcg.isEmpty()) return tcg
            return when (card.game) {
                // Yu-Gi-Oh! prices are keyed by rarity in this app; TCGplayer splits by edition instead.
                Game.YUGIOH -> mapOf(
                    (card.rarity ?: card.defaultVariant ?: "normal") to
                        (tcg["unlimited"] ?: tcg["1stEdition"] ?: tcg["limited"] ?: tcg.values.first()),
                )
                else -> tcg.mapKeys { (k, _) ->
                    when (k.lowercase(Locale.US)) {
                        "foiletched", "etchedfoil" -> "etched"
                        "coldfoil" -> "foil" // Lorcana's foils are "Cold Foil" on TCGplayer
                        else -> k
                    }
                }
            }
        }
    }
}
