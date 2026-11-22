package app.snapstack.data.source

import app.snapstack.model.Card
import app.snapstack.model.Game
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PriceChartingSourceTest {
    @Test fun gradeFields() {
        assertEquals("manual-only-price", PriceChartingSource.fieldFor("PSA", "10"))
        assertEquals("bgs-10-price", PriceChartingSource.fieldFor("BGS", "10"))
        assertEquals("condition-17-price", PriceChartingSource.fieldFor("CGC", "10"))
        assertEquals("condition-18-price", PriceChartingSource.fieldFor("sgc", "10"))
        assertNull(PriceChartingSource.fieldFor("TAG", "10"))
        assertEquals("box-only-price", PriceChartingSource.fieldFor("BGS", "9.5"))
        assertEquals("graded-price", PriceChartingSource.fieldFor("PSA", "9"))
        assertEquals("new-price", PriceChartingSource.fieldFor("PSA", "8.5"))
        assertEquals("cib-price", PriceChartingSource.fieldFor("TAG", "7"))
        assertNull(PriceChartingSource.fieldFor("PSA", "6"))
        assertNull(PriceChartingSource.fieldFor("PSA", "AUTH"))
    }

    private fun product(name: String, console: String, psa10: Int) =
        JSONObject().put("product-name", name).put("console-name", console).put("manual-only-price", psa10)

    @Test fun picksTheProductWithTheCardsNumber() {
        val card = Card("x", Game.POKEMON, "Charizard ex", null, "sv3pt5", "151", "199/165")
        val products = JSONArray()
            .put(product("Charizard ex #6", "Pokemon Scarlet & Violet 151", 500))
            .put(product("Charizard ex #199", "Pokemon Scarlet & Violet 151", 120000))
            .put(product("Charizard ex #223", "Pokemon Obsidian Flames", 9000))
        assertEquals(120000, PriceChartingSource.bestProduct(products, card)!!.optInt("manual-only-price"))
    }

    @Test fun onePieceCodesAndNoMatch() {
        val card = Card("x", Game.ONE_PIECE, "Ulti", null, "OP04", "Kingdoms of Intrigue", "OP04-043")
        val products = JSONArray()
            .put(product("Ulti #OP04-043", "One Piece Kingdoms of Intrigue", 2500))
            .put(product("Ulti [Alternate Art] #OP04-043", "One Piece Kingdoms of Intrigue", 9000))
        assertEquals(2500, PriceChartingSource.bestProduct(products, card)!!.optInt("manual-only-price"))
        val other = Card("y", Game.ONE_PIECE, "Nami", null, "OP01", "Romance Dawn", "OP01-016")
        assertNull(PriceChartingSource.bestProduct(products, other))
    }
}
