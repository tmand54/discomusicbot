package app.snapstack.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatchTest {
    @Test fun exactAndContained() {
        assertTrue(TextMatch.nameScore("Garganacl", listOf("Stage 2", "Garganacl")) >= 0.99)
        assertTrue(TextMatch.nameScore("Pikachu", listOf("Basic Pikachu HP 60")) >= 0.9)
        assertTrue(TextMatch.nameScore("Flabébé", listOf("Flabebe")) >= 0.99)
    }

    @Test fun ocrTyposStillMatch() {
        assertTrue(TextMatch.nameScore("Charizard ex", listOf("Charlzard ex")) > 0.8)
    }

    @Test fun differentNamesScoreLow() {
        assertTrue(TextMatch.nameScore("Bulbasaur", listOf("Charizard", "Fire Spin")) < 0.5)
    }

    @Test fun cardNameScoreUsesTheUsefulParts() {
        assertTrue(TextMatch.cardNameScore("Roronoa Zoro (001) (Parallel)", listOf("Roronoa Zoro", "Leader")) >= 0.95)
        assertTrue(TextMatch.cardNameScore("Delver of Secrets // Insectile Aberration", listOf("Delver of Secrets")) >= 0.95)
        assertTrue(TextMatch.cardNameScore("Ahri, Inquisitive", listOf("AHRI", "Unit")) >= 0.95)
        assertTrue(TextMatch.cardNameScore("Garganacl", listOf("Naclstack", "Stage 1")) < 0.6)
    }

    @Test fun editDistanceOnNumbers() {
        assertEquals(1, TextMatch.editDistance("128", "123"))
        assertEquals(0, TextMatch.editDistance("123", "123"))
        assertEquals(2, TextMatch.editDistance("128", "133"))
    }
}
