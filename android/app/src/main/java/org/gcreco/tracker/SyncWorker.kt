package org.gcreco.tracker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

/** Uploads the last 7 days of AI-app durations. Runs hourly, only with network. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (FirebaseApp.getApps(applicationContext).isEmpty()) return Result.success()
        val user = FirebaseAuth.getInstance().currentUser ?: return Result.success()
        val prefs = Prefs(applicationContext)
        if (!prefs.trackingEnabled) return Result.success()

        val reader = UsageReader(applicationContext)
        if (!reader.hasPermission()) return Result.success()

        return try {
            val db = FirebaseFirestore.getInstance()
            val batch = db.batch()
            val today = LocalDate.now()
            for (i in 0..6) {
                val day = today.minusDays(i.toLong())
                reader.secondsForDay(day).forEach { (pkg, secs) ->
                    val ref = db.collection("users").document(user.uid)
                        .collection("usage").document("${day}_$pkg")
                    batch.set(
                        ref,
                        mapOf(
                            "date" to day.toString(),
                            "packageName" to pkg,
                            "appName" to AiApps.nameOf(pkg),
                            "seconds" to secs,
                            "source" to "android_usage_stats",
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                }
            }
            batch.commit().await()
            prefs.lastSyncMillis = System.currentTimeMillis()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
