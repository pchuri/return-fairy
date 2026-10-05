package com.pchuri.returnfairy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pchuri.returnfairy.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd")
private val PRESET_DAYS = listOf(7, 14, 21)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBookDialog(
    defaultBorrower: String,
    defaultLoanDays: Int,
    onSave: (title: String, borrower: String, library: String, loanDate: LocalDate, dueDate: LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var borrower by rememberSaveable { mutableStateOf(defaultBorrower) }
    var library by rememberSaveable { mutableStateOf("") }
    var loanDateEpoch by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var dueDateEpoch by rememberSaveable {
        mutableStateOf(LocalDate.now().plusDays(defaultLoanDays.toLong()).toEpochDay())
    }
    var showDuePicker by rememberSaveable { mutableStateOf(false) }
    var showLoanPicker by rememberSaveable { mutableStateOf(false) }

    val loanDate = LocalDate.ofEpochDay(loanDateEpoch)
    val dueDate = LocalDate.ofEpochDay(dueDateEpoch)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.field_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = borrower,
                    onValueChange = { borrower = it },
                    label = { Text(stringResource(R.string.field_borrower)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = library,
                    onValueChange = { library = it },
                    label = { Text(stringResource(R.string.field_library)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESET_DAYS.forEach { days ->
                        FilterChip(
                            selected = dueDate == loanDate.plusDays(days.toLong()),
                            onClick = { dueDateEpoch = loanDate.plusDays(days.toLong()).toEpochDay() },
                            label = { Text(stringResource(R.string.preset_days, days)) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                NaturalLanguageDueDateField(
                    onResolved = { dueDateEpoch = it.toEpochDay() },
                    currentDueDates = { listOf(LocalDate.ofEpochDay(dueDateEpoch)) },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.field_loan_date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { showLoanPicker = true }) {
                            Text(loanDate.format(DATE_FORMAT))
                        }
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.field_due_date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { showDuePicker = true }) {
                            Text(dueDate.format(DATE_FORMAT))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, borrower, library, loanDate, dueDate) },
                enabled = title.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )

    if (showLoanPicker) {
        DatePickerModal(
            initial = loanDate,
            onPicked = { picked ->
                val shift = java.time.temporal.ChronoUnit.DAYS.between(loanDate, dueDate)
                loanDateEpoch = picked.toEpochDay()
                dueDateEpoch = picked.plusDays(shift).toEpochDay()
                showLoanPicker = false
            },
            onDismiss = { showLoanPicker = false },
        )
    }
    if (showDuePicker) {
        DatePickerModal(
            initial = dueDate,
            onPicked = { dueDateEpoch = it.toEpochDay(); showDuePicker = false },
            onDismiss = { showDuePicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerModal(initial: LocalDate, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    onPicked(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                } ?: onDismiss()
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}
