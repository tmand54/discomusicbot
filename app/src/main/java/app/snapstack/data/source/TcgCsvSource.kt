package app.snapstack.data.source

import app.snapstack.data.net.Http
import app.snapstack.model.Card
import app.snapstack.model.CardSet
import app.snapstack.model.Game
import app.snapstack.model.Variants
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.util.Locale

/**
 * Games without a dedicated free card API (One Piece, Riftbound) come from tcgcsv.com, a free
 * daily mirror of TCGplayer's catalog: one "group" per set, with products (cards) and their
 * Normal/Foil market prices. A whole set is two requests, so the first card of a set brings
 * in the entire set — prices included — and everything after resolves on the phone.
 *
 * Card ids are TCGplayer product ids, so parallels / alt arts that share a printed number are
 * separate cards (offered as alternatives when scanning).
 */
class TcgCsvSource(
    private val http: Http,
    override val game: Game,
    private val categoryId: Int,
    private val idPrefix: String,
) : CardSource {
    private val base = "https://tcgcsv.com/tcgplayer/$categoryId"

    override suspend fun fetchSets(): List<CardSet> {
        val arr = http.getJson("$base/groups")?.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                add(
                    CardSet(
                        game = game, code = g.getLong("groupId").toString(), name = g.optString("name"),
                        printedTotal = null, total = null,
                        abbreviation = g.optStr("abbreviation"),
                        releaseDate = g.optStr("publishedOn")?.take(10),
                    )
                )
            }
        }
    }

    override suspend fun fetchSetCards(set: CardSet): SetDownload = coroutineScope {
        val products = async { http.getJson("$base/${set.code}/products")?.optJSONArray("results") }
        val prices = async { runCatching { fetchGroupPrices(set.code) }.getOrDefault(emptyMap()) }
        val arr = products.await() ?: return@coroutineScope SetDownload(emptyList())
        val priceMap = prices.await()
        val now = System.currentTimeMillis()
        val cards = buildList {
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val ext = HashMap<String, String>()
                p.optJSONArray("extendedData")?.let { e ->
                    for (j in 0 until e.length()) e.optJSONObject(j)?.let { ext[it.optString("name")] = it.optString("value") }
                }
                val number = ext["Number"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue // sealed product
                val id = p.getLong("productId")
                val image = p.optStr("imageUrl")
                val prices = priceMap[id] ?: emptyMap()
                add(
                    Card(
                        id = "$idPrefix:$id", game = game, name = p.optString("name"),
                        setCode = set.code, setName = set.name, number = number,
                        rarity = ext["Rarity"],
                        imageSmall = image, imageLarge = image?.replace("_200w.", "_400w."),
                        prices = prices, variants = prices.keys.toList().ifEmpty { listOf("normal") },
                        url = p.optStr("url"), pricedAt = if (priceMap.isNotEmpty()) now else 0L,
                    )
                )
            }
        }
        SetDownload(cards, set.abbreviation)
    }

    /** productId -> (variant -> market price) for one set. */
    private suspend fun fetchGroupPrices(groupId: String): Map<Long, Map<String, Double>> {
        val arr = http.getJson("$base/$groupId/prices")?.optJSONArray("results") ?: return emptyMap()
        val out = HashMap<Long, LinkedHashMap<String, Double>>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val price = r.optPrice("marketPrice") ?: r.optPrice("midPrice") ?: r.optPrice("lowPrice") ?: continue
            val variant = Variants.fromKebab(r.optString("subTypeName", "Normal").lowercase(Locale.US))
            out.getOrPut(r.getLong("productId")) { LinkedHashMap() }[variant] = price
        }
        return out
    }

    override suspend fun fetchBySetNumber(setCode: String, number: String): Card? {
        val set = CardSet(game, setCode, setCode, null, null)
        val want = app.snapstack.model.normalizeNumber(number)
        return fetchSetCards(set).cards.firstOrNull { app.snapstack.model.normalizeNumber(it.number) == want }
    }

    /** One request per set, however many cards of that set are being refreshed. */
    override suspend fun fetchPrices(cards: List<Card>): List<Card> = coroutineScope {
        val now = System.currentTimeMillis()
        cards.groupBy { it.setCode }.map { (groupId, inSet) ->
            async {
                val prices = runCatching { fetchGroupPrices(groupId) }.getOrNull() ?: return@async emptyList()
                inSet.mapNotNull { c ->
                    val pid = c.id.substringAfter(':').toLongOrNull() ?: return@mapNotNull null
                    val p = prices[pid] ?: return@mapNotNull null
                    c.copy(prices = p, variants = (c.variants + p.keys).distinct(), pricedAt = now)
                }
            }
        }.flatMap { it.await() }
    }

    /** No search endpoint: names are searched in the on-device catalog (see CardRepository). */
    override suspend fun search(query: String): List<Card> = emptyList()

    companion object {
        const val ONE_PIECE_CATEGORY = 68
        const val RIFTBOUND_CATEGORY = 89

        /** Words TCGplayer appends to special printings that share a card number. */
        private val special = Regex(
            "\\((?:[^)]*\\b(?:parallel|alternate|alt art|manga|sp|spr|box topper|treasure|gold|signature|signed|" +
                "overnumbered|showcase|full art|jolly roger|pre-?release|textured|serial|foil|promo|reprint|wanted)\\b[^)]*)\\)",
            RegexOption.IGNORE_CASE,
        )

        /** 0 for the regular printing, higher for parallels / alt arts / promos. */
        fun specialness(name: String): Int = special.findAll(name).count() * 2 + name.count { it == '(' }
    }
}
