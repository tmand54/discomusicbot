package app.snapstack.scan

import app.snapstack.model.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlabReaderTest {
    private fun lines(vararg t: String) = t.mapIndexed { i, s -> OcrLine(s, Box(10f, i * 20f, 300f, i * 20f + 14f)) }

    @Test fun psaLabel() {
        val s = SlabReader.read(lines("2023 POKEMON SV 151", "#199", "CHARIZARD ex", "SPECIAL ILLUSTRATION RARE", "GEM MT 10", "105442370", "PSA"))!!
        assertEquals("PSA", s.grader); assertEquals("10", s.grade); assertEquals("GEM MT", s.gradeLabel)
        assertEquals("105442370", s.cert); assertEquals(Game.POKEMON, s.game); assertEquals("199", s.labelNumber)
        assertTrue(s.nameLines.first().startsWith("CHARIZARD"))
        val key = SlabReader.labelKey(s, null) as NameKey
        assertEquals(Game.POKEMON, key.game); assertEquals("199", key.number)
    }

    @Test fun psaWithoutBrandTextIsRecognisedByItsGradeWords() {
        val s = SlabReader.read(lines("2022 POKEMON JAPANESE", "PIKACHU", "NM-MT 8", "71234567"))!!
        assertEquals("PSA", s.grader); assertEquals("8", s.grade); assertEquals("71234567", s.cert)
    }

    @Test fun beckettWithSubgrades() {
        val s = SlabReader.read(lines("BECKETT", "2023 ONE PIECE OP04 #OP04-043", "ULTI", "9.5 GEM MINT", "CENTERING 9.5  CORNERS 9.5", "EDGES 10  SURFACE 9.5", "0012345678"))!!
        assertEquals("BGS", s.grader); assertEquals("9.5", s.grade); assertEquals("GEM MINT", s.gradeLabel)
        assertEquals(mapOf("centering" to "9.5", "corners" to "9.5", "edges" to "10", "surface" to "9.5"), s.subgrades)
        assertEquals("0012345678", s.cert)
        assertEquals(OnePieceKey("OP04-043").id, SlabReader.labelKey(s, null)!!.id)
    }

    @Test fun beckettBlackLabelAndNoBrandRead() {
        val s = SlabReader.read(lines("PRISTINE 10", "CENTERING 10 CORNERS 10 EDGES 10 SURFACE 10", "0098765432"))!!
        assertEquals("BGS", s.grader); assertEquals("10", s.grade)
    }

    @Test fun tagLabel() {
        val s = SlabReader.read(lines("TAG", "2024 MAGIC MKM #123", "EXPEDITED INHERITANCE", "GEM MINT 10", "A1234567"))!!
        assertEquals("TAG", s.grader); assertEquals("10", s.grade); assertEquals("A1234567", s.cert)
        assertEquals(Game.MAGIC, s.game)
    }

    @Test fun cgcLabel() {
        val s = SlabReader.read(lines("CGC", "PRISTINE 10", "Centering 10 Corners 10", "4123456789"))!!
        assertEquals("CGC", s.grader); assertEquals("10", s.grade); assertEquals("4123456789", s.cert)
    }

    @Test fun notASlab() {
        // A raw Pokémon card: "TAG TEAM" and HP numbers must not look like a slab.
        assertNull(SlabReader.read(lines("Pikachu & Zekrom GX", "TAG TEAM", "HP 240", "Full Blitz 150", "33/181")))
        assertNull(SlabReader.read(lines("Charizard", "120 HP", "4/102")))
        assertNull(SlabReader.read(emptyList()))
    }

    @Test fun wholeSlabReadsTheCardThroughTheCase() {
        // Label on top, then the card's own text seen through the case.
        val all = lines(
            "2023 POKEMON SV 151", "#199", "CHARIZARD ex", "GEM MT 10", "105442370", "PSA",
            "Charizard ex", "HP 330", "Burning Darkness", "Explosion Blaze", "199/165 SIR",
        )
        val slab = SlabReader.read(all)!!
        assertEquals("PSA 10", Grading.from(slab).display)
        val key = CardTextParser.parseLines(all, slab.game).first().key
        assertEquals(Game.POKEMON, key.game)
        assertTrue(key.label, key.label.contains("199"))
    }

    @Test fun onePieceSlabFallsBackToTheLabelCode() {
        val slab = SlabReader.read(lines("TAG", "2023 ONE PIECE KINGDOMS OF INTRIGUE", "ULTI #OP04-043", "GEM MINT 10", "A1234567"))!!
        assertEquals("TAG", slab.grader); assertEquals("A1234567", slab.cert)
        val key = SlabReader.labelKey(slab, null)!!
        assertEquals(Game.ONE_PIECE, key.game); assertTrue(key.label, key.label.contains("OP04-043"))
    }
}
