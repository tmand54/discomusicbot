package app.tcgportfolio.data.importer

import app.tcgportfolio.model.Condition
import app.tcgportfolio.model.Game
import app.tcgportfolio.scan.TextMatch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** One owned card (possibly several copies) read from another app's export. */
data class ImportRow(
    val game: Game?,
    val gameLabel: String?,
    val name: String,
    val number: String?,
    val setName: String?,
    val qty: Int = 1,
    val condition: Condition = Condition.NM,
    val foil: Boolean = false,
    val printing: String? = null,
    val costEach: Double? = null,
    val priceAtAdd: Double? = null,
    val grader: String? = null,
    val grade: String? = null,
    val gradedValue: Double? = null,
    val addedAt: Long? = null,
    val notes: String? = null,
    /** For pasted lists: the card number/name already parsed into a lookup key. */
    val key: app.tcgportfolio.scan.ScanKey? = null,
    /** "JP" for Japanese copies. */
    val language: String? = null,
)

enum class ImportFormat(val label: String) {
    LIST("Pasted list"),
    SNAPSTACK("TCG Investment Portfolio backup"),
    RIPFOLIO("Ripfolio backup"),
    CSV("CSV file"),
    UNKNOWN("Unknown file"),
}

data class ParsedImport(
    val format: ImportFormat,
    val rows: List<ImportRow>,
    /** What was in the file but can't be brought over, e.g. "sold cards" -> 4. */
    val skipped: Map<String, Int> = emptyMap(),
)

class ImportException(message: String) : Exception(message)

/**
 * Reads exports from other collection apps. Pure Kotlin + org.json so it is unit-tested on the JVM.
 *
 * Ripfolio's backup ("format": "ripfolio-local") stores, per owned card: card_name, card_number,
 * set_name, game_name, quantity, condition, is_foil, cost_basis (per copy), price_when_added,
 * grading fields and created_at. Its wantlist and custom lists only hold Ripfolio's internal ids.
 */
object ImportParsing {

    fun detect(text: String): ImportFormat {
        val t = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        if (t.startsWith("{")) {
            val o = runCatching { JSONObject(t) }.getOrNull() ?: return ImportFormat.UNKNOWN
            return when {
                o.optString("format") == "snapstack-backup" -> ImportFormat.SNAPSTACK
                o.optString("format") == "ripfolio-local" -> ImportFormat.RIPFOLIO
                o.optJSONArray("items")?.optJSONObject(0)?.has("card_name") == true -> ImportFormat.RIPFOLIO
                else -> ImportFormat.UNKNOWN
            }
        }
        if (t.startsWith("PK")) throw ImportException("That's a zip file. Unzip it first, then pick the .json or .csv inside.")
        val firstLine = t.lineSequence().firstOrNull().orEmpty()
        if (firstLine.contains(',') || firstLine.contains('\t') || firstLine.contains(';')) return ImportFormat.CSV
        return ImportFormat.UNKNOWN
    }

    fun parse(text: String): ParsedImport = when (detect(text)) {
        ImportFormat.RIPFOLIO -> parseRipfolio(text)
        ImportFormat.CSV -> parseCsv(text)
        ImportFormat.LIST -> parseList(text, null)
        ImportFormat.SNAPSTACK -> ParsedImport(ImportFormat.SNAPSTACK, emptyList())
        ImportFormat.UNKNOWN -> throw ImportException(
            "TCG Investment Portfolio couldn't recognise that file. It can read its own backups, Ripfolio backups " +
                "(ripfolio-backup-….json from Ripfolio → Settings → Back up) and CSV exports with a card name column."
        )
    }

    // ------------------------------------------------------------------ pasted list

    /** Label used for name-only lines when no game was picked. */
    const val NO_GAME = "lines with only a name (pick a game to look those up)"

    private val qtyPrefix = Regex("^(\\d{1,3})\\s*[xX×]?\\s+(.+)$")
    private val qtySuffix = Regex("^(.+?)\\s+[xX×](\\d{1,3})$")

    /**
     * One card per line: "4 Lightning Bolt", "2x 123/193", "OP01-001", "LOB-EN001 x3", "MKM 123",
     * "Charizard ex 199/165". Lines are parsed with the same reader as the scanner's "Type #".
     */
    fun parseList(text: String, game: Game?): ParsedImport {
        val rows = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("#") }
            .take(2000).map { line ->
                var qty = 1
                var rest = line
                qtyPrefix.find(line)?.let { m -> qty = m.groupValues[1].toInt(); rest = m.groupValues[2] }
                    ?: qtySuffix.find(line)?.let { m -> qty = m.groupValues[2].toInt(); rest = m.groupValues[1] }
                val key = app.tcgportfolio.scan.CardTextParser.parseTyped(rest, game)
                ImportRow(
                    game = key?.game, gameLabel = if (key == null) NO_GAME else null,
                    name = rest, number = null, setName = null, qty = qty.coerceAtLeast(1), key = key,
                )
            }.toList()
        return ParsedImport(ImportFormat.LIST, rows)
    }

    /** Pasted text: a CSV (has a header row with a name column) or a plain list. */
    fun parsePasted(text: String, game: Game?): ParsedImport {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) return parse(trimmed)
        val header = trimmed.lineSequence().firstOrNull().orEmpty().lowercase(Locale.US)
        val looksCsv = (header.contains(',') || header.contains('\t') || header.contains(';')) &&
            headerAliases.getValue("name").any { alias -> header.split(',', ';', '\t').map { it.trim().trim('"') }.contains(alias) }
        if (!looksCsv) return parseList(trimmed, game)
        val csv = parseCsv(trimmed)
        return if (game == null) csv else csv.copy(rows = csv.rows.map { if (it.game == null && it.gameLabel == null) it.copy(game = game) else it })
    }

    // ------------------------------------------------------------------ Ripfolio

    fun parseRipfolio(text: String): ParsedImport {
        val root = runCatching { JSONObject(text.trimStart('﻿')) }.getOrElse {
            throw ImportException("That Ripfolio file couldn't be read (it may be incomplete).")
        }
        val items = root.optJSONArray("items") ?: JSONArray()
        val rows = ArrayList<ImportRow>()
        val skipped = LinkedHashMap<String, Int>()
        fun skip(what: String, n: Int = 1) { if (n > 0) skipped[what] = (skipped[what] ?: 0) + n }

        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val qty = o.optDouble("quantity", 1.0).toInt().coerceAtLeast(1)
            when (val status = o.str("status") ?: "owned") {
                "owned" -> Unit
                "sold" -> { skip("sold cards", qty); continue }
                "traded" -> { skip("traded-away cards", qty); continue }
                "removed" -> { skip("removed cards", qty); continue }
                else -> { skip("cards with status \"$status\"", qty); continue }
            }
            val name = o.str("card_name")
            if (name == null) { skip("cards without a name", qty); continue }
            val gameLabel = o.str("game_name")
            val graded = o.optBoolean("is_graded", false)
            val gift = o.str("acquisition_type") == "gift_other"
            val notes = listOfNotNull(
                o.str("cert_number")?.let { "Cert #$it" },
                when (o.str("acquisition_type")) {
                    "pulled_from_pack" -> "Pulled from a pack"
                    "traded" -> "Traded for"
                    "gift_other" -> "Gift"
                    "pulled_self_graded" -> "Pulled and graded"
                    else -> null
                },
            ).joinToString(" · ").ifEmpty { null }
            rows += ImportRow(
                game = gameFrom(gameLabel, o.str("card_number")),
                gameLabel = gameLabel,
                name = name,
                number = o.str("card_number"),
                setName = o.str("set_name"),
                qty = qty,
                condition = conditionFrom(o.str("condition")),
                foil = o.optBoolean("is_foil", false),
                costEach = o.num("cost_basis") ?: if (gift) 0.0 else null,
                priceAtAdd = o.num("price_when_added"),
                grader = if (graded) o.str("grading_company")?.uppercase(Locale.US) else null,
                grade = if (graded) o.num("grade_received")?.let { formatGrade(it) } else null,
                gradedValue = if (graded) o.num("graded_value") else null,
                addedAt = o.str("created_at")?.let { parseDate(it) },
                notes = notes,
            )
        }
        root.optJSONArray("sealed")?.let { a ->
            var n = 0
            for (i in 0 until a.length()) {
                val s = a.optJSONObject(i) ?: continue
                if ((s.str("status") ?: "owned") == "owned") n += s.optInt("quantity", 1).coerceAtLeast(1)
            }
            skip("sealed products (boxes, packs, ETBs)", n)
        }
        skip("wantlist cards (Ripfolio's backup only keeps its own card ids for these)", root.optJSONArray("wantlist")?.length() ?: 0)
        skip("custom lists", root.optJSONArray("lists")?.length() ?: 0)
        return ParsedImport(ImportFormat.RIPFOLIO, rows, skipped)
    }

    // ------------------------------------------------------------------ CSV

    private val headerAliases: Map<String, List<String>> = mapOf(
        "name" to listOf("card name", "name", "card", "product name", "card_name", "title", "simple name"),
        "set" to listOf("set", "set name", "set_name", "edition", "expansion", "set title"),
        "number" to listOf("number", "card number", "collector number", "card_number", "collector_number", "no", "#", "card no"),
        "game" to listOf("game", "game name", "game_name", "category", "product line", "tcg"),
        "qty" to listOf("quantity", "qty", "count", "copies", "total quantity", "add to quantity", "have"),
        "condition" to listOf("condition", "cond"),
        "printing" to listOf("printing", "finish", "foil", "variant", "variance", "printing type", "version", "is_foil"),
        "cost" to listOf("purchase price", "price paid", "cost", "cost basis", "cost_basis", "paid", "buy price", "acquired price"),
        "grader" to listOf("grading company", "grader", "grading_company"),
        "grade" to listOf("grade", "grade received", "grade_received"),
        "added" to listOf("added", "date added", "created at", "created_at", "acquired", "date"),
        "language" to listOf("language", "lang", "card language"),
    )

    fun parseCsv(text: String): ParsedImport {
        val clean = text.trimStart('﻿')
        val first = clean.lineSequence().firstOrNull().orEmpty()
        val delimiter = listOf(',', ';', '\t').maxBy { d -> first.count { it == d } }
        val table = readCsv(clean, delimiter)
        if (table.isEmpty()) throw ImportException("That CSV file is empty.")
        val header = table.first().map { it.trim().lowercase(Locale.US).removeSurrounding("\"") }
        fun col(key: String): Int = headerAliases.getValue(key).firstNotNullOfOrNull { alias ->
            header.indexOf(alias).takeIf { it >= 0 }
        } ?: -1
        val cName = col("name")
        if (cName < 0) throw ImportException("That CSV has no card name column (looked for: Card Name, Name, Product Name).")
        val cSet = col("set"); val cNum = col("number"); val cGame = col("game"); val cQty = col("qty")
        val cCond = col("condition"); val cPrint = col("printing"); val cCost = col("cost")
        val cGrader = col("grader"); val cGrade = col("grade"); val cAdded = col("added"); val cLang = col("language")

        val rows = ArrayList<ImportRow>()
        for (r in table.drop(1)) {
            fun cell(i: Int) = if (i >= 0 && i < r.size) r[i].trim().takeIf { it.isNotEmpty() } else null
            val name = cell(cName) ?: continue
            val printing = cell(cPrint)
            val gameLabel = cell(cGame)
            rows += ImportRow(
                game = gameFrom(gameLabel, cell(cNum)),
                gameLabel = gameLabel,
                name = name,
                number = cell(cNum),
                setName = cell(cSet),
                qty = cell(cQty)?.toDoubleOrNull()?.toInt()?.coerceAtLeast(1) ?: 1,
                condition = conditionFrom(cell(cCond)),
                foil = printing?.let { p -> p.equals("true", true) || p.equals("yes", true) || p.contains("foil", true) || p.contains("holo", true) } ?: false,
                printing = printing?.takeUnless { it.equals("true", true) || it.equals("false", true) },
                costEach = cell(cCost)?.let { parseMoney(it) },
                grader = cell(cGrader)?.uppercase(Locale.US)?.takeUnless { it == "NONE" || it == "RAW" },
                grade = cell(cGrade),
                addedAt = cell(cAdded)?.let { parseDate(it) },
                language = cell(cLang)?.let { languageFrom(it) },
            )
        }
        return ParsedImport(ImportFormat.CSV, rows)
    }

    /** RFC 4180 reader: quoted fields, escaped quotes, newlines inside quotes. */
    fun readCsv(text: String, delimiter: Char = ','): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(c)
            } else when (c) {
                '"' -> quoted = true
                delimiter -> { row += field.toString(); field.setLength(0) }
                '\r' -> Unit
                '\n' -> {
                    row += field.toString(); field.setLength(0)
                    if (row.any { it.isNotBlank() }) rows += row
                    row = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        row += field.toString()
        if (row.any { it.isNotBlank() }) rows += row
        return rows
    }

    // ------------------------------------------------------------------ field helpers

    fun gameFrom(label: String?, number: String? = null): Game? {
        if (label != null && label.isNotBlank()) {
            val l = TextMatch.norm(label)
            return when {
            "japan" in l || l.endsWith(" jp") || "(jp)" in label.lowercase(Locale.US) -> null
            "pok" in l -> Game.POKEMON
            "magic" in l || l == "mtg" -> Game.MAGIC
            "yu gi" in l || "yugi" in l -> Game.YUGIOH
            "lorcana" in l -> Game.LORCANA
            "one piece" in l || "onepiece" in l -> Game.ONE_PIECE
            "riftbound" in l -> Game.RIFTBOUND
            "dragon ball z" in l || "panini" in l -> null // the old Panini DBZ game isn't covered
            "dragon ball" in l || "dbs" == l || "fusion world" in l -> Game.DRAGON_BALL
            else -> null
            }
        }
        val n = number?.uppercase(Locale.US) ?: return null
        return when {
            Regex("^(FB|FS|SB|BT|TB|DB|EX|SD|XD)\\d{1,2}-\\d{2,3}$|^FP-\\d{3}$").containsMatchIn(n) -> Game.DRAGON_BALL
            Regex("^(OP|ST|EB|PRB)\\d{2}-\\d{3}").containsMatchIn(n) -> Game.ONE_PIECE
            Regex("^[A-Z0-9]{2,5}-(EN|E)[A-Z]?\\d{2,3}$").matches(n) -> Game.YUGIOH
            else -> null
        }
    }

    /** True for labels like "Pokémon Japan" that the app doesn't price. */
    fun unsupportedGame(label: String?): Boolean = label != null && gameFrom(label) == null

    /** "Japanese" / "JP" / "JA" / "日本語" → "JP"; English or blank → null. */
    fun languageFrom(s: String?): String? {
        val l = s?.trim()?.lowercase(Locale.US) ?: return null
        return if (l.startsWith("jap") || l == "jp" || l == "ja" || l == "jpn" || s.contains("日本")) app.tcgportfolio.model.Languages.JP else null
    }

    fun conditionFrom(s: String?): Condition {
        val c = s?.lowercase(Locale.US)?.replace('_', ' ')?.trim() ?: return Condition.NM
        return when {
            c.startsWith("gem") || c.startsWith("mint") || c == "nm" || c.startsWith("near") -> Condition.NM
            c == "lp" || c.startsWith("light") || c == "ex" || c.startsWith("excellent") -> Condition.LP
            c == "mp" || c.startsWith("moder") || c == "gd" || c.startsWith("good") -> Condition.MP
            c == "hp" || c.startsWith("heav") || c == "pl" || c.startsWith("played") -> Condition.HP
            c.startsWith("dam") || c == "dmg" || c == "poor" -> Condition.DMG
            else -> Condition.NM
        }
    }

    private fun formatGrade(g: Double): String = if (g == Math.floor(g)) g.toInt().toString() else g.toString()

    fun parseMoney(s: String): Double? = s.replace(Regex("[^0-9.,-]"), "").let { t ->
        // "1.234,56" (EU) vs "1,234.56" (US)
        val normalized = if (t.contains(',') && t.lastIndexOf(',') > t.lastIndexOf('.')) t.replace(".", "").replace(',', '.') else t.replace(",", "")
        normalized.toDoubleOrNull()
    }

    fun parseDate(s: String): Long? = runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDate.parse(s.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()

    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

    private fun JSONObject.num(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return when (val v = opt(key)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }?.takeIf { !it.isNaN() }
    }

    // ------------------------------------------------------------------ matching helpers

    private val setPrefix = Regex("^[a-z]{1,5}\\d{0,3}[a-z]?\\s*[:\\-–]\\s+")
    private val noise = Regex("\\b(pokemon|pokémon|tcg|the|of|and|set|black star|cards)\\b")
    private val ordinals = mapOf(
        "4th" to "fourth", "5th" to "fifth", "6th" to "sixth", "7th" to "seventh",
        "8th" to "eighth", "9th" to "ninth", "10th" to "tenth",
    )

    /** "SV02: Paldea Evolved" / "SWSH03: Darkness Ablaze" / "XY - Evolutions" → comparable set names. */
    fun normalizeSetName(s: String): String {
        val n = TextMatch.norm(s.lowercase(Locale.US).replace(setPrefix, "").replace(Regex("\\s*\\([^)]*\\)"), ""))
        return n.split(' ').joinToString(" ") { ordinals[it] ?: it }
            .replace(noise, " ").replace(Regex("\\s+"), " ").trim()
    }

    fun setScore(a: String?, b: String?): Double {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return 0.0
        val x = normalizeSetName(a); val y = normalizeSetName(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val tx = x.split(' ').toSet(); val ty = y.split(' ').toSet()
        val (short, long) = if (x.length <= y.length) x to y else y to x
        val base = when {
            tx == ty -> 0.97 // same words, different order: "Commander: Bloomburrow" = "Bloomburrow Commander"
            short.length >= 3 && (" $long ").contains(" $short ") -> 0.9
            else -> TextMatch.similarity(x, y)
        }
        // Numbers in set names are decisive: "Scarlet & Violet 151" is not "Scarlet & Violet", "Base Set 2" is not "Base Set".
        val nx = x.split(' ').filter { t -> t.any(Char::isDigit) }.toSet()
        val ny = y.split(' ').filter { t -> t.any(Char::isDigit) }.toSet()
        return if (nx != ny) base * 0.7 else base
    }

    /** The set whose name best matches [setName], or null when nothing is a confident match. */
    fun bestSetMatch(setName: String?, sets: List<app.tcgportfolio.model.CardSet>): app.tcgportfolio.model.CardSet? {
        if (setName.isNullOrBlank() || sets.isEmpty()) return null
        val scored = sets.map { it to setScore(it.name, setName) }
        val top = scored.maxOf { it.second }
        if (top < 0.8) return null
        // Ties (e.g. "151" inside "Scarlet & Violet 151" vs "Scarlet & Violet"): prefer the longest set name.
        return scored.filter { it.second >= top - 1e-9 }.maxBy { normalizeSetName(it.first.name).length }.first
    }

    private val trailingNumber = Regex("\\s+[-–]\\s+[A-Za-z]{0,5}\\d+(-\\d+)?[a-z]?(/[A-Za-z]{0,4}\\d+)?\\s*$")
    private val parenthetical = Regex("\\s*[(\\[][^)\\]]*[)\\]]")

    private val inlineNumber = Regex("\\s+[-–]\\s+[A-Za-z]{0,5}\\d+(?:-\\d+)?[a-z]?(?:/[A-Za-z]{0,4}\\d+)?(?=\\s*(?:[(\\[]|$))")

    /** "Son Goku - FB01-001 (Alternate Art)" → "Son Goku (Alternate Art)": drops a TCGplayer-style number. */
    fun stripNumber(name: String): String = name.replace(inlineNumber, "").trim().ifEmpty { name.trim() }

    /** "Charizard ex - 199/165" → "Charizard ex"; "Lightning Bolt (Borderless)" → "Lightning Bolt". */
    fun baseName(name: String): String =
        stripNumber(name).replace(trailingNumber, "").replace(parenthetical, "").trim().ifEmpty { name.trim() }

    /** The number with any "/total" removed ("199/165" → "199"); keeps prefixes like "TG05". */
    fun numberOnly(number: String): String = number.substringBefore('/').trim()

    fun totalOf(number: String): String? = number.substringAfter('/', "").trim().takeIf { it.isNotEmpty() }

    /** Pulls "199/165" out of a TCGplayer-style name when the number column is missing. */
    fun numberFromName(name: String): String? =
        Regex("[-–]\\s+([A-Za-z]{0,5}\\d+[a-z]?(?:/[A-Za-z]{0,4}\\d+)?)\\s*$").find(name)?.groupValues?.get(1)

    /** The printing named in the card name or printing column, e.g. "(Reverse Holofoil)". */
    fun printingHint(row: ImportRow): String? {
        val text = listOfNotNull(row.printing, row.name).joinToString(" ").lowercase(Locale.US)
        return when {
            "reverse" in text -> "reverseHolofoil"
            "1st edition" in text && "holo" in text -> "1stEditionHolofoil"
            "1st edition" in text -> "1stEdition"
            "etched" in text -> "etched"
            "holofoil" in text || Regex("\\bholo\\b").containsMatchIn(text) -> "holofoil"
            "foil" in text -> "foil"
            row.printing?.lowercase(Locale.US)?.trim() in setOf("normal", "non-foil", "nonfoil", "regular") -> "normal"
            else -> null
        }
    }
}
