package app.snapstack.ui.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.snapstack.model.Condition
import app.snapstack.model.Game
import app.snapstack.data.source.TcgCsvSource
import app.snapstack.model.Variants
import app.snapstack.scan.ItemStatus
import app.snapstack.scan.SessionItem
import app.snapstack.ui.components.CardImage
import app.snapstack.ui.components.gainColor
import app.snapstack.ui.components.money
import app.snapstack.ui.components.signedMoney
import app.snapstack.ui.theme.Numeric

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemSheet(vm: ScannerViewModel, uid: Long, single: Boolean, onDismiss: () -> Unit, onOpenCard: (String) -> Unit) {
    val items by vm.items.collectAsState()
    val item = items.firstOrNull { it.uid == vm.session.currentUid(uid) }
    LaunchedEffect(item == null) { if (item == null) onDismiss() }
    if (item == null) return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                CardImage(item.card?.imageLarge ?: item.card?.imageSmall, Modifier.width(112.dp), corner = 8.dp, fallbackUrl = item.card?.imageSmall)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    when (item.status) {
                        ItemStatus.RESOLVING -> {
                            Text("Looking up ${item.key.label}…", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp)); CircularProgressIndicator(Modifier.size(24.dp))
                        }
                        ItemStatus.NOT_FOUND -> {
                            Text("No match for ${item.key.label}", style = MaterialTheme.typography.titleMedium)
                            Text("The number may have been misread. Remove it and try Snap or Type #.", style = MaterialTheme.typography.bodySmall)
                        }
                        ItemStatus.ERROR -> {
                            Text("Couldn't look up ${item.key.label}", style = MaterialTheme.typography.titleMedium)
                            Text(item.error ?: "Check your connection.", style = MaterialTheme.typography.bodySmall)
                        }
                        ItemStatus.READY -> {
                            val card = item.card!!
                            Text(card.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${card.setName} · ${card.displayNumber}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            card.rarity?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Spacer(Modifier.height(8.dp))
                            Text(money(item.unitPrice), style = MaterialTheme.typography.headlineSmall.merge(Numeric), fontWeight = FontWeight.Bold)
                            item.artScore?.let { score ->
                                Text(
                                    (if (item.artPicked) "Printing picked by artwork" else "Artwork compared") +
                                        " · ${(score.coerceIn(0f, 1f) * 100).toInt()}% look-alike",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            item.resolveMs?.let {
                                Text(
                                    "Found in $it ms" + (item.from?.let { f -> " · " + f.name.lowercase().replace("device", "on device") } ?: ""),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            TextButton(onClick = { onOpenCard(card.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                                Text("Card details")
                            }
                        }
                    }
                }
            }

            val card = item.card
            if (card != null) {
                val isDon = card.game == Game.ONE_PIECE && card.number == TcgCsvSource.DON_NUMBER
                var donFilter by remember(item.uid) { mutableStateOf("") }
                if (!item.confident || item.alternatives.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!item.confident) Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                        Text(
                            when {
                                isDon -> " Which DON!! card is it?"
                                !item.confident -> " Not sure — is it one of these?"
                                else -> "Other matches"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    if (isDon) OutlinedTextField(
                        value = donFilter, onValueChange = { donFilter = it }, singleLine = true,
                        placeholder = { Text("Filter: gold, Luffy, manga, OP05…") },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    val shown = if (isDon) {
                        item.alternatives.filter { alt ->
                            donFilter.isBlank() || alt.name.contains(donFilter.trim(), true) || alt.setName.contains(donFilter.trim(), true)
                        }.take(40)
                    } else item.alternatives.take(10)
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        shown.forEach { alt ->
                            Column(Modifier.width(if (isDon) 88.dp else 72.dp).clip(RoundedCornerShape(8.dp)).clickable { vm.session.chooseAlternative(item.uid, alt) }) {
                                CardImage(alt.imageSmall, Modifier.fillMaxWidth(), corner = 6.dp)
                                if (isDon) Text(donLabel(alt.name), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(alt.setName, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (isDon) Text(money(alt.price(null)), style = MaterialTheme.typography.labelSmall.merge(Numeric), color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                    if (isDon && shown.isEmpty()) Text("No DON!! card matches \"$donFilter\".", style = MaterialTheme.typography.bodySmall)
                    if (!item.confident) TextButton(onClick = { vm.session.markConfirmed(item.uid) }) { Text("It's correct") }
                }

                if (card.allVariants.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    Text("Printing", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        card.allVariants.forEach { v ->
                            FilterChip(
                                selected = item.variant == v,
                                onClick = { vm.session.setVariant(item.uid, v) },
                                label = { Text("${Variants.label(v)} ${card.prices[v]?.let { money(it) } ?: ""}".trim()) },
                            )
                        }
                    }
                    if (card.variantMatters) Text(
                        "Your choice is remembered for the next cards of this game.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text("Condition", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Condition.entries.forEach { c ->
                        FilterChip(selected = item.condition == c, onClick = { vm.session.setCondition(item.uid, c) }, label = { Text(c.code) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                QtyRow(item.qty) { vm.session.setQty(item.uid, it) }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.session.remove(item.uid); onDismiss() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Remove")
                }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(if (single) "Next card" else "Done") }
            }
        }
    }
}

/** "DON!! Card (Gold) (Promo)" → "Gold · Promo"; the plain card → "Plain". */
private fun donLabel(name: String): String =
    Regex("[(\\[]([^)\\]]+)[)\\]]").findAll(name).joinToString(" · ") { it.groupValues[1] }.ifEmpty { "Plain" }

@Composable
private fun QtyRow(qty: Int, onQty: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Quantity", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = { onQty(qty - 1) }) { Icon(Icons.Filled.Remove, "Less") }
        Text("$qty", style = MaterialTheme.typography.titleMedium.merge(Numeric))
        IconButton(onClick = { onQty(qty + 1) }) { Icon(Icons.Filled.Add, "More") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSheet(vm: ScannerViewModel, onDismiss: () -> Unit, onEdit: (Long) -> Unit) {
    val items by vm.items.collectAsState()
    val packCost by vm.session.packCost.collectAsState()
    val label by vm.session.label.collectAsState()
    var costText by remember { mutableStateOf(packCost?.let { "%.2f".format(it) } ?: "") }
    var saved by remember { mutableStateOf<Int?>(null) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ready = items.filter { it.status == ItemStatus.READY }
    val total = ready.sumOf { it.total }
    val count = ready.sumOf { it.qty }
    val failed = items.count { it.status == ItemStatus.NOT_FOUND || it.status == ItemStatus.ERROR }
    val pending = items.count { it.status == ItemStatus.RESOLVING }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).imePadding()) {
            Text("Scan session", style = MaterialTheme.typography.titleLarge)
            Text(
                "$count cards · ${money(total)}" + (if (pending > 0) " · $pending loading" else "") + (if (failed > 0) " · $failed not found" else ""),
                style = MaterialTheme.typography.bodyMedium.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = label, onValueChange = { vm.session.label.value = it },
                    label = { Text("Rip name (optional)") }, singleLine = true, modifier = Modifier.weight(1.3f),
                )
                OutlinedTextField(
                    value = costText,
                    onValueChange = { t -> costText = t; vm.session.packCost.value = t.replace(',', '.').toDoubleOrNull() },
                    label = { Text("Paid $") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
            packCost?.let { cost ->
                val pnl = total - cost
                Text(
                    "Rip result: ${signedMoney(pnl)} (${money(total)} pulled for ${money(cost)})",
                    style = MaterialTheme.typography.titleSmall.merge(Numeric), color = gainColor(pnl),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(items, key = { it.uid }) { item -> ReviewRow(item, onClick = { onEdit(item.uid) }, onQty = { vm.session.setQty(item.uid, it) }) }
            }
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            saved?.let {
                Text("Added $it cards to your collection.", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.navigationBarsPadding().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.session.clear(); onDismiss() }, modifier = Modifier.weight(1f)) { Text("Discard all") }
                Button(
                    onClick = { vm.saveSession { saved = it; onDismiss() } },
                    enabled = count > 0, modifier = Modifier.weight(1.4f),
                ) { Text("Add $count to collection") }
            }
        }
    }
}

@Composable
private fun ReviewRow(item: SessionItem, onClick: () -> Unit, onQty: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardImage(item.card?.imageSmall, Modifier.width(40.dp), corner = 4.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.card?.title ?: item.key.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            val sub = when (item.status) {
                ItemStatus.READY -> "${item.card!!.setName} · ${Variants.label(item.variant)} · ${item.condition.code}"
                ItemStatus.RESOLVING -> "Looking up ${item.key.label}…"
                ItemStatus.NOT_FOUND -> "No match for ${item.key.label} — tap to remove"
                ItemStatus.ERROR -> "Lookup failed — tap to remove"
            }
            Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.needsReview) Text("Check printing / match", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        IconButton(onClick = { onQty(item.qty - 1) }) { Icon(Icons.Filled.Remove, "Less") }
        Text("${item.qty}", style = MaterialTheme.typography.titleSmall.merge(Numeric))
        IconButton(onClick = { onQty(item.qty + 1) }) { Icon(Icons.Filled.Add, "More") }
        Text(money(item.total), style = MaterialTheme.typography.titleSmall.merge(Numeric), modifier = Modifier.width(72.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypeNumberSheet(game: Game?, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val hint = when (game) {
        Game.POKEMON -> "123/193  ·  PAL 123  ·  Charizard ex 199/165"
        Game.MAGIC -> "MKM 123  ·  or a card name"
        Game.YUGIOH -> "LOB-EN001  ·  89631139  ·  or a name"
        Game.LORCANA -> "5 123  (set, number)  ·  12/204 EN 5"
        Game.ONE_PIECE -> "OP01-001  ·  ST01-012  ·  EB01-061  ·  P-001"
        Game.RIFTBOUND -> "OGN 179/298  ·  OGN-179  ·  179/298"
        Game.DRAGON_BALL -> "FB01-001  ·  FS01-01  ·  BT22-064  ·  P-001"
        null -> "123/193 · MKM 123 · LOB-EN001 · OP01-001 · OGN 179/298"
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding()) {
            Text("Type the card number", style = MaterialTheme.typography.titleLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (text.isNotBlank()) onSubmit(text) }),
                label = { Text(game?.label ?: "Any game") },
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onSubmit(text) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Add") }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
