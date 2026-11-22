package app.tcgportfolio.ui.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tcgportfolio.AppGraph
import app.tcgportfolio.model.Card
import app.tcgportfolio.model.CardSet
import app.tcgportfolio.model.Game
import app.tcgportfolio.model.OwnedCard
import app.tcgportfolio.ui.components.CardImage
import app.tcgportfolio.ui.components.EmptyState
import app.tcgportfolio.ui.components.GameFilterRow
import app.tcgportfolio.ui.components.money
import app.tcgportfolio.ui.theme.Numeric
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One tile per card: all copies of it added together. */
data class Stack(val card: Card, val qty: Int, val value: Double, val unit: Double, val lastAdded: Long)

data class SetProgress(val game: Game, val code: String, val name: String, val owned: Int, val total: Int?)

enum class SortBy(val label: String) { RECENT("Recently added"), VALUE("Total value"), PRICE("Card price"), NAME("Name"), SET("Set & number") }

class CollectionViewModel : ViewModel() {
    private val graph = AppGraph.instance
    val stacks = combine(graph.collection.owned, graph.settings.conditionAdjusted.flow) { owned, adjust -> toStacks(owned, adjust) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val wantlist = graph.collection.wantlist.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sets = combine(graph.collection.owned, Game.entries.map { graph.collection.setsFor(it) }.let { flows ->
        combine(flows) { arr -> arr.toList().flatten() }
    }) { owned, sets ->
        val setIndex = sets.associateBy { it.game to it.code }
        owned.groupBy { it.card.game to it.card.setCode }.map { (k, copies) ->
            val s: CardSet? = setIndex[k]
            SetProgress(
                k.first, k.second, s?.name ?: copies.first().card.setName,
                copies.map { it.card.id }.toSet().size,
                s?.printedTotal ?: s?.cachedCount?.takeIf { it > 0 } ?: s?.total,
            )
        }.sortedWith(compareBy<SetProgress> { it.game.ordinal }.thenByDescending { it.owned })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun toStacks(owned: List<OwnedCard>, adjust: Boolean): List<Stack> =
        owned.groupBy { it.card.id }.map { (_, copies) ->
            val card = copies.first().card
            val qty = copies.sumOf { it.item.qty }
            val value = copies.sumOf { it.totalValue(adjust) }
            Stack(card, qty, value, copies.maxOf { it.unitValue(adjust) }, copies.maxOf { it.item.addedAt })
        }
}

@Composable
fun CollectionScreen(onOpenCard: (String) -> Unit, onOpenSet: (Game, String) -> Unit, onScan: () -> Unit) {
    val vm: CollectionViewModel = viewModel()
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Collection", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("Cards", "Sets", "Wantlist").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> CardsTab(vm, onOpenCard, onScan)
            1 -> SetsTab(vm, onOpenSet)
            else -> WantTab(vm, onOpenCard)
        }
    }
}

@Composable
private fun CardsTab(vm: CollectionViewModel, onOpenCard: (String) -> Unit, onScan: () -> Unit) {
    val stacks by vm.stacks.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var game by remember { mutableStateOf<Game?>(null) }
    var sort by rememberSaveable { mutableStateOf(SortBy.RECENT) }
    var sortMenu by remember { mutableStateOf(false) }

    val all = stacks
    if (all == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (all.isEmpty()) {
        EmptyState(
            Icons.Outlined.CollectionsBookmark, "No cards yet",
            "Scan a stack, a binder page or a fresh pack — everything you save shows up here with live prices.",
        ) { Button(onClick = onScan) { Icon(Icons.Filled.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("Scan cards") } }
        return
    }
    val q = query.trim().lowercase()
    val shown = all.filter { s ->
        (game == null || s.card.game == game) &&
            (q.isEmpty() || s.card.name.lowercase().contains(q) || s.card.setName.lowercase().contains(q) || s.card.number.lowercase() == q)
    }.let { list ->
        when (sort) {
            SortBy.RECENT -> list.sortedByDescending { it.lastAdded }
            SortBy.VALUE -> list.sortedByDescending { it.value }
            SortBy.PRICE -> list.sortedByDescending { it.unit }
            SortBy.NAME -> list.sortedBy { it.card.name.lowercase() }
            SortBy.SET -> list.sortedWith(compareBy<Stack> { it.card.setName }.thenBy { it.card.number.filter(Char::isDigit).toIntOrNull() ?: 0 })
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search name, set or #") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortBy.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.label + if (s == sort) "  ✓" else "") }, onClick = { sort = s; sortMenu = false })
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            GameFilterRow(game, { game = it }, contentPadding = PaddingValues(0.dp))
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "${shown.sumOf { it.qty }} cards · ${money(shown.sumOf { it.value })}",
                style = MaterialTheme.typography.labelLarge.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(shown, key = { it.card.id }) { s -> StackTile(s) { onOpenCard(s.card.id) } }
    }
}

@Composable
private fun StackTile(s: Stack, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        Box {
            CardImage(s.card.imageSmall, Modifier.fillMaxWidth())
            if (s.qty > 1) Text(
                "×${s.qty}",
                Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 5.dp, vertical = 1.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(s.card.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        Text(
            "${s.card.displayNumber} · ${s.card.setName}", maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(money(s.value), style = MaterialTheme.typography.labelMedium.merge(Numeric), color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun SetsTab(vm: CollectionViewModel, onOpenSet: (Game, String) -> Unit) {
    val sets by vm.sets.collectAsState()
    if (sets.isEmpty()) {
        EmptyState(Icons.Outlined.CollectionsBookmark, "No sets yet", "Set progress appears once you own cards.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(sets, key = { it.game.id + it.code }) { s ->
            ListItem(
                modifier = Modifier.clickable { onOpenSet(s.game, s.code) },
                overlineContent = { Text(s.game.label) },
                headlineContent = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column {
                        Text(if (s.total != null) "${s.owned} / ${s.total} cards" else "${s.owned} cards")
                        if (s.total != null && s.total > 0) LinearProgressIndicator(
                            progress = { (s.owned.toFloat() / s.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
    }
}

@Composable
private fun WantTab(vm: CollectionViewModel, onOpenCard: (String) -> Unit) {
    val want by vm.wantlist.collectAsState()
    if (want.isEmpty()) {
        EmptyState(Icons.Outlined.FavoriteBorder, "Wantlist is empty", "Tap the heart on any card to track its price here.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("${want.size} wanted · ${money(want.sumOf { it.price(null) ?: 0.0 })} to complete", style = MaterialTheme.typography.labelLarge.merge(Numeric))
        }
        items(want, key = { it.id }) { c ->
            Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onOpenCard(c.id) }) {
                CardImage(c.imageSmall, Modifier.fillMaxWidth())
                Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text(money(c.price(null)), style = MaterialTheme.typography.labelMedium.merge(Numeric), color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

// ---------------------------------------------------------------------- set detail

class SetDetailViewModel(val game: Game, val code: String) : ViewModel() {
    private val graph = AppGraph.instance
    val cards = graph.collection.cardsInSet(game, code).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val ownedIds = graph.collection.owned.map { list -> list.filter { it.card.setCode == code }.groupBy { it.card.id }.mapValues { e -> e.value.sumOf { it.item.qty } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val set = graph.collection.setsFor(game).map { list -> list.firstOrNull { it.code.equals(code, true) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val downloading = kotlinx.coroutines.flow.MutableStateFlow(false)
    val error = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun download() {
        viewModelScope.launch {
            downloading.value = true; error.value = null
            runCatching {
                graph.cards.ensureSetList(game)
                val s = set.value ?: CardSet(game, code, code, null, null)
                graph.cards.ensureSetCached(s)
            }.onFailure { error.value = it.message ?: "Download failed" }
            downloading.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetDetailScreen(game: Game, code: String, onBack: () -> Unit, onOpenCard: (String) -> Unit) {
    val vm: SetDetailViewModel = viewModel(key = "${game.id}|$code") { SetDetailViewModel(game, code) }
    val cards by vm.cards.collectAsState()
    val owned by vm.ownedIds.collectAsState()
    val set by vm.set.collectAsState()
    val downloading by vm.downloading.collectAsState()
    val error by vm.error.collectAsState()
    val list = cards.orEmpty()
    val complete = set?.cachedAt?.let { it > 0 } == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(set?.name ?: list.firstOrNull()?.setName ?: code, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    val total = set?.printedTotal ?: list.size.takeIf { complete }
                    Text(
                        "You own ${owned.size}" + (total?.let { " of $it" } ?: "") + " cards in this set",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!complete) {
                        Spacer(Modifier.size(8.dp))
                        Button(onClick = vm::download, enabled = !downloading) {
                            if (downloading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.CloudDownload, null)
                            Spacer(Modifier.width(8.dp)); Text("Download full set (works offline)")
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            items(list, key = { it.id }) { c ->
                val have = owned[c.id] ?: 0
                Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onOpenCard(c.id) }.alpha(if (have > 0) 1f else 0.38f)) {
                    CardImage(c.imageSmall, Modifier.fillMaxWidth())
                    Text("${c.displayNumber} ${c.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    if (have > 0) Text("Owned ×$have", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}
