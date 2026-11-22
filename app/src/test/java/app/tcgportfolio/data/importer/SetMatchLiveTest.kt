package app.tcgportfolio.data.importer

import app.tcgportfolio.data.net.Http
import app.tcgportfolio.data.source.LorcanaSource
import app.tcgportfolio.data.source.MagicSource
import app.tcgportfolio.data.source.PokemonSource
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Ripfolio set names are TCGplayer group names: check they map onto each source's sets. LIVE_API=1 */
class SetMatchLiveTest {
    private val http = Http(null, "TCGPortfolio-tests/1.0")

    @Before fun live() = assumeTrue(System.getenv("LIVE_API") == "1")

    private fun tcgplayerGroups(category: Int): List<String> = runBlocking {
        val arr = JSONObject(http.get("https://tcgcsv.com/tcgplayer/$category/groups")!!).getJSONArray("results")
        List(arr.length()) { arr.getJSONObject(it).getString("name") }
    }

    private fun report(label: String, names: List<String>, match: (String) -> String?, nameOf: (String) -> String? = { null }) {
        val misses = names.filter { match(it) == null }
        names.mapNotNull { n -> match(n)?.let { c -> "$n => ${nameOf(c) ?: c}" } }
            .filter { line -> val (a, b) = line.split(" => "); ImportParsing.normalizeSetName(a) != ImportParsing.normalizeSetName(b) }
            .forEach { println("  [$label] $it") }
        println("$label: ${names.size - misses.size}/${names.size} TCGplayer set names matched; unmatched e.g. ${misses.take(25)}")
    }

    @Test fun pokemon() = runBlocking {
        val sets = PokemonSource(http).fetchSets()
        fun m(n: String) = ImportParsing.bestSetMatch(n, sets)?.code
        assertEquals("sv02", m("SV02: Paldea Evolved"))
        assertEquals("sv03.5", m("SV: Scarlet & Violet 151"))
        assertEquals("swsh3", m("SWSH03: Darkness Ablaze"))
        assertEquals("base1", m("Base Set"))
        assertEquals("sv01", m("SV01: Scarlet & Violet Base Set"))
        assertEquals("swsh12.5", m("SWSH: Crown Zenith"))
        assertEquals("xy12", m("XY - Evolutions"))
        report("pokemon", tcgplayerGroups(3), { m(it) }) { c -> sets.first { it.code == c }.name }
    }

    @Test fun magic() = runBlocking {
        val sets = MagicSource(http).fetchSets()
        fun m(n: String) = ImportParsing.bestSetMatch(n, sets)?.code
        assertEquals("mkm", m("Murders at Karlov Manor"))
        assertEquals("blb", m("Bloomburrow"))
        assertEquals("m10", m("Magic 2010 (M10)"))
        assertEquals("mkc", m("Commander: Murders at Karlov Manor"))
        assertEquals("blc", m("Commander: Bloomburrow"))
        assertEquals("10e", m("10th Edition"))
        report("magic", tcgplayerGroups(1), { m(it) }) { c -> sets.first { it.code == c }.name }
    }

    @Test fun lorcana() = runBlocking {
        val sets = LorcanaSource(http).fetchSets()
        fun m(n: String) = ImportParsing.bestSetMatch(n, sets)?.code
        assertEquals("1", m("The First Chapter"))
        assertEquals("4", m("Ursula's Return"))
        report("lorcana", tcgplayerGroups(71), { m(it) }) { c -> sets.first { it.code == c }.name }
    }
}
