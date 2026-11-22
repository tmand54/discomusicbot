package app.snapstack.data.source

import app.snapstack.data.net.Http
import app.snapstack.model.Card
import app.snapstack.model.CardSet
import app.snapstack.model.Game
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.util.Locale

/**
 * Yu-Gi-Oh! via YGOPRODeck (free, no key, 20 req/s). A printing is identified by its set code
 * (e.g. LOB-EN001); rarities of the same printing are stored as variants.
 * For Yu-Gi-Oh! the set *name* is used as the set code because short codes are not unique.
 */
class YugiohSource(private val http: Http) : CardSource {
    override val game = Game.YUGIOH
    private val base = "https://db.ygoprodeck.com/api/v7"

    override suspend fun fetchSets(): List<CardSet> {
        val arr = http.getJsonArray("$base/cardsets.php") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optStr("set_name") ?: continue
                add(
                    CardSet(
                        game = game, code = name, name = name,
                        printedTotal = o.optInt("num_of_cards").takeIf { it > 0 },
                        total = o.optInt("num_of_cards").takeIf { it > 0 },
                        abbreviation = o.optStr("set_code"), releaseDate = o.optStr("tcg_date"),
                    )
                )
            }
        }
    }

    override suspend fun fetchSetCards(set: CardSet): SetDownload {
        val arr = http.getJson("$base/cardinfo.php?cardset=${set.name.urlEncode()}")?.optJSONArray("data")
            ?: return SetDownload(emptyList())
        val byCode = LinkedHashMap<String, Card>()
        for (i in 0 until arr.length()) {
            val card = arr.getJSONObject(i)
            val sets = card.optJSONArray("card_sets") ?: continue
            for (j in 0 until sets.length()) {
                val s = sets.getJSONObject(j)
                if (s.optString("set_name") != set.name) continue
                mergePrinting(byCode, card, s)
            }
        }
        return SetDownload(byCode.values.toList())
    }

    /** [number] is the full set code, e.g. "LOB-EN001". */
    override suspend fun fetchBySetNumber(setCode: String, number: String): Card? {
        val code = number.uppercase(Locale.US)
        val o = http.getJson("$base/cardsetsinfo.php?setcode=${code.urlEncode()}") ?: return null
        if (o.has("error")) return null
        val imageId = o.optLong("id")
        val rarity = o.optStr("set_rarity")
        val prices = LinkedHashMap<String, Double>()
        val price = o.optPrice("set_price")
        if (rarity != null && price != null) prices[rarity] = price
        val setName = o.optString("set_name")
        return Card(
            id = "ygo:$code", game = game, name = o.optString("name"),
            setCode = setName, setName = setName, number = code, rarity = rarity,
            imageSmall = "https://images.ygoprodeck.com/images/cards_small/$imageId.jpg",
            imageLarge = "https://images.ygoprodeck.com/images/cards/$imageId.jpg",
            prices = prices, variants = listOfNotNull(rarity),
            url = "https://www.tcgplayer.com/search/yugioh/product?q=${code.urlEncode()}",
            pricedAt = System.currentTimeMillis(),
        )
    }

    /** Lookup by the 8-digit passcode printed bottom-left; identifies the card but not the printing. */
    suspend fun fetchByPasscode(passcode: String): Card? {
        val arr = http.getJson("$base/cardinfo.php?id=$passcode")?.optJSONArray("data") ?: return null
        if (arr.length() == 0) return null
        return genericCard(arr.getJSONObject(0))
    }

    override suspend fun fetchPrices(cards: List<Card>): List<Card> = coroutineScope {
        val gate = Semaphore(4)
        cards.map { card ->
            async {
                gate.withPermit {
                    runCatching {
                        if (card.id.startsWith("ygo:pc:")) fetchByPasscode(card.id.removePrefix("ygo:pc:"))
                        else fetchBySetNumber(card.setCode, card.number)?.let { fresh ->
                            // cardsetsinfo returns one rarity; keep the others we already know.
                            fresh.copy(
                                prices = card.prices + fresh.prices,
                                variants = (card.variants + fresh.variants).distinct(),
                                imageSmall = card.imageSmall ?: fresh.imageSmall,
                                imageLarge = card.imageLarge ?: fresh.imageLarge,
                            )
                        }
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
    }

    override suspend fun search(query: String): List<Card> {
        val q = query.trim()
        if (setCodeRegex.matches(q.uppercase(Locale.US))) {
            return listOfNotNull(fetchBySetNumber("", q))
        }
        val arr = http.getJson("$base/cardinfo.php?fname=${q.urlEncode()}")?.optJSONArray("data") ?: return emptyList()
        val byCode = LinkedHashMap<String, Card>()
        for (i in 0 until minOf(arr.length(), 40)) {
            val card = arr.getJSONObject(i)
            val sets = card.optJSONArray("card_sets")
            if (sets == null || sets.length() == 0) {
                val g = genericCard(card); byCode[g.id] = g; continue
            }
            for (j in 0 until sets.length()) {
                val s = sets.getJSONObject(j)
                if (!s.optString("set_code").contains("-EN") && !s.optString("set_code").contains("-E")) continue
                mergePrinting(byCode, card, s)
            }
            if (byCode.size > 150) break
        }
        return byCode.values.toList()
    }

    private fun mergePrinting(into: MutableMap<String, Card>, card: JSONObject, s: JSONObject) {
        val code = s.optString("set_code").uppercase(Locale.US)
        if (code.isBlank()) return
        val rarity = s.optStr("set_rarity")
        val price = s.optPrice("set_price")
        val img = card.optJSONArray("card_images")?.optJSONObject(0)
        val existing = into["ygo:$code"]
        val prices = LinkedHashMap(existing?.prices ?: emptyMap())
        if (rarity != null && price != null) prices[rarity] = price
        val variants = ((existing?.variants ?: emptyList()) + listOfNotNull(rarity)).distinct()
        into["ygo:$code"] = Card(
            id = "ygo:$code", game = game, name = card.optString("name"),
            setCode = s.optString("set_name"), setName = s.optString("set_name"), number = code,
            rarity = existing?.rarity ?: rarity,
            imageSmall = img?.optStr("image_url_small"), imageLarge = img?.optStr("image_url"),
            prices = prices, variants = variants,
            url = card.optStr("ygoprodeck_url"), pricedAt = System.currentTimeMillis(),
        )
    }

    private fun genericCard(card: JSONObject): Card {
        val id = card.optLong("id")
        val img = card.optJSONArray("card_images")?.optJSONObject(0)
        val price = card.optJSONArray("card_prices")?.optJSONObject(0)?.optPrice("tcgplayer_price")
        return Card(
            id = "ygo:pc:$id", game = game, name = card.optString("name"),
            setCode = "Passcode", setName = "Any printing", number = id.toString(),
            rarity = card.optStr("type"),
            imageSmall = img?.optStr("image_url_small"), imageLarge = img?.optStr("image_url"),
            prices = price?.let { mapOf("normal" to it) } ?: emptyMap(),
            variants = listOf("normal"), url = card.optStr("ygoprodeck_url"),
            pricedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        val setCodeRegex = Regex("^[A-Z0-9]{2,5}-(EN|E|DE|FR|IT|SP|PT|AE|KR)?[A-Z]?\\d{2,3}$")
    }
}
