package app.snapstack.ui.importer

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapstack.AppGraph
import app.snapstack.model.Game

/** Opens the "Import from list or CSV" sheet. Hold it in a screen that stays on screen while the file picker is up. */
class ImportSheetState internal constructor() {
    var visible by mutableStateOf(false)
        internal set
    var game by mutableStateOf<Game?>(null)
        internal set

    fun open(defaultGame: Game? = null) {
        game = defaultGame
        visible = true
    }
}

@Composable
fun rememberImportSheet(): ImportSheetState {
    val state = remember { ImportSheetState() }
    val pickFile = rememberImportPicker()
    if (state.visible) {
        ImportSheet(
            initialGame = state.game,
            onDismiss = { state.visible = false },
            onPickFile = { state.visible = false; pickFile() },
        )
    }
    return state
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSheet(initialGame: Game?, onDismiss: () -> Unit, onPickFile: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var game by remember { mutableStateOf(initialGame) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).imePadding().navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Import from list or CSV", style = MaterialTheme.typography.titleLarge)
            Text(
                "Paste one card per line — \"4 Lightning Bolt\", \"2x 123/193\", \"OP01-001\", \"LOB-EN001 x3\", \"DON!!\" — " +
                    "or paste CSV text with a header row.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Game for lines that don't say", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = game == null, onClick = { game = null }, label = { Text("Auto") })
                Game.entries.forEach { g -> FilterChip(selected = game == g, onClick = { game = g }, label = { Text(g.label) }) }
            }
            OutlinedTextField(
                value = text, onValueChange = { text = it }, minLines = 5, maxLines = 10,
                modifier = Modifier.fillMaxWidth(), placeholder = { Text("Paste your list here") },
            )
            Button(
                onClick = { AppGraph.instance.importer.startText(text, game); text = ""; onDismiss() },
                enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth(),
            ) { Text("Import list") }

            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f))
                Text("  or  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.weight(1f))
            }
            OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text("Choose a file — CSV, collection-app backup, or SnapStack backup")
            }
            Text(
                "Files from other collection apps (for example a Ripfolio backup .json) and CSV exports are recognised automatically.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}
