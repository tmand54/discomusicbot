package app.snapstack.scan

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
}
