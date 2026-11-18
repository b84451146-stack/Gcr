package org.gcreco.tracker

import android.content.Context
import java.time.LocalDate

/** All game progress, saved on the phone. */
class GameState(ctx: Context) {
    private val sp = ctx.getSharedPreferences("gcr_game", Context.MODE_PRIVATE)

    var dam: Float
        get() = sp.getFloat("dam", 100f)
        set(v) = sp.edit().putFloat("dam", v.coerceIn(0f, 100f)).apply()

    val aiActiveUntil: Long
        get() = sp.getLong("ai_until", 0L)

    private fun today(): String = LocalDate.now().toString()

    /**
     * Updates the dam from today's total AI seconds.
     * River inflow refills the dam slowly. Never below 0 or above 100.
     * Returns true if new AI use was found.
     */
    fun applyUsage(totalSecondsToday: Long, now: Long): Boolean {
        val t = today()
        val ed = sp.edit()
        var d = dam

        val last = sp.getLong("tick", 0L)
        if (last > 0L && now > last) {
            d += (now - last) / 3600000f * RIVER_PER_HOUR
        }
        ed.putLong("tick", now)

        var applied = sp.getLong("use_applied", 0L)
        if (sp.getString("use_day", "") != t) {
            ed.putString("use_day", t)
            applied = 0L
        }

        var drained = false
        val delta = totalSecondsToday - applied
        if (delta > 0L) {
            d -= delta / 60f * WATER_PER_MIN
            ed.putLong("use_applied", totalSecondsToday)
            ed.putLong("ai_until", now + 8000L)
            drained = true
        } else {
            ed.putLong("use_applied", totalSecondsToday)
        }

        d = d.coerceIn(0f, 100f)
        ed.putFloat("dam", d)
        if (d <= 10f) ed.putBoolean("was_low", true)
        if (d <= 30f) ed.putBoolean("was_dry", true)
        ed.apply()
        return drained
    }

    fun quizzesUsedToday(): Int =
        if (sp.getString("quiz_day", "") == today()) sp.getInt("quiz_used", 0) else 0

    fun correctToday(): Int =
        if (sp.getString("quiz_day", "") == today()) sp.getInt("quiz_correct_today", 0) else 0

    fun quizzesLeft(): Int = (MAX_QUIZZES - quizzesUsedToday()).coerceAtLeast(0)

    fun pickQuestion(): Question {
        val seen = (sp.getString("seen", "") ?: "").split(",")
            .mapNotNull { it.toIntOrNull() }.toSet()
        var pool = QuizBank.all.filter { it.id !in seen }
        if (pool.isEmpty()) {
            sp.edit().putString("seen", "").apply()
            pool = QuizBank.all
        }
        return pool.random()
    }

    /** Counts the quiz and adds water if correct. Returns the water points actually added. */
    fun recordQuiz(id: Int, correct: Boolean, topic: String): Float {
        if (quizzesLeft() <= 0) return 0f
        val used = quizzesUsedToday() + 1
        var ct = correctToday()
        val before = dam
        var after = before
        val ed = sp.edit()
        if (correct) {
            ct += 1
            after = (before + QUIZ_REWARD).coerceAtMost(100f)
            ed.putInt("total_correct", sp.getInt("total_correct", 0) + 1)
            if (topic == "AI") ed.putInt("ai_correct", sp.getInt("ai_correct", 0) + 1)
        }
        ed.putString("quiz_day", today())
        ed.putInt("quiz_used", used)
        ed.putInt("quiz_correct_today", ct)
        ed.putFloat("dam", after)
        val seen = sp.getString("seen", "") ?: ""
        ed.putString("seen", if (seen.isEmpty()) "$id" else "$seen,$id")
        ed.apply()
        return after - before
    }

    fun badges(): Set<String> = HashSet(sp.getStringSet("badges", emptySet()) ?: emptySet())

    /** Returns names of badges earned just now. */
    fun checkBadges(): List<String> {
        val d = dam
        val ed = sp.edit()
        if (d <= 10f) ed.putBoolean("was_low", true)
        if (d <= 30f) ed.putBoolean("was_dry", true)
        ed.apply()

        val have = badges().toMutableSet()
        val fresh = mutableListOf<String>()
        fun earn(name: String, ok: Boolean) {
            if (ok && name !in have) {
                have.add(name)
                fresh.add(name)
            }
        }
        earn("FIRST DROP", sp.getInt("total_correct", 0) >= 1)
        earn("WATER GUARDIAN", correctToday() >= 3)
        earn("RESPONSIBLE AI USER", sp.getInt("ai_correct", 0) >= 1)
        earn("VILLAGE HERO", sp.getBoolean("was_low", false) && d >= 50f)
        earn("GREEN FARMER", sp.getBoolean("was_dry", false) && d >= 80f)
        if (fresh.isNotEmpty()) sp.edit().putStringSet("badges", have).apply()
        return fresh
    }

    fun reset() {
        sp.edit().clear().apply()
    }

    companion object {
        const val MAX_QUIZZES = 3
        const val QUIZ_REWARD = 25f
        const val WATER_PER_MIN = 0.5f   // educational estimate: dam % used per AI minute
        const val RIVER_PER_HOUR = 0.5f  // dam % refilled by the river per hour

        val BADGE_INFO = listOf(
            "FIRST DROP" to "Complete your first quiz correctly.",
            "WATER GUARDIAN" to "Get three quizzes right in one day.",
            "RESPONSIBLE AI USER" to "Answer an AI-awareness quiz correctly.",
            "VILLAGE HERO" to "Bring the dam back above 50% after a water shortage.",
            "GREEN FARMER" to "Restore the dam above 80% after a drought."
        )
    }
}
