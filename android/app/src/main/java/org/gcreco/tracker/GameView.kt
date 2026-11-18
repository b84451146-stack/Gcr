package org.gcreco.tracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Pixel-art world: dam, AI cooling facility and village, all drawn with rectangles. */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val p = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val tp = Paint().apply {
        isAntiAlias = false
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private var s = 1f
    private var ox = 0f
    private var oy = 0f

    private var damTarget = 100f
    private var damShown = 100f
    private var aiUntil = 0L
    private var aiMinutes = 0L
    private var vh = 1f
    private var first = true
    private var restoreUntil = 0L

    fun update(dam: Float, aiUntilMillis: Long, aiMin: Long) {
        damTarget = dam
        aiUntil = aiUntilMillis
        aiMinutes = aiMin
        if (first) {
            damShown = dam
            vh = dam / 100f
            first = false
        }
        invalidate()
    }

    fun playRestore() {
        restoreUntil = SystemClock.uptimeMillis() + 3500L
    }

    private fun col(v: Long): Int = v.toInt()

    private fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(sh: Int): Int {
            val x = (a shr sh) and 255
            val y = (b shr sh) and 255
            return (x * (1f - k) + y * k).toInt()
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun r(c: Canvas, x: Number, y: Number, w: Number, h: Number, color: Int) {
        p.color = color
        val l = ox + x.toFloat() * s
        val t = oy + y.toFloat() * s
        c.drawRect(l, t, l + w.toFloat() * s, t + h.toFloat() * s, p)
    }

    private fun txt(c: Canvas, str: String, cx: Float, base: Float, size: Float, color: Int) {
        tp.textSize = size * s
        tp.color = col(0xFF000000)
        c.drawText(str, ox + cx * s + s * 0.5f, oy + base * s + s * 0.5f, tp)
        tp.color = color
        c.drawText(str, ox + cx * s, oy + base * s, tp)
    }

    private fun mountain(c: Canvas, cx: Float, hh: Int, color: Int) {
        for (k in 0 until hh) {
            val wd = k * 1.8f + 2f
            r(c, cx - wd / 2f, 46f - hh + k, wd, 1.2f, color)
        }
    }

    private fun dashesV(c: Canvas, x: Float, y0: Float, y1: Float, wd: Float, t: Int, color: Int) {
        var yy = y0 - ((t / 60) % 6)
        while (yy < y1) {
            val a = if (yy < y0) y0 else yy
            val b = if (yy + 3f > y1) y1 else yy + 3f
            if (b > a) r(c, x, a, wd, b - a, color)
            yy += 6f
        }
    }

    private fun house(c: Canvas, x: Float, y: Float, roof: Int) {
        r(c, x, y + 4f, 12f, 8f, col(0xFFE8C99B))
        r(c, x - 1f, y + 2f, 14f, 3f, roof)
        r(c, x + 1f, y, 10f, 2f, roof)
        r(c, x + 5f, y + 8f, 3f, 4f, col(0xFF6D4C41))
        r(c, x + 1f, y + 6f, 3f, 3f, col(0xFFFFF59D))
    }

    private fun tree(c: Canvas, x: Float, y: Float, health: Float) {
        val crown = mix(col(0xFF9B8B4A), col(0xFF2E7D32), health)
        r(c, x + 2f, y + 5f, 2f, 5f, col(0xFF6D4C41))
        r(c, x, y + 1f, 6f, 5f, crown)
        r(c, x + 1f, y - 1f, 4f, 2f, crown)
    }

    /** mood: 1 happy, 0 neutral, -1 sad */
    private fun person(
        c: Canvas, x: Float, y: Float, shirt: Int, farmer: Boolean, mood: Int, t: Int
    ) {
        val skin = col(0xFFFFCCAA)
        val dark = col(0xFF3E2723)
        if (farmer) {
            r(c, x - 0.5f, y - 0.3f, 6f, 1.2f, col(0xFFFFD54F))
            r(c, x + 1f, y - 1.5f, 3f, 1.5f, col(0xFFFFD54F))
        }
        r(c, x + 0.5f, y, 4f, 3.2f, skin)
        r(c, x, y + 3.2f, 5f, 4.8f, shirt)
        r(c, x + 1f, y + 8f, 1.6f, 3f, dark)
        r(c, x + 2.9f, y + 8f, 1.6f, 3f, dark)
        r(c, x + 1.2f, y + 1f, 0.8f, 0.8f, dark)
        r(c, x + 3f, y + 1f, 0.8f, 0.8f, dark)
        if (mood > 0) {
            r(c, x + 1.3f, y + 2.2f, 2.4f, 0.6f, dark)
            r(c, x + 0.9f, y + 1.9f, 0.6f, 0.6f, dark)
            r(c, x + 3.5f, y + 1.9f, 0.6f, 0.6f, dark)
            val up = (t / 300) % 2 == 0
            r(c, x - 1.5f, y + 3.2f - (if (up) 2.5f else 0f), 1.5f, 3.5f, skin)
            r(c, x + 5f, y + 3.2f - (if (up) 0f else 2.5f), 1.5f, 3.5f, skin)
        } else if (mood < 0) {
            r(c, x + 1.3f, y + 2.5f, 2.4f, 0.6f, dark)
            r(c, x + 0.9f, y + 2.8f, 0.6f, 0.6f, dark)
            r(c, x + 3.5f, y + 2.8f, 0.6f, 0.6f, dark)
            val ty = ((t / 120) % 6) * 0.5f
            r(c, x + 0.8f, y + 1.8f + ty, 0.6f, 1f, col(0xFF4FC3F7))
            r(c, x - 1.2f, y + 3.5f, 1.2f, 4f, skin)
            r(c, x + 5f, y + 3.5f, 1.2f, 4f, skin)
        } else {
            r(c, x + 1.3f, y + 2.4f, 2.4f, 0.6f, dark)
            r(c, x - 1.2f, y + 3.5f, 1.2f, 4f, skin)
            r(c, x + 5f, y + 3.5f, 1.2f, 4f, skin)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        s = min(w / 96f, h / 120f)
        ox = (w - 96f * s) / 2f
        oy = (h - 120f * s) / 2f

        val up = SystemClock.uptimeMillis()
        val t = (up % 1000000L).toInt()
        val ai = System.currentTimeMillis() < aiUntil

        damShown += (damTarget - damShown) * 0.1f
        val tgt = damShown / 100f * (if (ai) 0.85f else 1f)
        vh += (tgt - vh) * 0.03f
        val health = vh.coerceIn(0f, 1f)
        val white = col(0xFFFFFFFF)

        // sky and ground
        p.color = mix(col(0xFFC8A27A), col(0xFF87CEEB), health)
        c.drawRect(0f, 0f, w, oy + 46f * s, p)
        p.color = mix(col(0xFF8A6B3A), col(0xFF4CAF50), health)
        c.drawRect(0f, oy + 46f * s, w, h, p)

        // sun and clouds
        r(c, 84, 4, 7, 7, col(0xFFFFE082))
        val cloud = mix(col(0xFFB0A090), white, health)
        for (i in 0..1) {
            val cx = ((t / 140 + i * 50) % 130) - 18
            val cy = 3 + i * 7
            r(c, cx, cy, 12, 3, cloud)
            r(c, cx + 3, cy - 2, 7, 2, cloud)
        }

        // mountains
        val mt = mix(col(0xFF9A8C70), col(0xFF6FA06F), health)
        mountain(c, 10f, 16, mt)
        mountain(c, 32f, 12, mt)
        mountain(c, 62f, 14, mt)
        mountain(c, 86f, 18, mt)

        // river entering the dam
        r(c, 0, 22, 22, 6, col(0xFF2F80ED))
        for (i in 0..4) {
            val hx = (t / 70 + i * 5) % 22
            r(c, hx, 23 + (i % 3) * 1.5f, 3, 0.8f, col(0xFF8EC9FF))
        }

        // dam wall
        r(c, 18, 18, 60, 36, col(0xFF6B6F76))
        var yy = 22f
        while (yy < 54f) {
            r(c, 18, yy, 60, 0.5f, col(0xFF8A8F98))
            yy += 6f
        }
        r(c, 22, 20, 52, 30, col(0xFF3A404C))

        // water in the dam
        val wh = 30f * (damShown / 100f).coerceIn(0f, 1f)
        if (wh > 0.5f) {
            r(c, 22, 50f - wh, 52, wh, col(0xFF1E5FBF))
            for (i in 0 until 13) {
                val off = sin(t / 220.0 + i).toFloat() * 0.6f
                r(c, 22 + i * 4, 50f - wh + off, 2.5f, 1f, col(0xFF6EB6FF))
            }
        } else {
            r(c, 22, 47, 52, 3, col(0xFF8D6E4A))
            for (i in 0..5) r(c, 26 + i * 8, 47, 0.8f, 3, col(0xFF5D4630))
        }
        // river flows through the wall
        r(c, 18, 22, 4, 5, col(0xFF2F80ED))
        // level ticks
        for (k in 1..3) r(c, 74, 50f - 30f * k / 4f, 3, 0.6f, white)

        // restore animation: water falls into the dam
        if (up < restoreUntil) {
            for (i in 0..3) {
                val dy = 18 + ((t / 40 + i * 7) % 28)
                r(c, 34 + i * 8, dy, 1.6f, 3, col(0xFF4FC3F7))
            }
            txt(c, "+WATER!", 48f, 36f, 5f, if ((t / 200) % 2 == 0) white else col(0xFFFFEB3B))
        }

        // sign board with percentage
        r(c, 22, 1, 52, 16, col(0xFF4E2F14))
        r(c, 23, 2, 50, 14, col(0xFF7B4B22))
        txt(c, "DAM WATER LEVEL", 48f, 6.5f, 4.2f, col(0xFFFFE9A8))
        val pctColor = if (damShown < 20f) {
            if ((t / 400) % 2 == 0) col(0xFFFF5252) else col(0xFFFFB74D)
        } else white
        txt(c, "${damShown.roundToInt()}%", 48f, 14.8f, 9f, pctColor)

        // outlets
        r(c, 29, 54, 6, 3, col(0xFF4A4E55))
        r(c, 61, 54, 6, 3, col(0xFF4A4E55))

        // pipe to the AI facility
        r(c, 30, 57, 4, 19, col(0xFFB0B5BD))
        r(c, 31, 57, 2, 19, col(0xFF3A3F47))
        if (ai && damShown > 0.5f) dashesV(c, 31f, 57f, 76f, 2f, t, col(0xFF4FC3F7))

        // channel to the village
        r(c, 61, 57, 1, 19, col(0xFF8D6E63))
        r(c, 67, 57, 1, 19, col(0xFF8D6E63))
        r(c, 62, 57, 5, 19, col(0xFF3A3F47))
        if (damShown > 0.5f) {
            val wd = if (ai) 2f else 4f
            dashesV(c, 62f + (5f - wd) / 2f, 57f, 76f, wd, t, col(0xFF4FC3F7))
        }

        // AI cooling facility
        r(c, 0, 62, 44, 58, col(0xFF8C929B))
        for (tx in intArrayOf(4, 15)) {
            r(c, tx, 70, 9, 16, col(0xFFD7DAE0))
            r(c, tx + 1, 68, 7, 2, col(0xFFD7DAE0))
            r(c, tx + 1, 75, 7, 1, col(0xFFB8BDC6))
            r(c, tx + 1, 80, 7, 1, col(0xFFB8BDC6))
            val puffs = if (ai) 4 else 1
            for (i in 0 until puffs) {
                val py = 66 - ((t / 80 + i * 6) % 14)
                val px = tx + 3 + sin(t / 300.0 + i + tx).toFloat() * 1.5f
                r(c, px, py, 3, 2, col(0xAAFFFFFF))
            }
        }
        // receiving tank
        r(c, 26, 76, 14, 10, col(0xFF9AA0A8))
        val fill = if (ai) 0.55f + 0.15f * sin(t / 250.0).toFloat() else 0.3f
        r(c, 27, 77f + 8f * (1f - fill), 12, 8f * fill, col(0xFF29B6F6))
        // server building
        r(c, 4, 86, 36, 26, col(0xFF39424E))
        r(c, 4, 84, 36, 3, col(0xFF222831))
        for (row in 0..1) {
            for (cl in 0..3) {
                val on = if (ai) (t / 200 + cl * 3 + row * 5) % 4 != 0 else (t / 900 + cl + row) % 3 == 0
                val lamp = if (on) col(0xFF00E676) else col(0xFF1B5E20)
                r(c, 7 + cl * 8, 90 + row * 7, 5, 4, lamp)
            }
        }
        txt(c, "AI COOLING", 22f, 108.5f, 3.3f, white)
        txt(c, "AI TODAY: ${aiMinutes}m", 22f, 118f, 3f, col(0xFFFFE9A8))

        // village
        r(c, 58, 76, 16, 9, col(0xFF8D6E63))
        val tankFill = (damShown / 100f * 0.9f + 0.1f).coerceIn(0f, 1f)
        r(c, 59, 77f + 7f * (1f - tankFill), 14, 7f * tankFill, col(0xFF29B6F6))
        house(c, 47f, 86f, col(0xFFD84315))
        house(c, 82f, 86f, col(0xFFEF6C00))
        val pw = 12f * (0.4f + 0.6f * health)
        r(c, 69f - pw / 2f, 92, pw, 5, col(0xFF2F80ED))
        if (health < 0.3f) r(c, 66, 94, 6, 3, col(0xFF8D6E4A))
        tree(c, 75f, 78f, health)
        tree(c, 89f, 74f, health)

        // fields and crops
        r(c, 47, 104, 48, 14, col(0xFF7A5230))
        val cropColor = mix(col(0xFFC8B560), col(0xFF43A047), health)
        val ch = 1f + 4f * health
        for (row in 0..1) {
            for (cl in 0..10) {
                val cx = 49 + cl * 4
                val cy = 106 + row * 6
                r(c, cx, cy + 5f - ch, 2, ch, cropColor)
                if (health > 0.7f) r(c, cx, cy + 4f - ch, 2, 1, col(0xFFFFEB3B))
            }
        }

        // villagers, farmer and animals
        val mood = if (health > 0.6f) 1 else if (health < 0.35f) -1 else 0
        person(c, 50f, 96f, col(0xFF3F7FBF), false, mood, t)
        person(c, 60f, 100f, col(0xFF6D8B3A), true, mood, t)
        person(c, 78f, 98f, col(0xFFD46A9F), false, mood, t)
        val cowBody = mix(col(0xFFBDB8A0), white, health)
        r(c, 86, 99, 7, 3.5f, cowBody)
        r(c, 88, 99.5f, 2, 1.5f, col(0xFF3E2723))
        r(c, 93, 98, 3, 3, cowBody)
        r(c, 87, 102.5f, 1, 2, col(0xFF3E2723))
        r(c, 91, 102.5f, 1, 2, col(0xFF3E2723))
        r(c, 54, 102, 2, 2, white)
        r(c, 56, 101, 1, 1, col(0xFFFF5252))

        val moodText = if (mood > 0) "HAPPY" else if (mood < 0) "SAD" else "OK"
        txt(c, "VILLAGE: $moodText", 71f, 119.5f, 3.2f, white)

        postInvalidateDelayed(66L)
    }
}
