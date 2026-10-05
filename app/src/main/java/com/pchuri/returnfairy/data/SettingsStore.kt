package com.pchuri.returnfairy.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("returnfairy_settings", Context.MODE_PRIVATE)

    var reminderHour: Int
        get() = prefs.getInt(KEY_REMINDER_HOUR, 9)
        set(value) = prefs.edit().putInt(KEY_REMINDER_HOUR, value).apply()

    var defaultLoanDays: Int
        get() = prefs.getInt(KEY_DEFAULT_LOAN_DAYS, 14)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_LOAN_DAYS, value).apply()

    var lastBorrower: String
        get() = prefs.getString(KEY_LAST_BORROWER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_BORROWER, value).apply()

    /** DownloadManager id of an in-flight AI model download, or -1. */
    var aiModelDownloadId: Long
        get() = prefs.getLong(KEY_AI_DOWNLOAD_ID, -1L)
        set(value) = prefs.edit().putLong(KEY_AI_DOWNLOAD_ID, value).apply()

    /** Ask GitHub once a day whether a newer APK exists. */
    var checkUpdates: Boolean
        get() = prefs.getBoolean(KEY_CHECK_UPDATES, true)
        set(value) = prefs.edit().putBoolean(KEY_CHECK_UPDATES, value).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    var latestKnownVersion: String
        get() = prefs.getString(KEY_LATEST_KNOWN_VERSION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LATEST_KNOWN_VERSION, value).apply()

    /** Version the user answered "Later" to; not offered again until something newer appears. */
    var dismissedVersion: String
        get() = prefs.getString(KEY_DISMISSED_VERSION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DISMISSED_VERSION, value).apply()

    companion object {
        private const val KEY_DISMISSED_VERSION = "dismissed_version"
        private const val KEY_CHECK_UPDATES = "check_updates"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_LATEST_KNOWN_VERSION = "latest_known_version"
        private const val KEY_REMINDER_HOUR = "reminder_hour"
        private const val KEY_DEFAULT_LOAN_DAYS = "default_loan_days"
        private const val KEY_LAST_BORROWER = "last_borrower"
        private const val KEY_AI_DOWNLOAD_ID = "ai_model_download_id"
    }
}
