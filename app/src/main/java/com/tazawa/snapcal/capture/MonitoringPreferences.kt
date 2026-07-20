package com.tazawa.snapcal.capture

import android.content.Context

class MonitoringPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("monitoring", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

    var calendarId: Long
        get() = preferences.getLong(KEY_CALENDAR_ID, NO_CALENDAR)
        set(value) = preferences.edit().putLong(KEY_CALENDAR_ID, value).apply()

    companion object {
        const val NO_CALENDAR = -1L
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CALENDAR_ID = "calendar_id"
    }
}
