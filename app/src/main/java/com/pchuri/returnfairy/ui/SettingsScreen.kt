package com.pchuri.returnfairy.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pchuri.returnfairy.R
import com.pchuri.returnfairy.core.SplibErrorKind

private val REMINDER_HOURS = listOf(8, 9, 12, 18, 20, 21)
private val LOAN_PERIODS = listOf(7, 14, 21)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.settingsState.collectAsStateWithLifecycle()
    var userId by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    // Collapsed by default: library sync is a secondary feature most users skip.
    // Expanded automatically once an account is linked.
    var splibExpanded by rememberSaveable { mutableStateOf(state.accounts.isNotEmpty()) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionTitle(stringResource(R.string.settings_reminder_section))
            Text(
                text = stringResource(R.string.settings_reminder_time),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                REMINDER_HOURS.forEach { hour ->
                    FilterChip(
                        selected = state.reminderHour == hour,
                        onClick = { viewModel.setReminderHour(hour) },
                        label = { Text(stringResource(R.string.settings_reminder_time_value, hour)) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.settings_default_period),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LOAN_PERIODS.forEach { days ->
                    FilterChip(
                        selected = state.defaultLoanDays == days,
                        onClick = { viewModel.setDefaultLoanDays(days) },
                        label = { Text(stringResource(R.string.preset_days, days)) },
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { splibExpanded = !splibExpanded }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_splib_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.accounts.isNotEmpty()) {
                    Text(
                        text = "${state.accounts.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(
                    if (splibExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (splibExpanded) {
                Text(
                    text = stringResource(R.string.settings_splib_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        items(if (splibExpanded) state.accounts else emptyList(), key = { it.userId }) { account ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = account.userId,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    IconButton(onClick = { viewModel.removeAccount(account.userId) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        if (splibExpanded && state.accounts.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.splib_no_accounts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (splibExpanded) item {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = userId,
                onValueChange = { userId = it },
                label = { Text(stringResource(R.string.splib_member_id)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.splib_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        viewModel.addOrUpdateAccount(userId, password)
                        userId = ""
                        password = ""
                    },
                    enabled = userId.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.splib_add_update))
                }
                Button(
                    onClick = { viewModel.syncFromSplib() },
                    enabled = state.accounts.isNotEmpty() && !state.syncing,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.splib_sync_now))
                    }
                }
            }

            state.lastSyncResult?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.splib_sync_result,
                        result.imported, result.updated, result.markedReturned,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                result.errors.forEach { (account, kind) ->
                    Text(
                        text = stringResource(
                            R.string.error_account_prefix,
                            account, stringResource(kind.messageRes()),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.splib_keystore_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            AiModelSection(
                ai = state.aiModel,
                onDownload = { viewModel.downloadAiModel() },
                onCancelDownload = { viewModel.cancelAiDownload() },
                onImport = { viewModel.importAiModel(it) },
                onDelete = { viewModel.deleteAiModel() },
            )
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.settings_app_section))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setCheckUpdates(!state.checkUpdates) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_check_updates),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_check_updates_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.checkUpdates, onCheckedChange = viewModel::setCheckUpdates)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_app_version, state.appVersion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AiModelSection(
    ai: AiModelUiState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onImport: (android.net.Uri) -> Unit,
    onDelete: () -> Unit,
) {
    // SAF cannot filter by .task extension, so accept anything and validate on import
    val pickModel = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImport) }

    Spacer(modifier = Modifier.height(24.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.settings_ai_section),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.settings_ai_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))

    when {
        ai.importProgress != null -> {
            LinearProgressIndicator(
                progress = { ai.importProgress },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        if (ai.downloading) R.string.settings_ai_downloading
                        else R.string.settings_ai_importing,
                        (ai.importProgress * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (ai.downloading) {
                    OutlinedButton(onClick = onCancelDownload) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }

        ai.installed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${ai.modelName} · ${formatSize(ai.modelSizeBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.settings_ai_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        else -> {
            Text(
                text = stringResource(R.string.settings_ai_model_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onDownload) {
                Text(stringResource(R.string.settings_ai_download))
            }
            TextButton(onClick = { pickModel.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.settings_ai_import))
            }
        }
    }

    ai.importError?.let { message ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_ai_import_error, message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1L shl 30) {
        String.format(java.util.Locale.US, "%.1f GB", bytes.toDouble() / (1L shl 30))
    } else {
        "${bytes / (1 shl 20)} MB"
    }

fun SplibErrorKind.messageRes(): Int = when (this) {
    SplibErrorKind.LOGIN_FAILED -> R.string.error_login_failed
    SplibErrorKind.SESSION_EXPIRED -> R.string.error_session_expired
    SplibErrorKind.NETWORK -> R.string.error_network
    SplibErrorKind.TIMEOUT -> R.string.error_timeout
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(8.dp))
}
