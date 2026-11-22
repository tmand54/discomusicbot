package app.tcgportfolio.data.source

import app.tcgportfolio.data.net.Http
import app.tcgportfolio.model.Card
import app.tcgportfolio.model.CardSet
import app.tcgportfolio.model.Game
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Magic: The Gathering via Scryfall (free, no key; asks for ~10 requests/second max). */
class MagicSource(private val http: Http) : CardSource {
    override val game = Game.MAGIC
    private val base = "https://api.scryfall.com"

    override suspend fun fetchSets(): List<CardSet> {
        val arr = http.getJson("$base/sets")?.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optBoolean("digital")) continue
                if (o.optString("set_type") in setOf("token", "memorabilia", "minigame")) continue
                add(
                    CardSet(
                        game = game, code = o.getString("code").lowercase(Locale.US), name = o.optString("name"),
                        printedTotal = o.optInt("printed_size").takeIf { it > 0 },
                        total = o.optInt("card_count").takeIf { it > 0 },
                        releaseDate = o.optStr("released_at"),
                    )
                )
            }
        }
    }

    override suspend fun fetchSetCards(set: CardSet): SetDownload {
        val cards = ArrayList<Card>()
        var url: String? = "$base/cards/search?q=${"e:${set.code}".urlEncode()}&unique=prints&order=set&include_extras=true"
        var pages = 0
        while (url != null && pages < 20) {
            val page = http.getJson(url) ?: break
            page.optJSONArray("data")?.let { arr -> for (i in 0 until arr.length()) cards += parse(arr.getJSONObject(i)) }
            url = if (page.optBoolean("has_more")) page.optStr("next_page") else null
            pages++
        }
        return SetDownload(cards)
    }

    override suspend fun fetchBySetNumber(setCode: String, number: String): Card? =
        http.getJson("$base/cards/${setCode.lowercase(Locale.US)}/${number.urlEncode()}")?.let { parse(it) }

    suspend fun fetchByName(name: String, setCode: String? = null): Card? {
        val set = setCode?.let { "&set=${it.lowercase(Locale.US)}" } ?: ""
        return http.getJson("$base/cards/named?fuzzy=${name.urlEncode()}$set")?.let { parse(it) }
    }

    override suspend fun fetchPrices(cards: List<Card>): List<Card> {
        val out = ArrayList<Card>()
        for (chunk in cards.chunked(75)) {
            val identifiers = JSONArray()
            chunk.forEach { c -> identifiers.put(JSONObject().put("set", c.setCode).put("collector_number", c.number)) }
            val resp = http.postJson("$base/cards/collection", JSONObject().put("identifiers", identifiers)) ?: continue
            JSONObject(resp).optJSONArray("data")?.let { arr -> for (i in 0 until arr.length()) out += parse(arr.getJSONObject(i)) }
        }
        return out
    }

    override suspend fun search(query: String): List<Card> {
        val q = query.trim()
        val url = "$base/cards/search?q=${q.urlEncode()}&unique=prints&order=released&dir=desc"
        val arr = http.getJson(url)?.optJSONArray("data") ?: return emptyList()
        return List(minOf(arr.length(), 120)) { parse(arr.getJSONObject(it)) }
    }

    private fun parse(o: JSONObject): Card {
        val set = o.getString("set").lowercase(Locale.US)
        val number = o.getString("collector_number")
        val images = o.optJSONObject("image_uris")
            ?: o.optJSONArray("card_faces")?.optJSONObject(0)?.optJSONObject("image_uris")
        val p = o.optJSONObject("prices")
        val prices = LinkedHashMap<String, Double>()
        p?.optPrice("usd")?.let { prices["normal"] = it }
        p?.optPrice("usd_foil")?.let { prices["foil"] = it }
        p?.optPrice("usd_etched")?.let { prices["etched"] = it }
        val finishes = o.optJSONArray("finishes")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        val variants = finishes.map { if (it == "nonfoil") "normal" else it }
        return Card(
            id = "mtg:$set:$number", game = game, name = o.optString("name"),
            setCode = set, setName = o.optString("set_name", set.uppercase(Locale.US)), number = number,
            rarity = o.optStr("rarity")?.replaceFirstChar { it.titlecase(Locale.US) },
            imageSmall = images?.optStr("small") ?: images?.optStr("normal"),
            imageLarge = images?.optStr("large") ?: images?.optStr("normal"),
            prices = prices, variants = variants,
            url = o.optJSONObject("purchase_uris")?.optStr("tcgplayer") ?: o.optStr("scryfall_uri"),
            pricedAt = System.currentTimeMillis(),
        )
    }
}
