package app.snapstack.ui.card

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.snapstack.AppGraph
import app.snapstack.model.Card
import app.snapstack.model.CollectionItem
import app.snapstack.model.PriceAlert
import app.snapstack.model.Variants
import app.snapstack.ui.components.CardImage
import app.snapstack.ui.components.GameBadge
import app.snapstack.ui.components.LineChart
import app.snapstack.ui.components.SectionHeader
import app.snapstack.ui.components.money
import app.snapstack.ui.theme.Numeric
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class CardDetailViewModel(val cardId: String) : ViewModel() {
    private val graph = AppGraph.instance
    val card = graph.collection.card(cardId).stateIn(viewModelScope, SharingStarted.Eagerly, graph.cards.getCached(cardId))
    val owned = graph.collection.ownedFor(cardId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val wanted = graph.collection.isWanted(cardId).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val alerts = graph.collection.alertsFor(cardId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val variant = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val history = variant.flatMapLatest { v -> graph.collection.priceHistory(cardId, v ?: "normal") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val refreshing = MutableStateFlow(false)

    init {
        refresh(force = false)
    }

    fun refresh(force: Boolean) {
        val c = card.value ?: graph.cards.getCached(cardId) ?: return
        viewModelScope.launch {
            refreshing.value = true
            runCatching { graph.cards.ensurePriced(c, if (force) 0 else 6 * 3600_000L) }
            refreshing.value = false
        }
    }

    fun toggleWanted() = viewModelScope.launch { graph.collection.setWanted(cardId, !wanted.value) }
    fun save(item: CollectionItem) = viewModelScope.launch {
        if (item.id == 0L) graph.collection.add(listOf(item)) else graph.collection.update(item)
    }
    fun delete(id: Long) = viewModelScope.launch { graph.collection.delete(id) }
    fun saveAlert(alert: PriceAlert) = viewModelScope.launch { graph.collection.saveAlert(alert) }
    fun deleteAlert(id: Long) = viewModelScope.launch { graph.collection.deleteAlert(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailScreen(cardId: String, onBack: () -> Unit) {
    val vm: CardDetailViewModel = viewModel(key = cardId) { CardDetailViewModel(cardId) }
    val card by vm.card.collectAsState()
    val owned by vm.owned.collectAsState()
    val wanted by vm.wanted.collectAsState()
    val alerts by vm.alerts.collectAsState()
    val selVariant by vm.variant.collectAsState()
    val history by vm.history.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<CollectionItem?>(null) }
    var alertDialog by remember { mutableStateOf(false) }

    LaunchedEffect(card?.defaultVariant) { if (vm.variant.value == null) vm.variant.value = card?.defaultVariant }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(card?.name ?: "Card", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = vm::toggleWanted) {
                        Icon(if (wanted) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Wantlist")
                    }
                    card?.let { c ->
                        IconButton(onClick = {
                            val text = "${c.title} — ${c.setName} ${c.displayNumber}: ${money(c.price(null))}"
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                        }) { Icon(Icons.Outlined.Share, "Share") }
                    }
                },
            )
        },
    ) { padding ->
        val c = card
        if (c == null) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Row(Modifier.padding(16.dp)) {
                    CardImage(c.imageLarge ?: c.imageSmall, Modifier.width(150.dp), corner = 10.dp, contentDescription = c.name, fallbackUrl = c.imageSmall)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        GameBadge(c.game)
                        Spacer(Modifier.height(6.dp))
                        Text(c.title, style = MaterialTheme.typography.titleLarge)
                        Text(c.setName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(listOfNotNull(c.displayNumber, c.rarity).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Text(money(c.price(selVariant)), style = MaterialTheme.typography.headlineMedium.merge(Numeric))
                        Text(
                            if (c.pricedAt > 0) "Market price · updated ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(c.pricedAt))}"
                            else "Loading price…",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { vm.refresh(force = true) }, enabled = !refreshing) {
                                if (refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Outlined.Refresh, "Refresh price")
                            }
                            IconButton(onClick = { alertDialog = true }) { Icon(Icons.Outlined.NotificationsActive, "Price alert") }
                        }
                    }
                }
            }
            if (c.allVariants.isNotEmpty()) item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    c.allVariants.forEach { v ->
                        FilterChip(
                            selected = selVariant == v, onClick = { vm.variant.value = v },
                            label = { Text("${Variants.label(v)}  ${money(c.prices[v])}") },
                        )
                    }
                }
            }
            item {
                SectionHeader("Price history")
                if (history.size >= 2) {
                    LineChart(history.map { it.price }, Modifier.fillMaxWidth().height(140.dp).padding(horizontal = 16.dp))
                    val first = history.first().price; val last = history.last().price
                    Text(
                        "${history.size} days tracked · ${money(first)} → ${money(last)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                } else {
                    Text(
                        "TCG Portfolio records this card's price each day it's in your collection, wantlist or alerts.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            item {
                SectionHeader("Your copies (${owned.sumOf { it.qty }})") {
                    Button(onClick = { editing = CollectionItem(cardId = c.id, variant = selVariant ?: c.defaultVariant, priceAtAdd = c.price(selVariant)) }) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Add")
                    }
                }
            }
            items(owned, key = { it.id }) { item -> OwnedRow(item, c, onClick = { editing = item }) }
            if (alerts.isNotEmpty()) {
                item { SectionHeader("Price alerts") }
                items(alerts, key = { it.id }) { a ->
                    ListItem(
                        headlineContent = { Text("${if (a.above) "Above" else "Below"} ${money(a.target)}") },
                        supportingContent = { Text(Variants.label(a.variant ?: c.defaultVariant) + if (!a.active) " · paused" else "") },
                        trailingContent = { IconButton(onClick = { vm.deleteAlert(a.id) }) { Icon(Icons.Outlined.Delete, "Delete alert") } },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                }
            }
            item {
                SectionHeader("Check the market")
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { open(context, c.url ?: tcgplayerSearch(c)) }) {
                        Icon(Icons.Outlined.ShoppingCart, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("TCGplayer")
                    }
                    OutlinedButton(onClick = { open(context, ebaySold(c)) }) { Text("eBay sold") }
                }
            }
        }
    }

    editing?.let { item ->
        card?.let { c ->
            ItemEditorSheet(
                card = c, initial = item,
                onDismiss = { editing = null },
                onSave = { vm.save(it); editing = null },
                onDelete = if (item.id != 0L) ({ vm.delete(item.id); editing = null }) else null,
            )
        }
    }
    if (alertDialog) card?.let { c ->
        AlertDialogEditor(c, selVariant ?: c.defaultVariant, onDismiss = { alertDialog = false }) { vm.saveAlert(it); alertDialog = false }
    }
}

@Composable
private fun OwnedRow(item: CollectionItem, card: Card, onClick: () -> Unit) {
    val unit = item.valueOverride ?: card.price(item.variant)
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text("${item.qty}× ${Variants.label(item.variant)} · ${item.condition.code}" + (item.grader?.let { " · $it ${item.grade.orEmpty()}" } ?: ""))
        },
        supportingContent = {
            val cost = item.costEach?.let { "Paid ${money(it)} each" } ?: "No purchase price"
            Text("$cost · added ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.addedAt))}")
        },
        trailingContent = { Text(money(unit?.let { it * item.qty }), style = MaterialTheme.typography.titleSmall.merge(Numeric), fontWeight = FontWeight.SemiBold) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

private fun open(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private fun query(c: Card) = Uri.encode(listOf(c.name, c.setName, c.number).joinToString(" "))

private fun tcgplayerSearch(c: Card): String {
    val game = when (c.game) {
        app.snapstack.model.Game.POKEMON -> "pokemon"
        app.snapstack.model.Game.MAGIC -> "magic"
        app.snapstack.model.Game.YUGIOH -> "yugioh"
        app.snapstack.model.Game.LORCANA -> "lorcana-tcg"
        app.snapstack.model.Game.ONE_PIECE -> "one-piece-card-game"
        app.snapstack.model.Game.RIFTBOUND -> "riftbound-league-of-legends-trading-card-game"
        app.snapstack.model.Game.DRAGON_BALL -> "dragon-ball-super-fusion-world"
    }
    return "https://www.tcgplayer.com/search/$game/product?q=${query(c)}"
}

private fun ebaySold(c: Card) = "https://www.ebay.com/sch/i.html?_nkw=${query(c)}&LH_Sold=1&LH_Complete=1"
