package com.pchuri.returnfairy.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pchuri.returnfairy.R
import com.pchuri.returnfairy.data.SettingsStore
import com.pchuri.returnfairy.notify.ensureNotificationChannel
import com.pchuri.returnfairy.notify.scheduleDailyCheck
import com.pchuri.returnfairy.update.UpdateChecker

private const val LEGACY_DATABASE = "returnfairy.db"

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ensureNotificationChannel(this)
        scheduleDailyCheck(this, SettingsStore(this).reminderHour)
        // 3.x kept typed-in and scanned books in a Room database; 4.0 shows library data only.
        deleteDatabase(LEGACY_DATABASE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            ReturnFairyTheme {
                ReturnFairyApp()
            }
        }
    }
}

@Composable
fun ReturnFairyApp(viewModel: AppViewModel = viewModel()) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_dashboard)) },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        }
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding)
        when (selectedTab) {
            0 -> DashboardScreen(viewModel, onOpenSettings = { selectedTab = 1 }, modifier)
            else -> SettingsScreen(viewModel, modifier)
        }
    }

    val updateVersion by viewModel.updateVersion.collectAsStateWithLifecycle()
    val context = LocalContext.current
    updateVersion?.let { version ->
        AlertDialog(
            onDismissRequest = viewModel::dismissUpdate,
            title = { Text(stringResource(R.string.update_title)) },
            text = { Text(stringResource(R.string.update_message, version)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.closeUpdatePrompt()
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASES_PAGE)))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, UpdateChecker.RELEASES_PAGE, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.update_download)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUpdate) { Text(stringResource(R.string.update_later)) }
            },
        )
    }
}
