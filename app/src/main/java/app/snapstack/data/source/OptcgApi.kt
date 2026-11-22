package app.snapstack.data.source

import app.snapstack.data.net.Http
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import java.util.Locale

/**
 * Second source for One Piece: optcgapi.com (free, no key). It has what TCGplayer's catalog lacks —
 * name search — so One Piece cards can be searched online without downloading every set, and it
 * provides TCGplayer market prices as a backup when tcgcsv.com can't be reached.
 */
class OptcgApi(private val http: Http) {
    private val base = "https://optcgapi.com/api"

    data class Hit(val code: String, val imageId: String, val name: String, val setName: String, val price: Double?)

    /** Cards whose name contains [query], from booster sets, starter decks and promos. */
    suspend fun search(query: String): List<Hit> = coroutineScope {
        val q = query.trim().urlEncode()
        listOf("sets", "decks", "promos").map { kind ->
            async { runCatching { http.get("$base/$kind/filtered/?card_name=$q")?.let { parse(it) } }.getOrNull().orEmpty() }
        }.awaitAll().flatten().distinctBy { it.imageId }
    }

    /** Every printing that shares a card code ("OP01-001" → regular + parallels). */
    suspend fun versions(code: String): List<Hit> {
        val c = code.uppercase(Locale.US)
        val kind = when {
            c.startsWith("ST") -> "decks"
            c.startsWith("P-") -> "promos"
            else -> "sets"
        }
        return http.get("$base/$kind/card/$c/")?.let { parse(it) }.orEmpty()
    }

    private fun parse(body: String): List<Hit> {
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val code = o.optStr("card_set_id") ?: continue
                add(
                    Hit(
                        code = code.uppercase(Locale.US), imageId = o.optStr("card_image_id") ?: code,
                        name = o.optString("card_name"), setName = o.optString("set_name"),
                        price = o.optPrice("market_price") ?: o.optPrice("inventory_price"),
                    )
                )
            }
        }
    }
}
