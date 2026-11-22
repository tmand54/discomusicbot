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

data class ImportLine(val raw: String, val qty: Int, val card: Card? = null, val done: Boolean = false)

class SearchViewModel : ViewModel() {
    private val graph = AppGraph.instance
    val game = MutableStateFlow(Game.fromId(graph.settings.scanGame.value) ?: Game.POKEMON)
    val results = MutableStateFlow<List<Card>?>(null)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val importLines = MutableStateFlow<List<ImportLine>>(emptyList())
    val importing = MutableStateFlow(false)
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

    /** Resolves a pasted list ("4 Lightning Bolt", "2x 123/193", "LOB-EN001") line by line, in parallel. */
    fun runImport(text: String) {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(500)
        val qtyPattern = Regex("^(\\d{1,3})\\s*[xX×]?\\s+(.+)$")
        val parsed = lines.map { line ->
            qtyPattern.find(line)?.let { m -> ImportLine(m.groupValues[2], m.groupValues[1].toInt().coerceAtLeast(1)) } ?: ImportLine(line, 1)
        }
        importLines.value = parsed
        viewModelScope.launch {
            importing.value = true
            val gate = Semaphore(4)
            parsed.mapIndexed { i, line ->
                async {
                    gate.withPermit {
                        val key = CardTextParser.parseTyped(line.raw, game.value)
                        val card = key?.let { runCatching { graph.cards.resolve(it).card }.getOrNull() }
                        importLines.value = importLines.value.toMutableList().also { it[i] = line.copy(card = card, done = true) }
                    }
                }
            }.awaitAll()
            importing.value = false
        }
    }

    fun saveImport(onDone: (Int) -> Unit) {
        val items = importLines.value.mapNotNull { l ->
            l.card?.let { CollectionItem(cardId = it.id, variant = it.defaultVariant, qty = l.qty, priceAtAdd = it.price(null)) }
        }
        viewModelScope.launch {
            graph.collection.add(items)
            importLines.value = emptyList()
            onDone(items.sumOf { it.qty })
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
                Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null); Spacer(Modifier.width(6.dp)); Text("Import list")
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
                        "${game.label} cards are searched on your phone — $have of $all sets downloaded. " +
                            "Scanning works without this: each set downloads the first time you scan a card from it.",
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
    // The picker lives here (not in the sheet) so it survives the sheet closing.
    val importFile = app.snapstack.ui.importer.rememberImportPicker()
    if (showImport) ImportSheet(vm, onDismiss = { showImport = false }, onImportFile = { showImport = false; importFile() })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSheet(vm: SearchViewModel, onDismiss: () -> Unit, onImportFile: () -> Unit) {
    val game by vm.game.collectAsState()
    val lines by vm.importLines.collectAsState()
    val importing by vm.importing.collectAsState()
    var text by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<Int?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Import a list · ${game.label}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onImportFile) { Text("From a file") }
            }
            Text(
                "Have a Ripfolio backup or a CSV? Use \"From a file\" — it brings in every game at once.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "One card per line. Quantities optional: \"4 Lightning Bolt\", \"2x 123/193\", \"LOB-EN001\", \"MKM 123\".",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (lines.isEmpty()) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, minLines = 6, maxLines = 12,
                    modifier = Modifier.fillMaxWidth(), placeholder = { Text("Paste your list here") },
                )
                Spacer(Modifier.height(12.dp))
                saved?.let { Text("Added $it cards to your collection.", color = MaterialTheme.colorScheme.secondary) }
                Button(onClick = { vm.runImport(text) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Look up cards") }
            } else {
                val done = lines.count { it.done }
                val found = lines.count { it.card != null }
                if (importing) LinearProgressIndicator(progress = { done.toFloat() / lines.size }, modifier = Modifier.fillMaxWidth())
                Text("$found of ${lines.size} matched", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(lines) { l ->
                        ListItem(
                            leadingContent = {
                                when {
                                    !l.done -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    l.card != null -> Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary)
                                    else -> Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                                }
                            },
                            headlineContent = { Text("${l.qty}× ${l.card?.title ?: l.raw}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(l.card?.let { "${it.setName} ${it.displayNumber} · ${money(it.price(null))}" } ?: if (l.done) "Not found" else "…", maxLines = 1) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { vm.importLines.value = emptyList() }, modifier = Modifier.weight(1f)) { Text("Back") }
                    Button(
                        onClick = { vm.saveImport { saved = it; text = "" } },
                        enabled = !importing && found > 0, modifier = Modifier.weight(1.4f),
                    ) { Text("Add $found to collection") }
                }
            }
        }
    }
}
