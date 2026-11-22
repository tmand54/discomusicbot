package app.snapstack.scan

import app.snapstack.model.Game
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

    @Test fun yugiohSetCodeBeatsPasscode() {
        val key = best(frame("Blue-Eyes White Dragon", "[DRAGON / NORMAL]", "LOB-EN001", "ATK/3000 DEF/2500", "89631139"))
        assertEquals(YugiohKey("LOB-EN001").id, key!!.id)
    }

    @Test fun yugiohZeroReadAsLetterO() {
        val key = best(frame("Dark Magician", "MP24-ENO12", "ATK/2500 DEF/2100"))
        assertEquals(YugiohKey("MP24-EN012").id, key!!.id)
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
        assertEquals(YugiohKey("LOB-EN001").id, cells[1]!!.key.id)
        assertEquals(LorcanaKey("5", "12").id, cells[2]!!.key.id)
        assertNull(cells[3])
    }

    @Test fun typedEntries() {
        assertEquals(PokemonKey("123", "193").id, CardTextParser.parseTyped("123/193", null)!!.id)
        assertEquals(MagicKey("mkm", "123").id, CardTextParser.parseTyped("MKM 123", null)!!.id)
        assertEquals(MagicKey("mkm", "7").id, CardTextParser.parseTyped("mkm/007", Game.MAGIC)!!.id)
        assertEquals(YugiohKey("LOB-EN001").id, CardTextParser.parseTyped("lob-en001", null)!!.id)
        assertEquals(LorcanaKey("5", "12").id, CardTextParser.parseTyped("12/204 EN 5", null)!!.id)
        assertEquals(LorcanaKey("5", "12").id, CardTextParser.parseTyped("5 12", Game.LORCANA)!!.id)
        val pal = CardTextParser.parseTyped("PAL 123", Game.POKEMON) as PokemonKey
        assertEquals("PAL", pal.abbrev); assertEquals("123", pal.number)
        val named = CardTextParser.parseTyped("Charizard ex 199/165", Game.POKEMON) as PokemonKey
        assertEquals(listOf("Charizard ex"), named.nameHints)
        val name = CardTextParser.parseTyped("Lightning Bolt", Game.MAGIC)
        assertTrue(name is NameKey)
        assertNull(CardTextParser.parseTyped("Lightning Bolt", null))
    }
}
