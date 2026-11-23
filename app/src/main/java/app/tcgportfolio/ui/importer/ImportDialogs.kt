package app.tcgportfolio.ui.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.tcgportfolio.AppGraph
import app.tcgportfolio.data.importer.ImportFormat
import app.tcgportfolio.data.importer.ImportUi

/**
 * Opens the system file picker for any file type (Ripfolio backups often arrive as
 * application/octet-stream, which a JSON-only filter would grey out) and hands it to the importer.
 */
@Composable
fun rememberImportPicker(): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { AppGraph.instance.importer.start(it) }
    }
    return { launcher.launch(arrayOf("*/*")) }
}

/** Progress, confirmation and result dialogs for imports — shown over whatever screen is open. */
@Composable
fun ImportDialogs() {
    val importer = AppGraph.instance.importer
    val state by importer.state.collectAsState()
    when (val s = state) {
        null -> Unit
        is ImportUi.Reading -> ProgressDialog("Reading ${s.fileName ?: "file"}…", null)
        is ImportUi.Running -> ProgressDialog(
            s.phase + if (s.total > 0) " — ${s.done} of ${s.total}" else "…",
            if (s.total > 0) s.done.toFloat() / s.total else null,
        )
        is ImportUi.ConfirmReplace -> AlertDialog(
            onDismissRequest = importer::dismiss,
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "${s.fileName ?: "This backup"} will replace the cards, wantlist, alerts and rips currently on this phone. " +
                        "Back up first if you want to keep them."
                )
            },
            confirmButton = { TextButton(onClick = { importer.confirmReplace(s.uri) }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = importer::dismiss) { Text("Cancel") } },
        )
        is ImportUi.Failed -> AlertDialog(
            onDismissRequest = importer::dismiss,
            title = { Text("Couldn't import") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = importer::dismiss) { Text("OK") } },
        )
        is ImportUi.Done -> {
            val r = s.report
            val context = LocalContext.current
            AlertDialog(
                onDismissRequest = importer::dismiss,
                title = {
                    Text(
                        when {
                            r.replaced -> "Backup restored"
                            r.importedCopies > 0 -> "Imported ${r.importedCopies} cards"
                            else -> "Nothing imported"
                        }
                    )
                },
                text = {
                    Column(
                        Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (r.replaced) {
                            Text("Restored ${r.importedRows} collection entries from ${r.fileName ?: "the backup"}.")
                        } else {
                            Text(
                                "${r.format.label}${r.fileName?.let { " · $it" } ?: ""}\n" +
                                    "Matched ${r.importedRows} of ${r.importedRows + r.unmatchedRows} entries" +
                                    " (${r.importedCopies} copies) and added them to your collection, with prices.",
                            )
                            if (r.format == ImportFormat.RIPFOLIO) Text(
                                "Condition, foil, purchase price, grading and date added were carried over.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (r.skipped.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Not imported", fontWeight = FontWeight.SemiBold)
                            r.skipped.forEach { (what, n) -> Text("• $n $what", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (r.unmatched.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Couldn't find ${r.unmatched.size}", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Add these by scanning or searching:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            r.unmatched.take(200).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            if (r.unmatched.size > 200) Text("…and ${r.unmatched.size - 200} more", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = importer::dismiss) { Text("Done") } },
                dismissButton = if (r.unmatched.isNotEmpty()) {
                    {
                    TextButton(onClick = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Unmatched cards", r.unmatched.joinToString("\n")))
                    }) { Text("Copy list") }
                }
                } else null,
            )
        }
    }
}

@Composable
private fun ProgressDialog(text: String, progress: Float?) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Importing") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(text)
                Spacer(Modifier.height(12.dp))
                if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Keep TCG Investment Portfolio open — cards are matched against the card databases and priced.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
    )
}
