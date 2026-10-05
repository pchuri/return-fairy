package com.pchuri.returnfairy.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("returnfairy_settings", Context.MODE_PRIVATE)

    /** Local hour of the daily due-date check. */
    var reminderHour: Int
        get() = prefs.getInt(KEY_REMINDER_HOUR, 9)
        set(value) = prefs.edit().putInt(KEY_REMINDER_HOUR, value).apply()

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

    /** Bumped when the way the daily work is scheduled changes; see scheduleDailyCheck. */
    var scheduleVersion: Int
        get() = prefs.getInt(KEY_SCHEDULE_VERSION, 0)
        set(value) = prefs.edit().putInt(KEY_SCHEDULE_VERSION, value).apply()

    var legacyCleaned: Boolean
        get() = prefs.getBoolean(KEY_LEGACY_CLEANED, false)
        set(value) = prefs.edit().putBoolean(KEY_LEGACY_CLEANED, value).apply()

    /** Settings 3.x kept for typed-in books and the AI model. Returns the AI download id, or -1. */
    fun removeLegacyKeys(): Long {
        val downloadId = prefs.getLong("ai_model_download_id", -1L)
        prefs.edit().remove("ai_model_download_id").remove("last_borrower").remove("default_loan_days").apply()
        return downloadId
    }

    companion object {
        private const val KEY_SCHEDULE_VERSION = "schedule_version"
        private const val KEY_LEGACY_CLEANED = "legacy_cleaned"
        private const val KEY_REMINDER_HOUR = "reminder_hour"
        private const val KEY_CHECK_UPDATES = "check_updates"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_LATEST_KNOWN_VERSION = "latest_known_version"
        private const val KEY_DISMISSED_VERSION = "dismissed_version"
    }
}
