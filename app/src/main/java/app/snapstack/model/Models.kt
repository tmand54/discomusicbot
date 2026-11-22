package app.snapstack.model

import java.util.Locale

enum class Game(val id: String, val label: String, val short: String) {
    POKEMON("pkm", "Pokémon", "PKM"),
    MAGIC("mtg", "Magic", "MTG"),
    YUGIOH("ygo", "Yu-Gi-Oh!", "YGO"),
    LORCANA("lor", "Lorcana", "LOR"),
    ONE_PIECE("op", "One Piece", "OP"),
    RIFTBOUND("rb", "Riftbound", "RB"),
    DRAGON_BALL("dbs", "Dragon Ball", "DB");

    companion object {
        fun fromId(id: String?): Game? = entries.firstOrNull { it.id == id }
    }
}

/** A single printing of a card, as cached locally. Prices are USD market prices keyed by variant. */
data class Card(
    val id: String,
    val game: Game,
    val name: String,
    val subtitle: String? = null,
    val setCode: String,
    val setName: String,
    val number: String,
    val rarity: String? = null,
    val imageSmall: String? = null,
    val imageLarge: String? = null,
    val prices: Map<String, Double> = emptyMap(),
    val variants: List<String> = emptyList(),
    val url: String? = null,
    val pricedAt: Long = 0L,
) {
    /** All variants we know about: priced ones first, then any extra printings. */
    val allVariants: List<String>
        get() = Variants.sort((prices.keys + variants).distinct())

    val defaultVariant: String?
        get() = allVariants.firstOrNull { prices.containsKey(it) } ?: allVariants.firstOrNull()

    fun price(variant: String?): Double? =
        (variant?.let { prices[it] }) ?: defaultVariant?.let { prices[it] }

    /** True when choosing the wrong printing would change the value noticeably. */
    val variantMatters: Boolean
        get() {
            val p = prices.values.filter { it > 0 }
            if (p.size < 2) return false
            val min = p.min(); val max = p.max()
            return max - min > 0.25 && max > min * 1.25
        }

    val displayNumber: String
        get() = when (game) {
            Game.YUGIOH, Game.ONE_PIECE, Game.DRAGON_BALL -> number
            else -> "#$number"
        }

    val title: String get() = if (subtitle.isNullOrBlank() || game != Game.LORCANA) name else "$name – $subtitle"
}

data class CardSet(
    val game: Game,
    val code: String,
    val name: String,
    val printedTotal: Int?,
    val total: Int?,
    val abbreviation: String? = null,
    val releaseDate: String? = null,
    val cachedCount: Int = 0,
    val cachedAt: Long = 0L,
)

enum class Condition(val code: String, val label: String, val factor: Double) {
    NM("NM", "Near Mint", 1.0),
    LP("LP", "Lightly Played", 0.85),
    MP("MP", "Moderately Played", 0.7),
    HP("HP", "Heavily Played", 0.5),
    DMG("DMG", "Damaged", 0.35);

    companion object {
        fun from(code: String?) = entries.firstOrNull { it.code == code } ?: NM
    }
}

data class CollectionItem(
    val id: Long = 0,
    val cardId: String,
    val variant: String?,
    val condition: Condition = Condition.NM,
    val qty: Int = 1,
    val costEach: Double? = null,
    val grader: String? = null,
    val grade: String? = null,
    val notes: String? = null,
    val valueOverride: Double? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val priceAtAdd: Double? = null,
    val ripId: Long? = null,
)

data class OwnedCard(val item: CollectionItem, val card: Card) {
    fun unitValue(conditionAdjusted: Boolean): Double {
        item.valueOverride?.let { return it }
        val base = card.price(item.variant) ?: 0.0
        return if (conditionAdjusted && item.grader == null) base * item.condition.factor else base
    }

    fun totalValue(conditionAdjusted: Boolean) = unitValue(conditionAdjusted) * item.qty
    val totalCost: Double? get() = item.costEach?.let { it * item.qty }
}

data class RipSession(
    val id: Long = 0,
    val startedAt: Long,
    val label: String,
    val packCost: Double?,
    val cardCount: Int,
    val value: Double,
    val game: Game?,
)

data class PricePoint(val day: Long, val price: Double)

data class PortfolioPoint(val day: Long, val value: Double, val cost: Double, val count: Int)

data class PriceAlert(
    val id: Long = 0,
    val cardId: String,
    val variant: String?,
    val above: Boolean,
    val target: Double,
    val active: Boolean = true,
    val lastFired: Long = 0,
)

object Variants {
    private val order = listOf(
        "normal", "holofoil", "reverseHolofoil", "1stEdition", "1stEditionHolofoil",
        "unlimited", "unlimitedHolofoil", "foil", "etched",
    )

    fun sort(list: List<String>): List<String> = list.sortedBy { v ->
        val i = order.indexOf(v); if (i < 0) order.size else i
    }

    fun label(variant: String?): String = when (variant) {
        null -> "Standard"
        "normal" -> "Normal"
        "holofoil" -> "Holo"
        "reverseHolofoil" -> "Reverse Holo"
        "1stEdition" -> "1st Edition"
        "1stEditionHolofoil" -> "1st Ed. Holo"
        "unlimited" -> "Unlimited"
        "unlimitedHolofoil" -> "Unlimited Holo"
        "foil" -> "Foil"
        "etched" -> "Etched Foil"
        else -> variant.replaceFirstChar { it.titlecase(Locale.US) }
    }

    /** "reverse-holofoil" -> "reverseHolofoil" */
    fun fromKebab(key: String): String {
        val parts = key.split('-', '_', ' ').filter { it.isNotBlank() }
        if (parts.isEmpty()) return key
        return parts.first().lowercase(Locale.US) + parts.drop(1).joinToString("") {
            it.lowercase(Locale.US).replaceFirstChar { c -> c.titlecase(Locale.US) }
        }
    }
}

fun normalizeNumber(n: String): String {
    val t = n.trim().lowercase(Locale.US)
    val stripped = t.trimStart('0')
    return stripped.ifEmpty { if (t.isEmpty()) "" else "0" }
}
