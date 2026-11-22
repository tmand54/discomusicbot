package app.tcgportfolio.ui.collection

import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import app.tcgportfolio.model.CollectionItem
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.BackHandler
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

data class DeletedCards(val cards: Int, val copies: Int, val items: List<CollectionItem>)

data class SetProgress(val game: Game, val code: String, val name: String, val owned: Int, val total: Int?)

enum class SortBy(val label: String) { RECENT("Recently added"), VALUE("Total value"), PRICE("Card price"), NAME("Name"), SET("Set & number") }

class CollectionViewModel : ViewModel() {
    private val graph = AppGraph.instance
    val stacks = combine(graph.collection.owned, graph.settings.conditionAdjusted.flow) { owned, adjust -> toStacks(owned, adjust) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val wantlist = graph.collection.wantlist.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Card ids selected with a long press on the Cards tab. */
    val selection = MutableStateFlow<Set<String>>(emptySet())
    /** The last bulk delete, kept until its Undo snackbar goes away. */
    val deleted = MutableStateFlow<DeletedCards?>(null)

    fun toggle(cardId: String) = selection.update { if (cardId in it) it - cardId else it + cardId }
    fun setSelection(cardIds: Set<String>) { selection.value = cardIds }
    fun clearSelection() { selection.value = emptySet() }

    fun deleteSelected() {
        val ids = selection.value
        if (ids.isEmpty()) return
        selection.value = emptySet()
        viewModelScope.launch {
            val items = graph.collection.deleteCards(ids)
            if (items.isNotEmpty()) deleted.value = DeletedCards(ids.size, items.sumOf { it.qty }, items)
        }
    }

    fun undoDelete() {
        val d = deleted.value ?: return
        deleted.value = null
        viewModelScope.launch { graph.collection.restoreCopies(d.items) }
    }

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
            listOf("Cards", "Sets", "Wantlist").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i; vm.clearSelection() }, text = { Text(t) })
            }
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
    val selectionRaw by vm.selection.collectAsState()
    val deleted by vm.deleted.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(deleted) {
        val d = deleted ?: return@LaunchedEffect
        val what = if (d.cards == 1) "1 card" else "${d.cards} cards"
        val result = snackbar.showSnackbar("Deleted $what from your collection", actionLabel = "Undo", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) vm.undoDelete() else vm.deleted.value = null
    }

    val all = stacks
    if (all == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (all.isEmpty()) {
        if (deleted != null) {
            // Everything was just deleted: keep the Undo snackbar up before showing the empty state.
            Box(Modifier.fillMaxSize()) { SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp)) }
            return
        }
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

    // Cards deleted or merged elsewhere drop out of the selection.
    val byId = remember(all) { all.associateBy { it.card.id } }
    val selection = selectionRaw.filterTo(LinkedHashSet()) { it in byId }
    val selecting = selection.isNotEmpty()
    BackHandler(enabled = selecting) { vm.clearSelection() }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
    if (selecting) {
        val picked = selection.mapNotNull { byId[it] }
        val allShown = shown.isNotEmpty() && shown.all { it.card.id in selection }
        SelectionBar(
            count = selection.size, value = picked.sumOf { it.value },
            allShown = allShown,
            onClose = vm::clearSelection,
            onToggleAll = {
                val ids = shown.map { it.card.id }.toSet()
                vm.setSelection(if (allShown) selection - ids else selection + ids)
            },
            onDelete = { confirmDelete = true },
        )
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
                "${shown.sumOf { it.qty }} cards · ${money(shown.sumOf { it.value })}" +
                    if (!selecting) " · hold a card to select" else "",
                style = MaterialTheme.typography.labelLarge.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(shown, key = { it.card.id }) { s ->
            val id = s.card.id
            StackTile(
                s, selecting = selecting, selected = id in selection,
                onClick = { if (selecting) vm.toggle(id) else onOpenCard(id) },
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); vm.toggle(id) },
            )
        }
    }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }

    if (confirmDelete) {
        val picked = selection.mapNotNull { byId[it] }
        val copies = picked.sumOf { it.qty }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Outlined.Delete, null) },
            title = { Text(if (picked.size == 1) "Delete this card?" else "Delete ${picked.size} cards?") },
            text = {
                Text(
                    (if (copies == 1) "1 copy" else "All $copies copies") + " (${money(picked.sumOf { it.value })}) will be removed " +
                        "from your collection, with their purchase prices, grading and notes. You can undo right after.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; vm.deleteSelected() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Replaces the search row while cards are selected: count, select all, and the delete button. */
@Composable
private fun SelectionBar(count: Int, value: Double, allShown: Boolean, onClose: () -> Unit, onToggleAll: () -> Unit, onDelete: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cancel selection") }
            Column(Modifier.weight(1f)) {
                Text("$count selected", style = MaterialTheme.typography.titleMedium)
                Text(money(value), style = MaterialTheme.typography.labelMedium.merge(Numeric))
            }
            TextButton(onClick = onToggleAll) { Text(if (allShown) "Deselect all" else "Select all") }
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Delete")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StackTile(s: Stack, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select")
            .then(
                if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)).padding(5.dp)
                else Modifier
            ),
    ) {
        Box {
            CardImage(s.card.imageSmall, Modifier.fillMaxWidth().alpha(if (selecting && !selected) 0.6f else 1f))
            if (selecting) Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                if (selected) "Selected" else "Not selected",
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(26.dp)
                    .background(if (selected) MaterialTheme.colorScheme.onPrimary else Color.Black.copy(alpha = 0.4f), CircleShape),
            )
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
