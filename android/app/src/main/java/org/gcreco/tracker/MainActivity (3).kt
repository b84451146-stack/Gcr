package org.gcreco.tracker

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var gs: GameState
    private lateinit var reader: UsageReader
    private lateinit var gameView: GameView
    private lateinit var tvStatus: TextView
    private lateinit var tvToday: TextView
    private lateinit var tvWeek: TextView
    private lateinit var tvSync: TextView
    private lateinit var tvQuiz: TextView
    private lateinit var btnSignIn: Button
    private lateinit var btnPermission: Button
    private lateinit var switchTracking: SwitchCompat

    private var todayMinutes = 0L
    private var weekMinutes = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        gs = GameState(this)
        reader = UsageReader(this)
        gameView = findViewById(R.id.gameView)
        tvStatus = findViewById(R.id.tvStatus)
        tvToday = findViewById(R.id.tvToday)
        tvWeek = findViewById(R.id.tvWeek)
        tvSync = findViewById(R.id.tvSync)
        tvQuiz = findViewById(R.id.tvQuiz)
        btnSignIn = findViewById(R.id.btnSignIn)
        btnPermission = findViewById(R.id.btnPermission)
        switchTracking = findViewById(R.id.switchTracking)

        switchTracking.isChecked = prefs.trackingEnabled
        switchTracking.setOnCheckedChangeListener { _, on ->
            prefs.trackingEnabled = on
            scheduleSync()
            refresh()
        }
        btnPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        btnSignIn.setOnClickListener { showSetup() }
        findViewById<Button>(R.id.btnSync).setOnClickListener {
            if (prefs.studentEmail.isEmpty()) {
                Toast.makeText(this, "Set up your name first", Toast.LENGTH_SHORT).show()
            } else {
                WorkManager.getInstance(this)
                    .enqueue(OneTimeWorkRequestBuilder<SyncWorker>().build())
                Toast.makeText(this, "Sync queued", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnQuiz).setOnClickListener { startQuiz() }
        findViewById<Button>(R.id.btnBadges).setOnClickListener { showBadges() }
        findViewById<Button>(R.id.btnReset).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset game?")
                .setMessage("The dam, quizzes and badges go back to the start. Your usage history is not deleted.")
                .setPositiveButton("Reset") { _, _ ->
                    gs.reset()
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        scheduleSync()
    }

    // ---------- quiz ----------

    private fun startQuiz() {
        if (gs.quizzesLeft() <= 0) {
            AlertDialog.Builder(this)
                .setTitle("No quizzes left today")
                .setMessage("You have used today's quiz rewards. Come back tomorrow!")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val q = gs.pickQuestion()
        AlertDialog.Builder(this)
            .setTitle("[${q.topic}] ${q.q}")
            .setItems(q.options.toTypedArray()) { _, which -> answer(q, which) }
            .setCancelable(false)
            .show()
    }

    private fun answer(q: Question, which: Int) {
        val correct = which == q.answer
        val added = gs.recordQuiz(q.id, correct, q.topic)
        if (added > 0f) gameView.playRestore()
        refresh()
        val head = if (correct) {
            if (added > 0f) "Correct! +${added.toInt()}% water flows back into the dam."
            else "Correct! The dam is already full, so there is nothing to add. Extra rewards are not saved."
        } else {
            "Not quite. No water this time."
        }
        AlertDialog.Builder(this)
            .setTitle(if (correct) "CORRECT!" else "NOT QUITE")
            .setMessage(
                head + "\n\n" + q.why +
                    "\n\nQuizzes left today: ${gs.quizzesLeft()}/${GameState.MAX_QUIZZES}"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showBadges() {
        val have = gs.badges()
        val sb = StringBuilder()
        sb.append("BADGES\n")
        for ((name, desc) in GameState.BADGE_INFO) {
            sb.append(if (name in have) "[x] " else "[ ] ").append(name).append("\n    ").append(desc).append("\n")
        }
        sb.append("\nDAILY MISSIONS\n")
        sb.append(if (gs.quizzesUsedToday() >= 1) "[x] " else "[ ] ").append("Answer a quiz today\n")
        sb.append(if (todayMinutes < 30) "[x] " else "[ ] ").append("Keep AI under 30 min today (now $todayMinutes min)\n")
        sb.append(if (gs.correctToday() >= 3) "[x] " else "[ ] ").append("Get 3 quizzes right today\n")
        sb.append("\nWEEKLY CHALLENGE\n")
        sb.append(if (weekMinutes < 150) "[x] " else "[ ] ").append("Keep AI under 150 min this week (now $weekMinutes min)\n")
        sb.append("\nNote: 10 minutes of AI = 10% dam water is an educational game rule, not a measurement of real AI water use.")
        AlertDialog.Builder(this)
            .setTitle("Badges and missions")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------- setup dialog ----------

    private fun showSetup() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 0)
        }
        val name = EditText(this).apply {
            hint = "Your name"
            setText(prefs.studentName)
            setSingleLine()
        }
        val email = EditText(this).apply {
            hint = "School email"
            setText(prefs.studentEmail)
            setSingleLine()
        }
        val code = EditText(this).apply {
            hint = "Class code"
            setText(prefs.classCode)
            setSingleLine()
        }
        box.addView(name)
        box.addView(email)
        box.addView(code)

        AlertDialog.Builder(this)
            .setTitle("Set up")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                val e = email.text.toString().trim().lowercase()
                val c = code.text.toString().trim()
                if (n.isEmpty() || !e.contains("@") || c.isEmpty()) {
                    Toast.makeText(this, "Fill in all three boxes", Toast.LENGTH_LONG).show()
                } else {
                    prefs.studentName = n
                    prefs.studentEmail = e
                    prefs.classCode = c
                    prefs.lastStatus = ""
                    scheduleSync()
                    WorkManager.getInstance(this)
                        .enqueue(OneTimeWorkRequestBuilder<SyncWorker>().build())
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- screen refresh ----------

    private fun refresh() {
        val setUp = prefs.studentEmail.isNotEmpty()
        tvStatus.text =
            if (setUp) "Signed up as ${prefs.studentName} (${prefs.studentEmail})"
            else "Not set up yet"
        btnSignIn.text =
            if (setUp) "Name, email and class code (tap to edit)"
            else "Set up name, email and class code"

        val hasPerm = reader.hasPermission()
        btnPermission.text =
            if (hasPerm) "Usage Access granted (tap to review)" else "Grant Usage Access"

        if (!hasPerm) {
            tvToday.text = "Grant Usage Access to see AI app usage."
            tvWeek.text = "-"
            todayMinutes = 0L
            weekMinutes = 0L
        } else {
            val todayMap = reader.secondsForDay(LocalDate.now())
            tvToday.text = "Today\n" + format(todayMap)
            val week = HashMap<String, Long>()
            for (i in 0..6) {
                reader.secondsForDay(LocalDate.now().minusDays(i.toLong()))
                    .forEach { (p, s) -> week[p] = (week[p] ?: 0L) + s }
            }
            tvWeek.text = "Last 7 days\n" + format(week)
            val totalToday = todayMap.values.sum()
            todayMinutes = totalToday / 60
            weekMinutes = week.values.sum() / 60
            gs.applyUsage(totalToday, System.currentTimeMillis())
        }

        gameView.update(gs.dam, gs.aiActiveUntil, todayMinutes)
        gameView.setStats(gs.deductedToday, gs.restoredToday, gs.overflowToday, gs.quizzesLeft(), todayMinutes)
        tvQuiz.text = "QUIZZES LEFT TODAY: ${gs.quizzesLeft()}/${GameState.MAX_QUIZZES}"

        val fresh = gs.checkBadges()
        if (fresh.isNotEmpty()) {
            Toast.makeText(this, "NEW BADGE: " + fresh.joinToString(", "), Toast.LENGTH_LONG).show()
        }

        val last = prefs.lastSyncMillis
        var sync = if (last == 0L) "Never synced"
        else "Last sync: " + DateFormat.getDateTimeInstance().format(Date(last))
        val status = prefs.lastStatus
        if (status.isNotEmpty() && status != "ok") sync += "\n" + status
        tvSync.text = sync
    }

    private fun format(m: Map<String, Long>): String {
        if (m.isEmpty()) return "No supported AI app usage detected."
        return m.entries.sortedByDescending { it.value }
            .joinToString("\n") { "${AiApps.nameOf(it.key)}: ${it.value / 60} min" }
    }

    private fun scheduleSync() {
        val wm = WorkManager.getInstance(this)
        if (!prefs.trackingEnabled || prefs.studentEmail.isEmpty()) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            ).build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    companion object {
        private const val WORK_NAME = "gcr_eco_sync"
    }
}
