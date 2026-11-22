package app.snapstack

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.snapstack.model.Game
import app.snapstack.ui.card.CardDetailScreen
import app.snapstack.ui.collection.CollectionScreen
import app.snapstack.ui.collection.SetDetailScreen
import app.snapstack.ui.home.PortfolioScreen
import app.snapstack.ui.scan.ScanScreen
import app.snapstack.ui.search.SearchScreen
import app.snapstack.ui.settings.SettingsScreen
import app.snapstack.ui.theme.SnapStackTheme

class MainActivity : ComponentActivity() {
    private val pendingCard = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingCard.value = intent?.getStringExtra(EXTRA_CARD_ID)
        setContent {
            SnapStackTheme {
                AppRoot(pendingCard.value) { pendingCard.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CARD_ID)?.let { pendingCard.value = it }
    }

    companion object {
        const val EXTRA_CARD_ID = "card_id"
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.PORTFOLIO, "Portfolio", Icons.Outlined.Insights),
    Tab(Routes.COLLECTION, "Collection", Icons.Outlined.CollectionsBookmark),
    Tab(Routes.SCAN, "Scan", Icons.Filled.CameraAlt),
    Tab(Routes.SEARCH, "Search", Icons.Outlined.Search),
    Tab(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
)

object Routes {
    const val PORTFOLIO = "portfolio"
    const val COLLECTION = "collection"
    const val SCAN = "scan"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    fun card(id: String) = "card/${Uri.encode(id)}"
    fun set(game: Game, code: String) = "set/${game.id}/${Uri.encode(code)}"
}

@Composable
private fun AppRoot(pendingCard: String?, onPendingHandled: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = route in tabs.map { it.route } && route != Routes.SCAN

    LaunchedEffect(pendingCard) {
        if (pendingCard != null) {
            nav.navigate(Routes.card(pendingCard))
            onPendingHandled()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(showBar, enter = fadeIn(), exit = fadeOut()) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(nav, startDestination = Routes.PORTFOLIO) {
                composable(Routes.PORTFOLIO) {
                    PortfolioScreen(
                        onOpenCard = { nav.navigate(Routes.card(it)) },
                        onScan = { nav.navigateTab(Routes.SCAN) },
                    )
                }
                composable(Routes.COLLECTION) {
                    CollectionScreen(
                        onOpenCard = { nav.navigate(Routes.card(it)) },
                        onOpenSet = { g, c -> nav.navigate(Routes.set(g, c)) },
                        onScan = { nav.navigateTab(Routes.SCAN) },
                    )
                }
                composable(Routes.SCAN) {
                    ScanScreen(
                        onClose = { if (!nav.popBackStack()) nav.navigateTab(Routes.PORTFOLIO) },
                        onOpenCard = { nav.navigate(Routes.card(it)) },
                    )
                }
                composable(Routes.SEARCH) { SearchScreen(onOpenCard = { nav.navigate(Routes.card(it)) }) }
                composable(Routes.SETTINGS) { SettingsScreen() }
                composable(
                    "card/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    val id = Uri.decode(entry.arguments?.getString("id").orEmpty())
                    CardDetailScreen(cardId = id, onBack = { nav.popBackStack() })
                }
                composable(
                    "set/{game}/{code}",
                    arguments = listOf(
                        navArgument("game") { type = NavType.StringType },
                        navArgument("code") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val game = Game.fromId(entry.arguments?.getString("game")) ?: Game.POKEMON
                    val code = Uri.decode(entry.arguments?.getString("code").orEmpty())
                    SetDetailScreen(game, code, onBack = { nav.popBackStack() }, onOpenCard = { nav.navigate(Routes.card(it)) })
                }
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
