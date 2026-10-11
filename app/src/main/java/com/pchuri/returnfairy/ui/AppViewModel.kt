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
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

data class DashboardUiState(
    /** Last lookup (cached until a fresh one finishes); null before the first lookup. */
    val snapshot: Snapshot? = null,
    val refreshing: Boolean = false,
    val hasAccounts: Boolean = false,
    internal val refreshSequence: Long = 0,
) {
    /** At least one account is showing a retained result after a connection failure. */
    val stale: Boolean get() = snapshot?.hasStaleResults == true
}

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
        val session = accountStore.beginLookup()
        if (session.accounts.isEmpty()) {
            refreshJob?.cancel()
            _dashboard.value = DashboardUiState(snapshot = null, hasAccounts = false, refreshSequence = session.sequence)
            return
        }
        if (refreshJob?.isActive == true) {
            if (!restart) return
            refreshJob?.cancel()
        }
        _dashboard.update { it.copy(refreshing = true, hasAccounts = true, refreshSequence = session.sequence) }
        refreshJob = viewModelScope.launch {
            val lookupJob = coroutineContext.job
            val fresh = client.fetchAll(session.accounts)
            // Merge per account, including partial failures. The store reads the latest worker
            // result while holding the write lock; memory is a fallback if saving previously failed.
            withContext(Dispatchers.IO) {
                snapshots.mergeAndSave(fresh, session, _dashboard.value.snapshot) { shown, _ ->
                    // A cancelled IO block may still finish its synchronous cache commit.
                    // Let it preserve unchanged accounts without ending the replacement's spinner.
                    _dashboard.completeLookup(session.sequence, shown) { lookupJob.isActive }
                }
            }
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
        val saved = accountStore.save(accounts)
        _settingsState.update { it.copy(accounts = saved) }
        _dashboard.update { it.copy(snapshot = snapshots.load(), hasAccounts = saved.isNotEmpty()) }
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

/** Sequence and result live in the same StateFlow value, so a CAS retry observes replacements. */
internal fun MutableStateFlow<DashboardUiState>.completeLookup(
    sequence: Long, shown: Snapshot, isActive: () -> Boolean,
) = update { state ->
    if (state.refreshSequence == sequence && isActive())
        state.copy(snapshot = shown.takeIf { it.accounts.isNotEmpty() }, refreshing = false)
    else state
}
