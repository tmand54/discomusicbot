package app.snapstack.data

import app.snapstack.data.net.Http
import app.snapstack.data.source.LorcanaSource
import app.snapstack.data.source.MagicSource
import app.snapstack.data.source.PokemonSource
import app.snapstack.data.source.OptcgApi
import app.snapstack.data.source.TcgCsvSource
import app.snapstack.data.source.TcgPlayerFallback
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

    @Test fun onePiece() = runBlocking {
        val src = TcgCsvSource(http, Game.ONE_PIECE, TcgCsvSource.ONE_PIECE_CATEGORY, "op")
        val sets = src.fetchSets()
        val op01 = sets.first { it.abbreviation == "OP01" }
        assertTrue(sets.size > 50)
        val cards = src.fetchSetCards(op01).cards
        val zoro = cards.filter { it.number == "OP01-001" }.sortedBy { TcgCsvSource.specialness(it.name) }
        assertTrue("regular + parallel", zoro.size >= 2)
        assertTrue(zoro.first().name.startsWith("Roronoa Zoro"))
        assertTrue(zoro.first().prices.isNotEmpty())
        assertTrue(TcgCsvSource.specialness(zoro.last().name) > TcgCsvSource.specialness(zoro.first().name))
        val repriced = src.fetchPrices(zoro)
        assertEquals(zoro.size, repriced.size)
        println("onepiece ${zoro.map { it.name to it.prices }} sets=${sets.size} op01=${cards.size}")
    }

    @Test fun onePieceJudgeVariant() = runBlocking {
        val src = TcgCsvSource(http, Game.ONE_PIECE, TcgCsvSource.ONE_PIECE_CATEGORY, "op")
        val sets = src.fetchSets()
        val primary = TcgCsvSource.onePiecePrimaryGroups("OP04-043", sets)
        val variants = TcgCsvSource.onePieceVariantGroups("OP04-043", sets)
        assertTrue(variants.any { it.name == "One Piece Promotion Cards" })
        val promos = variants.first { it.name == "One Piece Promotion Cards" }
        val printings = (primary + promos).flatMap { src.fetchSetCards(it).cards }.filter { it.number == "OP04-043" }
        assertTrue("regular Ulti", printings.any { it.name == "Ulti" })
        assertTrue("Judge Ulti", printings.any { it.name.contains("Judge") })
        // OP14 / EB04 live in a combined set.
        assertTrue(TcgCsvSource.onePiecePrimaryGroups("OP14-001", sets).isNotEmpty())
        assertTrue(TcgCsvSource.onePiecePrimaryGroups("EB04-001", sets).isNotEmpty())
        println("onepiece OP04-043 printings: ${printings.map { TcgCsvSource.printingLabel(it.name) to it.prices }}; variant sets=${variants.size}")
    }

    @Test fun onePieceDonCards() = runBlocking {
        val src = TcgCsvSource(http, Game.ONE_PIECE, TcgCsvSource.ONE_PIECE_CATEGORY, "op")
        val promos = src.fetchSets().first { it.name == "One Piece Promotion Cards" }
        val dons = src.fetchSetCards(promos).cards.filter { it.number == TcgCsvSource.DON_NUMBER }
        assertTrue("promo DON!! cards: ${dons.size}", dons.size > 20)
        assertTrue(dons.any { it.name.contains("Gold") })
        println("onepiece DON!! in promos: ${dons.size}, e.g. ${dons.take(3).map { it.name to it.prices }}")
    }

    @Test fun tcgplayerBackupPrices() = runBlocking {
        val fb = TcgPlayerFallback(http)
        val pkm = PokemonSource(http)
        val garganacl = pkm.fetchSetCards(pkm.fetchSets().first { it.code == "sv02" }).cards.first { it.number == "123" }
        val p1 = fb.pricesFor(garganacl)
        assertTrue("pokemon backup prices $p1", p1 != null && p1.isNotEmpty())
        val bolt = MagicSource(http).fetchBySetNumber("m10", "146")!!
        val p2 = fb.pricesFor(bolt)
        assertTrue("magic backup prices $p2", p2 != null && p2.isNotEmpty())
        val ariel = LorcanaSource(http).fetchBySetNumber("1", "1")!!
        val p3 = fb.pricesFor(ariel)
        assertTrue("lorcana backup prices $p3", p3 != null && p3.isNotEmpty())
        val bewd = YugiohSource(http).fetchBySetNumber("", "LOB-EN001")!!
        val p4 = fb.pricesFor(bewd)
        assertTrue("yugioh backup prices $p4", p4 != null && p4.isNotEmpty())
        // Identify a Pokémon card from the printed set code alone (no TCGdex involved).
        val found = fb.findPokemon("PAL", emptyList(), "123", "193")
        assertTrue(found.any { it.name == "Garganacl" && it.id.startsWith("pkm:tcgp:") })
        val repriced = fb.refreshOwn(found)
        assertTrue(repriced.isNotEmpty())
        println("tcgplayer backup: pkm=$p1 mtg(${bolt.name})=$p2 lor=$p3 ygo=$p4 found=${found.map { it.id to it.name }}")
    }

    @Test fun optcgapiBackup() = runBlocking {
        val api = OptcgApi(http)
        val hits = api.search("zoro")
        assertTrue(hits.any { it.code == "OP01-001" })
        val versions = api.versions("OP01-001")
        assertTrue(versions.size >= 2 && versions.all { it.code == "OP01-001" })
        println("optcgapi zoro hits=${hits.size} versions=${versions.map { it.name to it.price }}")
    }

    @Test fun dragonBall() = runBlocking {
        val src = TcgCsvSource(
            http, Game.DRAGON_BALL, TcgCsvSource.DRAGON_BALL_FUSION_WORLD_CATEGORY, "dbs",
            extraCategories = listOf(TcgCsvSource.DRAGON_BALL_SUPER_CATEGORY),
        )
        val sets = src.fetchSets()
        assertTrue("both Dragon Ball games: ${sets.size}", sets.size > 120)
        // Every code family maps onto real TCGplayer sets.
        for (code in listOf("FB01-001", "FS01-01", "FP-006", "BT22-064", "BT5-115", "TB3-018", "DB1-003", "EX15-01", "P-001", "ST01-001")) {
            assertTrue("no set for $code", TcgCsvSource.dragonBallGroups(code, sets).isNotEmpty())
        }
        val fb01 = TcgCsvSource.dragonBallGroups("FB01-001", sets)
        val goku = fb01.flatMap { src.fetchSetCards(it).cards }.filter { it.number == "FB01-001" }
        assertTrue(goku.any { it.name == "Son Goku" && it.prices.isNotEmpty() })
        assertTrue(goku.any { it.name == "Son Goku (Alternate Art)" })
        // A Super Card Game set (other category) downloads through the same source.
        val bt5 = TcgCsvSource.dragonBallGroups("BT5-115", sets).first()
        val bt5Cards = src.fetchSetCards(bt5).cards
        assertTrue(bt5Cards.any { it.number == "BT5-115" })
        assertTrue("TCGplayer's _PR suffix is dropped", bt5Cards.none { it.number.contains('_') })
        println("dragonball sets=${sets.size} goku=${goku.map { it.name to it.prices }} bt5=${bt5.name}:${bt5Cards.size}")
    }

    @Test fun riftbound() = runBlocking {
        val src = TcgCsvSource(http, Game.RIFTBOUND, TcgCsvSource.RIFTBOUND_CATEGORY, "rb")
        val sets = src.fetchSets()
        val ogn = sets.first { it.abbreviation == "OGN" }
        val cards = src.fetchSetCards(ogn).cards
        val c = cards.first { it.number == "119/298" }
        assertTrue(c.name.startsWith("Ahri"))
        assertTrue(c.prices.isNotEmpty())
        assertTrue(cards.any { it.number.removeSuffix("/298").toIntOrNull()?.let { n -> n > 298 } == true })
        println("riftbound ${c.name} ${c.prices} ${c.rarity} sets=${sets.size} ogn=${cards.size} codes=${sets.mapNotNull { it.abbreviation }}")
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
