package app.tcgportfolio.ui.home

import kotlin.math.abs
import app.tcgportfolio.ui.theme.LocalMarketColors
import app.tcgportfolio.data.ValuePoint
import app.tcgportfolio.data.PortfolioMath
import app.tcgportfolio.data.ChartRange
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tcgportfolio.AppGraph
import app.tcgportfolio.data.PortfolioSummary
import app.tcgportfolio.model.Card
import app.tcgportfolio.model.OwnedCard
import app.tcgportfolio.model.PortfolioPoint
import app.tcgportfolio.model.PriceAlert
import app.tcgportfolio.model.RipSession
import app.tcgportfolio.ui.components.CardImage
import app.tcgportfolio.ui.components.EmptyState
import app.tcgportfolio.ui.components.LineChart
import app.tcgportfolio.ui.components.SectionHeader
import app.tcgportfolio.ui.components.StatTile
import app.tcgportfolio.ui.components.gainColor
import app.tcgportfolio.ui.components.money
import app.tcgportfolio.ui.components.pct
import app.tcgportfolio.ui.components.signedMoney
import app.tcgportfolio.ui.theme.AccentBrush
import app.tcgportfolio.ui.theme.Numeric
import app.tcgportfolio.work.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

data class Mover(val owned: OwnedCard, val change: Double)

data class PortfolioState(
    val loaded: Boolean = false,
    val summary: PortfolioSummary? = null,
    val history: List<PortfolioPoint> = emptyList(),
    val ticks: List<ValuePoint> = emptyList(),
    val top: List<OwnedCard> = emptyList(),
    val movers: List<Mover> = emptyList(),
    val rips: List<RipSession> = emptyList(),
    val alerts: List<Pair<PriceAlert, Card?>> = emptyList(),
)

class PortfolioViewModel : ViewModel() {
    private val graph = AppGraph.instance
    val refreshing = MutableStateFlow<Pair<Int, Int>?>(null)
    val lastRefresh = MutableStateFlow(graph.settings.lastPriceRefresh)

    private val series = combine(graph.collection.portfolioHistory, graph.collection.portfolioTicks) { d, t -> d to t }

    val state = combine(
        graph.collection.owned, series, graph.collection.rips, graph.collection.alerts,
        graph.settings.conditionAdjusted.flow,
    ) { owned, (history, ticks), rips, alerts, adjust ->
        val byCard = owned.groupBy { it.card.id }.values.map { copies -> copies.maxBy { it.unitValue(adjust) } }
        PortfolioState(
            loaded = true,
            summary = graph.collection.summarize(owned),
            history = history,
            ticks = ticks,
            top = byCard.sortedByDescending { it.unitValue(adjust) }.take(12),
            movers = owned.mapNotNull { o ->
                val then = o.item.priceAtAdd ?: return@mapNotNull null
                val now = o.card.price(o.item.variant) ?: return@mapNotNull null
                Mover(o, (now - then) * o.item.qty)
            }.filter { kotlin.math.abs(it.change) >= 0.01 }.sortedByDescending { kotlin.math.abs(it.change) }.take(6),
            rips = rips,
            alerts = alerts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PortfolioState())

    fun refresh(context: android.content.Context) {
        if (refreshing.value != null) return
        viewModelScope.launch {
            refreshing.value = 0 to 0
            val fired = runCatching { graph.collection.refreshAll { done, total -> refreshing.value = done to total } }.getOrDefault(emptyList())
            Notifications.showAlerts(context.applicationContext, fired)
            lastRefresh.value = graph.settings.lastPriceRefresh
            refreshing.value = null
        }
    }

    fun deleteRip(id: Long) = viewModelScope.launch { graph.collection.deleteRip(id) }
    fun deleteAlert(id: Long) = viewModelScope.launch { graph.collection.deleteAlert(id) }
}


@Composable
fun PortfolioScreen(onOpenCard: (String) -> Unit, onScan: () -> Unit) {
    val vm: PortfolioViewModel = viewModel()
    val state by vm.state.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val lastRefresh by vm.lastRefresh.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var range by rememberSaveable { mutableStateOf(ChartRange.M1) }
    val importSheet = app.tcgportfolio.ui.importer.rememberImportSheet()

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(app.tcgportfolio.R.drawable.logo_mark), contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("TCG Portfolio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        refreshing?.let { (d, t) -> if (t > 0) "Updating prices… $d/$t" else "Updating prices…" }
                            ?: if (lastRefresh > 0) "Prices updated ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastRefresh))}"
                            else "Pull fresh prices any time",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.refresh(context) }, enabled = refreshing == null) {
                    if (refreshing != null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Refresh, "Refresh prices")
                }
            }
        }

        val s = state.summary
        if (state.loaded && (s == null || s.copies == 0)) {
            item {
                EmptyState(
                    Icons.Outlined.Inventory2, "Your portfolio starts with one scan",
                    "Point the camera at a card — TCG Portfolio reads the collector number on-device and prices it in a blink. " +
                        "Pokémon, Magic, Yu-Gi-Oh!, Lorcana, One Piece, Riftbound and Dragon Ball.",
                    Modifier.padding(top = 48.dp),
                ) {
                    Button(onClick = onScan) { Icon(Icons.Filled.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("Start scanning") }
                    androidx.compose.material3.TextButton(onClick = { importSheet.open() }) { Text("Import from list or CSV") }
                }
            }
            return@LazyColumn
        }
        if (s == null) return@LazyColumn

        item {
            Card(
                Modifier.fillMaxWidth().padding(16.dp).border(1.5.dp, AccentBrush, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                PerformanceCard(s, state, range) { range = it }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Cards", "${s.copies}", sub = "${s.distinct} unique")
                s.byGame.entries.sortedByDescending { it.value }.forEach { (g, v) -> StatTile(g.label, money(v)) }
            }
        }
        if (state.top.isNotEmpty()) {
            item { SectionHeader("Most valuable") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.top, key = { it.card.id }) { o ->
                        Column(Modifier.width(110.dp).clickable { onOpenCard(o.card.id) }) {
                            CardImage(o.card.imageSmall, Modifier.fillMaxWidth())
                            Text(o.card.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                            Text(money(o.unitValue(false)), style = MaterialTheme.typography.labelMedium.merge(Numeric), color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }
        if (state.movers.isNotEmpty()) {
            item { SectionHeader("Since you added them") }
            items(state.movers, key = { "m" + it.owned.item.id }) { m ->
                ListItem(
                    modifier = Modifier.clickable { onOpenCard(m.owned.card.id) },
                    leadingContent = { CardImage(m.owned.card.imageSmall, Modifier.width(36.dp), corner = 4.dp) },
                    headlineContent = { Text(m.owned.card.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${m.owned.item.qty}× · ${m.owned.card.setName}", maxLines = 1) },
                    trailingContent = { Text(signedMoney(m.change), color = gainColor(m.change), style = MaterialTheme.typography.titleSmall.merge(Numeric)) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        }
        if (state.rips.isNotEmpty()) {
            item { SectionHeader("Rips") }
            items(state.rips.take(10), key = { "r" + it.id }) { r ->
                val pnl = r.packCost?.let { r.value - it }
                ListItem(
                    headlineContent = { Text(r.label) },
                    supportingContent = {
                        Text("${r.cardCount} cards · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(r.startedAt))}" + (r.game?.let { " · ${it.label}" } ?: ""))
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(money(r.value), style = MaterialTheme.typography.titleSmall.merge(Numeric))
                                if (pnl != null) Text(signedMoney(pnl), color = gainColor(pnl), style = MaterialTheme.typography.labelMedium.merge(Numeric))
                            }
                            IconButton(onClick = { vm.deleteRip(r.id) }) { Icon(Icons.Outlined.Delete, "Delete rip record") }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        }
        if (state.alerts.isNotEmpty()) {
            item { SectionHeader("Price alerts") }
            items(state.alerts, key = { "a" + it.first.id }) { (a, card) ->
                ListItem(
                    modifier = Modifier.clickable { card?.let { onOpenCard(it.id) } },
                    headlineContent = { Text(card?.title ?: a.cardId, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${if (a.above) "Above" else "Below"} ${money(a.target)} · now ${money(card?.price(a.variant))}") },
                    trailingContent = { IconButton(onClick = { vm.deleteAlert(a.id) }) { Icon(Icons.Outlined.Delete, "Delete alert") } },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        }
    }
}

/**
 * The collection's value, its change over the chosen interval (gains only: cards added or removed
 * don't count), the chart, and ROI / invested / profit for the whole collection.
 */
@Composable
private fun PerformanceCard(s: PortfolioSummary, state: PortfolioState, range: ChartRange, onRange: (ChartRange) -> Unit) {
    val now = remember(state.history, state.ticks, s) { System.currentTimeMillis() }
    val series = remember(state.history, state.ticks, now) { PortfolioMath.merge(state.history, state.ticks, now) }
    val live = ValuePoint(now, s.value, s.basis)
    val stats = remember(series, range, live) { PortfolioMath.stats(series, range, now, live) }
    var scrub by remember(range) { mutableStateOf<ValuePoint?>(null) }
    val market = LocalMarketColors.current

    Column(Modifier.padding(18.dp)) {
        Text("Collection value", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(money(scrub?.value ?: s.value), style = MaterialTheme.typography.displaySmall)
        val sc = scrub
        if (sc != null && stats != null) {
            // While scrubbing: the change from the start of the interval to that point.
            val upTo = PortfolioMath.stats(stats.points.filter { it.t <= sc.t }, ChartRange.ALL, sc.t, null)
            Text(
                listOfNotNull(upTo?.let { changeText(it.gain, it.returnPct) }, pointTime(sc.t, range)).joinToString("  ·  "),
                style = MaterialTheme.typography.titleSmall.merge(Numeric), color = gainColor(upTo?.gain ?: 0.0),
            )
        } else if (stats != null) {
            Text(
                changeText(stats.gain, stats.returnPct) + "  " + if (stats.partial) "since you started" else range.caption,
                style = MaterialTheme.typography.titleSmall.merge(Numeric), color = gainColor(stats.gain),
            )
        }

        Spacer(Modifier.height(12.dp))
        if (stats != null && stats.points.size >= 2) {
            val up = stats.gain >= 0
            ValueChart(
                stats.points, stats.start, now,
                lineColor = if (abs(stats.gain) < 0.005) MaterialTheme.colorScheme.primary else if (up) market.gain else market.loss,
                basisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().height(150.dp),
                showBasis = s.basis > 0.005,
                onScrub = { scrub = it },
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(pointTime(stats.start, range), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (s.basis > 0.005) Text("- - Invested", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("Now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(
                "The chart builds up as you add cards and prices refresh.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
        RangeSelector(range, onRange, Modifier.padding(top = 10.dp))

        if (stats != null && abs(stats.flows) >= 0.01) Text(
            (if (stats.flows > 0) "Cards added" else "Cards removed") + " in this period: ${money(abs(stats.flows))} (not counted as gain or loss)",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth()) {
            Metric("ROI", s.roi?.let { pct(it) } ?: "—", s.roi?.let { gainColor(it) }, Modifier.weight(1f))
            Metric("Invested", money(s.basis), null, Modifier.weight(1f))
            Metric("Profit", signedMoney(s.profit), gainColor(s.profit), Modifier.weight(1f))
        }
        if (s.estimatedCopies > 0) Text(
            if (s.estimatedCopies == s.copies) "No purchase prices entered: ROI uses each card's price when you added it."
            else "${s.estimatedCopies} of ${s.copies} cards have no purchase price; their price when added is used.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value, style = MaterialTheme.typography.titleMedium.merge(Numeric),
            color = color ?: MaterialTheme.colorScheme.onSurface, maxLines = 1,
        )
    }
}

/** 1D · 5D · 1M · 6M · 1Y · All, sharing the width evenly. */
@Composable
private fun RangeSelector(selected: ChartRange, onSelect: (ChartRange) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ChartRange.entries.forEach { r ->
            val on = r == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(999.dp))
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onSelect(r) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    r.label, style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun changeText(gain: Double, returnPct: Double?): String =
    signedMoney(gain) + (returnPct?.let { " (${pct(it)})" } ?: "")

/** Time of day for the short intervals, the date for the long ones. */
private fun pointTime(t: Long, range: ChartRange): String {
    val date = Date(t)
    return when (range) {
        ChartRange.D1 -> DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
        ChartRange.D5 -> java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault()).format(date)
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
    }
}
