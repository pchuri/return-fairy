package com.pchuri.returnfairy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pchuri.returnfairy.R

private val REMINDER_HOURS = listOf(8, 9, 12, 18, 20, 21)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.settingsState.collectAsStateWithLifecycle()
    var label by rememberSaveable { mutableStateOf("") }
    var userId by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            SectionTitle(stringResource(R.string.settings_accounts_section))
            Description(stringResource(R.string.settings_accounts_description))
            if (state.accounts.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_no_accounts), style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(state.accounts, key = { it.userId }) { account ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(account.label.ifBlank { stringResource(R.string.settings_label_from_site) }, style = MaterialTheme.typography.bodyLarge)
                    Text(account.userId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { viewModel.removeAccount(account.userId) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.settings_remove_account))
                }
            }
        }
        item {
            OutlinedTextField(
                value = label, onValueChange = { label = it }, singleLine = true,
                label = { Text(stringResource(R.string.settings_label_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = userId, onValueChange = { userId = it }, singleLine = true,
                label = { Text(stringResource(R.string.settings_user_id)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, singleLine = true,
                label = { Text(stringResource(R.string.settings_password)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.addOrUpdateAccount(label, userId, password)
                    label = ""; userId = ""; password = ""
                },
                enabled = userId.isNotBlank() && password.isNotEmpty(),
            ) { Text(stringResource(R.string.settings_add_account)) }
        }

        item {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.settings_reminder_section))
            Text(stringResource(R.string.settings_reminder_time), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                REMINDER_HOURS.forEach { hour ->
                    FilterChip(
                        selected = state.reminderHour == hour,
                        onClick = { viewModel.setReminderHour(hour) },
                        label = { Text(stringResource(R.string.settings_reminder_time_value, hour)) },
                    )
                }
            }
            Description(stringResource(R.string.settings_reminder_description))
        }

        item {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.settings_app_section))
            Row(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.setCheckUpdates(!state.checkUpdates) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_check_updates), style = MaterialTheme.typography.bodyMedium)
                    Description(stringResource(R.string.settings_check_updates_description))
                }
                Switch(checked = state.checkUpdates, onCheckedChange = viewModel::setCheckUpdates)
            }
            Spacer(Modifier.height(8.dp))
            Description(stringResource(R.string.settings_app_version, state.appVersion))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun Description(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
