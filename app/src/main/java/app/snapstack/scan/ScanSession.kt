package app.snapstack.scan

import app.snapstack.data.CardRepository
import app.snapstack.data.ResolvedFrom
import app.snapstack.model.Card
import app.snapstack.model.CollectionItem
import app.snapstack.model.Condition
import app.snapstack.model.Game
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

enum class ItemStatus { RESOLVING, READY, NOT_FOUND, ERROR }

data class SessionItem(
    val uid: Long,
    val key: ScanKey,
    val status: ItemStatus,
    val scannedAt: Long,
    val card: Card? = null,
    val alternatives: List<Card> = emptyList(),
    val confident: Boolean = true,
    val variant: String? = null,
    val variantChosen: Boolean = false,
    val qty: Int = 1,
    val condition: Condition = Condition.NM,
    val from: ResolvedFrom? = null,
    val resolveMs: Long? = null,
    val error: String? = null,
) {
    val unitPrice: Double? get() = card?.price(variant)
    val total: Double get() = (unitPrice ?: 0.0) * qty
    /** Rapid mode never blocks: items that deserve a second look are flagged instead. */
    val needsReview: Boolean
        get() = status == ItemStatus.READY && (!confident || (card?.variantMatters == true && !variantChosen))
}

/**
 * The scan "cart": everything scanned since the last save. Lookups run concurrently in the
 * background, so the camera keeps reading cards while earlier ones are still resolving.
 */
class ScanSession(private val repo: CardRepository, private val scope: CoroutineScope) {
    private val ids = AtomicLong(1)
    private val aliases = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val _items = MutableStateFlow<List<SessionItem>>(emptyList())
    val items: StateFlow<List<SessionItem>> = _items.asStateFlow()

    /** "Answer once, apply to the rest of the stack" printing choice, per game. */
    private val _sticky = MutableStateFlow<Map<Game, String>>(emptyMap())
    val sticky: StateFlow<Map<Game, String>> = _sticky.asStateFlow()

    val packCost = MutableStateFlow<Double?>(null)
    val label = MutableStateFlow("")
    var startedAt: Long = System.currentTimeMillis()
        private set

    /** [hints]: the text OCR saw on the card, used to catch misreads (see [CardRepository.resolve]). */
    fun add(key: ScanKey, hints: List<String> = emptyList()): Long {
        val uid = ids.getAndIncrement()
        val now = System.currentTimeMillis()
        if (_items.value.isEmpty()) startedAt = now
        _items.update { listOf(SessionItem(uid, key, ItemStatus.RESOLVING, now)) + it }
        scope.launch {
            val r = repo.resolve(key, hints)
            val card = r.card
            if (card == null) {
                update(uid) { copy(status = if (r.error != null) ItemStatus.ERROR else ItemStatus.NOT_FOUND, error = r.error) }
                return@launch
            }
            update(uid) {
                copy(
                    status = ItemStatus.READY, card = card, alternatives = r.alternatives,
                    // Flag for review when a good read of the card doesn't show this card's name anywhere.
                    confident = r.confident && !(key !is OnePieceDonKey && hints.size >= 3 &&
                        TextMatch.cardNameScore(card.name, hints) < 0.35),
                    variant = pickVariant(card), from = r.from, resolveMs = System.currentTimeMillis() - scannedAt,
                )
            }
            // Each DON!! card is picked by hand, so don't fold several DON!! scans into one row.
            if (key !is OnePieceDonKey && mergeDuplicate(uid)) return@launch
            val priced = repo.ensurePriced(card)
            if (priced !== card) update(uid) {
                copy(card = priced, variant = if (variantChosen) variant else pickVariant(priced))
            }
        }
        return uid
    }

    /** Adds an already-known card (from search, alternatives, or the single-card sheet). */
    fun addCard(card: Card, variant: String? = null): Long {
        val uid = ids.getAndIncrement()
        val now = System.currentTimeMillis()
        if (_items.value.isEmpty()) startedAt = now
        val item = SessionItem(
            uid, NameKey(card.game, card.name, card.number), ItemStatus.READY, now, card = card,
            variant = variant ?: pickVariant(card), variantChosen = variant != null, resolveMs = 0,
            from = ResolvedFrom.DEVICE,
        )
        _items.update { listOf(item) + it }
        if (!mergeDuplicate(uid)) scope.launch {
            val priced = repo.ensurePriced(card)
            if (priced !== card) update(uid) { copy(card = priced, variant = if (variantChosen) variant else pickVariant(priced)) }
        }
        return uid
    }

    /** The uid an item lives under now (a duplicate scan is merged into the earlier row). */
    fun currentUid(uid: Long): Long {
        var u = uid
        repeat(8) { u = aliases[u] ?: return u }
        return u
    }

    fun remove(uid: Long) = _items.update { list -> list.filterNot { it.uid == uid } }
    fun removeAll(uids: Collection<Long>) = _items.update { list -> list.filterNot { it.uid in uids } }

    fun setQty(uid: Long, qty: Int) {
        if (qty <= 0) remove(uid) else update(uid) { copy(qty = qty) }
    }

    fun setCondition(uid: Long, condition: Condition) = update(uid) { copy(condition = condition) }

    fun setVariant(uid: Long, variant: String) {
        val item = _items.value.firstOrNull { it.uid == uid } ?: return
        update(uid) { copy(variant = variant, variantChosen = true) }
        val card = item.card ?: return
        if (card.variantMatters) _sticky.update { it + (card.game to variant) }
    }

    fun clearSticky(game: Game) = _sticky.update { it - game }

    fun chooseAlternative(uid: Long, card: Card) {
        update(uid) {
            copy(card = card, alternatives = (alternatives + (this.card?.let { listOf(it) } ?: emptyList())).filter { it.id != card.id },
                confident = true, variant = pickVariant(card), variantChosen = false)
        }
        scope.launch {
            val priced = repo.ensurePriced(card)
            if (priced !== card) update(uid) { copy(card = priced, variant = if (variantChosen) variant else pickVariant(priced)) }
        }
    }

    fun markConfirmed(uid: Long) = update(uid) { copy(confident = true, variantChosen = true) }

    fun clear() {
        _items.value = emptyList()
        _sticky.value = emptyMap()
        packCost.value = null
        label.value = ""
    }

    fun readyItems(): List<SessionItem> = _items.value.filter { it.status == ItemStatus.READY && it.card != null }

    fun toCollectionItems(): List<CollectionItem> = readyItems().reversed().map { s ->
        CollectionItem(
            cardId = s.card!!.id, variant = s.variant, condition = s.condition, qty = s.qty,
            priceAtAdd = s.unitPrice, addedAt = System.currentTimeMillis(),
        )
    }

    private fun pickVariant(card: Card): String? {
        val sticky = _sticky.value[card.game]
        return if (sticky != null && sticky in card.allVariants) sticky else card.defaultVariant
    }

    /** Same card + printing scanned again → bump the quantity of the existing row instead. */
    private fun mergeDuplicate(uid: Long): Boolean {
        var merged = false
        _items.update { list ->
            val me = list.firstOrNull { it.uid == uid } ?: return@update list
            val card = me.card ?: return@update list
            val twin = list.firstOrNull {
                it.uid != uid && it.status == ItemStatus.READY && it.card?.id == card.id && it.variant == me.variant &&
                    it.condition == me.condition
            } ?: return@update list
            merged = true
            aliases[uid] = twin.uid
            val bumped = twin.copy(qty = twin.qty + me.qty)
            listOf(bumped) + list.filterNot { it.uid == uid || it.uid == twin.uid }
        }
        return merged
    }

    private fun update(uid: Long, transform: SessionItem.() -> SessionItem) =
        _items.update { list -> list.map { if (it.uid == uid) it.transform() else it } }
}
