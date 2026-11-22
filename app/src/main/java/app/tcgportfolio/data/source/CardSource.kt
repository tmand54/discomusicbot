package app.tcgportfolio.data.source

import app.tcgportfolio.model.Card
import app.tcgportfolio.model.CardSet
import app.tcgportfolio.model.Game
import org.json.JSONObject

/** Network access to one game's public card database. All functions run on IO and may throw IOException. */
interface CardSource {
    val game: Game

    /** Every set (used for offline downloads and for Pokémon printed-total matching). */
    suspend fun fetchSets(): List<CardSet>

    /** Every card in a set. Cards may come back without prices (pricedAt = 0). */
    suspend fun fetchSetCards(set: CardSet): SetDownload

    /** A single printing by set code + collector number. */
    suspend fun fetchBySetNumber(setCode: String, number: String): Card?

    /** Fills in current prices. Returns only the cards that could be priced. */
    suspend fun fetchPrices(cards: List<Card>): List<Card>

    suspend fun search(query: String): List<Card>
}

data class SetDownload(val cards: List<Card>, val abbreviation: String? = null)

internal fun JSONObject.optStr(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

/** Reads numbers that APIs sometimes send as strings ("0.13") and sometimes as numbers. */
internal fun JSONObject.optPrice(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val v = when (val raw = opt(key)) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
    return v?.takeIf { it > 0.0 && !it.isNaN() }
}

internal fun String.urlEncode(): String = java.net.URLEncoder.encode(this, "UTF-8")
