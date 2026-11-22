package app.snapstack.data

import app.snapstack.data.net.Http
import app.snapstack.data.source.LorcanaSource
import app.snapstack.data.source.MagicSource
import app.snapstack.data.source.PokemonSource
import app.snapstack.data.source.YugiohSource
import app.snapstack.model.CardSet
import app.snapstack.model.Game
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Hits the real public APIs. Run with LIVE_API=1 ./gradlew testDebugUnitTest */
class LiveSourcesTest {
    private val http = Http(null, "SnapStack-tests/1.0")

    @Before fun live() = assumeTrue(System.getenv("LIVE_API") == "1")

    @Test fun pokemon() = runBlocking {
        val src = PokemonSource(http)
        val sets = src.fetchSets()
        val pal = sets.first { it.code == "sv02" }
        assertEquals(193, pal.printedTotal)
        assertTrue(sets.none { it.code == "A1" }) // TCG Pocket excluded
        val dl = src.fetchSetCards(pal)
        assertEquals("PAL", dl.abbreviation)
        val garganacl = dl.cards.first { it.number == "123" }
        assertEquals("Garganacl", garganacl.name)
        val priced = src.fetchPrices(listOf(garganacl)).single()
        assertTrue(priced.prices.isNotEmpty())
        println("pokemon ${priced.name} ${priced.prices} ${priced.url}")
        val search = src.search("charizard ex")
        assertTrue(search.isNotEmpty())
    }

    @Test fun magic() = runBlocking {
        val src = MagicSource(http)
        val card = src.fetchBySetNumber("mkm", "123")
        assertNotNull(card)
        assertEquals("Expedited Inheritance", card!!.name)
        assertTrue(card.prices.containsKey("normal"))
        val repriced = src.fetchPrices(listOf(card))
        assertEquals(1, repriced.size)
        val named = src.fetchByName("lightnin bolt")
        assertEquals("Lightning Bolt", named!!.name)
        val set = src.fetchSetCards(CardSet(Game.MAGIC, "mkm", "Murders at Karlov Manor", null, null))
        assertTrue(set.cards.size > 250)
        println("magic ${card.name} ${card.prices}; set cards ${set.cards.size}")
    }

    @Test fun yugioh() = runBlocking {
        val src = YugiohSource(http)
        val card = src.fetchBySetNumber("", "LOB-EN001")!!
        assertEquals("Blue-Eyes White Dragon", card.name)
        val pc = src.fetchByPasscode("89631139")!!
        assertEquals("Blue-Eyes White Dragon", pc.name)
        assertEquals(null, src.fetchBySetNumber("", "ZZZ-EN999"))
        val set = src.fetchSetCards(CardSet(Game.YUGIOH, "Legend of Blue Eyes White Dragon", "Legend of Blue Eyes White Dragon", null, null))
        assertTrue(set.cards.any { it.number == "LOB-EN001" })
        println("ygo ${card.name} ${card.prices}; set printings ${set.cards.size}")
    }

    @Test fun lorcana() = runBlocking {
        val src = LorcanaSource(http)
        val card = src.fetchBySetNumber("1", "1")!!
        assertEquals("Ariel", card.name)
        assertEquals("On Human Legs", card.subtitle)
        val priced = src.fetchPrices(listOf(card))
        assertEquals(1, priced.size)
        assertTrue(src.search("elsa").isNotEmpty())
        println("lorcana ${card.title} ${card.prices}")
    }
}
