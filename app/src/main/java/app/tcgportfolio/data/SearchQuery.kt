package app.tcgportfolio.data

import app.tcgportfolio.model.Card
import app.tcgportfolio.model.CardSet
import app.tcgportfolio.model.normalizeNumber
import java.text.Normalizer
import java.util.Locale

/**
 * The sets a game knows, for recognising set words in a search ("eb03", "mkm", "obf"): set codes,
 * abbreviations, and extra abbreviation → set-name aliases (TCGplayer's abbreviations for Pokémon,
 * whose own set list has none).
 */
class SetIndex(sets: List<CardSet>, aliases: Map<String, List<String>> = emptyMap()) {
    private val abbrevByCode: Map<String, String> =
        sets.mapNotNull { s -> s.abbreviation?.let { s.code to it.lowercase(Locale.US) } }.toMap()
    private val byWord: Map<String, List<CardSet>> = buildMap<String, MutableList<CardSet>> {
        for (s in sets) {
            getOrPut(s.code.lowercase(Locale.US)) { mutableListOf() } += s
            s.abbreviation?.let { a -> getOrPut(a.lowercase(Locale.US)) { mutableListOf() } += s }
        }
    }
    private val aliasNames: Map<String, List<String>> =
        aliases.entries.associate { (k, v) -> k.lowercase(Locale.US) to v.map(SearchQuery::setNameKey) }
    private val allSets = sets

    fun isSet(word: String) = word in byWord || word in aliasNames

    fun abbreviation(setCode: String): String? = abbrevByCode[setCode]

    /** The sets a word names: by code or abbreviation, or through an alias by set name. */
    fun setsFor(word: String): List<CardSet> {
        val direct = byWord[word].orEmpty()
        val named = aliasNames[word].orEmpty().flatMap { alias -> allSets.filter { SearchQuery.sameSet(SearchQuery.setNameKey(it.name), alias) } }
        return (direct + named).distinctBy { it.code }
    }

    /** Does [word] name the set this card is in? */
    fun cardInSet(word: String, card: Card): Boolean {
        if (card.setCode.equals(word, ignoreCase = true) || abbrevByCode[card.setCode] == word) return true
        if (byWord[word]?.any { it.code == card.setCode } == true) return true
        val names = aliasNames[word] ?: return false
        val key = SearchQuery.setNameKey(card.setName)
        return names.any { SearchQuery.sameSet(key, it) }
    }

    companion object {
        val EMPTY = SetIndex(emptyList())
    }
}

/**
 * A search of any words in any order: "robin eb03 sp", "charizard obf sir", "lob 005", "elsa first chapter".
 * Every word has to match something about the card: its name (including printing labels such as "(SP)" or
 * "(Alternate Art)"), set code, abbreviation or name, number, rarity (or its initials, "sir") or a finish.
 */
class SearchQuery(raw: String) {
    val tokens: List<String> = fold(raw).split(Regex("[\\s,;]+"))
        .map { it.trim('#', '"', '\'', '(', ')', '[', ']', '.') }
        .filter { it.isNotEmpty() }
        .distinct()

    val isEmpty: Boolean get() = tokens.isEmpty()

    /**
     * Name searches to try against an API, in order, until one returns cards. Numbers and printing /
     * rarity words are left out (a name API finds nothing for "robin eb03 sp" but everything for "robin");
     * set words are tried first, since "mew" or "elsa first chapter" may be part of the name.
     */
    fun nameQueries(index: SetIndex): List<String> {
        val words = tokens.filter { it !in PRINTING_WORDS }
        val plain = words.filter { it.none(Char::isDigit) }
        val core = plain.filter { !index.isSet(it) }
        val attempts = buildList {
            if (plain.isNotEmpty()) add(plain)
            if (core.isNotEmpty()) {
                add(core)
                if (core.size > 2) add(core.take(2))
                if (core.size > 1) add(core.take(1))
            }
            // "porygon2": a name with a digit in it.
            if (isEmpty()) words.filter { !index.isSet(it) && !looksLikeNumber(it) }.takeIf { it.isNotEmpty() }?.let { add(it) }
        }
        return attempts.map { it.joinToString(" ") }.distinct().take(4)
    }

    /** The name words alone ("robin" in "robin eb03 sp"), for searches that only take a name. */
    fun nameCore(index: SetIndex): String? =
        tokens.filter { it !in PRINTING_WORDS && it.none(Char::isDigit) && !index.isSet(it) }.joinToString(" ").ifEmpty { null }

    /** True when some word matches the card's name (not just its set, number or rarity). */
    fun matchesName(card: Card): Boolean = tokens.any { nameScore(it, card) > 0 }

    /** True when every word of the search matches the card. */
    fun matches(card: Card, index: SetIndex = SetIndex.EMPTY): Boolean = tokens.all { score(it, card, index) > 0 }

    /** How well the card matches: higher for name words than for set or rarity words. */
    fun score(card: Card, index: SetIndex = SetIndex.EMPTY): Double = tokens.sumOf { score(it, card, index) }

    /**
     * The cards that match every word, best first. When none do (an abbreviation we don't know, a typo),
     * the cards matching the most words, as long as they match at least one name word.
     */
    fun rank(cards: List<Card>, index: SetIndex = SetIndex.EMPTY): List<Card> {
        if (tokens.isEmpty()) return cards
        val scored = cards.map { c -> Triple(c, tokens.count { score(it, c, index) > 0 }, score(c, index)) }
        val full = scored.filter { it.second == tokens.size }
        val pool = full.ifEmpty { scored.filter { (c, n, _) -> n > 0 && tokens.any { t -> nameScore(t, c) > 0 } } }
        return pool.sortedWith(compareByDescending<Triple<Card, Int, Double>> { it.second }.thenByDescending { it.third }).map { it.first }
    }

    private fun score(t: String, card: Card, index: SetIndex): Double = maxOf(
        nameScore(t, card),
        numberScore(t, card),
        if (index.cardInSet(t, card)) 3.0 else setNameScore(t, card),
        rarityScore(t, card),
        variantScore(t, card),
    )

    private fun nameScore(t: String, card: Card): Double {
        val name = fold(card.name)
        val words = name.split(nonWord).filter { it.isNotEmpty() }
        return when {
            words.any { it == t } -> 4.0
            t.length >= 2 && words.any { it.startsWith(t) } && (t.length >= 3 || t.any(Char::isDigit)) -> 3.0
            // Inside a run-together name ("nicorobin"), but not printing words: "sec" isn't in "insect".
            t.length >= 4 && t !in PRINTING_WORDS && name.replace(nonWord, "").contains(t) -> 2.0
            else -> 0.0
        }
    }

    private fun numberScore(t: String, card: Card): Double {
        val full = fold(card.number)
        val left = full.substringBefore('/')
        if (t == full || t == left) return 3.5
        if (looksLikeNumber(t)) {
            val want = normalizeNumber(t.substringBefore('/').trimStart { it.isLetter() })
            val have = normalizeNumber(left.substringAfterLast('-').trimStart { it.isLetter() })
            if (want.isNotEmpty() && want == have && t.substringBefore('/').none { it.isLetter() }) return 3.0
        }
        // A set prefix of the number: "eb03" → "eb03-055", "lob" → "lob-en001".
        if (t.length >= 3 && left.startsWith(t) && (left.length == t.length || !left[t.length].isLetterOrDigit())) return 2.5
        return 0.0
    }

    private fun setNameScore(t: String, card: Card): Double {
        if (t.length < 3) return 0.0
        val words = fold(card.setName).split(nonWord).filter { it.isNotEmpty() }
        return if (words.any { it == t || (it.startsWith(t) && t.none(Char::isDigit)) }) 1.5 else 0.0
    }

    private fun rarityScore(t: String, card: Card): Double {
        val r = card.rarity?.let(::fold) ?: return 0.0
        val words = r.split(nonWord).filter { it.isNotEmpty() }
        val initials = words.joinToString("") { it.take(1) }
        return when {
            t == r || t == initials || words.any { it == t } -> 2.0
            t.length >= 3 && words.any { it.startsWith(t) } -> 1.0
            else -> 0.0
        }
    }

    private fun variantScore(t: String, card: Card): Double {
        if (t.length < 3) return 0.0
        return if (card.allVariants.any { fold(it).contains(t) }) 1.0 else 0.0
    }

    companion object {
        private val nonWord = Regex("[^a-z0-9]+")

        /** Printing, finish and rarity words: matched against cards, never sent to a name search. */
        val PRINTING_WORDS = setOf(
            "sp", "spr", "sec", "sr", "ur", "sar", "sir", "ir", "ar", "chr", "csr", "tr", "hr", "aa", "alt", "alternate",
            "art", "parallel", "manga", "promo", "foil", "holo", "holofoil", "reverse", "rev", "gold", "signed",
            "signature", "stamp", "stamped", "prerelease", "pre-release", "textured", "rainbow", "secret", "rare",
            "common", "uncommon", "leader", "judge", "winner", "showcase", "borderless", "extended", "etched",
            "enchanted", "legendary", "epic", "full", "fa", "exclusive", "1st", "first-edition", "unlimited", "jp",
        )

        fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.US)

        fun looksLikeNumber(t: String) = t.any(Char::isDigit) && Regex("^[a-z]{0,5}(-[a-z]{0,2})?\\d{1,4}[a-z]?(/[a-z]{0,4}\\d{1,4})?$").matches(t)

        /** "SV03: Obsidian Flames" and "Obsidian Flames" → "obsidian flames". */
        fun setNameKey(name: String): String =
            fold(name).substringAfter(": ").replace("&", " ").replace(nonWord, " ").trim().replace(Regex("\\s+"), " ")

        /** "scarlet violet 151" is the set TCGdex calls "151". */
        fun sameSet(a: String, b: String): Boolean =
            a == b || (a.length >= 3 && b.endsWith(" $a")) || (b.length >= 3 && a.endsWith(" $b"))
    }
}
