package app.tcgportfolio.data.source

import app.tcgportfolio.data.net.Http
import app.tcgportfolio.model.Card
import app.tcgportfolio.model.CardSet
import app.tcgportfolio.model.Game
import org.json.JSONObject

/** Disney Lorcana via Lorcast (free, no key, ~10 req/s). */
class LorcanaSource(private val http: Http) : CardSource {
    override val game = Game.LORCANA
    private val base = "https://api.lorcast.com/v0"

    override suspend fun fetchSets(): List<CardSet> {
        val arr = http.getJson("$base/sets")?.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    CardSet(
                        game = game, code = o.getString("code"), name = o.optString("name"),
                        printedTotal = null, total = null, releaseDate = o.optStr("released_at"),
                    )
                )
            }
        }
    }

    override suspend fun fetchSetCards(set: CardSet): SetDownload {
        val arr = http.getJsonArray("$base/sets/${set.code.urlEncode()}/cards") ?: return SetDownload(emptyList())
        return SetDownload(List(arr.length()) { parse(arr.getJSONObject(it)) })
    }

    override suspend fun fetchBySetNumber(setCode: String, number: String): Card? =
        http.getJson("$base/cards/${setCode.urlEncode()}/${number.urlEncode()}")?.let { parse(it) }

    override suspend fun fetchPrices(cards: List<Card>): List<Card> {
        val wanted = cards.map { it.id }.toHashSet()
        val out = ArrayList<Card>()
        // One request per set is far cheaper than one per card.
        for ((setCode, inSet) in cards.groupBy { it.setCode }) {
            if (inSet.size <= 2) {
                inSet.forEach { c -> runCatching { fetchBySetNumber(c.setCode, c.number) }.getOrNull()?.let { out += it } }
            } else {
                val all = runCatching {
                    fetchSetCards(CardSet(game, setCode, setCode, null, null)).cards
                }.getOrDefault(emptyList())
                out += all.filter { it.id in wanted }
            }
        }
        return out
    }

    override suspend fun search(query: String): List<Card> {
        val arr = http.getJson("$base/cards/search?q=${query.trim().urlEncode()}")?.optJSONArray("results")
            ?: return emptyList()
        return List(minOf(arr.length(), 120)) { parse(arr.getJSONObject(it)) }
    }

    private fun parse(o: JSONObject): Card {
        val set = o.optJSONObject("set")
        val setCode = set?.optStr("code") ?: "?"
        val number = o.optString("collector_number")
        val images = o.optJSONObject("image_uris")?.optJSONObject("digital")
        val p = o.optJSONObject("prices")
        val prices = LinkedHashMap<String, Double>()
        p?.optPrice("usd")?.let { prices["normal"] = it }
        p?.optPrice("usd_foil")?.let { prices["foil"] = it }
        return Card(
            id = "lor:$setCode:$number", game = game, name = o.optString("name"),
            subtitle = o.optStr("version"), setCode = setCode, setName = set?.optStr("name") ?: setCode,
            number = number, rarity = o.optStr("rarity")?.replace('_', ' '),
            imageSmall = images?.optStr("small"), imageLarge = images?.optStr("normal") ?: images?.optStr("large"),
            prices = prices, variants = listOf("normal", "foil"),
            url = o.optJSONObject("purchase_uris")?.optStr("tcgplayer"),
            pricedAt = System.currentTimeMillis(),
        )
    }
}
