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
}
