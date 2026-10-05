package com.pchuri.returnfairy.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.pchuri.returnfairy.R
import com.pchuri.returnfairy.scan.AiBookReader
import com.pchuri.returnfairy.scan.AiModelStore
import com.pchuri.returnfairy.scan.AiReadResult
import com.pchuri.returnfairy.scan.BookScanner
import com.pchuri.returnfairy.scan.ScanMode
import com.pchuri.returnfairy.scan.ScanOutcome
import com.pchuri.returnfairy.scan.ScanParser
import com.pchuri.returnfairy.scan.ScannedBook
import java.io.File
import java.time.LocalDate

/** Menu shown by the FAB: manual entry, the two scan modes, or AI (if a model is installed). */
@Composable
fun AddBookOptionsDialog(
    onManual: () -> Unit,
    onScan: (ScanMode) -> Unit,
    onAiScan: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Only offered once the user has imported an LLM model in Settings
    val aiAvailable = remember { AiModelStore.modelFile(context) != null }
    // Start the recognizer-model download now so it overlaps with taking the photo
    LaunchedEffect(Unit) { BookScanner(context).prewarm() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_add_book)) },
        text = {
            Column {
                if (aiAvailable) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.scan_ai)) },
                        supportingContent = { Text(stringResource(R.string.scan_ai_hint)) },
                        modifier = Modifier.clickableItem(onAiScan),
                    )
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.scan_receipt)) },
                    supportingContent = { Text(stringResource(R.string.scan_receipt_hint)) },
                    modifier = Modifier.clickableItem { onScan(ScanMode.RECEIPT) },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.scan_cover)) },
                    supportingContent = { Text(stringResource(R.string.scan_cover_hint)) },
                    modifier = Modifier.clickableItem { onScan(ScanMode.COVER) },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.scan_add_manually)) },
                    modifier = Modifier.clickableItem(onManual),
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private fun Modifier.clickableItem(onClick: () -> Unit) = this
    .fillMaxWidth()
    .clickable(onClick = onClick)

private fun createScanUri(context: Context): Uri {
    val dir = File(context.cacheDir, "scans").apply { mkdirs() }
    // One reused file: scans are transient and never leave the device.
    val file = File(dir, "scan.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/**
 * Camera capture → on-device OCR → editable review. Nothing leaves the device.
 */
@Composable
fun ScanFlow(
    mode: ScanMode,
    defaultLoanDays: Int,
    onSave: (List<ScannedBook>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val uri = remember { createScanUri(context) }
    var scanning by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var results by remember { mutableStateOf<List<ScannedBook>?>(null) }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var suggestedDate by remember { mutableStateOf<LocalDate?>(null) }
    var launched by remember { mutableStateOf(false) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (!taken) {
            onDismiss()
        } else {
            scanning = true
        }
    }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) camera.launch(uri) else errorRes = R.string.scan_camera_denied
    }

    LaunchedEffect(Unit) {
        if (launched) return@LaunchedEffect
        launched = true
        val hasCamera = context.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (hasCamera) camera.launch(uri) else permission.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect
        when (val outcome = BookScanner(context).scan(uri, mode)) {
            is ScanOutcome.Success -> {
                candidates = outcome.candidateLines
                results = outcome.books
            }
            is ScanOutcome.NeedsPicking -> {
                candidates = outcome.lines
                suggestedDate = outcome.suggestedDate
                // Nothing understood: start from an empty row the user fills by tapping
                results = listOf(ScannedBook("", outcome.suggestedDate))
            }
            ScanOutcome.NothingFound -> errorRes = R.string.scan_nothing_found
            ScanOutcome.ModelDownloading -> errorRes = R.string.scan_model_downloading
            is ScanOutcome.Failed -> errorRes = R.string.scan_failed
        }
        scanning = false
    }

    when {
        errorRes != null -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(stringResource(errorRes!!)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            },
        )

        scanning -> AlertDialog(
            onDismissRequest = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("  " + stringResource(R.string.scan_scanning))
                }
            },
            confirmButton = {},
        )

        results != null -> ScanReviewDialog(
            initial = results!!,
            candidateLines = candidates,
            defaultLoanDays = defaultLoanDays,
            onSave = onSave,
            onDismiss = onDismiss,
        )
    }
}

/**
 * Experimental: camera capture → on-device LLM (user-imported model) →
 * editable review. Same shape as [ScanFlow] but inference takes tens of
 * seconds, so the waiting dialog says so. Nothing leaves the device.
 */
@Composable
fun AiScanFlow(
    defaultLoanDays: Int,
    onSave: (List<ScannedBook>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val uri = remember { createScanUri(context) }
    var reading by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var results by remember { mutableStateOf<List<ScannedBook>?>(null) }
    var launched by remember { mutableStateOf(false) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (!taken) onDismiss() else reading = true
    }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) camera.launch(uri) else errorRes = R.string.scan_camera_denied
    }

    LaunchedEffect(Unit) {
        if (launched) return@LaunchedEffect
        launched = true
        val hasCamera = context.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (hasCamera) camera.launch(uri) else permission.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(reading) {
        if (!reading) return@LaunchedEffect
        when (val outcome = AiBookReader(context).read(uri)) {
            is AiReadResult.Success -> results = outcome.books
            AiReadResult.NothingFound -> errorRes = R.string.scan_nothing_found
            AiReadResult.NoModel -> errorRes = R.string.scan_ai_failed
            is AiReadResult.Failed -> errorRes = R.string.scan_ai_failed
        }
        reading = false
    }

    when {
        errorRes != null -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(stringResource(errorRes!!)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            },
        )

        reading -> AlertDialog(
            onDismissRequest = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("  " + stringResource(R.string.scan_ai_running))
                }
            },
            confirmButton = {},
        )

        results != null -> ScanReviewDialog(
            initial = results!!,
            candidateLines = emptyList(),
            defaultLoanDays = defaultLoanDays,
            onSave = onSave,
            onDismiss = onDismiss,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanReviewDialog(
    initial: List<ScannedBook>,
    candidateLines: List<String>,
    defaultLoanDays: Int,
    onSave: (List<ScannedBook>) -> Unit,
    onDismiss: () -> Unit,
) {
    val items = remember { mutableStateListOf<ScannedBook>().apply { addAll(initial) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_review_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.scan_review_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                NaturalLanguageDueDateField(
                    onResolved = { date ->
                        items.indices.forEach { index ->
                            items[index] = items[index].copy(dueDate = date)
                        }
                    },
                    currentDueDates = { items.map { it.dueDate } },
                    appliesToAllBooks = items.size > 1,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (items.size == 1 && candidateLines.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.scan_tap_to_add),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        candidateLines.forEach { line ->
                            AssistChip(
                                onClick = {
                                    val current = items[0]
                                    val joined = (current.title.trim() + " " + line).trim()
                                    items[0] = current.copy(title = joined)
                                },
                                label = { Text(line, maxLines = 1) },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                items.forEachIndexed { index, book ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = book.title,
                                onValueChange = { items[index] = book.copy(title = it) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp),
                            )
                            Text(
                                text = book.dueDate?.let {
                                    stringResource(R.string.due_on, it.format(ScanParser.DISPLAY_DATE))
                                } ?: stringResource(R.string.scan_no_due_date),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { items.removeAt(index) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        items.filter { it.title.isNotBlank() }.map { book ->
                            book.copy(
                                dueDate = book.dueDate
                                    ?: LocalDate.now().plusDays(defaultLoanDays.toLong())
                            )
                        }
                    )
                },
                enabled = items.any { it.title.isNotBlank() },
            ) {
                Text(stringResource(R.string.scan_save_count, items.count { it.title.isNotBlank() }))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
