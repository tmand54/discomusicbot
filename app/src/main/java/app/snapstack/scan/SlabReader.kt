package app.snapstack.scan

import app.snapstack.model.Game
import java.util.Locale

/** What a grading company's label says. */
data class SlabInfo(
    /** "PSA", "BGS", "TAG", "CGC" or "SGC". */
    val grader: String,
    /** "10", "9.5"… */
    val grade: String?,
    /** The grade's words: "GEM MT", "PRISTINE", "BLACK LABEL"… */
    val gradeLabel: String?,
    val cert: String?,
    /** BGS / CGC subgrades: centering, corners, edges, surface. */
    val subgrades: Map<String, String> = emptyMap(),
    val game: Game? = null,
    /** The card number printed on the label ("199", "OP04-043", "SV049"). */
    val labelNumber: String? = null,
    /** Label lines that can carry the card's name. */
    val nameLines: List<String> = emptyList(),
) {
    val display: String get() = listOfNotNull(grader, gradeLabel?.takeIf { it.isNotBlank() }, grade).joinToString(" ")

    /** Stable id for frame voting: the same slab read twice gives the same id. */
    val id: String get() = "slab|$grader|${grade ?: "?"}|${cert ?: ""}"
}

/**
 * Reads PSA, Beckett (BGS), TAG, CGC and SGC slab labels from OCR lines. Pure Kotlin, unit-tested.
 *
 * Typical labels:
 *  - PSA:  "2023 POKEMON SV 151 #199" / "CHARIZARD ex" / "SPECIAL ILLUSTRATION RARE" / "GEM MT 10" / "105442370"
 *  - BGS:  "BECKETT" / "9.5 GEM MINT" / "CENTERING 9.5 CORNERS 9.5 EDGES 10 SURFACE 9.5" / "0012345678"
 *  - TAG:  "TAG" / "GEM MINT 10" / "A1234567" (cert = 1 letter + 7 digits, next to the QR code)
 */
object SlabReader {
    private val gradeWords = "GEM\\s?-?\\s?MT|GEM\\s?MINT|PRISTINE|BLACK\\s?LABEL|MINT\\s?\\+|MINT|NM\\s?-?\\s?MT\\s?\\+?|NM|" +
        "EX\\s?-?\\s?MT|EX|VG\\s?-?\\s?EX|VG|GOOD|FAIR|POOR|PR|FR|AUTHENTIC|PERFECT"
    private val wordThenGrade = Regex("\\b($gradeWords)\\s*(10|[1-9](?:\\.5)?)(?![0-9])")
    private val gradeThenWord = Regex("(?<![0-9.])(10|[1-9](?:\\.5)?)\\s+($gradeWords)\\b")
    private val subgrade = Regex("\\b(CENTERING|CENTER|CORNERS|EDGES|SURFACE)\\s*:?\\s*(10|[1-9](?:\\.5)?)(?![0-9])")
    private val labelNumber = Regex("#\\s?([A-Z]{0,5}\\d{1,3}[A-Z]?(?:-[A-Z]?\\d{2,3})?(?:/\\d{2,3})?)")
    private val year = Regex("\\b(19[89]\\d|20[0-4]\\d)\\b")

    private val brandOnLine = mapOf(
        "PSA" to Regex("\\bPSA\\b"),
        "BGS" to Regex("\\bBECKETT\\b|\\bBGS\\b|\\bBVG\\b|\\bBCCG\\b"),
        "TAG" to Regex("\\bTAG\\b(?!\\s*TEAM)"), // "TAG TEAM" is a Pokémon card type, not the grader
        "CGC" to Regex("\\bCGC\\b"),
        "SGC" to Regex("\\bSGC\\b"),
    )

    private val certFor = mapOf(
        "PSA" to Regex("(?<![0-9])(\\d{8,9})(?![0-9])"),
        "BGS" to Regex("(?<![0-9])(\\d{10})(?![0-9])"),
        "CGC" to Regex("(?<![0-9])(\\d{10})(?![0-9])"),
        "SGC" to Regex("(?<![0-9])(\\d{7,10})(?![0-9])"),
        "TAG" to Regex("(?<![A-Z0-9])([A-Z]\\d{7})(?![0-9])"),
    )

    private val gameWords = listOf(
        Game.POKEMON to Regex("POK[EÉ]MON|PKMN"),
        Game.MAGIC to Regex("MAGIC|\\bMTG\\b"),
        Game.YUGIOH to Regex("YU-?GI-?OH|KONAMI"),
        Game.ONE_PIECE to Regex("ONE\\s?PIECE"),
        Game.LORCANA to Regex("LORCANA"),
        Game.DRAGON_BALL to Regex("DRAGON\\s?BALL|FUSION WORLD"),
        Game.RIFTBOUND to Regex("RIFTBOUND"),
    )

    fun read(lines: List<OcrLine>): SlabInfo? {
        if (lines.isEmpty()) return null
        val texts = lines.map { it.text.uppercase(Locale.US).replace('—', '-').trim() }
        val all = texts.joinToString("\n")

        var grader = brandOnLine.entries.firstOrNull { (_, r) -> texts.any { r.containsMatchIn(it) } }?.key
        val subs = LinkedHashMap<String, String>()
        subgrade.findAll(all).forEach { m ->
            val k = when (m.groupValues[1]) { "CENTER", "CENTERING" -> "centering"; "CORNERS" -> "corners"; "EDGES" -> "edges"; else -> "surface" }
            subs.putIfAbsent(k, m.groupValues[2])
        }
        // No brand read: Beckett is the one with four subgrades on the label.
        if (grader == null && subs.size >= 3) grader = "BGS"

        val (grade, words) = wordThenGrade.find(all)?.let { it.groupValues[2] to it.groupValues[1] }
            ?: gradeThenWord.find(all)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: (null to null)
        val cleanWords = words?.replace(Regex("\\s+"), " ")?.replace(" -", "-")?.replace("- ", "-")

        if (grader == null) {
            // PSA's own grade words ("GEM MT", "NM-MT") are distinctive enough on their own.
            if (cleanWords != null && Regex("GEM ?MT|NM-?MT|EX-?MT|VG-?EX").containsMatchIn(cleanWords)) grader = "PSA" else return null
        }
        if (grade == null && grader != "PSA" && subs.isEmpty()) return null // a brand word alone isn't a slab

        val cert = certFor[grader]?.let { r ->
            texts.asSequence().flatMap { t -> r.findAll(t).map { it.groupValues[1] } }
                .firstOrNull { c -> !year.matches(c) && c != grade }
        }
        val game = gameWords.firstOrNull { (_, r) -> r.containsMatchIn(all) }?.first
        val number = labelNumber.find(all)?.groupValues?.get(1)
        val skip = Regex("\\b(PSA|BECKETT|BGS|TAG|CGC|SGC|GRADING|CERT|AUTHENTIC|CENTERING|CORNERS|EDGES|SURFACE)\\b|$gradeWords")
        val names = lines.map { it.text.trim() }.filter { t ->
            val u = t.uppercase(Locale.US)
            u.count { it.isLetter() } >= 3 && !skip.containsMatchIn(u.replace(Regex("\\b(EX|GX|V|VMAX|VSTAR)\\b"), "")) &&
                !year.containsMatchIn(u) && !u.contains('#')
        }
        return SlabInfo(
            grader = grader, grade = grade, gradeLabel = cleanWords, cert = cert, subgrades = subs,
            game = game, labelNumber = number, nameLines = names.take(6),
        )
    }

    /**
     * The card key a slab label points at when the card's own number can't be read through the case:
     * One Piece / Dragon Ball / Yu-Gi-Oh! codes directly, otherwise the label's name + number.
     */
    fun labelKey(info: SlabInfo, game: Game?): ScanKey? {
        val n = info.labelNumber ?: return null
        val g = game ?: info.game
        CardTextParser.parseTyped(n, g)?.let { key ->
            if (key !is NameKey) return key
        }
        val name = info.nameLines.firstOrNull() ?: return null
        return g?.let { NameKey(it, name, n.substringBefore('/')) }
    }
}
