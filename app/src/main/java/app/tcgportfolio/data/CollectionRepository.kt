package app.tcgportfolio.data

import app.tcgportfolio.data.db.Database
import app.tcgportfolio.data.db.Topic
import app.tcgportfolio.model.Card
import app.tcgportfolio.model.CollectionItem
import app.tcgportfolio.model.Game
import app.tcgportfolio.model.OwnedCard
import app.tcgportfolio.model.PortfolioPoint
import app.tcgportfolio.model.PriceAlert
import app.tcgportfolio.model.RipSession
import app.tcgportfolio.model.Variants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class PortfolioSummary(
    val value: Double,
    val costBasis: Double,
    /** Profit/loss over the copies that have a purchase price. */
    val pnl: Double,
    val pricedCopies: Int,
    val copies: Int,
    val distinct: Int,
    val byGame: Map<Game, Double>,
)

data class FiredAlert(val alert: PriceAlert, val card: Card, val price: Double)

class CollectionRepository(
    private val db: Database,
    private val cards: CardRepository,
    private val settings: Settings,
) {
    val owned: Flow<List<OwnedCard>> = db.observe(Topic.COLLECTION, Topic.CARDS) { db.ownedCards() }
    val wantlist: Flow<List<Card>> = db.observe(Topic.WANTLIST, Topic.CARDS) { db.wantlist() }
    val portfolioHistory: Flow<List<PortfolioPoint>> = db.observe(Topic.HISTORY) { db.portfolioHistory() }
    val rips: Flow<List<RipSession>> = db.observe(Topic.RIPS) { db.rips() }
    val alerts: Flow<List<Pair<PriceAlert, Card?>>> =
        db.observe(Topic.ALERTS, Topic.CARDS) { db.alerts().map { it to db.getCard(it.cardId) } }

    fun card(cardId: String): Flow<Card?> = db.observe(Topic.CARDS) { db.getCard(cardId) }
    fun ownedFor(cardId: String): Flow<List<CollectionItem>> = db.observe(Topic.COLLECTION) { db.ownedFor(cardId) }
    fun isWanted(cardId: String): Flow<Boolean> = db.observe(Topic.WANTLIST) { db.isWanted(cardId) }
    fun priceHistory(cardId: String, variant: String) = db.observe(Topic.HISTORY) { db.priceHistory(cardId, variant) }
    fun alertsFor(cardId: String) = db.observe(Topic.ALERTS) { db.alerts().filter { it.cardId == cardId } }
    fun setsFor(game: Game) = db.observe(Topic.SETS) { db.getSets(game) }
    fun cardsInSet(game: Game, setCode: String) = db.observe(Topic.CARDS) { db.cardsInSet(game, setCode) }

    suspend fun add(items: List<CollectionItem>) = io {
        db.insertItems(items)
        val cardsById = db.getCards(items.map { it.cardId }.toSet())
        db.recordPrices(cardsById, today())
        snapshotPortfolio()
    }

    suspend fun update(item: CollectionItem) = io { db.updateItem(item); snapshotPortfolio() }
    suspend fun delete(id: Long) = io { db.deleteItem(id); snapshotPortfolio() }
    suspend fun setWanted(cardId: String, wanted: Boolean) = io { db.setWanted(cardId, wanted) }
    suspend fun saveAlert(alert: PriceAlert) = io { db.upsertAlert(alert) }
    suspend fun deleteAlert(id: Long) = io { db.deleteAlert(id) }
    suspend fun deleteRip(id: Long) = io { db.deleteRip(id) }

    /** Saves a scan session: one rip record (if it was a pack opening) plus the collection rows. */
    suspend fun saveSession(items: List<CollectionItem>, rip: RipSession?) = io {
        val ripId = rip?.let { db.insertRip(it) }
        db.insertItems(items.map { it.copy(ripId = ripId) })
        db.recordPrices(db.getCards(items.map { it.cardId }.toSet()), today())
        snapshotPortfolio()
    }

    fun summarize(owned: List<OwnedCard>): PortfolioSummary {
        val adjust = settings.conditionAdjusted.value
        var value = 0.0; var cost = 0.0; var pnl = 0.0; var priced = 0; var copies = 0
        val byGame = HashMap<Game, Double>()
        for (o in owned) {
            val v = o.totalValue(adjust)
            value += v
            copies += o.item.qty
            byGame[o.card.game] = (byGame[o.card.game] ?: 0.0) + v
            o.totalCost?.let { c -> cost += c; pnl += v - c; priced += o.item.qty }
        }
        return PortfolioSummary(value, cost, pnl, priced, copies, owned.map { it.card.id }.toSet().size, byGame)
    }

    private fun snapshotPortfolio() {
        val s = summarize(db.ownedCards())
        db.recordPortfolio(PortfolioPoint(today(), s.value, s.costBasis, s.copies))
    }

    /** Refreshes every tracked card's price, records history and returns alerts that fired. */
    suspend fun refreshAll(onProgress: (Int, Int) -> Unit = { _, _ -> }): List<FiredAlert> = io {
        val tracked = db.getCards(db.trackedCardIds())
        cards.refreshPrices(tracked, onProgress)
        snapshotPortfolio()
        settings.lastPriceRefresh = System.currentTimeMillis()
        checkAlerts()
    }

    private fun checkAlerts(): List<FiredAlert> {
        val fired = ArrayList<FiredAlert>()
        val now = System.currentTimeMillis()
        for (a in db.alerts()) {
            if (!a.active) continue
            val card = db.getCard(a.cardId) ?: continue
            val price = card.price(a.variant) ?: continue
            val hit = if (a.above) price >= a.target else price <= a.target
            if (hit && now - a.lastFired > 20 * 3600_000L) {
                db.upsertAlert(a.copy(lastFired = now))
                fired += FiredAlert(a, card, price)
            }
        }
        return fired
    }

    // ------------------------------------------------------------------ export / backup

    suspend fun exportCsv(out: OutputStream) = io {
        val adjust = settings.conditionAdjusted.value
        out.bufferedWriter().use { w ->
            w.appendLine("Game,Name,Set,Number,Rarity,Printing,Language,Condition,Quantity,Grader,Grade,Purchase Price,Market Price,Total Value,Added")
            for (o in db.ownedCards()) {
                val c = o.card; val i = o.item
                val added = Instant.ofEpochMilli(i.addedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                w.appendLine(
                    listOf(
                        c.game.label, c.title, c.setName, c.number, c.rarity ?: "", Variants.label(i.variant),
                        app.tcgportfolio.model.Languages.label(i.language),
                        i.condition.code, i.qty.toString(), i.grader ?: "", i.grade ?: "",
                        i.costEach?.let { "%.2f".format(it) } ?: "", "%.2f".format(o.unitValue(adjust)),
                        "%.2f".format(o.totalValue(adjust)), added.toString(),
                    ).joinToString(",") { csv(it) }
                )
            }
        }
    }

    suspend fun backup(out: OutputStream) = io {
        out.bufferedWriter().use { it.write(db.exportJson().toString(1)) }
    }

    suspend fun restore(input: InputStream): Int = io {
        val text = input.bufferedReader().use { it.readText() }
        val count = db.importJson(JSONObject(text))
        cards.clearMemory()
        count
    }

    /** See [Database.wipeCollection]. */
    suspend fun wipe(includeWantlistAndAlerts: Boolean) = io { db.wipeCollection(includeWantlistAndAlerts) }

    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        fun today(): Long = LocalDate.now().toEpochDay()
    }
}
