package app.snapstack.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.snapstack.AppGraph
import app.snapstack.model.Card
import app.snapstack.model.CollectionItem
import app.snapstack.model.Game
import app.snapstack.scan.CardTextParser
import app.snapstack.ui.components.CardImage
import app.snapstack.ui.components.EmptyState
import app.snapstack.ui.components.money
import app.snapstack.ui.theme.Numeric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class SearchViewModel : ViewModel() {
    private val graph = AppGraph.instance
    val game = MutableStateFlow(Game.fromId(graph.settings.scanGame.value) ?: Game.POKEMON)
    val results = MutableStateFlow<List<Card>?>(null)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    /** For catalog games (One Piece, Riftbound): sets on device vs. all sets. */
    val catalog = MutableStateFlow<Pair<Int, Int>?>(null)
    val catalogProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    private var job: Job? = null

    init { refreshCatalog() }

    fun selectGame(g: Game) {
        game.value = g
        refreshCatalog()
    }

    private fun refreshCatalog() {
        val g = game.value
        catalog.value = null
        if (!graph.cards.isCatalogGame(g)) return
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { graph.cards.catalogStatus(g) }
            if (game.value == g) catalog.value = status
        }
    }

    fun downloadCatalog(thenSearch: String) {
        if (catalogProgress.value != null) return
        val g = game.value
        viewModelScope.launch {
            catalogProgress.value = 0 to 0
            runCatching { graph.cards.downloadCatalog(g) { d, t -> catalogProgress.value = d to t } }
                .onFailure { error.value = "Download failed — check your connection" }
            catalogProgress.value = null
            refreshCatalog()
            if (thenSearch.isNotBlank()) search(thenSearch)
        }
    }

    fun search(q: String) {
        job?.cancel()
        if (q.isBlank()) return
        job = viewModelScope.launch {
            loading.value = true; error.value = null
            val g = game.value
            // Instant local hits first, then the full online results.
            results.value = withContext(Dispatchers.IO) { graph.db.searchLocal(g, q, 40) }
            runCatching { graph.cards.search(g, q) }
                .onSuccess { results.value = it }
                .onFailure { if (results.value.isNullOrEmpty()) error.value = "Search failed — check your connection" }
            loading.value = false
        }
    }

}

@Composable
fun SearchScreen(onOpenCard: (String) -> Unit) {
    val vm: SearchViewModel = viewModel()
    val game by vm.game.collectAsState()
    val results by vm.results.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Search", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { showImport = true }) {
                Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null); Spacer(Modifier.width(6.dp)); Text("Import list or CSV")
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Game.entries.forEach { g ->
                FilterChip(selected = game == g, onClick = { vm.selectGame(g); if (query.isNotBlank()) vm.search(query) }, label = { Text(g.label) })
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
            placeholder = {
                Text(
                    when (game) {
                        Game.YUGIOH -> "Name or set code (LOB-EN001)"
                        Game.ONE_PIECE -> "Name or number (OP01-001)"
                        Game.RIFTBOUND -> "Name or number (179/298)"
                        else -> "Card name"
                    }
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        val catalog by vm.catalog.collectAsState()
        val progress by vm.catalogProgress.collectAsState()
        catalog?.let { (have, all) ->
            if (have < all || progress != null) Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        if (game == Game.ONE_PIECE) {
                            "One Piece search works online. $have of $all sets are on your phone — download them all to search and scan offline too."
                        } else {
                            "${game.label} cards are searched on your phone — $have of $all sets downloaded. " +
                                "Scanning works without this: each set downloads the first time you scan a card from it."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    val p = progress
                    if (p != null) {
                        LinearProgressIndicator(
                            progress = { if (p.second > 0) p.first.toFloat() / p.second else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Downloading ${p.first} / ${p.second} sets…", style = MaterialTheme.typography.labelSmall)
                    } else {
                        Button(onClick = { vm.downloadCatalog(query) }) { Text("Download all ${game.label} sets") }
                    }
                }
            }
        }
        val list = results
        when {
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            list == null -> EmptyState(
                Icons.Outlined.Search, "Find any card",
                "Search ${game.label} by name. Results come from free public card databases, priced in USD (TCGplayer market).",
            )
            list.isEmpty() && !loading -> Text("No cards found.", modifier = Modifier.padding(16.dp))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("${list.size} results", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(list, key = { it.id }) { c ->
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onOpenCard(c.id) }) {
                        CardImage(c.imageSmall, Modifier.fillMaxWidth())
                        Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                        Text("${c.displayNumber} · ${c.setName}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (c.pricedAt > 0) Text(money(c.price(null)), style = MaterialTheme.typography.labelMedium.merge(Numeric), color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
    val importSheet = app.snapstack.ui.importer.rememberImportSheet()
    LaunchedEffect(showImport) { if (showImport) { importSheet.open(game); showImport = false } }
}
