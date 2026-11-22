package app.tcgportfolio.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tcgportfolio.data.ScanMode
import app.tcgportfolio.data.ScanSpeed
import app.tcgportfolio.model.Game
import app.tcgportfolio.model.Variants
import app.tcgportfolio.scan.ItemStatus
import app.tcgportfolio.scan.SessionItem
import app.tcgportfolio.ui.components.CardImage
import app.tcgportfolio.ui.components.money
import app.tcgportfolio.ui.theme.Gain
import app.tcgportfolio.ui.theme.HoloBrush
import app.tcgportfolio.ui.theme.Mint
import app.tcgportfolio.ui.theme.Numeric
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
fun ScanScreen(onClose: () -> Unit, onOpenCard: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    val vm: ScannerViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val items by vm.items.collectAsState()
    val sticky by vm.sticky.collectAsState()
    val showStats by vm.showStats.collectAsState()

    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var editUid by remember { mutableStateOf<Long?>(null) }
    var showReview by remember { mutableStateOf(false) }
    var showType by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraPreview(
                vm.analyzer, ui.torch, onCapture = { capture = it },
                modifier = Modifier.fillMaxSize().onSizeChanged { vm.setViewSize(it.width, it.height) },
                focusRequests = vm.focusRequests,
            )
            ScanOverlay(ui, Modifier.fillMaxSize())
        } else {
            PermissionPrompt(onRequest = { launcher.launch(Manifest.permission.CAMERA) }, onType = { showType = true })
        }

        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            TopControls(ui, vm, onClose)
            GameChips(ui.game, vm::setGame)
            val stickyVariant = ui.game?.let { sticky[it] } ?: sticky.entries.singleOrNull()?.value
            val stickyGame = ui.game ?: sticky.entries.singleOrNull()?.key
            if (stickyVariant != null && stickyGame != null) {
                AssistChip(
                    onClick = { vm.session.clearSticky(stickyGame) },
                    label = { Text("Printing: ${Variants.label(stickyVariant)}  ✕") },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    colors = AssistChipDefaults.assistChipColors(containerColor = Color.Black.copy(alpha = 0.55f), labelColor = Color.White),
                )
            }
            StatusLine(ui, showStats)
        }

        BottomPanel(
            ui = ui,
            items = items,
            onSnap = { vm.snap(capture) },
            snapEnabled = capture != null,
            onType = { showType = true },
            onReview = { showReview = true },
            onItem = { editUid = it },
            onUndo = vm::undoBinder,
            onGrid = vm::setGrid,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    val singleUid = ui.singleUid
    if (singleUid != null) {
        ItemSheet(
            vm = vm, uid = singleUid, single = true,
            onDismiss = { vm.nextCard() },
            onOpenCard = onOpenCard,
        )
    } else editUid?.let { uid ->
        ItemSheet(vm = vm, uid = uid, single = false, onDismiss = { editUid = null }, onOpenCard = onOpenCard)
    }
    if (showReview) ReviewSheet(vm, onDismiss = { showReview = false }, onEdit = { showReview = false; editUid = it })
    if (showType) TypeNumberSheet(ui.game, onDismiss = { showType = false }, onSubmit = { if (vm.typed(it)) showType = false })
}

@Composable
private fun TopControls(ui: ScanUi, vm: ScannerViewModel, onClose: () -> Unit) {
    var speedMenu by remember { mutableStateOf(false) }
    val iconColors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose, colors = iconColors) { Icon(Icons.Filled.Close, "Close scanner") }
        Spacer(Modifier.width(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            ScanMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = ui.mode == mode,
                    onClick = { vm.setMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(i, ScanMode.entries.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        inactiveContainerColor = Color.Black.copy(alpha = 0.45f),
                        inactiveContentColor = Color.White,
                    ),
                    icon = {},
                ) { Text(mode.label, maxLines = 1) }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box {
            IconButton(onClick = { speedMenu = true }, colors = iconColors) { Icon(Icons.Filled.Speed, "Scan speed") }
            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                ScanSpeed.entries.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(s.label + if (s == ui.speed) "  ✓" else "", fontWeight = FontWeight.SemiBold)
                                Text(s.description, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = { vm.setSpeed(s); speedMenu = false },
                    )
                }
            }
        }
        IconButton(onClick = { vm.setTorch(!ui.torch) }, colors = iconColors) {
            Icon(if (ui.torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff, "Torch")
        }
    }
}

@Composable
private fun GameChips(selected: Game?, onSelect: (Game?) -> Unit) {
    val colors = FilterChipDefaults.filterChipColors(
        containerColor = Color.Black.copy(alpha = 0.45f), labelColor = Color.White,
        selectedContainerColor = MaterialTheme.colorScheme.secondary, selectedLabelColor = MaterialTheme.colorScheme.onSecondary,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("Auto") }, colors = colors, border = null)
        Game.entries.forEach { g ->
            FilterChip(selected = selected == g, onClick = { onSelect(g) }, label = { Text(g.label) }, colors = colors, border = null)
        }
    }
}

@Composable
private fun StatusLine(ui: ScanUi, showStats: Boolean) {
    val text = when {
        ui.snapping -> "Reading photo…"
        ui.mode == ScanMode.BINDER -> "${ui.binderLive} of ${ui.binderRows * ui.binderCols} pockets readable — tap the shutter"
        ui.detection?.verified == true -> "✓ ${ui.detection.label}"
        ui.detection != null -> "Reading ${ui.detection.label}"
        ui.blurry -> "Hold steady — focusing…"
        ui.mode == ScanMode.SINGLE -> "Hold one card inside the frame"
        else -> "Slide cards under the camera, one after another"
    }
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
        if (showStats && ui.ocrMs > 0) {
            Spacer(Modifier.width(10.dp))
            Text(
                "OCR ${ui.ocrMs} ms · ${"%.0f".format(ui.fps)} fps",
                color = Mint, style = MaterialTheme.typography.labelSmall.merge(Numeric),
            )
        }
    }
}

/** Card guide (or binder grid), the box around the text being read, and a flash on each hit. */
@Composable
private fun ScanOverlay(ui: ScanUi, modifier: Modifier) {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(ui.flash) {
        if (ui.flash > 0) {
            flash.snapTo(0.55f); flash.animateTo(0f, tween(350))
        }
    }
    val accent = MaterialTheme.colorScheme.secondary
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val binder = ui.mode == ScanMode.BINDER
        val g = Guide.inView(size.width, size.height, binder)
        val guideW = g.w
        val guideH = g.h
        val left = g.left
        val top = g.top
        drawRect(Color.Black.copy(alpha = 0.45f))
        drawRoundRect(Color.Transparent, Offset(left, top), Size(guideW, guideH), CornerRadius(28f), blendMode = BlendMode.Clear)
        drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(left, top), Size(guideW, guideH), CornerRadius(28f), style = Stroke(3f))
        if (binder) {
            for (c in 1 until ui.binderCols) {
                val x = left + guideW * c / ui.binderCols
                drawLine(Color.White.copy(alpha = 0.5f), Offset(x, top), Offset(x, top + guideH), 2f)
            }
            for (r in 1 until ui.binderRows) {
                val y = top + guideH * r / ui.binderRows
                drawLine(Color.White.copy(alpha = 0.5f), Offset(left, y), Offset(left + guideW, y), 2f)
            }
        }
        // Map the detected text box from analysis-frame coordinates onto the preview (FILL_CENTER).
        ui.detection?.box?.let { b ->
            val fw = ui.detection.frameW.toFloat(); val fh = ui.detection.frameH.toFloat()
            if (fw > 0 && fh > 0) {
                val scale = max(size.width / fw, size.height / fh)
                val dx = (size.width - fw * scale) / 2f; val dy = (size.height - fh * scale) / 2f
                val pad = 8f
                drawRoundRect(
                    if (ui.detection.verified) Gain else accent,
                    Offset(b.left * scale + dx - pad, b.top * scale + dy - pad),
                    Size(b.w * scale + 2 * pad, b.h * scale + 2 * pad),
                    CornerRadius(10f), style = Stroke(5f),
                )
            }
        }
        if (flash.value > 0f) drawRect(Gain.copy(alpha = flash.value * 0.35f))
    }
}

@Composable
private fun BottomPanel(
    ui: ScanUi,
    items: List<SessionItem>,
    onSnap: () -> Unit,
    snapEnabled: Boolean,
    onType: () -> Unit,
    onReview: () -> Unit,
    onItem: (Long) -> Unit,
    onUndo: () -> Unit,
    onGrid: (String) -> Unit,
    modifier: Modifier,
) {
    val total = items.sumOf { it.total }
    val count = items.sumOf { if (it.status == ItemStatus.READY) it.qty else 0 }
    Surface(
        modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.navigationBarsPadding().padding(top = 10.dp, bottom = 8.dp)) {
            AnimatedVisibility(ui.message != null, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ui.message.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (ui.lastUndo.isNotEmpty()) TextButton(onClick = onUndo) { Text("Undo") }
                }
            }
            if (ui.mode == ScanMode.BINDER) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScannerViewModel.grids.forEach { g ->
                        val parts = g.split('x')
                        FilterChip(
                            selected = ui.binderCols == parts[0].toInt() && ui.binderRows == parts[1].toInt(),
                            onClick = { onGrid(g) }, label = { Text(g.replace("x", "×")) },
                        )
                    }
                }
            }
            if (items.isEmpty()) {
                Text(
                    "Scanned cards appear here instantly — prices fill in as they load.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 6.dp),
                ) {
                    items(items.take(40), key = { it.uid }) { item -> TrayTile(item, onClick = { onItem(item.uid) }) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = onType, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(Icons.Filled.Keyboard, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Type #")
                }
                Spacer(Modifier.weight(1f))
                ShutterButton(onSnap, enabled = snapEnabled && !ui.snapping, busy = ui.snapping, binder = ui.mode == ScanMode.BINDER)
                Spacer(Modifier.weight(1f))
                Button(onClick = onReview, enabled = items.isNotEmpty(), contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Review $count", style = MaterialTheme.typography.labelLarge)
                        Text(money(total), style = MaterialTheme.typography.labelSmall.merge(Numeric))
                    }
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(onClick: () -> Unit, enabled: Boolean, busy: Boolean, binder: Boolean) {
    Box(
        Modifier.size(68.dp).clip(CircleShape)
            .border(4.dp, if (enabled) HoloBrush else androidx.compose.ui.graphics.SolidColor(Color.Gray), CircleShape)
            .padding(7.dp).clip(CircleShape)
            .background(if (enabled) Color.White else Color.Gray)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
        else Icon(Icons.Filled.PhotoCamera, if (binder) "Capture binder page" else "Snap", tint = Color.Black)
    }
}

@Composable
private fun TrayTile(item: SessionItem, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.width(72.dp)) {
            CardImage(item.card?.imageSmall, Modifier.fillMaxWidth(), corner = 6.dp)
            when {
                item.status == ItemStatus.RESOLVING ->
                    CircularProgressIndicator(Modifier.size(22.dp).align(Alignment.Center), strokeWidth = 2.dp)
                item.status == ItemStatus.NOT_FOUND || item.status == ItemStatus.ERROR ->
                    Icon(Icons.Outlined.ErrorOutline, "Not found", Modifier.align(Alignment.Center), tint = MaterialTheme.colorScheme.error)
                item.needsReview ->
                    Icon(
                        Icons.AutoMirrored.Outlined.HelpOutline, "Check this card",
                        Modifier.align(Alignment.TopEnd).padding(2.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary).padding(2.dp).size(14.dp),
                        tint = Color.Black,
                    )
            }
            if (item.qty > 1) Text(
                "×${item.qty}",
                Modifier.align(Alignment.BottomEnd).padding(3.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 4.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            item.card?.name ?: item.key.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            when (item.status) {
                ItemStatus.READY -> money(item.unitPrice)
                ItemStatus.RESOLVING -> "…"
                ItemStatus.NOT_FOUND -> "Not found"
                ItemStatus.ERROR -> "Offline?"
            },
            style = MaterialTheme.typography.labelSmall.merge(Numeric), color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit, onType: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.ShoppingBag, null, tint = Color.White, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text("Camera access is needed to scan cards", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(
            "Text recognition runs entirely on your phone. Photos never leave the device.",
            color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text("Allow camera") }
        TextButton(onClick = onType) { Text("Type a card number instead") }
    }
}
