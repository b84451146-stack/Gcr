package org.gcreco.tracker

import android.content.Context
import java.time.LocalDate

data class Question(
    val id: Int,
    val topic: String,
    val q: String,
    val options: List<String>,
    val answer: Int,
    val why: String
)

/**
 * Water model (educational game rule, not a real measurement):
 *  - 1 minute of AI use (all AI apps combined) = 1 percentage point of dam water.
 *  - Usage is processed with a per-day watermark, so the same minutes are never deducted twice,
 *    and the watermark is saved, so restarting the app does not reset anything.
 *  - Dam never goes below 0. Demand beyond 0 is stored separately (overflowToday).
 *  - Quiz: 3 rewards per day, +25 each, dam never above 100, excess is not banked.
 *  - Daily values reset on the phone's local date; the dam refills to 100 on a new day.
 */
class GameState(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("gcr_game_v2", Context.MODE_PRIVATE)

    val dam: Float
        get() { rollover(); return sp.getFloat("dam", 100f) }

    val aiActiveUntil: Long
        get() = sp.getLong("aiUntil", 0L)

    val deductedToday: Float
        get() { rollover(); return sp.getFloat("dedToday", 0f) }

    val restoredToday: Float
        get() { rollover(); return sp.getFloat("resToday", 0f) }

    val overflowToday: Float
        get() { rollover(); return sp.getFloat("ovfToday", 0f) }

    fun quizzesUsedToday(): Int { rollover(); return sp.getInt("attempts", 0) }

    fun correctToday(): Int { rollover(); return sp.getInt("correct", 0) }

    fun quizzesLeft(): Int {
        rollover()
        if (sp.getInt("attempts", 0) >= MAX_ATTEMPTS) return 0
        return (MAX_QUIZZES - sp.getInt("correct", 0)).coerceAtLeast(0)
    }

    private fun rollover() {
        val today = LocalDate.now().toString()
        if (sp.getString("day", "") == today) return
        sp.edit()
            .putString("day", today)
            .putFloat("procMin", 0f)
            .putFloat("dedToday", 0f)
            .putFloat("resToday", 0f)
            .putFloat("ovfToday", 0f)
            .putFloat("lastMin", 0f)
            .putInt("attempts", 0)
            .putInt("correct", 0)
            .putString("answered", "")
            .putFloat("dam", 100f)
            .apply()
    }

    /** totalSecondsToday = all supported AI apps combined, since local midnight. */
    fun applyUsage(totalSecondsToday: Long, nowMillis: Long) {
        rollover()
        val demand = totalSecondsToday / 60f
        val done = sp.getFloat("procMin", 0f)
        val fresh = demand - done
        sp.edit().putFloat("lastMin", demand).apply()
        if (fresh <= 0.001f) return
        val d = sp.getFloat("dam", 100f)
        val taken = minOf(d, fresh)
        sp.edit()
            .putFloat("procMin", demand)
            .putFloat("dam", (d - taken).coerceAtLeast(0f))
            .putFloat("dedToday", sp.getFloat("dedToday", 0f) + taken)
            .putFloat("ovfToday", sp.getFloat("ovfToday", 0f) + (fresh - taken))
            .putFloat("dedTotal", sp.getFloat("dedTotal", 0f) + taken)
            .putLong("aiUntil", nowMillis + ACTIVE_WINDOW_MS)
            .apply()
    }

    private fun answeredIds(): List<String> =
        sp.getString("answered", "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()

    fun pickQuestion(): Question {
        rollover()
        val done = answeredIds()
        val pool = BANK.filter { it.id.toString() !in done }.ifEmpty { BANK }
        val q = pool.random()
        val order = q.options.indices.shuffled()
        return Question(q.id, q.topic, q.q, order.map { q.options[it] }, order.indexOf(q.answer), q.why)
    }

    /** Returns the percentage points actually added to the dam (0 if wrong, already claimed, or dam full). */
    @Suppress("UNUSED_PARAMETER")
    fun recordQuiz(id: Int, correct: Boolean, topic: String): Float {
        rollover()
        val seen = answeredIds()
        if (id.toString() in seen) return 0f
        val e = sp.edit()
        e.putString("answered", (seen + id.toString()).joinToString(","))
        e.putInt("attempts", sp.getInt("attempts", 0) + 1)
        var added = 0f
        if (correct && sp.getInt("correct", 0) < MAX_QUIZZES) {
            val d = sp.getFloat("dam", 100f)
            added = minOf(REWARD, 100f - d).coerceAtLeast(0f)
            e.putFloat("dam", (d + added).coerceAtMost(100f))
            e.putInt("correct", sp.getInt("correct", 0) + 1)
            e.putInt("correctTotal", sp.getInt("correctTotal", 0) + 1)
            e.putFloat("resToday", sp.getFloat("resToday", 0f) + added)
            e.putFloat("resTotal", sp.getFloat("resTotal", 0f) + added)
            if (d < 25f && added > 0f) e.putBoolean("comeback", true)
        }
        e.apply()
        return added
    }

    // ---------- badges ----------

    fun badges(): Set<String> = sp.getStringSet("badges", emptySet())?.toSet() ?: emptySet()

    fun checkBadges(): List<String> {
        rollover()
        val have = badges().toMutableSet()
        val fresh = ArrayList<String>()
        fun grant(name: String, cond: Boolean) {
            if (cond && name !in have) { have.add(name); fresh.add(name) }
        }
        grant("First Drop", sp.getInt("correctTotal", 0) >= 1)
        grant("Triple Spring", sp.getInt("correct", 0) >= MAX_QUIZZES)
        grant("Comeback", sp.getBoolean("comeback", false))
        grant("Mindful Day", sp.getInt("attempts", 0) >= 1 && sp.getFloat("lastMin", 0f) < 10f)
        grant("Green Village", sp.getFloat("dam", 100f) >= 90f && sp.getInt("correctTotal", 0) >= 5)
        if (fresh.isNotEmpty()) sp.edit().putStringSet("badges", have).apply()
        return fresh
    }

    fun reset() {
        sp.edit().clear().apply()
    }

    companion object {
        const val MAX_QUIZZES = 3
        private const val MAX_ATTEMPTS = 6
        private const val REWARD = 25f
        private const val ACTIVE_WINDOW_MS = 90_000L

        val BADGE_INFO: List<Pair<String, String>> = listOf(
            "First Drop" to "Answer your first quiz correctly",
            "Triple Spring" to "Earn all 3 quiz rewards in one day",
            "Comeback" to "Restore the dam when it was below 25%",
            "Mindful Day" to "Answer a quiz while AI use is under 10 min today",
            "Green Village" to "Dam at 90% or more after 5 correct quizzes"
        )

        private val BANK: List<Question> = listOf(
            Question(1, "Water", "How much of Earth's water is fresh water?",
                listOf("About 3%", "About 25%", "About 50%", "About 75%"), 0,
                "Most water is salty ocean. Only about 3% is fresh, and most of that is ice or underground."),
            Question(2, "AI", "Why do AI data centres need water?",
                listOf("To cool hot servers", "To make screens brighter", "To store photos", "To charge phones"), 0,
                "Servers make heat. Many data centres use water to carry that heat away."),
            Question(3, "AI", "Which habit saves the most game water?",
                listOf("Use AI only when it really helps", "Ask the same thing 10 times", "Leave AI apps open all day", "Use three AI apps at once"), 0,
                "In GCR Eco every AI minute counts the same, whatever the app. Fewer minutes means more water for the village."),
            Question(4, "Farming", "Which watering method wastes the least water?",
                listOf("Drip irrigation", "Spraying at midday", "Flooding the whole field", "Watering the paths"), 0,
                "Drip pipes put water right at the roots, so less of it evaporates."),
            Question(5, "Farming", "About how much of the world's freshwater use goes to farming?",
                listOf("About 70%", "About 5%", "About 20%", "Almost none"), 0,
                "Agriculture is the biggest user of freshwater worldwide, about 70%."),
            Question(6, "Drought", "What is a drought?",
                listOf("A long period with much less rain than normal", "A big flood", "A very cold winter", "A strong wind"), 0,
                "A drought is a long dry spell. Rivers, dams and soil run low."),
            Question(7, "Home", "When is it best to water a garden?",
                listOf("Early morning or evening", "At noon in full sun", "Only when it rains", "Never"), 0,
                "Cooler hours mean less evaporation, so more water reaches the plants."),
            Question(8, "Home", "Which wastes more water over a day?",
                listOf("A dripping tap", "A closed tap", "A full water bottle", "A rain barrel"), 0,
                "Small drips add up to many litres over a day."),
            Question(9, "Nature", "What does a rain barrel do?",
                listOf("Collects rainwater for later use", "Makes rain", "Cleans the air", "Heats the house"), 0,
                "Collected rainwater can water plants instead of using tap water."),
            Question(10, "Nature", "What do wetlands do for water?",
                listOf("Store and filter it", "Make it salty", "Freeze it", "Remove all of it"), 0,
                "Wetlands soak up floods, filter dirt and release water slowly."),
            Question(11, "AI", "What is the rule '10 minutes of AI = 10% dam water' in this game?",
                listOf("A simple game rule to build awareness", "A lab measurement", "A law", "The exact water AI uses"), 0,
                "It is an educational game rule, not a measured amount of real AI water use."),
            Question(12, "AI", "Besides cooling, what else links AI to water use?",
                listOf("Water used to make the electricity", "Water inside the phone", "Rain on the data centre", "Nothing"), 0,
                "Power plants often use water too, so electricity use has a water footprint."),
            Question(13, "Farming", "What helps soil keep its moisture?",
                listOf("Mulch on top of the soil", "Bare dry dirt", "Leaving it in full sun", "Digging it up daily"), 0,
                "Mulch shades the soil, so less water evaporates.")
        )
    }
}
