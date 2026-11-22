package app.snapstack.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.snapstack.AppGraph
import app.snapstack.BuildConfig
import app.snapstack.data.ScanSpeed
import app.snapstack.data.Settings
import app.snapstack.model.CardSet
import app.snapstack.model.Game
import app.snapstack.work.PriceRefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class SettingsViewModel : ViewModel() {
    val graph = AppGraph.instance
    val settings: Settings = graph.settings
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val cachedCounts = MutableStateFlow<Map<Game, Int>>(emptyMap())
    val offlineGame = MutableStateFlow(Game.POKEMON)
    val offlineSets = MutableStateFlow<List<CardSet>?>(null)
    val downloading = MutableStateFlow<Set<String>>(emptySet())

    init { loadCounts() }

    fun loadCounts() = viewModelScope.launch {
        cachedCounts.value = withContext(Dispatchers.IO) { Game.entries.associateWith { graph.db.cachedCardCount(it) } }
    }

    fun loadSets(game: Game) {
        offlineGame.value = game
        offlineSets.value = null
        viewModelScope.launch {
            runCatching { graph.cards.ensureSetList(game) }
            offlineSets.value = withContext(Dispatchers.IO) { graph.db.getSets(game) }
        }
    }

    fun download(set: CardSet) {
        downloading.value = downloading.value + set.code
        viewModelScope.launch {
            val n = runCatching { graph.cards.ensureSetCached(set) }.getOrNull()
            downloading.value = downloading.value - set.code
            message.value = if (n != null) "Saved $n cards from ${set.name} for offline scanning" else "Couldn't download ${set.name}"
            offlineSets.value = withContext(Dispatchers.IO) { graph.db.getSets(set.game) }
            loadCounts()
        }
    }

    fun clearCatalog() = viewModelScope.launch {
        withContext(Dispatchers.IO) { graph.db.clearCatalog() }
        graph.cards.clearMemory()
        message.value = "Offline card data cleared (your collection is untouched)"
        loadCounts()
    }

    fun backup(uri: android.net.Uri, context: android.content.Context) = viewModelScope.launch {
        busy.value = true
        message.value = runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { graph.collection.backup(it) }
            "Backup saved"
        }.getOrElse { "Backup failed: ${it.message}" }
        busy.value = false
    }

    fun restore(uri: android.net.Uri, context: android.content.Context) = viewModelScope.launch {
        busy.value = true
        message.value = runCatching {
            val n = context.contentResolver.openInputStream(uri)!!.use { graph.collection.restore(it) }
            "Restored $n collection entries"
        }.getOrElse { "Restore failed: ${it.message}" }
        busy.value = false
    }

    fun exportCsv(uri: android.net.Uri, context: android.content.Context) = viewModelScope.launch {
        busy.value = true
        message.value = runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { graph.collection.exportCsv(it) }
            "CSV exported"
        }.getOrElse { "Export failed: ${it.message}" }
        busy.value = false
    }

    fun reschedule(context: android.content.Context) = PriceRefreshWorker.schedule(context, settings)
}

@Composable
fun SettingsScreen() {
    val vm: SettingsViewModel = viewModel()
    val s = vm.settings
    val context = LocalContext.current
    val message by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()
    val counts by vm.cachedCounts.collectAsState()
    var offline by remember { mutableStateOf(false) }

    val speed by s.scanSpeed.flow.collectAsState()
    val beep by s.beep.flow.collectAsState()
    val haptics by s.haptics.flow.collectAsState()
    val stats by s.showOcrStats.flow.collectAsState()
    val prefetch by s.autoPrefetch.flow.collectAsState()
    val adjust by s.conditionAdjusted.flow.collectAsState()
    val bg by s.backgroundRefresh.flow.collectAsState()
    val wifi by s.wifiOnly.flow.collectAsState()

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.backup(it, context) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.restore(it, context) }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { vm.exportCsv(it, context) }
    }
    var notifGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (busy) CircularProgressIndicator(Modifier.padding(16.dp))

        Group("Scanning")
        ScanSpeed.entries.forEach { sp ->
            ListItem(
                modifier = Modifier.clickable { s.scanSpeed.value = sp.name },
                leadingContent = { RadioButton(selected = speed == sp.name, onClick = { s.scanSpeed.value = sp.name }) },
                headlineContent = { Text(sp.label) },
                supportingContent = { Text(sp.description) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
        Toggle("Beep on scan", null, beep) { s.beep.value = it }
        Toggle("Vibrate on scan", null, haptics) { s.haptics.value = it }
        Toggle("Show OCR speed", "Milliseconds per frame and frames per second on the scanner", stats) { s.showOcrStats.value = it }
        Toggle(
            "Auto-download sets",
            "After the first card of a set, fetch the whole set so the next cards resolve instantly and offline",
            prefetch,
        ) { s.autoPrefetch.value = it }

        Group("Offline card data")
        ListItem(
            modifier = Modifier.clickable { offline = true; vm.loadSets(Game.POKEMON) },
            headlineContent = { Text("Download sets for offline scanning") },
            supportingContent = { Text(Game.entries.joinToString(" · ") { "${it.short} ${counts[it] ?: 0}" } + " cards on device") },
            trailingContent = { Icon(Icons.Outlined.CloudDownload, null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )
        ListItem(
            modifier = Modifier.clickable { vm.clearCatalog() },
            headlineContent = { Text("Clear offline card data") },
            supportingContent = { Text("Frees space. Cards in your collection, wantlist and alerts are kept.") },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )

        Group("Prices")
        Toggle("Condition-adjusted values", "Value played cards below Near Mint (LP 85%, MP 70%, HP 50%, DMG 35%)", adjust) { s.conditionAdjusted.value = it }
        Toggle("Daily background refresh", "Updates prices, value history and price alerts once a day", bg) {
            s.backgroundRefresh.value = it; vm.reschedule(context)
        }
        Toggle("Only on Wi-Fi", null, wifi) { s.wifiOnly.value = it; vm.reschedule(context) }
        if (!notifGranted) ListItem(
            modifier = Modifier.clickable { if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            headlineContent = { Text("Allow price alert notifications") },
            supportingContent = { Text("Needed to tell you when a card crosses your target price") },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )

        Group("Your data")
        Action("Back up collection", "Save everything to a file you can restore on any phone") {
            backupLauncher.launch("snapstack-backup-${LocalDate.now()}.json")
        }
        Action("Restore from backup", "Replaces your current collection with the file's contents") {
            restoreLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
        }
        Action("Export CSV", "Spreadsheet of every copy with prices") { csvLauncher.launch("snapstack-collection-${LocalDate.now()}.csv") }

        Group("About")
        Text(
            "SnapStack ${BuildConfig.VERSION_NAME}. Your collection lives only on this phone — no account, no tracking. " +
                "Card text is read on-device with ML Kit; only the card number is sent to look a card up.\n\n" +
                "Card data and prices: TCGdex (Pokémon), Scryfall (Magic), YGOPRODeck (Yu-Gi-Oh!) and Lorcast (Lorcana). " +
                "Prices are TCGplayer market prices in USD where available. SnapStack is not affiliated with, endorsed or " +
                "sponsored by any card game publisher, TCGplayer, or the data providers. All card names and images are " +
                "property of their respective owners.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        Spacer(Modifier.size(24.dp))
    }

    if (offline) OfflineSheet(vm, onDismiss = { offline = false; vm.loadCounts() })
}

@Composable
private fun Group(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun Action(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfflineSheet(vm: SettingsViewModel, onDismiss: () -> Unit) {
    val game by vm.offlineGame.collectAsState()
    val sets by vm.offlineSets.collectAsState()
    val downloading by vm.downloading.collectAsState()
    var filter by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text("Offline sets", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                "Downloaded sets resolve on the phone with zero network wait.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Game.entries.forEach { g -> FilterChip(selected = game == g, onClick = { vm.loadSets(g) }, label = { Text(g.label) }) }
            }
            OutlinedTextField(
                value = filter, onValueChange = { filter = it }, singleLine = true, placeholder = { Text("Filter sets") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            val list = sets
            if (list == null) {
                CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
            } else {
                val shown = list.filter { filter.isBlank() || it.name.contains(filter, true) || it.code.contains(filter, true) }
                LazyColumn(Modifier.heightIn(max = 480.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(shown, key = { it.code }) { set ->
                        ListItem(
                            headlineContent = { Text(set.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    if (set.cachedAt > 0) "${set.cachedCount} cards on device"
                                    else listOfNotNull(set.code.takeIf { it != set.name }, set.total?.let { "$it cards" }).joinToString(" · ")
                                )
                            },
                            trailingContent = {
                                when {
                                    set.code in downloading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                    set.cachedAt > 0 -> IconButton(onClick = { vm.download(set) }) {
                                        Icon(Icons.Outlined.CheckCircle, "Downloaded — tap to update", tint = MaterialTheme.colorScheme.secondary)
                                    }
                                    else -> IconButton(onClick = { vm.download(set) }) { Icon(Icons.Outlined.CloudDownload, "Download") }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        )
                    }
                }
            }
        }
    }
}
