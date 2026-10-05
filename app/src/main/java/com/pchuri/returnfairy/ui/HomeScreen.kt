package com.pchuri.returnfairy.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pchuri.returnfairy.R
import com.pchuri.returnfairy.data.db.BookEntry
import com.pchuri.returnfairy.data.db.BookSource
import com.pchuri.returnfairy.data.db.PickupStatus
import com.pchuri.returnfairy.scan.ScanMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val activeBooks by viewModel.activeBooks.collectAsStateWithLifecycle()
    val returnedBooks by viewModel.returnedBooks.collectAsStateWithLifecycle()
    val settingsState by viewModel.settingsState.collectAsStateWithLifecycle()
    var showAddOptions by rememberSaveable { mutableStateOf(false) }
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var scanMode by rememberSaveable { mutableStateOf<ScanMode?>(null) }
    var aiScan by rememberSaveable { mutableStateOf(false) }
    var showReturned by rememberSaveable { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = settingsState.syncing,
            onRefresh = { viewModel.syncFromSplib() },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (activeBooks.isEmpty() && returnedBooks.isEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 140.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.empty_no_books),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(activeBooks, key = { it.id }) { book ->
                        ActiveBookCard(
                            book = book,
                            onReturn = { viewModel.markReturned(book) },
                            onExtend = { viewModel.extend(book) },
                        )
                    }

                    if (returnedBooks.isNotEmpty()) {
                        item(key = "returned-header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${stringResource(R.string.section_returned)} (${returnedBooks.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                IconButton(onClick = { showReturned = !showReturned }) {
                                    Icon(
                                        if (showReturned) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (showReturned) {
                            items(returnedBooks, key = { "r-${it.id}" }) { book ->
                                ReturnedBookCard(
                                    book = book,
                                    onUndo = { viewModel.unmarkReturned(book) },
                                    onDelete = { viewModel.delete(book) },
                                )
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddOptions = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add_book))
        }
    }

    if (showAddOptions) {
        AddBookOptionsDialog(
            onManual = { showAddOptions = false; showAddSheet = true },
            onScan = { mode -> showAddOptions = false; scanMode = mode },
            onAiScan = { showAddOptions = false; aiScan = true },
            onDismiss = { showAddOptions = false },
        )
    }

    scanMode?.let { mode ->
        ScanFlow(
            mode = mode,
            defaultLoanDays = viewModel.defaultLoanDays,
            onSave = { books -> viewModel.addScannedBooks(books); scanMode = null },
            onDismiss = { scanMode = null },
        )
    }

    if (aiScan) {
        AiScanFlow(
            defaultLoanDays = viewModel.defaultLoanDays,
            onSave = { books -> viewModel.addScannedBooks(books); aiScan = false },
            onDismiss = { aiScan = false },
        )
    }

    if (showAddSheet) {
        AddBookDialog(
            defaultBorrower = viewModel.lastBorrower,
            defaultLoanDays = viewModel.defaultLoanDays,
            onSave = { title, borrower, library, loanDate, dueDate ->
                viewModel.addBook(title, borrower, library, loanDate, dueDate)
                showAddSheet = false
            },
            onDismiss = { showAddSheet = false },
        )
    }
}

private data class DueBadge(val label: String, val color: Color, val urgent: Boolean)

@Composable
private fun dueBadge(book: BookEntry): DueBadge {
    val dark = isSystemInDarkTheme()
    val dueDate = book.dueDate
    if (dueDate == null) {
        val label = when (book.pickupStatus) {
            PickupStatus.REQUESTED -> stringResource(R.string.status_requested)
            PickupStatus.SENDING -> stringResource(R.string.status_sending)
            PickupStatus.OBTAINED -> stringResource(R.string.status_obtained)
            null -> "-"
        }
        return DueBadge(label, if (dark) StatusColors.waitingDark else StatusColors.waiting, urgent = false)
    }
    val days = ChronoUnit.DAYS.between(LocalDate.now(), dueDate).toInt()
    return when {
        days < 0 -> DueBadge(stringResource(R.string.badge_overdue), if (dark) StatusColors.urgentDark else StatusColors.urgent, urgent = true)
        days == 0 -> DueBadge(stringResource(R.string.badge_due_today), if (dark) StatusColors.urgentDark else StatusColors.urgent, urgent = true)
        days <= 1 -> DueBadge("D-$days", if (dark) StatusColors.urgentDark else StatusColors.urgent, urgent = true)
        days <= 3 -> DueBadge("D-$days", if (dark) StatusColors.approachingDark else StatusColors.approaching, urgent = false)
        else -> DueBadge("D-$days", MaterialTheme.colorScheme.onSurfaceVariant, urgent = false)
    }
}

@Composable
private fun ActiveBookCard(book: BookEntry, onReturn: () -> Unit, onExtend: () -> Unit) {
    val badge = dueBadge(book)
    val isPending = book.dueDate == null
    // Library-synced books are read-only: the website is the source of truth,
    // so return/extend happen there and the next sync reflects them here.
    val isSynced = book.source == BookSource.SPLIB

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
    ) {
        Column(
            modifier = Modifier.padding(
                start = 14.dp, end = 14.dp, top = 12.dp,
                bottom = if (isPending || isSynced) 12.dp else 6.dp,
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (badge.urgent) FontWeight.Bold else FontWeight.Normal,
                    )
                    val parts = mutableListOf<String>()
                    if (book.isInterlibrary || isPending) parts.add(stringResource(R.string.label_interlibrary))
                    if (book.borrower.isNotBlank()) parts.add(book.borrower)
                    if (book.library.isNotBlank()) parts.add(book.library)
                    if (isSynced) parts.add(stringResource(R.string.label_library_synced))
                    if (parts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = parts.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (book.isInterlibrary || isPending) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = badge.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = badge.color,
                        fontWeight = FontWeight.Bold,
                    )
                    book.dueDate?.let {
                        Text(
                            text = it.format(DATE_FORMAT),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (!isPending && !isSynced) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onExtend) { Text(stringResource(R.string.action_extend)) }
                    OutlinedButton(onClick = onReturn) { Text(stringResource(R.string.action_return)) }
                }
            }
        }
    }
}

@Composable
private fun ReturnedBookCard(book: BookEntry, onUndo: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                book.returnedAt?.let {
                    Text(
                        text = stringResource(R.string.returned_on, it.format(DATE_FORMAT)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (book.source == BookSource.SPLIB) {
                    Text(
                        text = stringResource(R.string.label_library_synced),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // The library site owns the returned state for synced books.
            if (book.source != BookSource.SPLIB) {
                TextButton(onClick = onUndo) { Text(stringResource(R.string.action_undo_return)) }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
