package app.tcgportfolio

import android.app.Application
import app.tcgportfolio.data.CardRepository
import app.tcgportfolio.data.CollectionRepository
import app.tcgportfolio.data.Settings
import app.tcgportfolio.data.db.Database
import app.tcgportfolio.data.importer.CollectionImporter
import app.tcgportfolio.data.net.Http
import app.tcgportfolio.data.source.LorcanaSource
import app.tcgportfolio.data.source.MagicSource
import app.tcgportfolio.data.source.PokemonSource
import app.tcgportfolio.data.source.OptcgApi
import app.tcgportfolio.data.source.TcgCsvSource
import app.tcgportfolio.data.source.TcgPlayerFallback
import app.tcgportfolio.data.source.YugiohSource
import app.tcgportfolio.model.Game
import app.tcgportfolio.scan.ArtMatcher
import app.tcgportfolio.scan.ScanSession
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
    val session: ScanSession by lazy { ScanSession(cards, appScope, artMatcher) }
    val importer: CollectionImporter by lazy { CollectionImporter(app, db, cards, collection, appScope) }

    companion object {
        lateinit var instance: AppGraph
            private set

        fun init(app: Application) {
            instance = AppGraph(app)
        }
    }
}
