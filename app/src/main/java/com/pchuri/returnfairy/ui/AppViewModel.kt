package com.pchuri.returnfairy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pchuri.returnfairy.core.SplibErrorKind
import com.pchuri.returnfairy.data.Account
import com.pchuri.returnfairy.data.AccountStore
import com.pchuri.returnfairy.data.BookRepository
import com.pchuri.returnfairy.data.SettingsStore
import com.pchuri.returnfairy.data.SyncResult
import com.pchuri.returnfairy.data.db.AppDatabase
import com.pchuri.returnfairy.data.db.BookEntry
import com.pchuri.returnfairy.data.db.BookSource
import com.pchuri.returnfairy.notify.scheduleDailyReminder
import com.pchuri.returnfairy.scan.AiModelStore
import com.pchuri.returnfairy.scan.ScannedBook
import kotlinx.coroutines.Dispatchers
import com.pchuri.returnfairy.update.UpdateChecker
import okhttp3.OkHttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class SettingsUiState(
    val accounts: List<Account> = emptyList(),
    val reminderHour: Int = 9,
    val defaultLoanDays: Int = 14,
    val syncing: Boolean = false,
    val lastSyncResult: SyncResult? = null,
    val aiModel: AiModelUiState = AiModelUiState(),
    val checkUpdates: Boolean = true,
    val appVersion: String = "",
)

/** State of the experimental on-device LLM model. */
data class AiModelUiState(
    val modelName: String? = null,
    val modelSizeBytes: Long = 0,
    /** 0..1 while an import or download is running, null otherwise. */
    val importProgress: Float? = null,
    /** True when the progress belongs to a background download (cancellable). */
    val downloading: Boolean = false,
    val importError: String? = null,
) {
    val installed: Boolean get() = modelName != null
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BookRepository(AppDatabase.get(application).bookDao())
    private val accountStore = AccountStore(application)
    private val settings = SettingsStore(application)

    val activeBooks: StateFlow<List<BookEntry>> = repository.activeBooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val returnedBooks: StateFlow<List<BookEntry>> = repository.returnedBooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _settingsState = MutableStateFlow(
        SettingsUiState(
            accounts = accountStore.load(),
            reminderHour = settings.reminderHour,
            defaultLoanDays = settings.defaultLoanDays,
            checkUpdates = settings.checkUpdates,
            appVersion = appVersion(application),
        )
    )
    val settingsState: StateFlow<SettingsUiState> = _settingsState.asStateFlow()

    private val updateClient by lazy { OkHttpClient() }
    private val _updateVersion = MutableStateFlow<String?>(null)
    /** Newer release version to offer, or null. */
    val updateVersion: StateFlow<String?> = _updateVersion.asStateFlow()

    init {
        // Linked accounts sync automatically on launch (v1 behavior)
        if (_settingsState.value.accounts.isNotEmpty()) syncFromSplib()
        refreshAiModel()
        // Resume tracking a model download that outlived the last process
        if (settings.aiModelDownloadId != -1L) watchAiDownload()
        checkForUpdate()
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
        _settingsState.value = _settingsState.value.copy(checkUpdates = enabled)
        if (enabled) checkForUpdate() else _updateVersion.value = null
    }

    val lastBorrower: String get() = settings.lastBorrower
    val defaultLoanDays: Int get() = settings.defaultLoanDays

    fun addBook(title: String, borrower: String, library: String, loanDate: LocalDate, dueDate: LocalDate) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        settings.lastBorrower = borrower.trim()
        viewModelScope.launch {
            repository.add(
                BookEntry(
                    title = trimmed,
                    borrower = borrower.trim(),
                    library = library.trim(),
                    loanDate = loanDate,
                    dueDate = dueDate,
                    source = BookSource.MANUAL,
                )
            )
        }
    }

    /** Save books produced by a scan, after the user has reviewed them. */
    fun addScannedBooks(books: List<ScannedBook>) {
        if (books.isEmpty()) return
        val borrower = settings.lastBorrower
        viewModelScope.launch {
            books.forEach { book ->
                val due = book.dueDate ?: LocalDate.now().plusDays(settings.defaultLoanDays.toLong())
                repository.add(
                    BookEntry(
                        title = book.title.trim(),
                        borrower = borrower,
                        loanDate = LocalDate.now(),
                        dueDate = due,
                        source = BookSource.MANUAL,
                    )
                )
            }
        }
    }

    fun markReturned(book: BookEntry) = viewModelScope.launch { repository.markReturned(book) }
    fun unmarkReturned(book: BookEntry) = viewModelScope.launch { repository.unmarkReturned(book) }
    fun extend(book: BookEntry) = viewModelScope.launch { repository.extend(book) }
    fun delete(book: BookEntry) = viewModelScope.launch { repository.delete(book) }

    fun setReminderHour(hour: Int) {
        settings.reminderHour = hour
        _settingsState.update { it.copy(reminderHour = hour) }
        scheduleDailyReminder(getApplication(), hour)
    }

    fun setDefaultLoanDays(days: Int) {
        settings.defaultLoanDays = days
        _settingsState.update { it.copy(defaultLoanDays = days) }
    }

    fun addOrUpdateAccount(userId: String, password: String) {
        val trimmedId = userId.trim()
        if (trimmedId.isEmpty() || password.isEmpty()) return
        val updated = _settingsState.value.accounts.filter { it.userId != trimmedId } +
            Account(trimmedId, password)
        accountStore.save(updated)
        _settingsState.update { it.copy(accounts = updated) }
    }

    fun removeAccount(userId: String) {
        val updated = _settingsState.value.accounts.filter { it.userId != userId }
        accountStore.save(updated)
        _settingsState.update { it.copy(accounts = updated) }
    }

    /** One-tap model download via the system DownloadManager (survives app close). */
    fun downloadAiModel() {
        if (_settingsState.value.aiModel.importProgress != null) return
        try {
            settings.aiModelDownloadId = AiModelStore.startDownload(getApplication())
        } catch (e: Exception) {
            updateAiModel { it.copy(importError = e.message) }
            return
        }
        updateAiModel { it.copy(importProgress = 0f, downloading = true, importError = null) }
        watchAiDownload()
    }

    fun cancelAiDownload() {
        val id = settings.aiModelDownloadId
        if (id != -1L) AiModelStore.cancelDownload(getApplication(), id)
        settings.aiModelDownloadId = -1L
        refreshAiModel()
    }

    private fun watchAiDownload() {
        updateAiModel { it.copy(importProgress = it.importProgress ?: 0f, downloading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val id = settings.aiModelDownloadId
                if (id == -1L) return@launch // cancelled
                when (val state = AiModelStore.downloadState(getApplication(), id)) {
                    is AiModelStore.DownloadState.Running -> {
                        updateAiModel { it.copy(importProgress = state.progress, downloading = true) }
                        kotlinx.coroutines.delay(750)
                    }
                    AiModelStore.DownloadState.Succeeded -> {
                        AiModelStore.finalizeDownload(getApplication())
                        settings.aiModelDownloadId = -1L
                        refreshAiModel()
                        return@launch
                    }
                    is AiModelStore.DownloadState.Failed -> {
                        settings.aiModelDownloadId = -1L
                        refreshAiModel(error = "download error ${state.reason}")
                        return@launch
                    }
                    AiModelStore.DownloadState.Gone -> {
                        settings.aiModelDownloadId = -1L
                        refreshAiModel()
                        return@launch
                    }
                }
            }
        }
    }

    fun importAiModel(uri: android.net.Uri) {
        if (_settingsState.value.aiModel.importProgress != null) return
        updateAiModel { it.copy(importProgress = 0f, importError = null) }
        viewModelScope.launch {
            val result = runCatching {
                var lastPct = -1
                AiModelStore.import(getApplication(), uri) { progress ->
                    // Throttle: the copy loop reports every 1 MB of a multi-GB file
                    val pct = (progress * 100).toInt()
                    if (progress >= 0 && pct > lastPct) {
                        lastPct = pct
                        updateAiModel { it.copy(importProgress = pct / 100f) }
                    }
                }
            }
            refreshAiModel(error = result.exceptionOrNull()?.message)
        }
    }

    fun deleteAiModel() {
        viewModelScope.launch(Dispatchers.IO) {
            AiModelStore.delete(getApplication())
            refreshAiModel()
        }
    }

    private fun refreshAiModel(error: String? = null) {
        val file = AiModelStore.modelFile(getApplication())
        updateAiModel {
            AiModelUiState(
                modelName = file?.name,
                modelSizeBytes = file?.length() ?: 0,
                importError = error,
            )
        }
    }

    private fun updateAiModel(transform: (AiModelUiState) -> AiModelUiState) {
        _settingsState.update { it.copy(aiModel = transform(it.aiModel)) }
    }

    fun syncFromSplib() {
        val accounts = _settingsState.value.accounts
        if (accounts.isEmpty() || _settingsState.value.syncing) return
        _settingsState.update { it.copy(syncing = true) }
        viewModelScope.launch {
            val result = try {
                repository.syncFromSplib(accounts.map { it.userId to it.password })
            } catch (e: Exception) {
                SyncResult(0, 0, 0, accounts.map { it.userId to SplibErrorKind.NETWORK })
            }
            _settingsState.update { it.copy(syncing = false, lastSyncResult = result) }
        }
    }
}


private fun appVersion(context: Application): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull().orEmpty()
