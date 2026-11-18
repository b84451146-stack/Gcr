package org.gcreco.tracker

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads REAL foreground time of supported AI apps from Android UsageStats.
 * This is app-open time only. It cannot know how many prompts were sent.
 */
class UsageReader(private val context: Context) {

    private val usm =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    fun hasPermission(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** package name -> seconds in foreground on that local date */
    fun secondsForDay(date: LocalDate): Map<String, Long> {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(System.currentTimeMillis(), dayEnd)
        if (end <= start) return emptyMap()

        val events = usm.queryEvents(start, end)
        val e = UsageEvents.Event()
        val open = HashMap<String, Long>()
        val totals = HashMap<String, Long>()

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            if (!AiApps.isSupported(pkg)) continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> open[pkg] = e.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> open.remove(pkg)?.let { t ->
                    totals[pkg] = (totals[pkg] ?: 0L) + (e.timeStamp - t) / 1000
                }
            }
        }
        // App still open right now
        open.forEach { (pkg, t) ->
            totals[pkg] = (totals[pkg] ?: 0L) + (end - t) / 1000
        }
        return totals.filterValues { it > 0 }
    }
}
