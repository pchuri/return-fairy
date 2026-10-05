package com.pchuri.returnfairy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pchuri.returnfairy.R
import com.pchuri.returnfairy.scan.AiDueDateInterpreter
import com.pchuri.returnfairy.scan.AiDueDateResult
import com.pchuri.returnfairy.scan.DueDateParseResult
import com.pchuri.returnfairy.scan.DueDatePhraseParser
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val NATURAL_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd")

@Composable
fun NaturalLanguageDueDateField(
    onResolved: (LocalDate) -> Unit,
    currentDueDates: () -> List<LocalDate?>,
    modifier: Modifier = Modifier,
    appliesToAllBooks: Boolean = false,
) {
    val applicationContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var phrase by rememberSaveable { mutableStateOf("") }
    var resolving by remember { mutableStateOf(false) }
    var resolvedDate by rememberSaveable { mutableStateOf<String?>(null) }
    var failure by rememberSaveable { mutableStateOf<DueDateFailure?>(null) }
    val selectedDueDates = currentDueDates()
    val confirmedDate = resolvedDate?.takeIf { formattedDate ->
        selectedDueDates.isNotEmpty() && selectedDueDates.all { date ->
            date?.format(NATURAL_DATE_FORMAT) == formattedDate
        }
    }

    fun accept(date: LocalDate) {
        onResolved(date)
        resolvedDate = date.format(NATURAL_DATE_FORMAT)
        failure = null
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = phrase,
                onValueChange = {
                    phrase = it
                    resolvedDate = null
                    failure = null
                },
                label = { Text(stringResource(R.string.natural_due_label)) },
                placeholder = { Text(stringResource(R.string.natural_due_example)) },
                singleLine = true,
                enabled = !resolving,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val today = LocalDate.now()
                    when (val result = DueDatePhraseParser.parse(phrase, today)) {
                        is DueDateParseResult.Resolved -> accept(result.date)
                        DueDateParseResult.Rejected -> failure = DueDateFailure.NOT_UNDERSTOOD
                        DueDateParseResult.Unsupported -> {
                            val dueDatesBeforeResolution = currentDueDates()
                            resolving = true
                            scope.launch {
                                try {
                                    val aiResult = AiDueDateInterpreter(applicationContext).interpret(phrase, today)
                                    if (currentDueDates() == dueDatesBeforeResolution) {
                                        when (aiResult) {
                                            is AiDueDateResult.Resolved -> accept(aiResult.date)
                                            AiDueDateResult.NoModel -> failure = DueDateFailure.NO_MODEL
                                            AiDueDateResult.NotUnderstood -> failure = DueDateFailure.NOT_UNDERSTOOD
                                        }
                                    }
                                } finally {
                                    resolving = false
                                }
                            }
                        }
                    }
                },
                enabled = phrase.isNotBlank() && !resolving,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                if (resolving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.action_apply))
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        val supportingText = when {
            confirmedDate != null -> stringResource(R.string.natural_due_resolved, confirmedDate)
            failure == DueDateFailure.NO_MODEL -> stringResource(R.string.natural_due_no_model)
            failure == DueDateFailure.NOT_UNDERSTOOD -> stringResource(R.string.natural_due_not_understood)
            resolving -> stringResource(R.string.natural_due_resolving)
            appliesToAllBooks -> stringResource(R.string.natural_due_all_books)
            else -> stringResource(R.string.natural_due_hint)
        }
        Text(
            text = supportingText,
            style = MaterialTheme.typography.labelSmall,
            color = if (failure == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

private enum class DueDateFailure {
    NO_MODEL,
    NOT_UNDERSTOOD,
}
