package app.snapstack.scan

import app.snapstack.data.CardRepository
import app.snapstack.data.Resolution
import app.snapstack.data.ResolvedFrom
import app.snapstack.data.source.PriceChartingSource
import app.snapstack.model.Card
import app.snapstack.model.CollectionItem
import app.snapstack.model.Condition
import app.snapstack.model.Game
import app.snapstack.model.Languages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

enum class ItemStatus { RESOLVING, READY, NOT_FOUND, ERROR }

/** Where a slab's value came from. */
enum class GradedLookup { NONE, LOOKING_UP, FOUND, NOT_FOUND, MANUAL }

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
    /** How closely the camera picture matches the chosen printing's artwork (0..1), when compared. */
    val artScore: Float? = null,
    /** The printing was chosen by artwork among cards that share its number. */
    val artPicked: Boolean = false,
    /** "JP" for a Japanese copy (One Piece). */
    val language: String? = null,
    /** A graded slab: grader, grade, cert, subgrades. */
    val grading: Grading? = null,
    /** The slab's value (from PriceCharting when a key is set, or typed in). */
    val gradedValue: Double? = null,
    val gradedLookup: GradedLookup = GradedLookup.NONE,
) {
    val unitPrice: Double? get() = gradedValue ?: card?.price(variant)
    val total: Double get() = (unitPrice ?: 0.0) * qty
    /** Rapid mode never blocks: items that deserve a second look are flagged instead. */
    val needsReview: Boolean
        get() = status == ItemStatus.READY && (!confident || (card?.variantMatters == true && !variantChosen))
}

/**
 * The scan "cart": everything scanned since the last save. Lookups run concurrently in the
 * background, so the camera keeps reading cards while earlier ones are still resolving.
 */
class ScanSession(
    private val repo: CardRepository,
    private val scope: CoroutineScope,
    private val artMatcher: ArtMatcher? = null,
    private val gradedPrices: PriceChartingSource? = null,
) {
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
    fun add(
        key: ScanKey, hints: List<String> = emptyList(), thumb: CardThumb? = null,
        language: String? = null, grading: Grading? = null,
    ): Long {
        val uid = ids.getAndIncrement()
        val now = System.currentTimeMillis()
        if (_items.value.isEmpty()) startedAt = now
        _items.update { listOf(SessionItem(uid, key, ItemStatus.RESOLVING, now, language = language, grading = grading)) + it }
        scope.launch {
            var japanese = language == Languages.JP || (key.game == Game.ONE_PIECE && looksNonLatin(hints))
            // Japanese cards don't print the English name, so don't use the OCR text to second-guess them.
            val r = repo.resolve(key, if (japanese) emptyList() else hints).let { res ->
                // Japan gets sets months before the English release: keep the code as a Japanese card.
                if (res.card == null && key is OnePieceKey && runCatching { repo.isJapaneseOnlyCode(key.code) }.getOrDefault(false)) {
                    japanese = true
                    update(uid) { copy(language = Languages.JP) }
                    Resolution(repo.japaneseOnly(key.code), from = ResolvedFrom.DEVICE)
                } else res
            }
            val card = r.card
            if (card == null) {
                update(uid) { copy(status = if (r.error != null) ItemStatus.ERROR else ItemStatus.NOT_FOUND, error = r.error) }
                return@launch
            }
            // Flag for review when a good read of the card doesn't show this card's name anywhere.
            val nameOk = japanese ||
                !(key !is OnePieceDonKey && hints.size >= 3 && TextMatch.cardNameScore(card.name, hints) < 0.35)
            update(uid) {
                copy(
                    status = ItemStatus.READY, card = card, alternatives = r.alternatives,
                    confident = r.confident && nameOk,
                    variant = pickVariant(card), from = r.from, resolveMs = System.currentTimeMillis() - scannedAt,
                )
            }
            // One Piece: promo / Judge / pre-release / reprint printings of this code live in other sets.
            var alternatives = r.alternatives
            var fallback = r.confident
            if (card.game == Game.ONE_PIECE && key !is OnePieceDonKey) {
                val extra = runCatching { repo.otherPrintings(card) }.getOrDefault(emptyList())
                    .filter { e -> alternatives.none { it.id == e.id } }
                if (extra.isNotEmpty()) {
                    alternatives = alternatives + extra
                    val base = card.price(null) ?: 0.0
                    // A much pricier printing could be the one in hand: ask, unless the artwork settles it.
                    fallback = fallback && extra.none { (it.price(null) ?: 0.0) > base * 2 + 1.0 }
                    update(uid) {
                        if (this.card?.id != card.id) this
                        else copy(alternatives = alternatives, confident = fallback && nameOk)
                    }
                }
            }
            // Several printings share this number (parallel, alt art, promo, DON!!): let the artwork decide.
            if (thumb != null && alternatives.isNotEmpty()) {
                matchArt(uid, card, alternatives, thumb, don = key is OnePieceDonKey, nameOk = nameOk, fallback = fallback)
            }
            if (grading != null) lookUpGradedValue(uid)
            // Each DON!! card is picked by hand, and each slab is its own copy: don't fold those into one row.
            if (key !is OnePieceDonKey && grading == null && mergeDuplicate(uid)) return@launch
            val current = _items.value.firstOrNull { it.uid == uid }?.card ?: card
            val priced = repo.ensurePriced(current)
            if (priced !== current) update(uid) {
                if (this.card?.id != priced.id) this
                else copy(card = priced, variant = if (variantChosen) variant else pickVariant(priced))
            }
        }
        return uid
    }

    private suspend fun matchArt(
        uid: Long, original: Card, alternatives: List<Card>, thumb: CardThumb,
        don: Boolean, nameOk: Boolean, fallback: Boolean,
    ) {
        val matcher = artMatcher ?: return
        val pool = (listOf(original) + alternatives).take(48)
        val ranked = runCatching { matcher.rank(thumb, pool) }.getOrDefault(emptyList())
        if (ranked.isEmpty()) return
        val decisive = ArtMatch.decisive(ranked)
        // DON!! cards differ only in art, so ask for a clearer win before trusting the pick outright.
        val strong = ArtMatch.decisive(ranked, minScore = 0.55f, minGap = 0.1f)
        update(uid) {
            if (card?.id != original.id) return@update this // the user already picked one
            val chosen = if (decisive) ranked.first().first else original
            val byLook = ranked.map { it.first } + alternatives.filter { a -> ranked.none { it.first.id == a.id } }
            copy(
                card = chosen,
                alternatives = byLook.filter { it.id != chosen.id },
                confident = nameOk && (if (decisive) (!don || strong) else fallback),
                variant = if (chosen.id != original.id) pickVariant(chosen) else variant,
                artScore = ranked.firstOrNull { it.first.id == chosen.id }?.second,
                artPicked = decisive,
            )
        }
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

    fun setLanguage(uid: Long, language: String?) = update(uid) { copy(language = language) }

    /** Changes (or removes) the grading on an item and refreshes its graded value. */
    fun setGrading(uid: Long, grading: Grading?) {
        update(uid) { copy(grading = grading, gradedValue = null, gradedLookup = GradedLookup.NONE) }
        if (grading != null) scope.launch { lookUpGradedValue(uid) }
    }

    /** A value typed by the user wins over the looked-up one; clearing it looks the value up again. */
    fun setGradedValue(uid: Long, value: Double?) {
        if (value != null) update(uid) { copy(gradedValue = value, gradedLookup = GradedLookup.MANUAL) }
        else setGrading(uid, _items.value.firstOrNull { it.uid == uid }?.grading)
    }

    /** Edits the cert number without dropping the value already found for this grade. */
    fun setCert(uid: Long, cert: String?) = update(uid) { copy(grading = grading?.copy(cert = cert?.trim()?.ifEmpty { null })) }

    val canLookUpGradedValues: Boolean get() = gradedPrices?.enabled == true

    private suspend fun lookUpGradedValue(uid: Long) {
        val source = gradedPrices?.takeIf { it.enabled } ?: return
        val item = _items.value.firstOrNull { it.uid == uid } ?: return
        val card = item.card ?: return
        val g = item.grading ?: return
        val grade = g.grade ?: return
        update(uid) { copy(gradedLookup = GradedLookup.LOOKING_UP) }
        val price = runCatching { source.gradedPrice(card, g.grader, grade) }.getOrNull()
        update(uid) {
            val same = this.grading?.grader == g.grader && this.grading.grade == g.grade
            if (!same || this.gradedLookup == GradedLookup.MANUAL) this
            else copy(gradedValue = price, gradedLookup = if (price != null) GradedLookup.FOUND else GradedLookup.NOT_FOUND)
        }
    }

    /** OCR of Japanese text through the Latin model yields almost no English words. */
    private fun looksNonLatin(hints: List<String>): Boolean {
        if (hints.size < 4) return false
        val words = hints.sumOf { line -> Regex("[A-Za-z]{4,}").findAll(line).count() }
        return words <= 1
    }

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
                confident = true, variant = pickVariant(card), variantChosen = false, artPicked = false, artScore = null)
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
            priceAtAdd = s.unitPrice, addedAt = System.currentTimeMillis(), language = s.language,
            grader = s.grading?.grader, grade = s.grading?.grade, notes = s.grading?.notes,
            valueOverride = s.gradedValue,
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
                    it.condition == me.condition && it.language == me.language
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
