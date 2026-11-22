package app.snapstack.ui.card

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.snapstack.model.Card
import app.snapstack.model.CollectionItem
import app.snapstack.model.Condition
import app.snapstack.model.PriceAlert
import app.snapstack.model.Variants
import app.snapstack.ui.components.money
import app.snapstack.ui.theme.Numeric

private val graders = listOf("PSA", "BGS", "CGC", "SGC", "TAG")

private fun Double?.text() = this?.let { "%.2f".format(it) } ?: ""
private fun String.money(): Double? = replace(',', '.').replace("$", "").trim().toDoubleOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditorSheet(
    card: Card,
    initial: CollectionItem,
    onDismiss: () -> Unit,
    onSave: (CollectionItem) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var variant by remember { mutableStateOf(initial.variant ?: card.defaultVariant) }
    var condition by remember { mutableStateOf(initial.condition) }
    var qty by remember { mutableIntStateOf(initial.qty) }
    var cost by remember { mutableStateOf(initial.costEach.text()) }
    var grader by remember { mutableStateOf(initial.grader) }
    var grade by remember { mutableStateOf(initial.grade.orEmpty()) }
    var custom by remember { mutableStateOf(initial.valueOverride.text()) }
    var notes by remember { mutableStateOf(initial.notes.orEmpty()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (initial.id == 0L) "Add to collection" else "Edit copy", style = MaterialTheme.typography.titleLarge)
            Text("${card.title} · ${card.setName}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (card.allVariants.isNotEmpty()) {
                Text("Printing", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    card.allVariants.forEach { v ->
                        FilterChip(selected = variant == v, onClick = { variant = v }, label = { Text("${Variants.label(v)} ${money(card.prices[v])}") })
                    }
                }
            }
            Text("Condition", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Condition.entries.forEach { c -> FilterChip(selected = condition == c, onClick = { condition = c }, label = { Text(c.code) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Quantity", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { if (qty > 1) qty-- }) { Icon(Icons.Filled.Remove, "Less") }
                Text("$qty", style = MaterialTheme.typography.titleMedium.merge(Numeric))
                IconButton(onClick = { qty++ }) { Icon(Icons.Filled.Add, "More") }
            }
            OutlinedTextField(
                value = cost, onValueChange = { cost = it }, label = { Text("Purchase price each ($)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Used for profit & loss") },
            )
            Text("Graded?", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = grader == null, onClick = { grader = null }, label = { Text("Raw") })
                graders.forEach { g -> FilterChip(selected = grader == g, onClick = { grader = g }, label = { Text(g) }) }
            }
            if (grader != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = grade, onValueChange = { grade = it }, label = { Text("Grade") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = custom, onValueChange = { custom = it }, label = { Text("Slab value ($)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                }
            } else {
                OutlinedTextField(
                    value = custom, onValueChange = { custom = it }, label = { Text("Custom value each ($) — optional") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Overrides the market price for this copy") },
                )
            }
            OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onDelete != null) OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Delete") }
                Button(
                    onClick = {
                        onSave(
                            initial.copy(
                                variant = variant, condition = condition, qty = qty, costEach = cost.money(),
                                grader = grader, grade = grade.trim().takeIf { grader != null && it.isNotEmpty() },
                                valueOverride = custom.money(), notes = notes.trim().ifEmpty { null },
                                priceAtAdd = initial.priceAtAdd ?: card.price(variant),
                            )
                        )
                    },
                    modifier = Modifier.weight(1.4f),
                ) { Text(if (initial.id == 0L) "Add" else "Save") }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
fun AlertDialogEditor(card: Card, variant: String?, onDismiss: () -> Unit, onSave: (PriceAlert) -> Unit) {
    val current = card.price(variant)
    var above by remember { mutableStateOf(true) }
    var target by remember { mutableStateOf(current?.let { "%.2f".format(it * 1.2) } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Price alert") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${card.title} · ${Variants.label(variant)} · now ${money(current)}")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = above, onClick = { above = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Rises above") }
                    SegmentedButton(selected = !above, onClick = { above = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Falls below") }
                }
                OutlinedTextField(
                    value = target, onValueChange = { target = it }, label = { Text("Target ($)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text("Checked during the daily price refresh.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { target.money()?.let { onSave(PriceAlert(cardId = card.id, variant = variant, above = above, target = it)) } },
                enabled = target.money() != null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
