package app.tcgportfolio.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.tcgportfolio.model.Card
import app.tcgportfolio.model.CardSet
import app.tcgportfolio.model.CollectionItem
import app.tcgportfolio.model.Condition
import app.tcgportfolio.model.Game
import app.tcgportfolio.model.OwnedCard
import app.tcgportfolio.model.PortfolioPoint
import app.tcgportfolio.model.PriceAlert
import app.tcgportfolio.model.PricePoint
import app.tcgportfolio.model.RipSession
import app.tcgportfolio.model.normalizeNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** Which group of tables changed; used to re-run observing queries. */
enum class Topic { CARDS, SETS, COLLECTION, WANTLIST, HISTORY, ALERTS, RIPS }

/**
 * Plain SQLite storage (no annotation processing) with tiny change-notification flows.
 * Everything the user owns lives here, on the device.
 */
class Database(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    private val versions = Topic.entries.associateWith { MutableStateFlow(0L) }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    fun notify(vararg topics: Topic) = topics.forEach { t -> versions.getValue(t).value++ }

    fun <T> observe(vararg topics: Topic, query: () -> T): Flow<T> {
        val flows = topics.map { versions.getValue(it) }
        val trigger: Flow<Any> = if (flows.size == 1) flows[0] else combine(flows) { it.sum() }
        return trigger.map { query() }.flowOn(Dispatchers.IO)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE cards(
                id TEXT PRIMARY KEY, game TEXT NOT NULL, name TEXT NOT NULL, subtitle TEXT,
                set_code TEXT NOT NULL, set_name TEXT NOT NULL, number TEXT NOT NULL, number_norm TEXT NOT NULL,
                rarity TEXT, img_s TEXT, img_l TEXT, prices TEXT, variants TEXT, url TEXT,
                priced_at INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX cards_set_num ON cards(game, set_code, number_norm)")
        db.execSQL("CREATE INDEX cards_name ON cards(game, name COLLATE NOCASE)")
        db.execSQL(
            """CREATE TABLE sets(
                game TEXT NOT NULL, code TEXT NOT NULL, name TEXT NOT NULL, printed_total INTEGER, total INTEGER,
                abbrev TEXT, release TEXT, cached_count INTEGER NOT NULL DEFAULT 0, cached_at INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(game, code))"""
        )
        db.execSQL("CREATE INDEX sets_printed ON sets(game, printed_total)")
        db.execSQL(
            """CREATE TABLE collection(
                id INTEGER PRIMARY KEY AUTOINCREMENT, card_id TEXT NOT NULL, variant TEXT, cond TEXT NOT NULL,
                qty INTEGER NOT NULL, cost REAL, grader TEXT, grade TEXT, notes TEXT, value_override REAL,
                added_at INTEGER NOT NULL, price_at_add REAL, rip_id INTEGER, lang TEXT)"""
        )
        db.execSQL("CREATE INDEX collection_card ON collection(card_id)")
        db.execSQL("CREATE TABLE wantlist(card_id TEXT PRIMARY KEY, added_at INTEGER NOT NULL)")
        db.execSQL(
            """CREATE TABLE price_history(card_id TEXT NOT NULL, variant TEXT NOT NULL, day INTEGER NOT NULL,
                price REAL NOT NULL, PRIMARY KEY(card_id, variant, day))"""
        )
        db.execSQL(
            """CREATE TABLE portfolio_history(day INTEGER PRIMARY KEY, value REAL NOT NULL, cost REAL NOT NULL,
                count INTEGER NOT NULL, basis REAL)"""
        )
        createTicks(db)
        db.execSQL(
            """CREATE TABLE alerts(id INTEGER PRIMARY KEY AUTOINCREMENT, card_id TEXT NOT NULL, variant TEXT,
                above INTEGER NOT NULL, target REAL NOT NULL, active INTEGER NOT NULL, last_fired INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE rips(id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL, label TEXT NOT NULL,
                pack_cost REAL, card_count INTEGER NOT NULL, value REAL NOT NULL, game TEXT)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE collection ADD COLUMN lang TEXT")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE portfolio_history ADD COLUMN basis REAL")
            createTicks(db)
            backfillBasis(db)
        }
    }

    /** Timestamped snapshots of the collection's value for the 1D / 5D charts (kept for about a week). */
    private fun createTicks(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE IF NOT EXISTS portfolio_ticks(ts INTEGER PRIMARY KEY, value REAL NOT NULL, basis REAL NOT NULL, count INTEGER NOT NULL)"
    )

    /**
     * Fills in the invested basis of history rows that don't have one (from before it was recorded, or
     * restored from an older backup), from the copies owned at the end of each day.
     */
    private fun backfillBasis(db: SQLiteDatabase) {
        val days = db.rawQuery("SELECT day FROM portfolio_history WHERE basis IS NULL", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        val zone = java.time.ZoneId.systemDefault()
        for (day in days) {
            val endMs = java.time.LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli()
            db.execSQL(
                """UPDATE portfolio_history SET basis = (SELECT COALESCE(SUM(COALESCE(cost, price_at_add, 0) * qty), 0)
                   FROM collection WHERE added_at < ?) WHERE day = ?""",
                arrayOf<Any>(endMs, day),
            )
        }
    }

    // ---------------------------------------------------------------- cards

    fun upsertCards(cards: Collection<Card>, keepPrices: Boolean = true) {
        if (cards.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (c in cards) {
                var card = c
                if (keepPrices && c.pricedAt == 0L) {
                    // Don't wipe prices we already have with an unpriced (e.g. set-list) copy.
                    getCardInternal(db, c.id)?.let { old ->
                        if (old.pricedAt > 0) card = c.copy(
                            prices = old.prices, pricedAt = old.pricedAt,
                            variants = (c.variants + old.variants).distinct(),
                            rarity = c.rarity ?: old.rarity,
                            imageSmall = c.imageSmall ?: old.imageSmall,
                            imageLarge = c.imageLarge ?: old.imageLarge,
                            url = c.url ?: old.url,
                        )
                    }
                }
                db.insertWithOnConflict("cards", null, card.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notify(Topic.CARDS)
    }

    fun getCard(id: String): Card? = getCardInternal(readableDatabase, id)

    private fun getCardInternal(db: SQLiteDatabase, id: String): Card? =
        db.rawQuery("SELECT * FROM cards WHERE id=?", arrayOf(id)).use { c -> if (c.moveToFirst()) c.toCard() else null }

    fun getCards(ids: Collection<String>): List<Card> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(500).flatMap { chunk ->
            val q = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery("SELECT * FROM cards WHERE id IN ($q)", chunk.toTypedArray()).use { it.toCards() }
        }
    }

    fun findBySetNumber(game: Game, setCode: String, number: String): List<Card> =
        readableDatabase.rawQuery(
            "SELECT * FROM cards WHERE game=? AND set_code=? COLLATE NOCASE AND number_norm=?",
            arrayOf(game.id, setCode, normalizeNumber(number)),
        ).use { it.toCards() }

    fun findByNumberInSets(game: Game, setCodes: List<String>, number: String): List<Card> {
        if (setCodes.isEmpty()) return emptyList()
        val q = setCodes.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT * FROM cards WHERE game=? AND number_norm=? AND set_code IN ($q)",
            arrayOf(game.id, normalizeNumber(number)) + setCodes,
        ).use { it.toCards() }
    }

    /**
     * Cards with this printed number in any set (or only in [setCodes]). With [prefix] a number
     * typed without its total ("179") also matches "179/298".
     */
    fun findByNumber(game: Game, number: String, prefix: Boolean = false, setCodes: List<String>? = null): List<Card> {
        val n = normalizeNumber(number)
        val args = mutableListOf(game.id, n)
        var sql = "SELECT * FROM cards WHERE game=? AND (number_norm=?"
        if (prefix) { sql += " OR number_norm LIKE ?"; args += "$n/%" }
        sql += ")"
        if (setCodes != null) {
            if (setCodes.isEmpty()) return emptyList()
            sql += " AND set_code IN (${setCodes.joinToString(",") { "?" }})"; args += setCodes
        }
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { it.toCards() }
    }

    fun cardsInSet(game: Game, setCode: String): List<Card> =
        readableDatabase.rawQuery(
            "SELECT * FROM cards WHERE game=? AND set_code=? ORDER BY CAST(number_norm AS INTEGER), number_norm",
            arrayOf(game.id, setCode),
        ).use { it.toCards() }

    fun searchLocal(game: Game?, query: String, limit: Int = 60): List<Card> {
        val args = mutableListOf("%${query.trim()}%")
        var where = "name LIKE ?"
        if (game != null) { where += " AND game=?"; args += game.id }
        return readableDatabase.rawQuery(
            "SELECT * FROM cards WHERE $where ORDER BY priced_at DESC LIMIT $limit", args.toTypedArray(),
        ).use { it.toCards() }
    }

    /**
     * Pre-filter for a word search: every word must appear in the name, set name, number or rarity, or the
     * card must be in one of the sets that word names ([setCodes]). [SearchQuery] then does the exact matching.
     */
    fun searchWords(game: Game, words: List<String>, setCodes: Map<String, Collection<String>>, limit: Int = 400): List<Card> {
        if (words.isEmpty()) return emptyList()
        val args = mutableListOf(game.id)
        val clauses = words.map { w ->
            val like = "%$w%"
            args += listOf(like, like, like, like)
            val codes = setCodes[w].orEmpty()
            args += codes
            "(name LIKE ? OR set_name LIKE ? OR number LIKE ? OR IFNULL(rarity, '') LIKE ?" +
                (if (codes.isNotEmpty()) " OR set_code IN (${codes.joinToString(",") { "?" }})" else "") + ")"
        }
        return readableDatabase.rawQuery(
            "SELECT * FROM cards WHERE game=? AND ${clauses.joinToString(" AND ")} ORDER BY priced_at DESC LIMIT $limit",
            args.toTypedArray(),
        ).use { it.toCards() }
    }

    fun cachedCardCount(game: Game): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM cards WHERE game=?", arrayOf(game.id)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    // ---------------------------------------------------------------- sets

    fun upsertSets(sets: List<CardSet>) {
        if (sets.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (s in sets) {
                val cv = ContentValues().apply {
                    put("game", s.game.id); put("code", s.code); put("name", s.name)
                    put("printed_total", s.printedTotal); put("total", s.total)
                    put("release", s.releaseDate)
                    if (s.abbreviation != null) put("abbrev", s.abbreviation)
                }
                val updated = db.update("sets", cv, "game=? AND code=?", arrayOf(s.game.id, s.code))
                if (updated == 0) {
                    cv.put("abbrev", s.abbreviation)
                    cv.put("cached_count", s.cachedCount); cv.put("cached_at", s.cachedAt)
                    db.insert("sets", null, cv)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notify(Topic.SETS)
    }

    fun markSetCached(game: Game, code: String, count: Int, abbreviation: String? = null) {
        val cv = ContentValues().apply {
            put("cached_count", count); put("cached_at", System.currentTimeMillis())
            if (abbreviation != null) put("abbrev", abbreviation)
        }
        writableDatabase.update("sets", cv, "game=? AND code=?", arrayOf(game.id, code))
        notify(Topic.SETS)
    }

    /** Marks a game's sets as not downloaded so they are fetched again (cards already saved are kept). */
    fun resetSetsCached(game: Game) {
        writableDatabase.execSQL("UPDATE sets SET cached_at=0 WHERE game=?", arrayOf(game.id))
        notify(Topic.SETS)
    }

    fun getSets(game: Game): List<CardSet> =
        readableDatabase.rawQuery("SELECT * FROM sets WHERE game=? ORDER BY release DESC", arrayOf(game.id))
            .use { c -> buildList { while (c.moveToNext()) add(c.toSet()) } }

    fun getSet(game: Game, code: String): CardSet? =
        readableDatabase.rawQuery("SELECT * FROM sets WHERE game=? AND code=? COLLATE NOCASE", arrayOf(game.id, code))
            .use { c -> if (c.moveToFirst()) c.toSet() else null }

    fun setsByPrintedTotal(game: Game, printedTotal: Int): List<CardSet> =
        readableDatabase.rawQuery(
            "SELECT * FROM sets WHERE game=? AND printed_total=? ORDER BY release DESC",
            arrayOf(game.id, printedTotal.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toSet()) } }

    fun setsByAbbrev(game: Game, abbrev: String): List<CardSet> =
        readableDatabase.rawQuery(
            "SELECT * FROM sets WHERE game=? AND abbrev=? COLLATE NOCASE", arrayOf(game.id, abbrev),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toSet()) } }

    fun setCount(game: Game): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM sets WHERE game=?", arrayOf(game.id)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun clearCatalog() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Keep cards that are referenced by the user's data.
            db.execSQL(
                """DELETE FROM cards WHERE id NOT IN (SELECT card_id FROM collection)
                   AND id NOT IN (SELECT card_id FROM wantlist) AND id NOT IN (SELECT card_id FROM alerts)"""
            )
            db.execSQL("UPDATE sets SET cached_count=0, cached_at=0")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notify(Topic.CARDS, Topic.SETS)
    }

    /**
     * Deletes the user's collection: every copy, the rip history and the value history; also the
     * wantlist and price alerts when [includeWantlistAndAlerts]. Downloaded card data and settings stay.
     */
    fun wipeCollection(includeWantlistAndAlerts: Boolean) {
        val tables = listOf("collection", "rips", "portfolio_history", "portfolio_ticks") +
            if (includeWantlistAndAlerts) listOf("wantlist", "alerts") else emptyList()
        val db = writableDatabase
        db.beginTransaction()
        try {
            tables.forEach { db.delete(it, null, null) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notify(Topic.COLLECTION, Topic.RIPS, Topic.HISTORY, Topic.WANTLIST, Topic.ALERTS)
    }

    // ---------------------------------------------------------------- collection

    fun insertItems(items: List<CollectionItem>): List<Long> {
        val db = writableDatabase
        val ids = mutableListOf<Long>()
        db.beginTransaction()
        try {
            for (item in items) ids += db.insert("collection", null, item.toValues(includeId = false))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notify(Topic.COLLECTION)
        return ids
    }

    fun updateItem(item: CollectionItem) {
        writableDatabase.update("collection", item.toValues(includeId = false), "id=?", arrayOf(item.id.toString()))
        notify(Topic.COLLECTION)
    }

    fun deleteItem(id: Long) {
        writableDatabase.delete("collection", "id=?", arrayOf(id.toString()))
        notify(Topic.COLLECTION)
    }

    fun ownedCards(): List<OwnedCard> =
        readableDatabase.rawQuery(
            """SELECT c.*, k.* FROM collection c JOIN cards k ON k.id = c.card_id ORDER BY c.added_at DESC""", null,
        ).use { cur -> buildList { while (cur.moveToNext()) add(OwnedCard(cur.toItem(), cur.toCard())) } }

    /** Deletes every copy of these cards and returns the deleted rows, so [restoreItems] can undo it. */
    fun deleteCopiesOf(cardIds: Collection<String>): List<CollectionItem> {
        if (cardIds.isEmpty()) return emptyList()
        val removed = mutableListOf<CollectionItem>()
        val db = writableDatabase
        db.beginTransaction()
        try {
            // SQLite allows at most 999 bound parameters per statement.
            cardIds.distinct().chunked(500).forEach { chunk ->
                val args = chunk.toTypedArray()
                val marks = chunk.joinToString(",") { "?" }
                db.rawQuery("SELECT * FROM collection WHERE card_id IN ($marks)", args).use { c -> while (c.moveToNext()) removed += c.toItem() }
                db.delete("collection", "card_id IN ($marks)", args)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notify(Topic.COLLECTION)
        return removed
    }

    /** Puts deleted rows back exactly as they were (same ids, rips, prices paid and notes). */
    fun restoreItems(items: List<CollectionItem>) {
        if (items.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            items.forEach { db.insertWithOnConflict("collection", null, it.toValues(includeId = true), SQLiteDatabase.CONFLICT_IGNORE) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notify(Topic.COLLECTION)
    }

    fun ownedFor(cardId: String): List<CollectionItem> =
        readableDatabase.rawQuery("SELECT * FROM collection WHERE card_id=? ORDER BY added_at DESC", arrayOf(cardId))
            .use { c -> buildList { while (c.moveToNext()) add(c.toItem()) } }

    fun allItems(): List<CollectionItem> =
        readableDatabase.rawQuery("SELECT * FROM collection ORDER BY id", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toItem()) } }

    fun trackedCardIds(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery(
            "SELECT card_id FROM collection UNION SELECT card_id FROM wantlist UNION SELECT card_id FROM alerts WHERE active=1",
            null,
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    // ---------------------------------------------------------------- wantlist

    fun setWanted(cardId: String, wanted: Boolean) {
        if (wanted) {
            val cv = ContentValues().apply { put("card_id", cardId); put("added_at", System.currentTimeMillis()) }
            writableDatabase.insertWithOnConflict("wantlist", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        } else {
            writableDatabase.delete("wantlist", "card_id=?", arrayOf(cardId))
        }
        notify(Topic.WANTLIST)
    }

    fun wantlist(): List<Card> =
        readableDatabase.rawQuery(
            "SELECT k.* FROM wantlist w JOIN cards k ON k.id = w.card_id ORDER BY w.added_at DESC", null,
        ).use { it.toCards() }

    fun wantlistIds(): List<Pair<String, Long>> =
        readableDatabase.rawQuery("SELECT card_id, added_at FROM wantlist", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) } }

    fun isWanted(cardId: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM wantlist WHERE card_id=?", arrayOf(cardId)).use { it.moveToFirst() }

    // ---------------------------------------------------------------- history

    fun recordPrices(cards: Collection<Card>, day: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (card in cards) for ((variant, price) in card.prices) {
                val cv = ContentValues().apply {
                    put("card_id", card.id); put("variant", variant); put("day", day); put("price", price)
                }
                db.insertWithOnConflict("price_history", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notify(Topic.HISTORY)
    }

    fun priceHistory(cardId: String, variant: String): List<PricePoint> =
        readableDatabase.rawQuery(
            "SELECT day, price FROM price_history WHERE card_id=? AND variant=? ORDER BY day", arrayOf(cardId, variant),
        ).use { c -> buildList { while (c.moveToNext()) add(PricePoint(c.getLong(0), c.getDouble(1))) } }

    fun recordPortfolio(point: PortfolioPoint) {
        val cv = ContentValues().apply {
            put("day", point.day); put("value", point.value); put("cost", point.cost); put("count", point.count)
            put("basis", point.basis)
        }
        writableDatabase.insertWithOnConflict("portfolio_history", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        notify(Topic.HISTORY)
    }

    /**
     * Records a timestamped snapshot. Snapshots less than a minute apart (a bulk add, a price refresh
     * in progress) collapse into one, and those older than [TICK_DAYS] days are dropped.
     */
    fun recordTick(ts: Long, value: Double, basis: Double, count: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("portfolio_ticks", "ts > ?", arrayOf((ts - 60_000L).toString()))
            db.delete("portfolio_ticks", "ts < ?", arrayOf((ts - TICK_DAYS * 86_400_000L).toString()))
            val cv = ContentValues().apply { put("ts", ts); put("value", value); put("basis", basis); put("count", count) }
            db.insertWithOnConflict("portfolio_ticks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notify(Topic.HISTORY)
    }

    fun portfolioTicks(): List<app.tcgportfolio.data.ValuePoint> =
        readableDatabase.rawQuery("SELECT ts, value, basis FROM portfolio_ticks ORDER BY ts", null)
            .use { c -> buildList { while (c.moveToNext()) add(app.tcgportfolio.data.ValuePoint(c.getLong(0), c.getDouble(1), c.getDouble(2))) } }

    fun portfolioHistory(): List<PortfolioPoint> =
        readableDatabase.rawQuery("SELECT day, value, cost, count, basis FROM portfolio_history ORDER BY day", null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(PortfolioPoint(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getInt(3), if (c.isNull(4)) null else c.getDouble(4)))
                }
            }

    // ---------------------------------------------------------------- alerts

    fun upsertAlert(alert: PriceAlert) {
        val cv = ContentValues().apply {
            put("card_id", alert.cardId); put("variant", alert.variant); put("above", if (alert.above) 1 else 0)
            put("target", alert.target); put("active", if (alert.active) 1 else 0); put("last_fired", alert.lastFired)
        }
        if (alert.id == 0L) writableDatabase.insert("alerts", null, cv)
        else writableDatabase.update("alerts", cv, "id=?", arrayOf(alert.id.toString()))
        notify(Topic.ALERTS)
    }

    fun deleteAlert(id: Long) {
        writableDatabase.delete("alerts", "id=?", arrayOf(id.toString()))
        notify(Topic.ALERTS)
    }

    fun alerts(): List<PriceAlert> =
        readableDatabase.rawQuery("SELECT * FROM alerts ORDER BY id DESC", null).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    PriceAlert(
                        id = c.long("id"), cardId = c.str("card_id")!!, variant = c.str("variant"),
                        above = c.int("above") == 1, target = c.double("target")!!, active = c.int("active") == 1,
                        lastFired = c.long("last_fired"),
                    )
                )
            }
        }

    // ---------------------------------------------------------------- rips

    fun insertRip(rip: RipSession): Long {
        val cv = ContentValues().apply {
            put("started_at", rip.startedAt); put("label", rip.label); put("pack_cost", rip.packCost)
            put("card_count", rip.cardCount); put("value", rip.value); put("game", rip.game?.id)
        }
        val id = writableDatabase.insert("rips", null, cv)
        notify(Topic.RIPS)
        return id
    }

    fun rips(): List<RipSession> =
        readableDatabase.rawQuery("SELECT * FROM rips ORDER BY started_at DESC", null).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    RipSession(
                        id = c.long("id"), startedAt = c.long("started_at"), label = c.str("label") ?: "Rip",
                        packCost = c.double("pack_cost"), cardCount = c.int("card_count"), value = c.double("value") ?: 0.0,
                        game = Game.fromId(c.str("game")),
                    )
                )
            }
        }

    fun deleteRip(id: Long) {
        writableDatabase.delete("rips", "id=?", arrayOf(id.toString()))
        notify(Topic.RIPS)
    }

    // ---------------------------------------------------------------- backup

    fun exportJson(): JSONObject {
        val root = JSONObject()
        root.put("format", "snapstack-backup")
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())
        val ids = trackedCardIds()
        root.put("cards", JSONArray().apply { getCards(ids).forEach { put(it.toJson()) } })
        root.put("collection", JSONArray().apply { allItems().forEach { put(it.toJson()) } })
        root.put("wantlist", JSONArray().apply {
            wantlistIds().forEach { (id, at) -> put(JSONObject().put("cardId", id).put("addedAt", at)) }
        })
        root.put("alerts", JSONArray().apply {
            alerts().forEach { a ->
                put(JSONObject().put("cardId", a.cardId).put("variant", a.variant).put("above", a.above)
                    .put("target", a.target).put("active", a.active))
            }
        })
        root.put("rips", JSONArray().apply {
            rips().forEach { r ->
                put(JSONObject().put("id", r.id).put("startedAt", r.startedAt).put("label", r.label)
                    .put("packCost", r.packCost).put("cardCount", r.cardCount).put("value", r.value)
                    .put("game", r.game?.id))
            }
        })
        root.put("portfolio", JSONArray().apply {
            portfolioHistory().forEach { p ->
                put(JSONObject().put("day", p.day).put("value", p.value).put("cost", p.cost).put("count", p.count).put("basis", p.basis))
            }
        })
        return root
    }

    /** Replaces the user's data with the backup. Returns number of collection rows restored. */
    fun importJson(root: JSONObject): Int {
        require(root.optString("format") == "snapstack-backup") { "Not a TCG Investment Portfolio backup file" }
        val db = writableDatabase
        var restored = 0
        db.beginTransaction()
        try {
            listOf("collection", "wantlist", "alerts", "rips", "portfolio_history", "portfolio_ticks").forEach { db.delete(it, null, null) }
            root.optJSONArray("cards")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val card = cardFromJson(arr.getJSONObject(i)) ?: continue
                    db.insertWithOnConflict("cards", null, card.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            val ripIdMap = HashMap<Long, Long>()
            root.optJSONArray("rips")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val cv = ContentValues().apply {
                        put("started_at", o.optLong("startedAt")); put("label", o.optString("label", "Rip"))
                        if (!o.isNull("packCost")) put("pack_cost", o.optDouble("packCost"))
                        put("card_count", o.optInt("cardCount")); put("value", o.optDouble("value", 0.0))
                        put("game", o.optString("game").ifBlank { null })
                    }
                    ripIdMap[o.optLong("id")] = db.insert("rips", null, cv)
                }
            }
            root.optJSONArray("collection")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = itemFromJson(arr.getJSONObject(i)) ?: continue
                    val mapped = item.copy(ripId = item.ripId?.let { ripIdMap[it] })
                    db.insert("collection", null, mapped.toValues(includeId = false)); restored++
                }
            }
            root.optJSONArray("wantlist")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val cv = ContentValues().apply { put("card_id", o.getString("cardId")); put("added_at", o.optLong("addedAt")) }
                    db.insertWithOnConflict("wantlist", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            root.optJSONArray("alerts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val cv = ContentValues().apply {
                        put("card_id", o.getString("cardId")); put("variant", o.optString("variant").ifBlank { null })
                        put("above", if (o.optBoolean("above")) 1 else 0); put("target", o.optDouble("target"))
                        put("active", if (o.optBoolean("active", true)) 1 else 0)
                    }
                    db.insert("alerts", null, cv)
                }
            }
            root.optJSONArray("portfolio")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val cv = ContentValues().apply {
                        put("day", o.getLong("day")); put("value", o.optDouble("value")); put("cost", o.optDouble("cost"))
                        put("count", o.optInt("count"))
                        if (o.has("basis") && !o.isNull("basis")) put("basis", o.optDouble("basis"))
                    }
                    db.insertWithOnConflict("portfolio_history", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            backfillBasis(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notify(*Topic.entries.toTypedArray())
        return restored
    }

    companion object {
        const val NAME = "snapstack.db"
        const val VERSION = 3
        const val TICK_DAYS = 10L
    }
}

// -------------------------------------------------------------------- mapping helpers

private fun Cursor.idx(col: String) = getColumnIndexOrThrow(col)
private fun Cursor.str(col: String): String? = idx(col).let { if (isNull(it)) null else getString(it) }
private fun Cursor.long(col: String): Long = idx(col).let { if (isNull(it)) 0L else getLong(it) }
private fun Cursor.int(col: String): Int = idx(col).let { if (isNull(it)) 0 else getInt(it) }
private fun Cursor.double(col: String): Double? = idx(col).let { if (isNull(it)) null else getDouble(it) }

private fun Cursor.toCards(): List<Card> = buildList { while (moveToNext()) add(toCard()) }

private fun Cursor.toCard(): Card {
    // In joined queries the card's own "id" column comes after the collection's, so look it up by the last match.
    val idIndex = columnNames.indexOfLast { it == "id" }
    return Card(
        id = getString(idIndex),
        game = Game.fromId(str("game")) ?: Game.POKEMON,
        name = str("name") ?: "",
        subtitle = str("subtitle"),
        setCode = str("set_code") ?: "",
        setName = str("set_name") ?: "",
        number = str("number") ?: "",
        rarity = str("rarity"),
        imageSmall = str("img_s"),
        imageLarge = str("img_l"),
        prices = parsePrices(str("prices")),
        variants = str("variants")?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
        url = str("url"),
        pricedAt = long("priced_at"),
    )
}

private fun Cursor.toItem(): CollectionItem {
    val idIndex = columnNames.indexOfFirst { it == "id" }
    return CollectionItem(
        id = getLong(idIndex),
        cardId = str("card_id")!!,
        variant = str("variant"),
        condition = Condition.from(str("cond")),
        qty = int("qty"),
        costEach = double("cost"),
        grader = str("grader"),
        grade = str("grade"),
        notes = str("notes"),
        valueOverride = double("value_override"),
        addedAt = long("added_at"),
        priceAtAdd = double("price_at_add"),
        ripId = idx("rip_id").let { if (isNull(it)) null else getLong(it) },
        language = getColumnIndex("lang").let { if (it < 0 || isNull(it)) null else getString(it) },
    )
}

private fun Cursor.toSet() = CardSet(
    game = Game.fromId(str("game")) ?: Game.POKEMON,
    code = str("code")!!,
    name = str("name") ?: "",
    printedTotal = idx("printed_total").let { if (isNull(it)) null else getInt(it) },
    total = idx("total").let { if (isNull(it)) null else getInt(it) },
    abbreviation = str("abbrev"),
    releaseDate = str("release"),
    cachedCount = int("cached_count"),
    cachedAt = long("cached_at"),
)

private fun parsePrices(json: String?): Map<String, Double> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        val o = JSONObject(json)
        buildMap { o.keys().forEach { k -> val v = o.optDouble(k); if (!v.isNaN()) put(k, v) } }
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun Card.toValues() = ContentValues().apply {
    put("id", id); put("game", game.id); put("name", name); put("subtitle", subtitle)
    put("set_code", setCode); put("set_name", setName); put("number", number); put("number_norm", normalizeNumber(number))
    put("rarity", rarity); put("img_s", imageSmall); put("img_l", imageLarge)
    put("prices", JSONObject(prices as Map<*, *>).toString()); put("variants", variants.joinToString(","))
    put("url", url); put("priced_at", pricedAt); put("updated_at", System.currentTimeMillis())
}

private fun CollectionItem.toValues(includeId: Boolean) = ContentValues().apply {
    if (includeId) put("id", id)
    put("card_id", cardId); put("variant", variant); put("cond", condition.code); put("qty", qty)
    put("cost", costEach); put("grader", grader); put("grade", grade); put("notes", notes)
    put("value_override", valueOverride); put("added_at", addedAt); put("price_at_add", priceAtAdd); put("rip_id", ripId)
    put("lang", language)
}

fun Card.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("game", game.id); put("name", name); put("subtitle", subtitle)
    put("setCode", setCode); put("setName", setName); put("number", number); put("rarity", rarity)
    put("imageSmall", imageSmall); put("imageLarge", imageLarge)
    put("prices", JSONObject(prices as Map<*, *>)); put("variants", JSONArray(variants)); put("url", url)
    put("pricedAt", pricedAt)
}

fun cardFromJson(o: JSONObject): Card? {
    val game = Game.fromId(o.optString("game")) ?: return null
    val prices = o.optJSONObject("prices")?.let { p ->
        buildMap { p.keys().forEach { k -> val v = p.optDouble(k); if (!v.isNaN()) put(k, v) } }
    } ?: emptyMap()
    val variants = o.optJSONArray("variants")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
    return Card(
        id = o.getString("id"), game = game, name = o.optString("name"),
        subtitle = o.optString("subtitle").takeIf { it.isNotBlank() && it != "null" },
        setCode = o.optString("setCode"), setName = o.optString("setName"), number = o.optString("number"),
        rarity = o.optString("rarity").takeIf { it.isNotBlank() && it != "null" },
        imageSmall = o.optString("imageSmall").takeIf { it.startsWith("http") },
        imageLarge = o.optString("imageLarge").takeIf { it.startsWith("http") },
        prices = prices, variants = variants,
        url = o.optString("url").takeIf { it.startsWith("http") }, pricedAt = o.optLong("pricedAt"),
    )
}

private fun CollectionItem.toJson(): JSONObject = JSONObject().apply {
    put("cardId", cardId); put("variant", variant); put("condition", condition.code); put("qty", qty)
    put("costEach", costEach); put("grader", grader); put("grade", grade); put("notes", notes)
    put("valueOverride", valueOverride); put("addedAt", addedAt); put("priceAtAdd", priceAtAdd); put("ripId", ripId)
    put("language", language)
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun itemFromJson(o: JSONObject): CollectionItem? {
    val cardId = o.optStringOrNull("cardId") ?: return null
    return CollectionItem(
        cardId = cardId, variant = o.optStringOrNull("variant"), condition = Condition.from(o.optStringOrNull("condition")),
        qty = o.optInt("qty", 1).coerceAtLeast(1), costEach = o.optDoubleOrNull("costEach"),
        grader = o.optStringOrNull("grader"), grade = o.optStringOrNull("grade"), notes = o.optStringOrNull("notes"),
        valueOverride = o.optDoubleOrNull("valueOverride"), addedAt = o.optLong("addedAt", System.currentTimeMillis()),
        priceAtAdd = o.optDoubleOrNull("priceAtAdd"), ripId = if (o.isNull("ripId")) null else o.optLong("ripId"),
        language = o.optStringOrNull("language"),
    )
}
