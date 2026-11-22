package app.snapstack

import android.app.Application
import app.snapstack.data.CardRepository
import app.snapstack.data.CollectionRepository
import app.snapstack.data.Settings
import app.snapstack.data.db.Database
import app.snapstack.data.importer.CollectionImporter
import app.snapstack.data.net.Http
import app.snapstack.data.source.LorcanaSource
import app.snapstack.data.source.MagicSource
import app.snapstack.data.source.PokemonSource
import app.snapstack.data.source.PriceChartingSource
import app.snapstack.data.source.OptcgApi
import app.snapstack.data.source.TcgCsvSource
import app.snapstack.data.source.TcgPlayerFallback
import app.snapstack.data.source.YugiohSource
import app.snapstack.model.Game
import app.snapstack.scan.ArtMatcher
import app.snapstack.scan.ScanSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-rolled dependency graph: one instance of each service for the whole app. */
class AppGraph(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = Settings(app)
    val db = Database(app)
    val http = Http(app.cacheDir, "TCGPortfolio/${BuildConfig.VERSION_NAME} (Android; personal collection app)")

    val cards: CardRepository by lazy {
        CardRepository(
            db, settings, appScope,
            mapOf(
                Game.POKEMON to PokemonSource(http) { code -> db.getSet(Game.POKEMON, code)?.name },
                Game.MAGIC to MagicSource(http),
                Game.YUGIOH to YugiohSource(http),
                Game.LORCANA to LorcanaSource(http),
                Game.ONE_PIECE to TcgCsvSource(http, Game.ONE_PIECE, TcgCsvSource.ONE_PIECE_CATEGORY, "op"),
                Game.RIFTBOUND to TcgCsvSource(http, Game.RIFTBOUND, TcgCsvSource.RIFTBOUND_CATEGORY, "rb"),
                Game.DRAGON_BALL to TcgCsvSource(
                    http, Game.DRAGON_BALL, TcgCsvSource.DRAGON_BALL_FUSION_WORLD_CATEGORY, "dbs",
                    extraCategories = listOf(TcgCsvSource.DRAGON_BALL_SUPER_CATEGORY),
                ),
            ),
            tcgplayer = TcgPlayerFallback(http),
            optcg = OptcgApi(http),
        )
    }
    val collection: CollectionRepository by lazy { CollectionRepository(db, cards, settings) }
    val artMatcher: ArtMatcher by lazy { ArtMatcher(app) }
    val priceCharting by lazy { PriceChartingSource(http) { settings.priceChartingKey.value } }
    val session: ScanSession by lazy { ScanSession(cards, appScope, artMatcher, priceCharting) }
    val importer: CollectionImporter by lazy { CollectionImporter(app, db, cards, collection, appScope) }

    companion object {
        lateinit var instance: AppGraph
            private set

        fun init(app: Application) {
            instance = AppGraph(app)
        }
    }
}
