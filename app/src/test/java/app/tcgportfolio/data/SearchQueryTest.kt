package app.tcgportfolio.data

import app.tcgportfolio.model.Card
import app.tcgportfolio.model.CardSet
import app.tcgportfolio.model.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {
    private val heroines = "Extra Booster: One Piece Heroines Edition"
    private fun op(id: String, name: String, number: String, rarity: String, set: String = "24545", setName: String = heroines) =
        Card(id, Game.ONE_PIECE, name, null, set, setName, number, rarity)

    private val robins = listOf(
        op("a", "Nico Robin (054)", "EB03-054", "C"),
        op("b", "Nico Robin (055)", "EB03-055", "SR"),
        op("c", "Nico Robin (055) (Alternate Art)", "EB03-055", "SR"),
        op("d", "Nico Robin (055) (SP)", "EB03-055", "SR"),
        op("e", "DON!! Card (Nico Robin)", "DON!!", "DON!!"),
        op("f", "Nico Robin", "OP01-017", "R", set = "3188", setName = "Romance Dawn"),
        op("g", "Nami (SP)", "OP01-016", "SR", set = "24545"),
    )
    private val opIndex = SetIndex(
        listOf(
            CardSet(Game.ONE_PIECE, "24545", heroines, null, null, abbreviation = "EB03"),
            CardSet(Game.ONE_PIECE, "3188", "Romance Dawn", null, null, abbreviation = "OP01"),
        ),
    )

    private fun ids(query: String, cards: List<Card>, index: SetIndex = SetIndex.EMPTY) = SearchQuery(query).rank(cards, index).map { it.id }

    @Test fun robinEb03Sp() {
        assertEquals(listOf("d"), ids("robin eb03 sp", robins, opIndex))
        // Any order, any case.
        assertEquals(listOf("d"), ids("SP EB03 Robin", robins, opIndex))
    }

    @Test fun setWordsNumbersAndPrintings() {
        assertEquals(setOf("a", "b", "c", "d", "e"), ids("robin eb03", robins, opIndex).toSet())
        assertEquals(listOf("c"), ids("robin alt", robins, opIndex))
        assertEquals(setOf("b", "c", "d"), ids("eb03 055", robins, opIndex).toSet())
        assertEquals(setOf("b", "c", "d"), ids("eb03-055", robins, opIndex).toSet())
        // A set by name, and "op01" as a set (Romance Dawn) or a number prefix.
        assertEquals(setOf("a", "b", "c", "d", "e"), ids("robin heroines", robins, opIndex).toSet())
        assertEquals(setOf("f"), ids("robin op01", robins, opIndex).toSet())
        // SP printings across the set, by set code alone.
        assertEquals(setOf("d", "g"), ids("eb03 sp", robins, opIndex).toSet())
    }

    private val obf = Card("pkm:sv03-223", Game.POKEMON, "Charizard ex", null, "sv03", "Obsidian Flames", "223", "Special illustration rare",
        variants = listOf("holofoil"))
    private val base = Card("pkm:base1-4", Game.POKEMON, "Charizard", null, "base1", "Base", "4", "Rare Holo", variants = listOf("holofoil"))
    private val mew = Card("pkm:sv03.5-151", Game.POKEMON, "Mew ex", null, "sv03.5", "151", "151", "Double rare")
    private val bulba = Card("pkm:sv03.5-1", Game.POKEMON, "Bulbasaur", null, "sv03.5", "151", "1", "Common",
        variants = listOf("normal", "reverseHolofoil"))
    private val center = Card("pkm:tcgp:1", Game.POKEMON, "Kyogre (Pokémon Center Exclusive)", null, "24451", "ME: Mega Evolution Promo", "117", null)
    private val pkmIndex = SetIndex(
        listOf(CardSet(Game.POKEMON, "sv03", "Obsidian Flames", 197, 230), CardSet(Game.POKEMON, "sv03.5", "151", 165, 207)),
        aliases = mapOf("obf" to listOf("SV03: Obsidian Flames"), "mew" to listOf("SV: Scarlet & Violet 151")),
    )
    private val pokemon = listOf(obf, base, mew, bulba, center)

    @Test fun pokemonAbbreviationsRarityInitialsAndNumbers() {
        assertEquals(listOf(obf.id), ids("charizard obf sir", pokemon, pkmIndex))
        assertEquals(listOf(obf.id), ids("charizard obf", pokemon, pkmIndex))
        assertEquals(listOf(obf.id), ids("charizard 223/197", pokemon, pkmIndex))
        assertEquals(listOf(obf.id), ids("charizard special", pokemon, pkmIndex))
        assertEquals(listOf(bulba.id), ids("bulbasaur reverse", pokemon, pkmIndex))
        assertEquals(listOf(center.id), ids("kyogre pokemon center", pokemon, pkmIndex)) // accents ignored
    }

    @Test fun aWordThatIsANameAndASetPutsTheNameFirst() {
        val r = ids("mew", pokemon, pkmIndex)
        assertEquals(mew.id, r.first())
        assertTrue(bulba.id in r) // in the 151 set, whose abbreviation is MEW
        assertTrue(SearchQuery("mew").matchesName(mew))
        assertFalse(SearchQuery("mew").matchesName(bulba))
    }

    @Test fun unknownWordsStillReturnTheNamedCards() {
        // "xyz" matches nothing: fall back to the cards matching the most words, if a name word is among them.
        assertEquals(setOf(obf.id, base.id), ids("charizard xyz", pokemon, pkmIndex).toSet())
        assertTrue(ids("xyz qqq", pokemon, pkmIndex).isEmpty())
    }

    @Test fun printingWordsDontMatchInsideOtherNames() {
        val insect = Card("x", Game.MAGIC, "Insectile Aberration", null, "isd", "Innistrad", "51", "common")
        assertFalse(SearchQuery("sec").matches(insect))
        assertTrue(SearchQuery("insect").matches(insect))
    }

    @Test fun otherGamesCodes() {
        val bolt = Card("m", Game.MAGIC, "Lightning Bolt", null, "mkm", "Murders at Karlov Manor", "123", "uncommon", variants = listOf("normal", "foil"))
        assertTrue(SearchQuery("lightning bolt mkm").matches(bolt))
        assertTrue(SearchQuery("bolt foil").matches(bolt))
        val dm = Card("y", Game.YUGIOH, "Dark Magician", null, "lob", "Legend of Blue Eyes White Dragon", "LOB-EN005", "Ultra Rare")
        assertTrue(SearchQuery("dark magician lob").matches(dm))
        assertTrue(SearchQuery("lob 005").matches(dm))
        assertTrue(SearchQuery("magician ultra").matches(dm))
        assertFalse(SearchQuery("dark magician lob 006").matches(dm))
    }

    @Test fun nameQueriesForTheApis() {
        assertEquals(listOf("robin"), SearchQuery("robin eb03 sp").nameQueries(opIndex))
        assertEquals(listOf("charizard obf", "charizard"), SearchQuery("charizard obf sir").nameQueries(pkmIndex))
        assertEquals(emptyList<String>(), SearchQuery("eb03 sp").nameQueries(opIndex))
        assertEquals(listOf("porygon2"), SearchQuery("porygon2").nameQueries(pkmIndex))
        assertEquals(listOf("mew"), SearchQuery("mew").nameQueries(pkmIndex))
        assertEquals(listOf("elsa first chapter", "elsa first", "elsa"), SearchQuery("elsa first chapter").nameQueries(SetIndex.EMPTY))
        assertEquals("robin", SearchQuery("robin eb03 sp").nameCore(opIndex))
    }
}
