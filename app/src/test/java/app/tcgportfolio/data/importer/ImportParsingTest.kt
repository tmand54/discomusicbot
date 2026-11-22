package app.tcgportfolio.data.importer

import app.tcgportfolio.model.CardSet
import app.tcgportfolio.model.Condition
import app.tcgportfolio.model.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportParsingTest {

    /** Shaped like Ripfolio's "ripfolio-local" v1 backup (Settings → Back up). */
    private val ripfolio = """
        {"format":"ripfolio-local","version":1,"exported_at":"2026-11-20T10:00:00.000Z",
         "items":[
          {"id":"a1","card_id":"6f1c0e1e-0000-4000-8000-000000000001","card_name":"Charizard ex - 199/165","card_number":"199/165",
           "set_name":"SV: Scarlet & Violet 151","game_name":"Pokémon TCG","quantity":2,"condition":"near_mint","is_foil":true,
           "status":"owned","is_graded":false,"cost_basis":95.5,"price_when_added":110.25,"acquisition_type":"bought_single",
           "created_at":"2026-03-01T12:00:00.000Z"},
          {"id":"a2","card_name":"Roronoa Zoro (001) (Parallel)","card_number":"OP01-001","set_name":"Romance Dawn",
           "game_name":"One Piece Card Game","quantity":1,"condition":"lightly_played","status":"owned",
           "is_graded":true,"grading_company":"psa","grade_received":10,"graded_value":1500,"cert_number":"12345678",
           "acquisition_type":"pulled_from_pack","created_at":"2026-04-02T08:30:00.000Z"},
          {"id":"a3","card_name":"Lightning Bolt","card_number":"141","set_name":"Magic 2010","game_name":"Magic: The Gathering",
           "quantity":4,"condition":"moderately_played","status":"owned","acquisition_type":"gift_other"},
          {"id":"a4","card_name":"Pikachu","card_number":"025","set_name":"Pokémon Japan Promos","game_name":"Pokémon Japan",
           "quantity":1,"status":"owned"},
          {"id":"a5","card_name":"Dark Magician","card_number":"LOB-EN005","set_name":"Legend of Blue Eyes White Dragon",
           "game_name":"Yu-Gi-Oh!","quantity":3,"status":"sold","sold_price":20}
         ],
         "sealed":[{"id":"s1","sealed_product_id":"x","quantity":2,"status":"owned"}],
         "lists":[{"id":"l1","name":"Chase"}],
         "list_cards":[{"list_id":"l1","card_id":"c1"}],
         "wantlist":[{"id":"w1","card_id":"c2"},{"id":"w2","card_id":"c3"}],
         "tracked_sets":[]}
    """.trimIndent()

    @Test fun detectsFormats() {
        assertEquals(ImportFormat.RIPFOLIO, ImportParsing.detect(ripfolio))
        assertEquals(ImportFormat.SNAPSTACK, ImportParsing.detect("""{"format":"snapstack-backup","version":1}"""))
        assertEquals(ImportFormat.CSV, ImportParsing.detect("Card Name,Set,Quantity\nPikachu,Base Set,1"))
        assertEquals(ImportFormat.UNKNOWN, ImportParsing.detect("""{"hello":"world"}"""))
        assertEquals(ImportFormat.RIPFOLIO, ImportParsing.detect("﻿" + ripfolio))
    }

    @Test fun readsRipfolioBackup() {
        val p = ImportParsing.parseRipfolio(ripfolio)
        assertEquals(4, p.rows.size) // the sold card is skipped
        val zard = p.rows[0]
        assertEquals(Game.POKEMON, zard.game)
        assertEquals(2, zard.qty)
        assertTrue(zard.foil)
        assertEquals(95.5, zard.costEach!!, 0.001)
        assertEquals(110.25, zard.priceAtAdd!!, 0.001)
        assertEquals(1772366400000L, zard.addedAt)

        val zoro = p.rows[1]
        assertEquals(Game.ONE_PIECE, zoro.game)
        assertEquals(Condition.LP, zoro.condition)
        assertEquals("PSA", zoro.grader); assertEquals("10", zoro.grade); assertEquals(1500.0, zoro.gradedValue!!, 0.0)
        assertTrue(zoro.notes!!.contains("Cert #12345678"))

        val bolt = p.rows[2]
        assertEquals(Game.MAGIC, bolt.game); assertEquals(Condition.MP, bolt.condition); assertEquals(0.0, bolt.costEach!!, 0.0)

        assertNull(p.rows[3].game) // Pokémon Japan isn't supported
        assertEquals(3, p.skipped["sold cards"])
        assertEquals(2, p.skipped.entries.first { it.key.startsWith("sealed") }.value)
        assertEquals(2, p.skipped.entries.first { it.key.startsWith("wantlist") }.value)
        assertEquals(1, p.skipped["custom lists"])
    }

    @Test fun rejectsGarbage() {
        val e = runCatching { ImportParsing.parse("just some text") }.exceptionOrNull()
        assertTrue(e is ImportException)
        assertTrue(runCatching { ImportParsing.parse("PK\u0003\u0004zipdata") }.exceptionOrNull() is ImportException)
    }

    @Test fun readsCsvWithQuotesAndAliases() {
        val csv = "\"Product Name\",\"Set Name\",Number,Quantity,Condition,Printing,\"Price Paid\",Game\n" +
            "\"Charizard ex - 199/165\",\"SV: Scarlet & Violet 151\",199/165,2,Near Mint,Holofoil,\"$1,234.50\",Pokemon\n" +
            "\"Ariel - On Human Legs\",The First Chapter,1/204,1,Lightly Played,Normal,,Lorcana\n" +
            "\"Name, with comma\",\"Set \"\"quoted\"\"\",5,1,,,,Magic\n"
        val p = ImportParsing.parseCsv(csv)
        assertEquals(3, p.rows.size)
        assertEquals(1234.5, p.rows[0].costEach!!, 0.001)
        assertTrue(p.rows[0].foil)
        assertEquals(Game.LORCANA, p.rows[1].game)
        assertFalse(p.rows[1].foil)
        assertEquals("Name, with comma", p.rows[2].name)
        assertEquals("Set \"quoted\"", p.rows[2].setName)
    }

    @Test fun readsOwnCsvExport() {
        val csv = "Game,Name,Set,Number,Rarity,Printing,Condition,Quantity,Grader,Grade,Purchase Price,Market Price,Total Value,Added\n" +
            "Pokémon,Garganacl,Paldea Evolved,123,Rare,Reverse Holo,NM,1,,,0.50,0.26,0.26,2026-11-22\n"
        val r = ImportParsing.parseCsv(csv).rows.single()
        assertEquals(Game.POKEMON, r.game); assertEquals("123", r.number); assertEquals("reverseHolofoil", ImportParsing.printingHint(r))
        assertEquals(0.5, r.costEach!!, 0.0)
    }

    @Test fun languageColumn() {
        val p = ImportParsing.parseCsv("Name,Number,Game,Language\nUlti,OP04-043,One Piece,Japanese\nZoro,OP01-001,One Piece,English\n")
        assertEquals("JP", p.rows[0].language)
        assertNull(p.rows[1].language)
        assertEquals("JP", ImportParsing.languageFrom("日本語"))
        assertEquals("JP", ImportParsing.languageFrom("JP"))
        assertNull(ImportParsing.languageFrom("EN"))
    }

    @Test fun semicolonCsv() {
        val p = ImportParsing.parseCsv("name;set;qty\nPikachu;Base Set;3\n")
        assertEquals(3, p.rows.single().qty)
    }

    @Test fun csvWithoutNameColumnIsRejected() {
        assertTrue(runCatching { ImportParsing.parseCsv("foo,bar\n1,2") }.exceptionOrNull() is ImportException)
    }

    @Test fun pastedList() {
        val p = ImportParsing.parseList("4 Lightning Bolt\n2x 123/193\nOP01-001\nLOB-EN001 x3\nDON!!\n// comment\nCharizard", null)
        assertEquals(5 + 1, p.rows.size)
        assertEquals(4, p.rows[0].qty); assertNull(p.rows[0].game) // a bare name needs a game
        assertEquals(ImportParsing.NO_GAME, p.rows[0].gameLabel)
        assertEquals(2, p.rows[1].qty); assertEquals(Game.POKEMON, p.rows[1].game)
        assertEquals(Game.ONE_PIECE, p.rows[2].game)
        assertEquals(3, p.rows[3].qty); assertEquals(Game.YUGIOH, p.rows[3].game)
        assertTrue(p.rows[4].key is app.tcgportfolio.scan.OnePieceDonKey)
        val magic = ImportParsing.parseList("4 Lightning Bolt", Game.MAGIC).rows.single()
        assertEquals(Game.MAGIC, magic.game); assertEquals(4, magic.qty)
    }

    @Test fun pastedTextCanBeCsv() {
        val csv = ImportParsing.parsePasted("Name,Set,Quantity\nPikachu,Base Set,2\n", Game.POKEMON)
        assertEquals(ImportFormat.CSV, csv.format)
        assertEquals(Game.POKEMON, csv.rows.single().game)
        val list = ImportParsing.parsePasted("2 Pikachu, the electric mouse", Game.POKEMON)
        assertEquals(ImportFormat.LIST, list.format)
    }

    @Test fun namesNumbersAndPrintings() {
        assertEquals("Charizard ex", ImportParsing.baseName("Charizard ex - 199/165"))
        assertEquals("Lightning Bolt", ImportParsing.baseName("Lightning Bolt (Borderless)"))
        assertEquals("199/165", ImportParsing.numberFromName("Charizard ex - 199/165"))
        assertEquals("TG05/TG30", ImportParsing.numberFromName("Pikachu - TG05/TG30"))
        assertEquals("199", ImportParsing.numberOnly("199/165"))
        assertEquals("165", ImportParsing.totalOf("199/165"))
        val rev = ImportRow(Game.POKEMON, null, "Pikachu (Reverse Holofoil)", "25", null)
        assertEquals("reverseHolofoil", ImportParsing.printingHint(rev))
    }

    @Test fun gameNames() {
        assertEquals(Game.POKEMON, ImportParsing.gameFrom("Pokémon TCG"))
        assertEquals(Game.POKEMON, ImportParsing.gameFrom("pokemon"))
        assertNull(ImportParsing.gameFrom("Pokémon (JP)"))
        assertNull(ImportParsing.gameFrom("Pokemon Japan"))
        assertEquals(Game.MAGIC, ImportParsing.gameFrom("Magic: The Gathering"))
        assertEquals(Game.YUGIOH, ImportParsing.gameFrom("Yu-Gi-Oh!"))
        assertEquals(Game.LORCANA, ImportParsing.gameFrom("Disney Lorcana"))
        assertEquals(Game.ONE_PIECE, ImportParsing.gameFrom("One Piece Card Game"))
        assertEquals(Game.RIFTBOUND, ImportParsing.gameFrom("Riftbound: League of Legends Trading Card Game"))
        assertEquals(Game.DRAGON_BALL, ImportParsing.gameFrom("Dragon Ball Super Fusion World"))
        assertEquals(Game.DRAGON_BALL, ImportParsing.gameFrom("Dragon Ball"))
        assertNull(ImportParsing.gameFrom("Dragon Ball Z TCG"))
        assertEquals(Game.DRAGON_BALL, ImportParsing.gameFrom(null, "FB01-001"))
        assertEquals(Game.DRAGON_BALL, ImportParsing.gameFrom(null, "BT22-064"))
        assertEquals("Son Goku", ImportParsing.baseName("Son Goku - FB01-001"))
        assertEquals("Son Goku (Alternate Art)", ImportParsing.stripNumber("Son Goku - FB01-001 (Alternate Art)"))
        assertEquals("Pikachu - 25th Anniversary", ImportParsing.stripNumber("Pikachu - 25th Anniversary"))
        assertEquals(Game.ONE_PIECE, ImportParsing.gameFrom(null, "OP05-119"))
        assertEquals(Game.YUGIOH, ImportParsing.gameFrom(null, "LOB-EN001"))
    }

    @Test fun conditions() {
        assertEquals(Condition.NM, ImportParsing.conditionFrom("gem_mint"))
        assertEquals(Condition.LP, ImportParsing.conditionFrom("Lightly Played"))
        assertEquals(Condition.MP, ImportParsing.conditionFrom("moderately_played"))
        assertEquals(Condition.HP, ImportParsing.conditionFrom("heavily_played"))
        assertEquals(Condition.DMG, ImportParsing.conditionFrom("damaged"))
    }

    private fun sets(game: Game, vararg pairs: Pair<String, String>) = pairs.map { (code, name) -> CardSet(game, code, name, null, null) }

    @Test fun matchesTcgplayerSetNames() {
        val pkm = sets(
            Game.POKEMON, "sv01" to "Scarlet & Violet", "sv02" to "Paldea Evolved", "sv03.5" to "151",
            "swsh3" to "Darkness Ablaze", "base1" to "Base Set", "base4" to "Base Set 2", "swsh12.5" to "Crown Zenith",
            "xy12" to "Evolutions", "sv08.5" to "Prismatic Evolutions", "sv07" to "Stellar Crown", "svp" to "SVP Black Star Promos",
        )
        val cases = mapOf(
            "SV02: Paldea Evolved" to "sv02", "SV: Scarlet & Violet 151" to "sv03.5", "SWSH03: Darkness Ablaze" to "swsh3",
            "Base Set" to "base1", "Base Set 2" to "base4", "Base Set (Shadowless)" to "base1",
            "SV01: Scarlet & Violet Base Set" to "sv01", "SWSH: Crown Zenith" to "swsh12.5", "XY - Evolutions" to "xy12",
            "SV: Prismatic Evolutions" to "sv08.5", "SV07: Stellar Crown" to "sv07",
        )
        for ((tcgplayer, code) in cases) assertEquals(tcgplayer, code, ImportParsing.bestSetMatch(tcgplayer, pkm)?.code)
        assertNull(ImportParsing.bestSetMatch("Totally Unknown Expansion", pkm))
    }
}
