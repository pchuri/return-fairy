package com.pchuri.returnfairy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pchuri.returnfairy.core.Snapshot
import com.pchuri.returnfairy.core.SplibClient
import com.pchuri.returnfairy.data.Account
import com.pchuri.returnfairy.data.AccountStore
import com.pchuri.returnfairy.data.SettingsStore
import com.pchuri.returnfairy.data.SnapshotStore
import com.pchuri.returnfairy.notify.scheduleDailyCheck
import com.pchuri.returnfairy.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

data class DashboardUiState(
    /** Last lookup (cached until a fresh one finishes); null before the first lookup. */
    val snapshot: Snapshot? = null,
    val refreshing: Boolean = false,
    val hasAccounts: Boolean = false,
    /** The last lookup could not reach the site; [snapshot] is the earlier result. */
    val stale: Boolean = false,
)

data class SettingsUiState(
    val accounts: List<Account> = emptyList(),
    val reminderHour: Int = 9,
    val checkUpdates: Boolean = true,
    val appVersion: String = "",
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val accountStore = AccountStore(application)
    private val settings = SettingsStore(application)
    private val snapshots = SnapshotStore(application)
    private val client = SplibClient()

    private val _settingsState = MutableStateFlow(
        SettingsUiState(
            accounts = accountStore.load(),
            reminderHour = settings.reminderHour,
            checkUpdates = settings.checkUpdates,
            appVersion = appVersion(application),
        )
    )
    val settingsState: StateFlow<SettingsUiState> = _settingsState.asStateFlow()

    private val _dashboard = MutableStateFlow(
        DashboardUiState(snapshot = snapshots.load(), hasAccounts = _settingsState.value.accounts.isNotEmpty())
    )
    val dashboard: StateFlow<DashboardUiState> = _dashboard.asStateFlow()

    private val updateClient by lazy { OkHttpClient() }
    private val _updateVersion = MutableStateFlow<String?>(null)
    /** Newer release version to offer, or null. */
    val updateVersion: StateFlow<String?> = _updateVersion.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refresh()
        checkForUpdate()
    }

    /**
     * Looks up every account again. The cached result stays on screen until this finishes.
     * A pull while a lookup runs is ignored; an account change ([restart]) replaces it.
     */
    fun refresh(restart: Boolean = false) {
        val accounts = _settingsState.value.accounts
        if (accounts.isEmpty()) {
            refreshJob?.cancel()
            _dashboard.value = DashboardUiState(snapshot = null, hasAccounts = false)
            return
        }
        if (refreshJob?.isActive == true) {
            if (!restart) return
            refreshJob?.cancel()
        }
        _dashboard.update { it.copy(refreshing = true, hasAccounts = true) }
        refreshJob = viewModelScope.launch {
            val fresh = client.fetchAll(accounts)
            // Offline (every account failed to connect): show the last result rather than an error
            // wall. Login failures still show, so a wrong password is never hidden by old data.
            // The daily worker may have saved something newer while the app sat in the background.
            val cached = listOfNotNull(_dashboard.value.snapshot, withContext(Dispatchers.IO) { snapshots.load() })
                .maxByOrNull { it.fetchedAt }
            val shown = if (fresh.isOffline() && cached != null) fresh.withOfflineFallback(cached) else fresh
            withContext(Dispatchers.IO) { snapshots.save(shown) }
            _dashboard.update { it.copy(snapshot = shown, refreshing = false, stale = shown !== fresh) }
        }
    }

    fun addOrUpdateAccount(label: String, userId: String, password: String) {
        val id = userId.trim()
        if (id.isEmpty() || password.isEmpty()) return
        val updated = _settingsState.value.accounts.filterNot { it.userId == id } + Account(id, password, label.trim())
        saveAccounts(updated)
    }

    fun removeAccount(userId: String) {
        saveAccounts(_settingsState.value.accounts.filterNot { it.userId == userId })
    }

    private fun saveAccounts(accounts: List<Account>) {
        accountStore.save(accounts)
        _settingsState.update { it.copy(accounts = accounts) }
        if (accounts.isEmpty()) snapshots.clear()
        refresh(restart = true)
    }

    fun setReminderHour(hour: Int) {
        settings.reminderHour = hour
        _settingsState.update { it.copy(reminderHour = hour) }
        scheduleDailyCheck(getApplication(), hour, reschedule = true)
    }

    private fun checkForUpdate() {
        if (!settings.checkUpdates) return
        val now = System.currentTimeMillis()
        val last = settings.lastUpdateCheck
        // A clock set backwards makes `now < last`; check then too instead of waiting it out.
        if (now >= last && now - last < UpdateChecker.CHECK_INTERVAL_MS) {
            // Checked recently: re-offer a newer version found then, without hitting the network.
            offerUpdate(settings.latestKnownVersion)
            return
        }
        viewModelScope.launch {
            val latest = UpdateChecker.fetchLatestVersion(updateClient) ?: return@launch
            settings.lastUpdateCheck = now
            settings.latestKnownVersion = latest
            offerUpdate(latest)
        }
    }

    private fun offerUpdate(version: String) {
        val local = _settingsState.value.appVersion
        if (UpdateChecker.shouldOffer(version, local, settings.dismissedVersion)) _updateVersion.value = version
    }

    /** "Later": stop offering this version. A newer release will be offered again. */
    fun dismissUpdate() {
        _updateVersion.value?.let { settings.dismissedVersion = it }
        _updateVersion.value = null
    }

    /** "Download": close the prompt for now, but offer it again next time if still not installed. */
    fun closeUpdatePrompt() {
        _updateVersion.value = null
    }

    fun setCheckUpdates(enabled: Boolean) {
        settings.checkUpdates = enabled
        _settingsState.update { it.copy(checkUpdates = enabled) }
        if (enabled) checkForUpdate() else _updateVersion.value = null
    }
}

private fun appVersion(context: Application): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull().orEmpty()
