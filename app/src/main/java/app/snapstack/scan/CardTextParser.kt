package app.snapstack.scan

import app.snapstack.data.source.PokemonSource
import app.snapstack.model.Game
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns raw OCR lines into card identifiers. Pure Kotlin (no Android types) so it is unit-tested
 * and cheap enough to run on every camera frame.
 *
 * What each game prints:
 *  - Pokémon:  "PAL EN 123/193" (Scarlet & Violet era) or "123/193" (older), promos "SWSH045"
 *  - Magic:    "0123 R" above "MKM • EN" (2023+), "123/280 R" above "DOM • EN" (2015-2022)
 *  - Yu-Gi-Oh: set code "LOB-EN001" under the art, 8-digit passcode bottom-left
 *  - Lorcana:  "123/204 • EN • 5" (number / total • language • set)
 */
object CardTextParser {

    private const val LOOSE_DIGIT = "[0-9OoIl|]"

    private val pokemonNumber = Regex(
        "(?<![0-9A-Za-z/])((?:[A-Z]{1,4})?$LOOSE_DIGIT{1,3}[a-z]?)\\s?[/⁄∕]\\s?((?:[A-Z]{1,4})?$LOOSE_DIGIT{2,3})(?![0-9A-Za-z])"
    )
    private val lorcanaTail = Regex("^[\\s\\W_]{0,4}(EN|DE|FR|IT|JA|ZH)[\\s\\W_]{0,4}(\\d{1,2})(?!\\d)")
    private val pokemonAbbrev = Regex("(?:^|\\s)([A-Z]{3})\\s*(?:EN)?\\s*$")
    private val pokemonPromo = Regex(
        "(?<![A-Za-z0-9])(SWSH|SVP|SM|XY|BW|MEP|DP|HGSS)\\s?(?:EN\\s?)?($LOOSE_DIGIT{2,3})(?![0-9A-Za-z/])"
    )
    private val magicSetLang = Regex(
        "(?<![A-Za-z0-9])([A-Z0-9]{3,5})\\s?[•·.*\\-+‧∙●]?\\s?(EN|DE|FR|IT|ES|SP|PT|JA|JP|KO|RU|ZHS|ZHT|PH)(?![A-Za-z0-9])"
    )
    private val magicNumber = Regex(
        "(?<![0-9A-Za-z])(?:[CURMSTLP]\\s)?($LOOSE_DIGIT{1,4})(?:\\s?/\\s?$LOOSE_DIGIT{1,4})?(?:\\s?[CURMSTLP★*])?(?![0-9A-Za-z])"
    )
    private val ygoCode = Regex(
        "(?<![A-Za-z0-9])([A-Z0-9]{2,5})\\s?-\\s?(EN|E|DE|FR|IT|SP|PT|AE|KR)?($LOOSE_DIGIT{3})(?![0-9])"
    )
    private val ygoPasscode = Regex("(?<![0-9])([0-9]{8})(?![0-9])")
    private val hasLetter = Regex("[A-Z]")
    private val nameLike = Regex("[A-Za-zÀ-ÿ]{3,}")

    private val notMagicSets = setOf("THE", "AND", "YOU", "FOR", "ALL", "ITS", "ATK", "DEF", "HP", "NOT", "ONE", "TWO")

    private val context: Map<Game, List<Regex>> = mapOf(
        Game.POKEMON to listOf(
            "\\bhp\\s?\\d{2,3}\\b", "\\b\\d{2,3}\\s?hp\\b", "weakness", "resistance", "retreat", "pok[eé]mon",
            "evolves", "\\bstage\\s?[12]\\b", "\\bbasic\\b", "nintendo", "game freak", "creatures", "\\bability\\b",
        ).map { Regex(it) },
        Game.MAGIC to listOf(
            "\\bcreature\\b", "\\binstant\\b", "\\bsorcery\\b", "\\benchantment\\b", "\\bartifact\\b", "planeswalker",
            "\\blegendary\\b", "wizards", "\\billus\\b", "\\bflying\\b", "\\btrample\\b", "\\bhaste\\b", "\\bmana\\b",
        ).map { Regex(it) },
        Game.YUGIOH to listOf(
            "\\batk\\s?/", "\\bdef\\s?/", "konami", "spell card", "trap card", "\\beffect\\b", "1st edition",
            "limited edition", "\\btuner\\b", "\\bxyz\\b", "\\bsynchro\\b", "\\bfusion\\b", "\\blink-\\d",
        ).map { Regex(it) },
        Game.LORCANA to listOf(
            "disney", "ravensburger", "storyborn", "dreamborn", "floodborn", "\\blore\\b", "\\bsing\\b",
            "\\bevasive\\b", "\\bbodyguard\\b", "\\bchallenger\\b", "\\bshift\\b", "\\bsong\\b",
        ).map { Regex(it) },
    )

    /** 0..1 confidence that the visible text belongs to each game. */
    fun gameContext(lines: List<OcrLine>): Map<Game, Double> {
        val text = lines.joinToString("\n") { it.text }.lowercase(Locale.US)
        return context.mapValues { (_, patterns) ->
            min(1.0, patterns.count { it.containsMatchIn(text) } / 3.0)
        }
    }

    fun parse(frame: OcrFrame, game: Game? = null): List<Detection> = parseLines(frame.lines, game)

    fun parseLines(lines: List<OcrLine>, game: Game? = null): List<Detection> {
        if (lines.isEmpty()) return emptyList()
        val ctx = gameContext(lines)
        fun allowed(g: Game) = game == null || game == g
        fun ctxOf(g: Game) = ctx[g] ?: 0.0

        val out = ArrayList<Detection>()
        val nameHints = lines.map { it.text }.filter { nameLike.containsMatchIn(it) && it.length <= 40 }.take(30)
        val magicLines = HashSet<Int>()

        // ---- Magic: "SET • EN" line + collector number on the line above (or the same line).
        if (allowed(Game.MAGIC)) lines.forEachIndexed { i, line ->
            for (m in magicSetLang.findAll(line.text)) {
                val set = m.groupValues[1]
                if (!hasLetter.containsMatchIn(set) || set in notMagicSets) continue
                val rest = line.text.substring(m.range.last + 1)
                if (Regex("^\\s*$LOOSE_DIGIT{1,3}\\s?/").containsMatchIn(rest)) continue // Pokémon "PAL EN 123/193"
                if (Regex("^[\\s\\W_]{0,4}\\d{1,2}(?!\\d)").containsMatchIn(rest) &&
                    Regex("\\d\\s?/\\s?\\d").containsMatchIn(line.text)
                ) continue // Lorcana "12/204 • EN • 5"
                val before = line.text.substring(0, m.range.first)
                val numberHit = magicNumberIn(before)?.let { it to i }
                    ?: linesAbove(lines, i).firstNotNullOfOrNull { j -> magicNumberIn(lines[j].text)?.let { it to j } }
                    ?: continue
                magicLines += i; magicLines += numberHit.second
                val score = 0.62 + 0.3 * ctxOf(Game.MAGIC) + if (game == Game.MAGIC) 0.1 else 0.0
                out += Detection(MagicKey(set.lowercase(Locale.US), numberHit.first), score, line.box.union(lines[numberHit.second].box))
            }
        }

        // ---- Pokémon & Lorcana: number/total.
        lines.forEachIndexed { i, line ->
            for (m in pokemonNumber.findAll(line.text)) {
                val number = cleanNumber(m.groupValues[1]) ?: continue
                val totalRaw = cleanNumber(m.groupValues[2]) ?: continue
                val totalDigits = totalRaw.filter { it.isDigit() }
                if (totalDigits.length < 2 || totalDigits.all { it == '0' }) continue
                val numDigits = number.filter { it.isDigit() }.toIntOrNull()
                val totalInt = totalDigits.toInt()
                if (numDigits != null && numDigits > totalInt * 2.2 + 20) continue

                val tail = lorcanaTail.find(line.text.substring(m.range.last + 1))
                if (tail != null && allowed(Game.LORCANA)) {
                    val score = 0.8 + 0.2 * ctxOf(Game.LORCANA) + if (game == Game.LORCANA) 0.1 else 0.0
                    out += Detection(LorcanaKey(tail.groupValues[2].trimStart('0').ifEmpty { "0" }, number.trimStart('0').ifEmpty { "0" }), score, line.box)
                    continue
                }
                if (!allowed(Game.POKEMON)) continue
                val abbrev = pokemonAbbrev.find(line.text.substring(0, m.range.first))?.groupValues?.get(1)
                    ?.takeIf { it != "HP" && it != "EN" }
                var score = 0.58 + 0.3 * ctxOf(Game.POKEMON) + (if (abbrev != null) 0.08 else 0.0) +
                    if (game == Game.POKEMON) 0.1 else 0.0
                // An MTG "123/280 R" line sits right above its "SET • EN" line: don't call that Pokémon.
                if (i in magicLines) score -= 0.35
                if (ctxOf(Game.LORCANA) > ctxOf(Game.POKEMON) && game == null) score -= 0.15
                out += Detection(PokemonKey(number, totalRaw, abbrev, nameHints), score, line.box)
            }
            if (allowed(Game.POKEMON)) for (m in pokemonPromo.findAll(line.text)) {
                val prefix = m.groupValues[1]
                if (prefix !in PokemonSource.promoSets) continue
                val digits = digitsLoose(m.groupValues[2])
                if (!digits.all { it.isDigit() }) continue
                val score = 0.45 + 0.35 * ctxOf(Game.POKEMON) + if (game == Game.POKEMON) 0.15 else 0.0
                out += Detection(PokemonPromoKey(prefix, digits, nameHints), score, line.box)
            }
        }

        // ---- Yu-Gi-Oh!: set code, else passcode.
        if (allowed(Game.YUGIOH)) lines.forEach { line ->
            for (m in ygoCode.findAll(line.text)) {
                var prefix = m.groupValues[1]
                val lang = m.groupValues[2]
                val digits = digitsLoose(m.groupValues[3])
                if (!digits.all { it.isDigit() } || !hasLetter.containsMatchIn(prefix)) continue
                if (lang.isEmpty() && game != Game.YUGIOH && ctxOf(Game.YUGIOH) < 0.34) continue
                if (prefix.length == 4 && Regex("^[A-Z]{2}[O0-9]{2}$").matches(prefix)) {
                    prefix = prefix.take(2) + prefix.drop(2).replace('O', '0')
                }
                val score = (if (lang.isNotEmpty()) 0.8 else 0.5) + 0.2 * ctxOf(Game.YUGIOH) +
                    if (game == Game.YUGIOH) 0.1 else 0.0
                out += Detection(YugiohKey("$prefix-$lang$digits"), score, line.box)
            }
            if (line.text.length <= 14) for (m in ygoPasscode.findAll(line.text)) {
                if (game != Game.YUGIOH && ctxOf(Game.YUGIOH) < 0.34) continue
                val score = 0.42 + 0.3 * ctxOf(Game.YUGIOH)
                out += Detection(YugiohPasscodeKey(m.groupValues[1]), score, line.box)
            }
        }

        return out.groupBy { it.key.id }
            .map { (_, ds) -> ds.maxBy { it.score } }
            .sortedByDescending { it.score }
    }

    /** Parses a binder page: assigns OCR lines to a rows×cols grid and reads each pocket on its own. */
    fun parseGrid(frame: OcrFrame, rows: Int, cols: Int, game: Game? = null, region: Box? = null): List<Detection?> {
        val cells = List(rows * cols) { ArrayList<OcrLine>() }
        val area = region ?: Box(0f, 0f, frame.width.toFloat(), frame.height.toFloat())
        if (area.w <= 0 || area.h <= 0) return List(rows * cols) { null }
        for (line in frame.lines) {
            val x = line.box.cx; val y = line.box.cy
            if (x < area.left || x > area.right || y < area.top || y > area.bottom) continue
            val c = (((x - area.left) / area.w) * cols).toInt().coerceIn(0, cols - 1)
            val r = (((y - area.top) / area.h) * rows).toInt().coerceIn(0, rows - 1)
            cells[r * cols + c] += line
        }
        return cells.map { parseLines(it, game).firstOrNull() }
    }

    /**
     * Parses what someone typed: "123/193", "PAL 123", "MKM 123", "mkm/123", "LOB-EN001", "12/204 EN 5",
     * or "Charizard ex 4/102". Falls back to a name lookup.
     */
    fun parseTyped(input: String, game: Game?): ScanKey? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val upper = text.uppercase(Locale.US)

        if (game == null || game == Game.YUGIOH) {
            ygoCode.find(upper)?.let { m ->
                if (m.groupValues[2].isNotEmpty() || game == Game.YUGIOH) {
                    return YugiohKey("${m.groupValues[1]}-${m.groupValues[2]}${digitsLoose(m.groupValues[3])}")
                }
            }
            if (game == Game.YUGIOH) Regex("^\\d{8}$").find(text)?.let { return YugiohPasscodeKey(it.value) }
        }
        if (game == null || game == Game.LORCANA) {
            Regex("(\\d{1,3})\\s?/\\s?\\d{2,3}[\\s\\W_]{0,4}(?:EN|DE|FR|IT|JA|ZH)[\\s\\W_]{0,4}(\\d{1,2})", RegexOption.IGNORE_CASE)
                .find(text)?.let { return LorcanaKey(it.groupValues[2], it.groupValues[1].trimStart('0').ifEmpty { "0" }) }
            if (game == Game.LORCANA) {
                Regex("^(\\d{1,2})\\s*[-/: ]\\s*(\\d{1,3})$").find(text)?.let {
                    return LorcanaKey(it.groupValues[1], it.groupValues[2].trimStart('0').ifEmpty { "0" })
                }
            }
        }
        if (game == null || game == Game.MAGIC) {
            Regex("^([A-Za-z0-9]{3,5})\\s*[-/: #]\\s*([0-9]{1,4}[a-z★]?)$").find(text)?.let {
                val set = it.groupValues[1]
                // "MKM 123" — in Auto mode a lettered code + number is read as a Magic set.
                if (game == Game.MAGIC || hasLetter.containsMatchIn(set.uppercase(Locale.US))) {
                    return MagicKey(set.lowercase(Locale.US), it.groupValues[2].trimStart('0').ifEmpty { "0" })
                }
            }
        }
        if (game == null || game == Game.POKEMON) {
            pokemonNumber.find(text)?.let { m ->
                val number = cleanNumber(m.groupValues[1]) ?: return@let
                val total = cleanNumber(m.groupValues[2]) ?: return@let
                val name = text.substring(0, m.range.first).trim().takeIf { it.isNotEmpty() }
                val abbrev = pokemonAbbrev.find(text.substring(0, m.range.first).uppercase(Locale.US))?.groupValues?.get(1)
                return PokemonKey(number, total, abbrev, listOfNotNull(name))
            }
            if (game == Game.POKEMON) Regex("^([A-Za-z]{3})\\s*[-/: #]?\\s*(\\d{1,3})$").find(text)?.let {
                return PokemonKey(it.groupValues[2].trimStart('0').ifEmpty { "0" }, null, it.groupValues[1].uppercase(Locale.US))
            }
        }
        val g = game ?: return null
        val named = Regex("^(.*?)(?:\\s+#?(\\d{1,4}))?$").find(text)
        val name = named?.groupValues?.get(1)?.trim().orEmpty().ifEmpty { text }
        return NameKey(g, name, named?.groupValues?.get(2)?.takeIf { it.isNotEmpty() })
    }

    // ---------------------------------------------------------------- helpers

    internal fun digitsLoose(s: String): String = buildString(s.length) {
        for (ch in s) append(
            when (ch) {
                'O', 'o', 'D', 'Q' -> '0'
                'I', 'l', '|', 'i', '!' -> '1'
                else -> ch
            }
        )
    }

    /** Keeps a letter prefix ("TG05"), fixes O/0 and l/1 confusions in the digits, rejects junk. */
    private fun cleanNumber(raw: String): String? {
        val prefix = raw.takeWhile { it.isUpperCase() && it !in "OI" }
        val rest = raw.drop(prefix.length)
        val suffix = rest.takeLastWhile { it.isLowerCase() && it !in "ol" }
        val core = rest.dropLast(suffix.length)
        if (core.isEmpty()) return null
        val realDigits = core.count { it.isDigit() }
        if (realDigits * 2 < core.length) return null
        val digits = digitsLoose(core)
        if (!digits.all { it.isDigit() }) return null
        return prefix + digits + suffix
    }

    private fun magicNumberIn(text: String): String? {
        for (m in magicNumber.findAll(text)) {
            val digits = digitsLoose(m.groupValues[1])
            if (!digits.all { it.isDigit() }) continue
            if (m.groupValues[1].count { it.isDigit() } * 2 < m.groupValues[1].length) continue
            return digits.trimStart('0').ifEmpty { "0" }
        }
        return null
    }

    /** Indices of lines just above line [i] (same block first, then by geometry), nearest first. */
    private fun linesAbove(lines: List<OcrLine>, i: Int): List<Int> {
        val target = lines[i].box
        val h = max(target.h, 1f)
        return lines.indices.filter { j ->
            if (j == i) return@filter false
            val b = lines[j].box
            val above = b.cy < target.cy && b.bottom <= target.top + h * 0.6f && target.top - b.bottom < h * 2.5f
            val aligned = abs(b.left - target.left) < h * 4f || (b.left < target.right && b.right > target.left)
            above && aligned
        }.sortedBy { j -> target.top - lines[j].box.bottom + if (lines[j].block == lines[i].block) -1000f else 0f }
    }
}
