package app.snapstack.scan

import app.snapstack.model.Game
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
    val h get() = bottom - top
    val w get() = right - left
    fun union(o: Box) = Box(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
}

data class OcrLine(val text: String, val box: Box, val block: Int = 0)

/** OCR output for one camera frame, in upright image coordinates. */
data class OcrFrame(val lines: List<OcrLine>, val width: Int, val height: Int, val tookMs: Long = 0)

/** What a card's printed identifier told us. [id] is what frame voting compares. */
sealed interface ScanKey {
    val game: Game
    val id: String
    val label: String
}

data class PokemonKey(
    val number: String,
    val total: String?,
    val abbrev: String? = null,
    val nameHints: List<String> = emptyList(),
) : ScanKey {
    override val game get() = Game.POKEMON
    override val id get() = "pkm|${number.lowercase(Locale.US)}|${total ?: ""}"
    override val label get() = listOfNotNull(abbrev, if (total != null) "$number/$total" else number).joinToString(" ")
}

data class PokemonPromoKey(val prefix: String, val number: String, val nameHints: List<String> = emptyList()) : ScanKey {
    override val game get() = Game.POKEMON
    override val id get() = "pkmpromo|$prefix|$number"
    override val label get() = "$prefix $number"
}

data class MagicKey(val set: String, val number: String) : ScanKey {
    override val game get() = Game.MAGIC
    override val id get() = "mtg|${set.lowercase(Locale.US)}|${number.lowercase(Locale.US)}"
    override val label get() = "${set.uppercase(Locale.US)} $number"
}

data class YugiohKey(val code: String) : ScanKey {
    override val game get() = Game.YUGIOH
    override val id get() = "ygo|${code.uppercase(Locale.US)}"
    override val label get() = code.uppercase(Locale.US)
}

data class YugiohPasscodeKey(val passcode: String) : ScanKey {
    override val game get() = Game.YUGIOH
    override val id get() = "ygopc|$passcode"
    override val label get() = passcode
}

data class LorcanaKey(val set: String, val number: String) : ScanKey {
    override val game get() = Game.LORCANA
    override val id get() = "lor|$set|${number.lowercase(Locale.US)}"
    override val label get() = "Set $set #$number"
}

data class OnePieceKey(val code: String) : ScanKey {
    override val game get() = Game.ONE_PIECE
    override val id get() = "op|${code.uppercase(Locale.US)}"
    override val label get() = code.uppercase(Locale.US)
}

/**
 * A One Piece DON!! card. They print no number or name, so the scanner only knows "a DON!! card";
 * [setHint] (the set of the last One Piece card scanned) puts that set's DON!! cards first.
 */
data class OnePieceDonKey(val setHint: String? = null) : ScanKey {
    override val game get() = Game.ONE_PIECE
    override val id get() = "op|DON!!"
    override val label get() = "DON!!"
}

/** Riftbound prints "OGN • 179/298"; the set code is optional (it is not always readable). */
data class RiftboundKey(val set: String?, val number: String, val total: String?) : ScanKey {
    override val game get() = Game.RIFTBOUND
    override val id get() = "rb|${number.lowercase(Locale.US).trimStart('0')}|${total ?: ""}"
    override val label get() = listOfNotNull(set, if (total != null) "$number/$total" else number).joinToString(" ")
}

/** A card name to look up (typed lists, or a stubborn card snapped without a readable number). */
data class NameKey(override val game: Game, val name: String, val number: String? = null) : ScanKey {
    override val id get() = "name|${game.id}|${TextMatch.norm(name)}|${number ?: ""}"
    override val label get() = if (number != null) "$name #$number" else name
}

data class Detection(val key: ScanKey, val score: Double, val box: Box?)

object TextMatch {
    private val marks = Regex("\\p{M}+")
    private val nonAlnum = Regex("[^a-z0-9 ]")
    private val spaces = Regex("\\s+")

    fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(marks, "").lowercase(Locale.US)
        .replace(nonAlnum, " ").replace(spaces, " ").trim()

    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val d = levenshtein(a, b)
        return 1.0 - d.toDouble() / max(a.length, b.length)
    }

    /** How well a card name is supported by the OCR lines (0..1). */
    fun nameScore(name: String, hints: List<String>): Double {
        val n = norm(name)
        if (n.isEmpty() || hints.isEmpty()) return 0.0
        var best = 0.0
        for (h in hints) {
            val hn = norm(h)
            if (hn.isEmpty()) continue
            if (hn == n) return 1.0
            if (n.length >= 4 && (" $hn ").contains(" $n ")) best = max(best, 0.95)
            best = max(best, similarity(n, hn))
            // Compare against same-length token windows ("Basic Pikachu 60 HP" vs "Pikachu").
            val tokens = hn.split(' ')
            val want = n.split(' ').size
            if (tokens.size > want) {
                for (i in 0..tokens.size - want) {
                    best = max(best, similarity(n, tokens.subList(i, i + want).joinToString(" ")))
                }
            }
        }
        return best
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
