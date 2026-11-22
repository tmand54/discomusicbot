package app.tcgportfolio.data.source

import app.tcgportfolio.model.Card
import app.tcgportfolio.model.Game
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

class OnePieceGroupsTest {
    private fun set(code: String, abbr: String) = app.tcgportfolio.model.CardSet(Game.ONE_PIECE, code, abbr, null, null, abbreviation = abbr)

    // Real TCGplayer One Piece set abbreviations.
    private val sets = listOf(
        set("1", "OP04"), set("2", "OP04 PRE"), set("3", "OP-PR"), set("4", "PRB-01"), set("5", "PRB-02"),
        set("6", "OP05"), set("7", "ST-01"), set("8", "OP14-EB04"), set("9", "OP14 RE"), set("10", "OP-RP"),
        set("11", "LT-01"), set("12", "SD01"), set("13", "OP15-EB04"), set("14", "EB02"),
    )

    @Test fun primarySets() {
        assertEquals(listOf("1"), TcgCsvSource.onePiecePrimaryGroups("OP04-043", sets).map { it.code })
        assertEquals(listOf("7"), TcgCsvSource.onePiecePrimaryGroups("ST01-012", sets).map { it.code })
        // Combined releases serve both codes.
        assertEquals(listOf("8"), TcgCsvSource.onePiecePrimaryGroups("OP14-001", sets).map { it.code })
        assertEquals(listOf("8", "13"), TcgCsvSource.onePiecePrimaryGroups("EB04-010", sets).map { it.code })
    }

    @Test fun variantSetsForAJudgePromo() {
        val v = TcgCsvSource.onePieceVariantGroups("OP04-043", sets).map { it.abbreviation }.toSet()
        // Its own pre-release set + the cross-set ones (Promotion Cards hold the Judge versions).
        assertEquals(setOf("OP04 PRE", "OP-PR", "PRB-01", "PRB-02", "OP-RP", "LT-01", "SD01"), v)
        val v14 = TcgCsvSource.onePieceVariantGroups("OP14-020", sets).map { it.abbreviation }.toSet()
        assert("OP14 RE" in v14 && "OP14-EB04" !in v14 && "OP04 PRE" !in v14)
    }

    @Test fun printingLabels() {
        assertEquals("Judge Pack Vol. 3", TcgCsvSource.printingLabel("Ulti (Judge Pack Vol. 3)"))
        assertEquals("Parallel", TcgCsvSource.printingLabel("Roronoa Zoro (001) (Parallel)"))
        assertEquals("Regular", TcgCsvSource.printingLabel("Ulti"))
        assertEquals("Regular", TcgCsvSource.printingLabel("Roronoa Zoro (001)"))
    }
}
