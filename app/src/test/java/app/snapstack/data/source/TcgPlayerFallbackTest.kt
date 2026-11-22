package app.snapstack.data.source

import app.snapstack.model.Card
import app.snapstack.model.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TcgPlayerFallbackTest {
    private fun card(game: Game, name: String, number: String, rarity: String? = null, subtitle: String? = null) =
        Card("x", game, name, subtitle, "s", "Set", number, rarity)

    private fun product(id: Long, name: String, number: String, prices: Map<String, Double>) =
        Card("tcgp:$id", Game.POKEMON, name, null, "g", "Group", number, prices = prices)

    @Test fun matchesByNumberAndName() {
        val products = listOf(
            product(1, "Garganacl - 123/193", "123/193", mapOf("holofoil" to 0.13, "reverseHolofoil" to 0.26)),
            product(2, "Naclstack - 122/193", "122/193", mapOf("normal" to 0.05)),
            product(3, "Garganacl - 123/193 (Cosmos Holo)", "123/193", mapOf("holofoil" to 3.0)),
        )
        val hit = TcgPlayerFallback.bestProduct(card(Game.POKEMON, "Garganacl", "123"), products)
        assertEquals("tcgp:1", hit!!.id) // regular printing preferred over the special one
        assertNull(TcgPlayerFallback.bestProduct(card(Game.POKEMON, "Pikachu", "123"), products))
    }

    @Test fun lorcanaTitleMatchesTcgplayerName() {
        val products = listOf(product(9, "Ariel - On Human Legs", "1/204", mapOf("normal" to 0.13, "foil" to 0.58)))
        val ariel = card(Game.LORCANA, "Ariel", "1", subtitle = "On Human Legs")
        assertEquals("tcgp:9", TcgPlayerFallback.bestProduct(ariel, products)!!.id)
    }

    @Test fun lorcanaColdFoilIsFoil() {
        val ariel = card(Game.LORCANA, "Ariel", "1", subtitle = "On Human Legs")
        assertEquals(mapOf("normal" to 0.13, "foil" to 0.58), TcgPlayerFallback.adaptPrices(ariel, mapOf("normal" to 0.13, "coldFoil" to 0.58)))
    }

    @Test fun yugiohPricesKeyedByRarity() {
        val ygo = card(Game.YUGIOH, "Blue-Eyes White Dragon", "LOB-EN001", rarity = "Ultra Rare")
        val adapted = TcgPlayerFallback.adaptPrices(ygo, mapOf("1stEdition" to 900.0, "unlimited" to 250.0))
        assertEquals(mapOf("Ultra Rare" to 250.0), adapted)
    }
}
