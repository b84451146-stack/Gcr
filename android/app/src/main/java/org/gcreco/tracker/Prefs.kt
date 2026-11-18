package org.gcreco.tracker

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("gcr_eco", Context.MODE_PRIVATE)

    var trackingEnabled: Boolean
        get() = sp.getBoolean("tracking", true)
        set(v) = sp.edit().putBoolean("tracking", v).apply()

    var lastSyncMillis: Long
        get() = sp.getLong("last_sync", 0L)
        set(v) = sp.edit().putLong("last_sync", v).apply()

    var studentName: String
        get() = sp.getString("student_name", "") ?: ""
        set(v) = sp.edit().putString("student_name", v).apply()

    var studentEmail: String
        get() = sp.getString("student_email", "") ?: ""
        set(v) = sp.edit().putString("student_email", v).apply()

    var classCode: String
        get() = sp.getString("class_code", "") ?: ""
        set(v) = sp.edit().putString("class_code", v).apply()

    var lastStatus: String
        get() = sp.getString("last_status", "") ?: ""
        set(v) = sp.edit().putString("last_status", v).apply()
}
