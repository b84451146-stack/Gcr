package org.gcreco.tracker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Sends the last 7 days of AI-app durations, plus today's game numbers
 * (water lost, water restored, dam level, quizzes), to the teacher's Google Sheet.
 * Runs hourly, only with network.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.trackingEnabled || prefs.studentEmail.isEmpty()) return Result.success()

        val reader = UsageReader(applicationContext)
        if (!reader.hasPermission()) return Result.success()

        if (Config.SHEET_URL.startsWith("PASTE")) {
            prefs.lastStatus = "Sheet link not set in Config.kt"
            return Result.success()
        }

        return try {
            val rows = JSONArray()
            val today = LocalDate.now()
            for (i in 0..6) {
                val day = today.minusDays(i.toLong())
                val byApp = HashMap<String, Long>()
                reader.secondsForDay(day).forEach { (pkg, secs) ->
                    val name = AiApps.nameOf(pkg)
                    byApp[name] = (byApp[name] ?: 0L) + secs
                }
                byApp.forEach { (name, secs) ->
                    rows.put(
                        JSONObject()
                            .put("date", day.toString())
                            .put("app", name)
                            .put("seconds", secs)
                    )
                }
            }

            // today's totals, grouped for the student summary row
            val todayMap = reader.secondsForDay(today)
            var chat = 0L
            var gem = 0L
            var cla = 0L
            var oth = 0L
            todayMap.forEach { (pkg, secs) ->
                val n = AiApps.nameOf(pkg).lowercase()
                when {
                    "chatgpt" in n || "openai" in n -> chat += secs
                    "gemini" in n -> gem += secs
                    "claude" in n -> cla += secs
                    else -> oth += secs
                }
            }
            val totalToday = todayMap.values.sum()
            val gs = GameState(applicationContext)
            gs.applyUsage(totalToday, System.currentTimeMillis())
            val game = JSONObject()
                .put("chatgpt", chat / 60.0)
                .put("gemini", gem / 60.0)
                .put("claude", cla / 60.0)
                .put("other", oth / 60.0)
                .put("total", totalToday / 60.0)
                .put("lost", gs.deductedToday.toDouble())
                .put("restored", gs.restoredToday.toDouble())
                .put("dam", gs.dam.toDouble())
                .put("quizzes", gs.correctToday())

            val body = JSONObject()
                .put("code", prefs.classCode)
                .put("email", prefs.studentEmail)
                .put("name", prefs.studentName)
                .put("rows", rows)
                .put("today", game)
                .toString()

            val reply = withContext(Dispatchers.IO) { post(body) }
            if (reply == "ok") {
                prefs.lastSyncMillis = System.currentTimeMillis()
                prefs.lastStatus = "ok"
            } else {
                prefs.lastStatus = "Server said: $reply"
            }
            Result.success()
        } catch (e: Exception) {
            prefs.lastStatus = "Network error, will retry"
            Result.retry()
        }
    }

    private fun post(body: String): String {
        val conn = URL(Config.SHEET_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.setRequestProperty("Content-Type", "text/plain")
            conn.outputStream.use { it.write(body.toByteArray()) }
            return conn.inputStream.bufferedReader().use { it.readText() }.trim()
        } finally {
            conn.disconnect()
        }
    }
}
