package app.tcgportfolio.data.source

import java.util.Locale
import app.tcgportfolio.data.net.Http
import app.tcgportfolio.model.Card
import app.tcgportfolio.model.CardSet
import app.tcgportfolio.model.Game
import app.tcgportfolio.model.Variants
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/** Pokémon TCG (English) via TCGdex — free, no key, TCGplayer market prices per printing. */
class PokemonSource(
    private val http: Http,
    private val setNameLookup: (String) -> String? = { null },
) : CardSource {
    override val game = Game.POKEMON
    private val base = "https://api.tcgdex.net/v2/en"

    override suspend fun fetchSets(): List<CardSet> {
        val arr = http.getJsonArray("$base/sets") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                if (isPocket(id, o.optStr("logo") ?: o.optStr("symbol"))) continue
                val count = o.optJSONObject("cardCount")
                add(
                    CardSet(
                        game = game, code = id, name = o.optString("name", id),
                        printedTotal = count?.optInt("official")?.takeIf { it > 0 },
                        total = count?.optInt("total")?.takeIf { it > 0 },
                        // The list is chronological; keep that order for "newest first" sorting.
                        releaseDate = "%05d".format(i),
                    )
                )
            }
        }
    }

    override suspend fun fetchSetCards(set: CardSet): SetDownload {
        val o = http.getJson("$base/sets/${set.code}") ?: return SetDownload(emptyList())
        val setName = o.optString("name", set.name)
        val abbrev = o.optJSONObject("abbreviation")?.optStr("official")
        val cards = o.optJSONArray("cards")
        val list = buildList {
            if (cards != null) for (i in 0 until cards.length()) {
                val c = cards.getJSONObject(i)
                val image = c.optStr("image")
                add(
                    Card(
                        id = "pkm:" + c.getString("id"), game = game, name = c.optString("name"),
                        setCode = set.code, setName = setName, number = c.optString("localId"),
                        imageSmall = image?.let { "$it/low.webp" }, imageLarge = image?.let { "$it/high.webp" },
                    )
                )
            }
        }
        return SetDownload(list, abbrev)
    }

    override suspend fun fetchBySetNumber(setCode: String, number: String): Card? {
        fetchCard("$setCode-$number")?.let { return it }
        val digits = number.toIntOrNull() ?: return null
        return fetchCard("$setCode-%03d".format(digits))
    }

    suspend fun fetchCard(tcgdexId: String): Card? = http.getJson("$base/cards/$tcgdexId")?.let { parseDetail(it) }

    override suspend fun fetchPrices(cards: List<Card>): List<Card> = coroutineScope {
        val gate = Semaphore(6)
        cards.map { card ->
            async {
                gate.withPermit {
                    runCatching { fetchCard(card.id.removePrefix("pkm:")) }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
    }

    override suspend fun search(query: String): List<Card> = searchFiltered(query, null, null)

    /**
     * Name search with TCGdex's own exact filters on rarity and/or set. Its results don't include the
     * rarity, so cards found through a rarity filter are tagged with it.
     */
    suspend fun searchFiltered(name: String?, rarity: String?, setId: String?): List<Card> {
        val params = listOfNotNull(
            name?.takeIf { it.isNotBlank() }?.let { "name=${it.urlEncode()}" },
            rarity?.let { "rarity=${"eq:$it".urlEncode()}" },
            setId?.let { "set.id=${"eq:$it".urlEncode()}" },
        )
        if (params.isEmpty()) return emptyList()
        val arr = http.getJsonArray("$base/cards?${params.joinToString("&")}&pagination:itemsPerPage=80") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val id = c.getString("id")
                val image = c.optStr("image")
                if (image != null && image.contains("/tcgp/")) continue
                val localId = c.optString("localId")
                val setCode = id.removeSuffix("-$localId")
                if (isPocket(setCode, image)) continue
                add(
                    Card(
                        id = "pkm:$id", game = game, name = c.optString("name"), setCode = setCode,
                        setName = setNameLookup(setCode) ?: setCode, number = localId, rarity = rarity,
                        imageSmall = image?.let { "$it/low.webp" }, imageLarge = image?.let { "$it/high.webp" },
                    )
                )
            }
        }.reversed() // newest sets first
    }

    @Volatile private var rarityNames: List<String>? = null

    /** TCGdex's rarity names ("Special illustration rare", "Hyper rare"…). */
    suspend fun rarities(): List<String> = rarityNames ?: run {
        val arr = runCatching { http.getJsonArray("$base/rarities") }.getOrNull()
        val list = arr?.let { a -> List(a.length()) { a.getString(it) } }?.filter { it != "None" }?.takeIf { it.isNotEmpty() } ?: KNOWN_RARITIES
        list.also { rarityNames = it }
    }

    private fun parseDetail(o: JSONObject): Card {
        val set = o.optJSONObject("set")
        val image = o.optStr("image")
        val prices = LinkedHashMap<String, Double>()
        var productId: Long? = null

        fun readTcgplayer(tp: JSONObject?) {
            if (tp == null) return
            for (key in tp.keys()) {
                val v = tp.optJSONObject(key) ?: continue
                val price = v.optPrice("marketPrice") ?: v.optPrice("midPrice") ?: v.optPrice("lowPrice") ?: continue
                prices.putIfAbsent(Variants.fromKebab(key), price)
                if (productId == null && v.has("productId")) productId = v.optLong("productId")
            }
        }
        readTcgplayer(o.optJSONObject("pricing")?.optJSONObject("tcgplayer"))
        o.optJSONArray("variants_detailed")?.let { arr ->
            for (i in 0 until arr.length()) readTcgplayer(arr.optJSONObject(i)?.optJSONObject("pricing")?.optJSONObject("tcgplayer"))
        }

        val variants = mutableListOf<String>()
        if (prices.isEmpty()) o.optJSONObject("variants")?.let { v ->
            if (v.optBoolean("normal")) variants += "normal"
            if (v.optBoolean("holo")) variants += "holofoil"
            if (v.optBoolean("reverse")) variants += "reverseHolofoil"
            if (v.optBoolean("firstEdition")) variants += "1stEdition"
        }

        val setCode = set?.optStr("id") ?: o.getString("id").substringBeforeLast('-')
        return Card(
            id = "pkm:" + o.getString("id"), game = game, name = o.optString("name"),
            setCode = setCode, setName = set?.optStr("name") ?: setCode,
            number = o.optString("localId"), rarity = o.optStr("rarity"),
            imageSmall = image?.let { "$it/low.webp" }, imageLarge = image?.let { "$it/high.webp" },
            prices = prices, variants = variants,
            url = productId?.let { "https://www.tcgplayer.com/product/$it" },
            pricedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private val pocketId = Regex("^(A\\d+[a-z]?|B\\d+[a-z]?|P-[AB])$")
        fun isPocket(setId: String, imageOrLogo: String?): Boolean =
            pocketId.matches(setId) || (imageOrLogo?.contains("/tcgp/") == true)

        /** Black Star promo prefixes printed on cards -> TCGdex set ids. */
        /** Used when TCGdex's rarity list can't be fetched. */
        val KNOWN_RARITIES = listOf(
            "ACE SPEC Rare", "Amazing Rare", "Black White Rare", "Classic Collection", "Common", "Double rare",
            "Full Art Trainer", "Holo Rare", "Holo Rare V", "Holo Rare VMAX", "Holo Rare VSTAR", "Hyper rare",
            "Illustration rare", "LEGEND", "Mega Attack Rare", "Mega Hyper Rare", "Promo", "Radiant Rare", "Rare",
            "Rare Holo", "Rare Holo LV.X", "Rare PRIME", "Secret Rare", "Shiny Ultra Rare", "Shiny rare",
            "Special illustration rare", "Ultra Rare", "Uncommon",
        )

        /**
         * The rarities a search word means: by initials ("sir", "ir", "hr", "sr") or a distinctive word
         * ("hyper", "secret", "illustration"). Generic words that would match most rarities are ignored.
         */
        fun raritiesFor(word: String, all: List<String>): List<String> {
            val w = word.lowercase(Locale.US)
            if (w.length < 2 || w in setOf("rare", "holo", "v", "ex")) return emptyList()
            val hits = all.filter { r ->
                val words = r.lowercase(Locale.US).split(Regex("[^a-z0-9.]+")).filter { it.isNotEmpty() }
                w == r.lowercase(Locale.US) || w == words.joinToString("") { it.take(1) } || (w.length >= 4 && words.any { it.startsWith(w) })
            }
            return if (hits.size in 1..3) hits else emptyList()
        }

        val promoSets = mapOf(
            "SWSH" to "swshp", "SVP" to "svp", "SM" to "smp", "XY" to "xyp", "BW" to "bwp",
            "MEP" to "mep", "DP" to "dpp", "HGSS" to "hgssp",
        )
    }
}
