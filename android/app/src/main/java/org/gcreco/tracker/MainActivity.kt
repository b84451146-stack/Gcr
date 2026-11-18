package org.gcreco.tracker

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var reader: UsageReader
    private lateinit var tvStatus: TextView
    private lateinit var tvToday: TextView
    private lateinit var tvWeek: TextView
    private lateinit var tvSync: TextView
    private lateinit var btnSignIn: Button
    private lateinit var btnPermission: Button
    private lateinit var switchTracking: SwitchCompat

    private val firebaseReady get() = FirebaseApp.getApps(this).isNotEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        reader = UsageReader(this)
        tvStatus = findViewById(R.id.tvStatus)
        tvToday = findViewById(R.id.tvToday)
        tvWeek = findViewById(R.id.tvWeek)
        tvSync = findViewById(R.id.tvSync)
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
        btnSignIn.setOnClickListener {
            if (!firebaseReady) return@setOnClickListener
            if (FirebaseAuth.getInstance().currentUser != null) {
                FirebaseAuth.getInstance().signOut()
                WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME)
                refresh()
            } else {
                signIn()
            }
        }
        findViewById<Button>(R.id.btnSync).setOnClickListener {
            if (!firebaseReady || FirebaseAuth.getInstance().currentUser == null) {
                Toast.makeText(this, "Sign in first", Toast.LENGTH_SHORT).show()
            } else {
                WorkManager.getInstance(this)
                    .enqueue(OneTimeWorkRequestBuilder<SyncWorker>().build())
                Toast.makeText(this, "Sync queued", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        scheduleSync()
    }

    private fun refresh() {
        val user = if (firebaseReady) FirebaseAuth.getInstance().currentUser else null
        tvStatus.text = when {
            !firebaseReady -> "Firebase not configured. Add app/google-services.json."
            user != null -> "Signed in as ${user.email}"
            else -> "Not signed in"
        }
        btnSignIn.text =
            if (user != null) "Sign out" else "Sign in with school Google account"

        val hasPerm = reader.hasPermission()
        btnPermission.text =
            if (hasPerm) "Usage Access granted (tap to review)" else "Grant Usage Access"

        if (!hasPerm) {
            tvToday.text = "Grant Usage Access to see AI app usage."
            tvWeek.text = "-"
        } else {
            tvToday.text = format(reader.secondsForDay(LocalDate.now()))
            val week = HashMap<String, Long>()
            for (i in 0..6) {
                reader.secondsForDay(LocalDate.now().minusDays(i.toLong()))
                    .forEach { (p, s) -> week[p] = (week[p] ?: 0L) + s }
            }
            tvWeek.text = format(week)
        }
        val last = prefs.lastSyncMillis
        tvSync.text = if (last == 0L) "Never synced"
        else "Last sync: " + DateFormat.getDateTimeInstance().format(Date(last))
    }

    private fun format(m: Map<String, Long>): String {
        if (m.isEmpty()) return "No supported AI app usage detected."
        return m.entries.sortedByDescending { it.value }
            .joinToString("\n") { "${AiApps.nameOf(it.key)}: ${it.value / 60} min" }
    }

    private fun scheduleSync() {
        val wm = WorkManager.getInstance(this)
        if (!prefs.trackingEnabled || !firebaseReady ||
            FirebaseAuth.getInstance().currentUser == null
        ) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            ).build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private fun signIn() {
        lifecycleScope.launch {
            try {
                val provider = OAuthProvider.newBuilder("google.com")
                    .addCustomParameter("prompt", "select_account")
                    .build()
                val res = FirebaseAuth.getInstance()
                    .startActivityForSignInWithProvider(this@MainActivity, provider)
                    .await()
                val u = res.user!!
                FirebaseFirestore.getInstance().collection("users").document(u.uid)
                    .set(
                        mapOf(
                            "email" to u.email?.lowercase(),
                            "displayName" to u.displayName,
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                scheduleSync()
                refresh()
            } catch (e: Exception) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Sign-in failed")
                    .setMessage(e.toString())
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "gcr_eco_sync"
    }
}
