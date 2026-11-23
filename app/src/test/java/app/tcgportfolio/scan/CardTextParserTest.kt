package app.tcgportfolio.scan

import app.tcgportfolio.model.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTextParserTest {

    /** Builds a frame from lines stacked top to bottom, like OCR output for a card held upright. */
    private fun frame(vararg lines: String, block: Int = 0): OcrFrame {
        var y = 10f
        val out = lines.map { t ->
            val l = OcrLine(t, Box(20f, y, 20f + t.length * 9f, y + 14f), block)
            y += 20f
            l
        }
        return OcrFrame(out, 720, 1000)
    }

    private fun best(f: OcrFrame, game: Game? = null) = CardTextParser.parse(f, game).firstOrNull()?.key

    @Test fun pokemonScarletVioletLine() {
        val key = best(frame("Garganacl", "Stage 2", "HP 180", "Weakness", "Retreat", "G PAL EN 123/193"))
        assertEquals(PokemonKey("123", "193", "PAL", emptyList()).id, key!!.id)
        assertEquals("PAL", (key as PokemonKey).abbrev)
        assertTrue(key.nameHints.contains("Garganacl"))
    }

    @Test fun pokemonClassicNumber() {
        val key = best(frame("Charizard", "120 HP", "Fire Spin", "4/102")) as PokemonKey
        assertEquals("4", key.number)
        assertEquals("102", key.total)
    }

    @Test fun pokemonOcrConfusionsAreRepaired() {
        val key = best(frame("Pikachu", "HP 60", "Weakness Resistance", "O45/l98")) as PokemonKey
        assertEquals("045", key.number)
        assertEquals("198", key.total)
    }

    @Test fun pokemonTrainerGallery() {
        val key = best(frame("Pikachu", "HP 70", "TG05/TG30")) as PokemonKey
        assertEquals("TG05", key.number)
        assertEquals("TG30", key.total)
    }

    @Test fun pokemonPromo() {
        val key = best(frame("Pikachu V", "HP 190", "Retreat", "SWSH061"), Game.POKEMON)
        assertTrue(key is PokemonPromoKey)
        assertEquals("061", (key as PokemonPromoKey).number)
    }

    @Test fun magicModernFrame() {
        val key = best(frame("Expedited Inheritance", "Enchantment", "0123 M", "MKM • EN"))
        assertEquals(MagicKey("mkm", "123").id, key!!.id)
    }

    @Test fun magicOlderFrameBeatsPokemonLookalike() {
        val key = best(frame("Shivan Dragon", "Creature — Dragon", "Flying", "123/269 R", "DOM • EN  Artist Name"))
        assertEquals(MagicKey("dom", "123").id, key!!.id)
    }

    @Test fun magicDotReadAsPeriod() {
        val key = best(frame("Lightning Bolt", "Instant", "0141 C", "M10 . EN"))
        assertEquals(MagicKey("m10", "141").id, key!!.id)
    }

    @Test fun yugiohIsNotOffered() {
        // Yu-Gi-Oh! was removed from the app: its codes must not turn into Yu-Gi-Oh! lookups.
        val key = best(frame("Blue-Eyes White Dragon", "[DRAGON / NORMAL]", "LOB-EN001", "ATK/3000 DEF/2500", "89631139"))
        assertTrue(key !is YugiohKey && key !is YugiohPasscodeKey)
        assertTrue(CardTextParser.parseTyped("lob-en001", null) !is YugiohKey)
        assertTrue(CardTextParser.parseTyped("LOB-EN001", Game.YUGIOH) !is YugiohKey)
    }

    @Test fun lorcanaCollectorLine() {
        val key = best(frame("Ariel", "On Human Legs", "Storyborn · Hero · Princess", "1/204 • EN • 1"))
        assertEquals(LorcanaKey("1", "1").id, key!!.id)
    }

    @Test fun lorcanaSeparatorVariants() {
        for (line in listOf("45/204 · EN · 5", "45/204 - EN - 5", "45/204 EN 5", "45/204·EN·5")) {
            val key = best(frame("Elsa", "Disney", line))
            assertEquals(line, LorcanaKey("5", "45").id, key!!.id)
        }
    }

    @Test fun onePieceCode() {
        val key = best(frame("Roronoa Zoro", "LEADER", "[DON!! x1] [Your Turn] All of your Characters gain +1000 power.", "L OP01-001"))
        assertEquals(OnePieceKey("OP01-001").id, key!!.id)
    }

    @Test fun onePieceStarterExtraAndPremium() {
        assertEquals(OnePieceKey("ST01-012").id, best(frame("Monkey.D.Luffy", "Counter", "ST01-O12"))!!.id)
        assertEquals(OnePieceKey("EB01-061").id, best(frame("Character", "EB01 - 061"))!!.id)
        assertEquals(OnePieceKey("PRB01-001").id, best(frame("Sanji", "PRB01-001"))!!.id)
    }

    @Test fun onePieceBeatsYugiohLookalike() {
        // "OP01-001" also fits the Yu-Gi-Oh! code shape; with One Piece words around it, One Piece wins.
        val dets = CardTextParser.parse(frame("Leader", "DON!! x1", "Blocker", "OP01-001"))
        assertTrue(dets.first().key is OnePieceKey)
    }

    @Test fun onePiecePromoNeedsContext() {
        assertNull(best(frame("Something", "P-001")))
        assertEquals(OnePieceKey("P-001").id, best(frame("Monkey.D.Luffy", "[DON!! x2] [Rush]", "Counter", "P-001"))!!.id)
        assertEquals(OnePieceKey("P-001").id, best(frame("P-001"), Game.ONE_PIECE)!!.id)
    }

    @Test fun onePieceDonCard() {
        assertEquals(OnePieceDonKey().id, best(frame("DON!!"))!!.id)
        assertEquals(OnePieceDonKey().id, best(frame("DONI!", "+1000"))!!.id)
        assertEquals(OnePieceDonKey().id, best(frame("D0N!!", "Your Turn"), Game.ONE_PIECE)!!.id)
    }

    @Test fun regularOnePieceCardIsNotDon() {
        // Rules text mentions DON!!, but the card code wins.
        val key = best(frame("Roronoa Zoro", "Leader", "[DON!! x1] [Your Turn]", "All of your Characters gain +1000 power.", "DON!!", "OP01-001"))
        assertEquals(OnePieceKey("OP01-001").id, key!!.id)
        // A text-heavy card with a lone "DON!!" line but no readable code isn't treated as a DON!! card.
        val wordy = frame("Nami", "Character", "Straw Hat Crew", "[On Play] Look at 5 cards", "Counter", "DON!!", "Trigger Draw 1 card")
        assertTrue(CardTextParser.parse(wordy).none { it.key is OnePieceDonKey })
    }

    @Test fun dragonBallFusionWorld() {
        assertEquals(DragonBallKey("FB01-001").id, best(frame("Son Goku", "Leader", "FB01-001"))!!.id)
        assertEquals(DragonBallKey("FS01-01").id, best(frame("Son Goku", "FS01-O1"))!!.id)
        assertEquals(DragonBallKey("FP-006").id, best(frame("Vegeta", "FP-006"))!!.id)
    }

    @Test fun dragonBallSuperCardGame() {
        assertEquals(DragonBallKey("BT22-064").id, best(frame("SS Son Goku", "Battle", "BT22-064"))!!.id)
        assertEquals(DragonBallKey("BT5-115").id, best(frame("Frieza", "BT5-115"))!!.id)
        assertEquals(DragonBallKey("TB3-018").id, best(frame("TB3-018"))!!.id)
        // "P-001" is a Dragon Ball promo when the card reads like Dragon Ball…
        assertEquals(DragonBallKey("P-001").id, best(frame("Son Goku", "Saiyan", "Combo", "Critical", "P-001"))!!.id)
    }

    @Test fun sharedCodesFollowTheCardsWords() {
        // "ST01-001" is printed by both games.
        val op = best(frame("Monkey.D.Luffy", "Leader", "[DON!! x1]", "Counter", "Blocker", "ST01-001"))
        assertTrue(op is OnePieceKey)
        val dbs = best(frame("Son Goten", "Saiyan", "Combo", "Awaken", "Critical", "ST01-001"))
        assertTrue(dbs is DragonBallKey)
        assertTrue(best(frame("ST01-001"), Game.DRAGON_BALL) is DragonBallKey)
        assertTrue(best(frame("ST01-001"), Game.ONE_PIECE) is OnePieceKey)
    }

    @Test fun riftboundSetAndNumber() {
        val key = best(frame("Ahri, Inquisitive", "Unit", "OGN • 119/298")) as RiftboundKey
        assertEquals("OGN", key.set); assertEquals("119", key.number); assertEquals("298", key.total)
    }

    @Test fun riftboundAltArtAndSigned() {
        val alt = best(frame("Jinx", "SFD · 201a/221")) as RiftboundKey
        assertEquals("201a", alt.number)
        val signed = best(frame("Viktor", "OGN 308*/298")) as RiftboundKey
        assertEquals("308*", signed.number)
    }

    @Test fun riftboundWithoutReadableSetCode() {
        // Number only: Riftbound when the game is locked or the card's words say Riftbound.
        val locked = best(frame("Poro Herder", "061/298"), Game.RIFTBOUND) as RiftboundKey
        assertEquals(null, locked.set); assertEquals("061", locked.number)
        val byContext = best(frame("Poro Herder", "Unit", "Might 3", "Deathknell", "Legion", "061/298"))
        assertTrue(byContext is RiftboundKey)
        // Without either, a bare number/total stays a Pokémon read.
        assertTrue(best(frame("Pikachu", "HP 60", "061/198")) is PokemonKey)
    }

    @Test fun riftboundPublicCode() {
        val key = best(frame("Teemo", "OGN-263")) as RiftboundKey
        assertEquals("OGN", key.set); assertEquals("263", key.number); assertEquals(null, key.total)
    }

    @Test fun riftboundCodesCanBeRegistered() {
        assertTrue(best(frame("Unit", "ZZQ • 010/120")) !is RiftboundKey)
        CardTextParser.registerRiftboundCodes(listOf("ZZQ"))
        assertTrue(best(frame("Unit", "ZZQ • 010/120")) is RiftboundKey)
    }

    @Test fun lockedGameIgnoresOtherGames() {
        val f = frame("Charizard", "HP 120", "4/102")
        assertNull(best(f, Game.MAGIC))
        assertNull(best(f, Game.YUGIOH))
    }

    @Test fun copyrightAndStatsAreNotCardNumbers() {
        val f = frame("©2023 Pokémon/Nintendo/Creatures/GAME FREAK", "ATK/3000 DEF/2500", "Illus. 5ban Graphics")
        assertTrue(CardTextParser.parse(f).isEmpty())
    }

    @Test fun binderGridReadsEachPocket() {
        val lines = listOf(
            OcrLine("4/102", Box(50f, 300f, 110f, 315f)),
            OcrLine("Charizard", Box(50f, 20f, 150f, 35f)),
            OcrLine("LOB-EN001", Box(450f, 280f, 540f, 295f)),
            OcrLine("12/204 • EN • 5", Box(60f, 780f, 200f, 795f)),
        )
        val cells = CardTextParser.parseGrid(OcrFrame(lines, 800, 1000), rows = 2, cols = 2)
        assertEquals(4, cells.size)
        assertEquals(PokemonKey("4", "102").id, cells[0]!!.key.id)
        assertTrue(cells[1]?.key !is YugiohKey) // Yu-Gi-Oh! isn't offered
        assertEquals(LorcanaKey("5", "12").id, cells[2]!!.key.id)
        assertNull(cells[3])
    }

    @Test fun typedEntries() {
        assertEquals(PokemonKey("123", "193").id, CardTextParser.parseTyped("123/193", null)!!.id)
        assertEquals(MagicKey("mkm", "123").id, CardTextParser.parseTyped("MKM 123", null)!!.id)
        assertEquals(MagicKey("mkm", "7").id, CardTextParser.parseTyped("mkm/007", Game.MAGIC)!!.id)
        assertEquals(LorcanaKey("5", "12").id, CardTextParser.parseTyped("12/204 EN 5", null)!!.id)
        assertEquals(LorcanaKey("5", "12").id, CardTextParser.parseTyped("5 12", Game.LORCANA)!!.id)
        val pal = CardTextParser.parseTyped("PAL 123", Game.POKEMON) as PokemonKey
        assertEquals("PAL", pal.abbrev); assertEquals("123", pal.number)
        val named = CardTextParser.parseTyped("Charizard ex 199/165", Game.POKEMON) as PokemonKey
        assertEquals(listOf("Charizard ex"), named.nameHints)
        assertEquals(OnePieceKey("OP01-001").id, CardTextParser.parseTyped("op01-001", null)!!.id)
        assertEquals(OnePieceKey("P-001").id, CardTextParser.parseTyped("P-001", Game.ONE_PIECE)!!.id)
        assertTrue(CardTextParser.parseTyped("DON!!", null) is OnePieceDonKey)
        assertEquals(DragonBallKey("FB01-001").id, CardTextParser.parseTyped("fb01-001", null)!!.id)
        assertEquals(DragonBallKey("BT22-064").id, CardTextParser.parseTyped("BT22-064", null)!!.id)
        assertTrue(CardTextParser.parseTyped("ST01-001", null) is OnePieceKey)
        assertTrue(CardTextParser.parseTyped("ST01-001", Game.DRAGON_BALL) is DragonBallKey)
        assertEquals(DragonBallKey("P-001").id, CardTextParser.parseTyped("P-001", Game.DRAGON_BALL)!!.id)
        assertTrue(CardTextParser.parseTyped("don", Game.ONE_PIECE) is OnePieceDonKey)
        val rb = CardTextParser.parseTyped("OGN 179/298", null) as RiftboundKey
        assertEquals("OGN", rb.set); assertEquals("179", rb.number); assertEquals("298", rb.total)
        val rb2 = CardTextParser.parseTyped("ogn-7", null) as RiftboundKey
        assertEquals("007", rb2.number); assertEquals(null, rb2.total)
        val rb3 = CardTextParser.parseTyped("179/298", Game.RIFTBOUND) as RiftboundKey
        assertEquals(null, rb3.set)
        val name = CardTextParser.parseTyped("Lightning Bolt", Game.MAGIC)
        assertTrue(name is NameKey)
        assertNull(CardTextParser.parseTyped("Lightning Bolt", null))
    }

    @Test fun megaEvolutionPromosWithOrWithoutTheP() {
        for (game in listOf(Game.POKEMON, null)) for (text in listOf("MEP 117", "MEP EN 117", "ME117", "ME 117", "Kyogre ME117")) {
            val key = CardTextParser.parseTyped(text, game) as? PokemonPromoKey
            assertEquals("$text / $game", "MEP", key?.prefix)
            assertEquals("$text / $game", "117", key?.number)
        }
        assertEquals(listOf("Kyogre"), (CardTextParser.parseTyped("Kyogre ME117", null) as PokemonPromoKey).nameHints)
        assertEquals("045", (CardTextParser.parseTyped("SWSH045", null) as PokemonPromoKey).number)
        // Scanned: the promo code on the card's bottom line.
        val scanned = best(frame("Kyogre", "HP 130", "Origin Pulse", "MEP EN 117"), Game.POKEMON) as PokemonPromoKey
        assertEquals("MEP", scanned.prefix)
        // Typed codes of other games still work.
        assertTrue(CardTextParser.parseTyped("MKM 123", null) is MagicKey)
        assertTrue(CardTextParser.parseTyped("PAL 123", Game.POKEMON) is PokemonKey)
        // Main-set codes that start with ME aren't promos.
        val main = best(frame("Kyogre", "HP 130", "MEG EN 034/132"), Game.POKEMON)
        assertTrue(main !is PokemonPromoKey)
    }
}
