package app.snapstack.data.source

import app.snapstack.data.net.Http
import app.snapstack.model.Card
import app.snapstack.scan.TextMatch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Graded (slab) values from PriceCharting — only when the user has added their own PriceCharting API
 * key in Settings (the API is a paid PriceCharting subscription; there is no free graded-price feed).
 * Prices come back in US cents. Card fields, per PriceCharting's API docs:
 *   graded-price = grade 9, box-only-price = grade 9.5, new-price = grade 8 / 8.5, cib-price = grade 7 / 7.5,
 *   manual-only-price = PSA 10, bgs-10-price = BGS 10, condition-17-price = CGC 10, condition-18-price = SGC 10.
 */
class PriceChartingSource(private val http: Http, private val key: () -> String?) {
    val enabled: Boolean get() = !key().isNullOrBlank()

    suspend fun gradedPrice(card: Card, grader: String, grade: String): Double? {
        val token = key()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val field = fieldFor(grader, grade) ?: return null
        val q = listOf(card.name.substringBefore(" (").trim(), card.number.substringBefore('/'), card.setName).joinToString(" ")
        val body = http.get("https://www.pricecharting.com/api/products?t=${token.urlEncode()}&q=${q.urlEncode()}") ?: return null
        val products = JSONObject(body).optJSONArray("products") ?: return null
        val product = bestProduct(products, card) ?: return null
        val cents = product.optInt(field, 0)
        return if (cents > 0) cents / 100.0 else null
    }

    companion object {
        fun fieldFor(grader: String, grade: String): String? {
            val g = grade.toDoubleOrNull() ?: return null
            return when {
                g >= 10.0 -> when (grader.uppercase(Locale.US)) {
                    "PSA" -> "manual-only-price"
                    "BGS" -> "bgs-10-price"
                    "CGC" -> "condition-17-price"
                    "SGC" -> "condition-18-price"
                    else -> null // e.g. TAG 10: PriceCharting's API has no field for it
                }
                g >= 9.5 -> "box-only-price"
                g >= 9.0 -> "graded-price"
                g >= 8.0 -> "new-price"
                g >= 7.0 -> "cib-price"
                else -> null
            }
        }

        /** The listed product that is this card: its number in the product name, then the best name/set match. */
        fun bestProduct(products: JSONArray, card: Card): JSONObject? {
            val number = card.number.substringBefore('/').trimStart('0').lowercase(Locale.US)
            val name = card.name.substringBefore(" (")
            var best: JSONObject? = null
            var bestScore = 0.0
            for (i in 0 until products.length()) {
                val p = products.optJSONObject(i) ?: continue
                val pname = p.optString("product-name")
                val numbers = Regex("#\\s?([A-Za-z0-9-]+)").findAll(pname).map { it.groupValues[1].trimStart('0').lowercase(Locale.US) }.toList()
                val numberOk = number.isEmpty() || number in numbers
                if (!numberOk) continue
                val score = TextMatch.nameScore(name, listOf(pname.substringBefore('#').trim())) +
                    0.3 * TextMatch.similarity(TextMatch.norm(card.setName), TextMatch.norm(p.optString("console-name")))
                if (score > bestScore) { bestScore = score; best = p }
            }
            return best?.takeIf { bestScore >= 0.6 }
        }
    }
}
