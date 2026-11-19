package org.gcreco.tracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private fun gc(rgb: Int): Int = rgb or -0x1000000

private fun gmix(a: Int, b: Int, k: Float): Int {
    val f = k.coerceIn(0f, 1f)
    val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt()
    val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt()
    val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
    return Color.rgb(r, g, bl)
}

private fun ghash(a: Int): Int {
    var h = a * 374761393
    h = (h xor (h ushr 13)) * 1274126177
    return (h xor (h ushr 16)) and 0x7fffffff
}

private val G_WHITE = gc(0xFFFFFF)
private val G_BLACK = gc(0x000000)

/**
 * Pixel-art scene. Drawn at a small logical resolution and scaled up by a whole number,
 * so every pixel in the scene has the same size.
 *
 * MainActivity API: update(dam, aiActiveUntil, todayMinutes), playRestore(), setStats(...).
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class FloatText(val s: String, val x: Int, val y: Int, val born: Float, val col: Int)
    private class Villager(
        var x: Float, var dir: Int, val shirt: Int, val hair: Int, val skin: Int,
        val phase: Float, val speed: Float
    )
    private class Puff(var x: Float, var y: Float, val vx: Float, val vy: Float, val life: Float, val kind: Int) {
        var age = 0f
    }

    // ---------- game state ----------
    private var targetDam = 100f
    private var shownDam = 100f
    private var health = 1f
    private var happy = 1f
    private var aiUntil = 0L
    private var todayMin = 0L
    private var dedToday = 0f
    private var resToday = 0f
    private var ovfToday = 0f
    private var quizLeft = 3
    private var gotFirstUpdate = false
    private var gotStats = false
    private var restoreUntil = -1f
    private var shockUntil = -1f

    // ---------- clock ----------
    private var t = 0f
    private var lastNs = 0L
    private var hourNow = 12f
    private var lastClock = -99f

    // ---------- buffers and layout ----------
    private var lw = 240
    private var lh = 140
    private var scale = 4
    private lateinit var buf: Bitmap
    private lateinit var cv: Canvas
    private val dst = Rect()

    private var groundY = 70
    private var damX0 = 60
    private var damX1 = 82
    private var wallTop = 30
    private var lakeTop = 46
    private var bedY = 82
    private var facX0 = 170
    private var facBase = 78
    private var pipeY = 54
    private var chanY = 92
    private var pathY = 84
    private var feetY = 86
    private var hudY = 126
    private var vx0 = 100
    private var vx1 = 160
    private var houseN = 3

    private var stX = IntArray(0)
    private var stY = IntArray(0)
    private var tfX = IntArray(0)
    private var tfY = IntArray(0)
    private var crX = IntArray(0)
    private var crY = IntArray(0)

    private val vills = ArrayList<Villager>()
    private val puffs = ArrayList<Puff>()
    private val floats = ArrayList<FloatText>()
    private val rnd = Random(11)
    private var accSteam = 0f
    private var accSmoke = 0f
    private var accDust = 0f
    private var accSplash = 0f

    // per-frame values
    private var nightAmt = 0f
    private var skyBotCache = 0
    private var sunX = 0
    private var sunY = 0
    private var celestial = 0
    private val lights = IntArray(5 * 260)
    private var lightN = 0

    private val dry: Float get() = 1f - health

    // ---------- paints ----------
    private val p = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val gp = Paint().apply {
        isAntiAlias = false
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val tintP = Paint().apply {
        isAntiAlias = false
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }
    private val tp = Paint().apply {
        isAntiAlias = false
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val bp = Paint().apply { isFilterBitmap = false; isAntiAlias = false }

    // =====================================================================
    //  public API
    // =====================================================================

    fun update(dam: Float, aiActiveUntil: Long, todayMinutes: Long) {
        targetDam = dam.coerceIn(0f, 100f)
        aiUntil = aiActiveUntil
        todayMin = todayMinutes
        if (!gotFirstUpdate) {
            gotFirstUpdate = true
            shownDam = targetDam
            health = targetDam / 100f
            happy = health
        }
        invalidate()
    }

    fun setStats(deducted: Float, restored: Float, overflow: Float, quizzesLeft: Int, todayMinutes: Long) {
        if (gotStats && lh > 0) {
            if (deducted > dedToday + 0.05f) {
                val d = (deducted - dedToday).roundToInt().coerceAtLeast(1)
                floats.add(FloatText("-$d%", damX0 + 11, wallTop + 20, t, gc(0xFF6B5E)))
                shockUntil = t + 4f
            }
            if (restored > resToday + 0.05f) {
                val d = (restored - resToday).roundToInt().coerceAtLeast(1)
                floats.add(FloatText("+$d%", max(6, damX0 / 2 - 6), lakeTop + 4, t, gc(0x6BFF8A)))
            }
        }
        gotStats = true
        dedToday = deducted
        resToday = restored
        ovfToday = overflow
        quizLeft = quizzesLeft
        todayMin = todayMinutes
        invalidate()
    }

    fun playRestore() {
        restoreUntil = t + 4.5f
        invalidate()
    }

    // =====================================================================
    //  size / layout
    // =====================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        scale = max(2, (w / 240f).roundToInt())
        lw = max(160, w / scale)
        lh = (h / scale).coerceIn(110, 260)
        buf = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
        cv = Canvas(buf)
        layout()
    }

    private fun layout() {
        groundY = (lh * 0.50f).toInt()
        damX0 = (lw * 0.27f).toInt()
        damX1 = damX0 + 22
        wallTop = groundY - 40
        lakeTop = groundY - 24
        bedY = groundY + 12
        facX0 = lw - 62
        facBase = groundY + 8
        pipeY = groundY - 16
        chanY = groundY + 22
        pathY = groundY + 14
        feetY = groundY + 16
        hudY = lh - 14
        vx0 = damX1 + 18
        vx1 = facX0 - 8
        houseN = ((vx1 - vx0) / 14).coerceIn(2, 4)

        stX = IntArray(40) { ghash(it * 7 + 3) % lw }
        stY = IntArray(40) { ghash(it * 11 + 5) % max(1, (groundY * 0.7f).toInt()) }
        tfX = IntArray(70) { ghash(it * 3 + 1) % lw }
        tfY = IntArray(70) { groundY + 3 + ghash(it * 5 + 2) % max(1, lh - groundY - 20) }
        crX = IntArray(14) { ghash(it * 13 + 9) % lw }
        crY = IntArray(14) { groundY + 10 + ghash(it * 17 + 4) % max(1, lh - groundY - 28) }

        val shirts = intArrayOf(gc(0xD9534F), gc(0x4F8FD9), gc(0xE8B84A), gc(0x7B5BD6), gc(0x4FB38A))
        val hairs = intArrayOf(gc(0x3B2A1E), gc(0xC9A24A), gc(0x1E1E26), gc(0x8A4B2A), gc(0xB8B8C0))
        val skins = intArrayOf(gc(0xF2C9A0), gc(0xD9A577), gc(0x8D5B3A), gc(0xF5D3B0), gc(0xB57A50))
        vills.clear()
        val n = ((vx1 - vx0) / 15).coerceIn(2, 5)
        for (i in 0 until n) {
            vills.add(
                Villager(
                    vx0 + rnd.nextFloat() * (vx1 - vx0), if (i % 2 == 0) 1 else -1,
                    shirts[i], hairs[i], skins[i], rnd.nextFloat(), 6f + rnd.nextFloat() * 4f
                )
            )
        }
    }

    private fun houseX(i: Int) = vx0 + i * ((vx1 - vx0) / houseN) + 1
    private fun houseW(i: Int) = 10 + (i % 2) * 2
    private fun houseH(i: Int) = 11 + (i % 3)

    // =====================================================================
    //  frame loop
    // =====================================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!::buf.isInitialized) return
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.033f else ((now - lastNs) / 1e9f).coerceAtMost(0.05f)
        lastNs = now
        t += dt
        step(dt)
        render()
        val sw = lw * scale
        val sh = lh * scale
        val ox = (width - sw) / 2
        val oy = (height - sh) / 2
        canvas.drawColor(Color.BLACK)
        dst.set(ox, oy, ox + sw, oy + sh)
        canvas.drawBitmap(buf, null, dst, bp)
        postInvalidateDelayed(33)
    }

    private fun mood(): Int = when {
        t < shockUntil -> 3
        health > 0.6f -> 0
        health > 0.3f -> 1
        else -> 2
    }

    private fun step(dt: Float) {
        shownDam += (targetDam - shownDam) * min(1f, dt * 1.6f)
        if (abs(targetDam - shownDam) < 0.05f) shownDam = targetDam
        val goal = targetDam / 100f
        val rate = if (goal < health) 0.045f else 0.04f
        health += (goal - health).coerceIn(-rate * dt, rate * dt)
        happy += (health - happy) * min(1f, dt * 0.8f)

        val shock = t < shockUntil
        for (v in vills) {
            if (shock) { v.dir = -1; continue }
            v.x += v.dir * v.speed * (0.35f + 0.65f * health) * dt
            if (v.x < vx0) { v.x = vx0.toFloat(); v.dir = 1 }
            if (v.x > vx1) { v.x = vx1.toFloat(); v.dir = -1 }
        }

        val active = System.currentTimeMillis() < aiUntil
        accSteam += dt * (if (active) 16f else 5f) * (0.5f + 0.5f * shownDam / 100f)
        while (accSteam >= 1f) {
            accSteam -= 1f
            val cx = if (rnd.nextBoolean()) facX0 + 43 else facX0 + 54
            puff(0, cx + rnd.nextFloat() * 3f - 1.5f, (groundY - 17).toFloat(),
                2f + rnd.nextFloat() * 3f, -(8f + rnd.nextFloat() * 6f), 1.8f + rnd.nextFloat())
        }
        if (health > 0.25f) {
            accSmoke += dt * 0.5f * houseN
            while (accSmoke >= 1f) {
                accSmoke -= 1f
                val i = rnd.nextInt(houseN)
                puff(1, (houseX(i) + houseW(i) - 3).toFloat(), (groundY + 10 - houseH(i) - 7).toFloat(),
                    3f + rnd.nextFloat() * 2f, -(5f + rnd.nextFloat() * 3f), 2f + rnd.nextFloat())
            }
        }
        if (health < 0.55f) {
            accDust += dt * (0.55f - health) * 26f
            while (accDust >= 1f) {
                accDust -= 1f
                puff(2, -2f, groundY + 4f + rnd.nextFloat() * (lh - groundY - 22),
                    12f + rnd.nextFloat() * 14f, rnd.nextFloat() * 3f - 1.5f, 6f)
            }
        }
        if (t < restoreUntil) {
            accSplash += dt * 30f
            val surf = surfY()
            while (accSplash >= 1f) {
                accSplash -= 1f
                puff(3, 13f + rnd.nextFloat() * 3f, surf.toFloat(),
                    rnd.nextFloat() * 16f - 8f, -(10f + rnd.nextFloat() * 10f), 0.5f)
            }
        }
        val it = puffs.iterator()
        while (it.hasNext()) {
            val pf = it.next()
            pf.age += dt
            if (pf.age >= pf.life) { it.remove(); continue }
            pf.x += pf.vx * dt
            pf.y += pf.vy * dt
        }
        val fi = floats.iterator()
        while (fi.hasNext()) { if (t - fi.next().born > 3.5f) fi.remove() }
    }

    private fun puff(kind: Int, x: Float, y: Float, vx: Float, vy: Float, life: Float) {
        if (puffs.size < 140) puffs.add(Puff(x, y, vx, vy, life, kind))
    }

    // =====================================================================
    //  low-level drawing
    // =====================================================================

    private fun fill(x: Int, y: Int, w: Int, h: Int, col: Int) {
        if (w <= 0 || h <= 0) return
        p.color = col
        cv.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), p)
    }

    private fun fillA(x: Int, y: Int, w: Int, h: Int, col: Int, a: Int) {
        if (w <= 0 || h <= 0) return
        p.color = col
        p.alpha = a.coerceIn(0, 255)
        cv.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), p)
    }

    private fun px(x: Int, y: Int, col: Int) = fill(x, y, 1, 1, col)

    private fun disc(cx: Int, cy: Int, r: Int, col: Int) {
        for (dy in -r..r) {
            val w = sqrt((r * r - dy * dy).toDouble() + 0.5).toInt()
            fill(cx - w, cy + dy, 2 * w + 1, 1, col)
        }
    }

    private fun glowDisc(cx: Int, cy: Int, r: Int, col: Int, a: Int) {
        gp.color = col
        gp.alpha = a.coerceIn(0, 255)
        var k = r
        while (k >= 1) {
            for (dy in -k..k) {
                val w = sqrt((k * k - dy * dy).toDouble() + 0.5).toInt()
                cv.drawRect((cx - w).toFloat(), (cy + dy).toFloat(), (cx + w + 1).toFloat(), (cy + dy + 1).toFloat(), gp)
            }
            k -= 2
        }
    }

    private fun glowRect(x: Int, y: Int, w: Int, h: Int, col: Int, a: Int) {
        gp.color = col
        gp.alpha = a.coerceIn(0, 255)
        cv.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), gp)
    }

    private fun addLight(x: Int, y: Int, w: Int, h: Int, col: Int) {
        if (lightN >= 260) return
        val b = lightN * 5
        lights[b] = x; lights[b + 1] = y; lights[b + 2] = w; lights[b + 3] = h; lights[b + 4] = col
        lightN++
    }

    private fun spr(rows: Array<String>, x: Int, y: Int, col: Int) {
        for (r in rows.indices) {
            val s = rows[r]
            for (i in s.indices) if (s[i] == 'X') px(x + i, y + r, col)
        }
    }

    private fun txt(s: String, x: Float, y: Float, size: Float, col: Int, shadow: Boolean = true) {
        tp.textSize = size
        if (shadow) {
            tp.color = gc(0x0A0F1B)
            cv.drawText(s, x + 1f, y + 1f, tp)
        }
        tp.color = col
        cv.drawText(s, x, y, tp)
    }

    private fun txtC(s: String, cx: Float, y: Float, size: Float, col: Int, shadow: Boolean = true) {
        tp.textSize = size
        txt(s, cx - tp.measureText(s) / 2f, y, size, col, shadow)
    }

    private fun panel(x: Int, y: Int, w: Int, h: Int) {
        fill(x, y, w, h, gc(0x141B2D))
        fill(x, y, w, 1, gc(0x4A5C86))
        fill(x, y, 1, h, gc(0x3B4B70))
        fill(x, y + h - 1, w, 1, gc(0x080C16))
        fill(x + w - 1, y, 1, h, gc(0x080C16))
        px(x + 1, y + 1, gc(0x6C82B5))
    }

    // =====================================================================
    //  time of day
    // =====================================================================

    private fun clockUpdate() {
        if (t - lastClock < 20f) return
        lastClock = t
        val cal = Calendar.getInstance()
        hourNow = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
    }

    private fun keyed(arr: IntArray): Int {
        var i = 0
        while (i < K_H.size - 2 && hourNow >= K_H[i + 1]) i++
        val f = (hourNow - K_H[i]) / (K_H[i + 1] - K_H[i])
        return gmix(arr[i], arr[i + 1], f)
    }

    private fun nightAmount(): Float {
        val h = hourNow
        return when {
            h < 5f || h >= 20f -> 1f
            h < 6.5f -> 1f - (h - 5f) / 1.5f
            h >= 18.5f -> (h - 18.5f) / 1.5f
            else -> 0f
        }
    }

    // =====================================================================
    //  render
    // =====================================================================

    private fun render() {
        clockUpdate()
        nightAmt = nightAmount()
        val active = System.currentTimeMillis() < aiUntil
        lightN = 0
        val top = gmix(keyed(K_TOP), gc(0x9C8F78), dry * 0.2f)
        val bot = gmix(keyed(K_BOT), gc(0xD9B98C), dry * 0.35f)
        skyBotCache = bot

        drawSky(top, bot)
        drawStars()
        drawSunMoon()
        drawClouds()
        drawBirds()
        drawMountains(bot)
        drawGround()
        drawLake()
        drawDam()
        drawPipeline(active)
        drawTrees()
        drawVillage()
        drawFacility(active)
        drawPath()
        drawChannel()
        drawFields()
        drawAnimals()
        for (v in vills) drawVillager(v)
        drawPuffs()
        drawRain()

        tintP.color = gmix(keyed(K_AMB), gc(0xFFE7B8), dry * 0.25f)
        cv.drawRect(0f, 0f, lw.toFloat(), lh.toFloat(), tintP)

        drawLightsPass(active)
        drawHud()
        drawInfoPanel()
        drawFloats()
    }

    // ---------- sky ----------

    private fun drawSky(top: Int, bot: Int) {
        val steps = 16
        var prev = -1
        for (y in 0 until groundY) {
            val s = y * steps / groundY
            fill(0, y, lw, 1, gmix(top, bot, s / (steps - 1f)))
            if (prev >= 0 && s != prev) {
                val cp = gmix(top, bot, prev / (steps - 1f))
                var x = y and 1
                while (x < lw) { px(x, y, cp); x += 2 }
            }
            prev = s
        }
    }

    private fun drawStars() {
        if (nightAmt <= 0.05f) return
        for (i in stX.indices) {
            val tw = sin(t * 2f + i) * 0.5f + 0.5f
            val a = (nightAmt * (110f + 145f * tw)).toInt()
            fillA(stX[i], stY[i], 1, 1, G_WHITE, a)
            if (i % 7 == 0) fillA(stX[i] + 1, stY[i], 1, 1, gc(0xBFD4FF), a / 2)
        }
    }

    private fun drawSunMoon() {
        val h = hourNow
        celestial = 0
        if (h >= 5.5f && h <= 19f) {
            val f = (h - 5.5f) / 13.5f
            sunX = (f * lw).toInt()
            sunY = groundY - (sin(f * PI.toFloat()) * groundY * 0.72f).toInt() - 2
            celestial = 1
            disc(sunX, sunY, 4, gc(0xFFE9A6))
            disc(sunX, sunY, 3, gc(0xFFF6D0))
        } else {
            val h2 = if (h >= 19f) h - 19f else h + 5f
            val f = (h2 / 10.5f).coerceIn(0f, 1f)
            sunX = (f * lw).toInt()
            sunY = groundY - (sin(f * PI.toFloat()) * groundY * 0.7f).toInt() - 2
            celestial = 2
            disc(sunX, sunY, 3, gc(0xE6ECFF))
            px(sunX - 1, sunY, gc(0xC4CCE8))
            px(sunX + 1, sunY + 1, gc(0xC4CCE8))
        }
    }

    private fun drawClouds() {
        val n = 2 + (health * 3f).toInt()
        val hi = gmix(G_WHITE, gc(0xE8DCC0), dry * 0.6f)
        val mid = gmix(gc(0xEEF3FA), gc(0xD9C8A4), dry * 0.6f)
        val sh = gmix(gc(0xC3CFE2), gc(0xB39F7C), dry * 0.6f)
        for (i in 0 until n) {
            val shape = CLOUDS[i % CLOUDS.size]
            val wid = shape[0].length
            val span = lw + wid + 20
            val x = (((CL_X[i] + t * CL_V[i]) % span) - wid - 10).toInt()
            val y = (groundY * CL_Y[i]).toInt()
            for (r in shape.indices) {
                val col = if (r == 0) hi else if (r == shape.size - 1) sh else mid
                val row = shape[r]
                for (k in row.indices) if (row[k] == 'X') px(x + k, y + r, col)
            }
        }
    }

    private fun drawBirds() {
        if (nightAmt > 0.5f || health < 0.4f) return
        val col = gc(0x2B3445)
        for (i in 0 until 3) {
            val bx = (((t * 14f + i * 70f) % (lw + 20)) - 10).toInt()
            val by = (groundY * 0.3f + sin(t * 1.4f + i) * 4f + i * 5f).toInt()
            if (((t * 6f + i) % 1f) < 0.5f) {
                px(bx - 1, by, col); px(bx - 2, by - 1, col); px(bx + 1, by, col); px(bx + 2, by - 1, col)
            } else {
                px(bx - 1, by, col); px(bx - 2, by + 1, col); px(bx + 1, by, col); px(bx + 2, by + 1, col)
            }
            px(bx, by, col)
        }
    }

    // ---------- landscape ----------

    private fun mtnH(layer: Int, x: Float): Float {
        val f = MT_F[layer]
        val ph = MT_PH[layer]
        return MT_AMP[layer] * (0.50f + 0.32f * sin(x * f + ph) + 0.18f * sin(x * f * 2.6f + ph * 1.7f))
    }

    private fun drawMountains(skyBot: Int) {
        val sway = sin(t * 0.05f) * 5f
        val far = gmix(gmix(gc(0x9FB4D8), skyBot, 0.3f), gc(0xC9AE86), dry * 0.5f)
        val mid = gmix(gmix(gc(0x7E9AC0), skyBot, 0.15f), gc(0xB09870), dry * 0.5f)
        val near = gmix(gc(0x5E8E74), gc(0x9B8157), dry)
        val cols = intArrayOf(far, mid, near)
        for (layer in 0..2) {
            val col = cols[layer]
            val light = gmix(col, G_WHITE, 0.3f)
            val dark = gmix(col, G_BLACK, 0.25f)
            for (x in 0 until lw) {
                val xx = x + sway * MT_PAR[layer]
                val hg = mtnH(layer, xx)
                val top = groundY - hg.toInt()
                fill(x, top, 1, groundY - top, col)
                px(x, top, light)
                if (mtnH(layer, xx + 1f) < hg - 0.35f) fill(x, top + 1, 1, 3, dark)
                if (layer == 0 && hg > MT_AMP[0] * 0.82f) fill(x, top, 1, 2, gc(0xF2F6FF))
            }
        }
    }

    private fun drawGround() {
        val hi = gmix(gc(0x86CC52), gc(0xD0AC62), dry)
        val mid = gmix(gc(0x58A23E), gc(0xB08A52), dry)
        val dk = gmix(gc(0x356F36), gc(0x7D5F38), dry)
        fill(0, groundY, lw, lh - groundY, mid)
        fill(0, groundY, lw, 2, hi)
        var y = groundY + 9
        var row = 0
        while (y < lh) {
            var x = row and 1
            while (x < lw) { px(x, y, dk); x += 2 }
            y += 12
            row++
        }
        val n = (70 * (0.4f + 0.6f * health)).toInt()
        for (i in 0 until min(n, tfX.size)) {
            fill(tfX[i], tfY[i], 1, 2, hi)
            px(tfX[i] + 1, tfY[i] + 1, dk)
        }
        if (health < 0.5f) {
            val a = ((0.5f - health) * 2f * 200f).toInt()
            val crack = gc(0x5A3E22)
            for (i in crX.indices) {
                var x = crX[i]
                var yy = crY[i]
                for (s in 0 until 7) {
                    fillA(x, yy, 1, 1, crack, a)
                    x += (ghash(i * 7 + s) % 3) - 1
                    yy += 1
                }
            }
        }
    }

    private fun surfY(): Int = lakeTop + ((100f - shownDam) / 100f * (bedY - lakeTop)).roundToInt()

    private fun drawLake() {
        val surf = surfY()
        val alg = (1f - shownDam / 100f) * 0.45f
        val cA = gmix(gc(0x9AD9F5), skyBotCache, 0.35f)
        val cB = gmix(gc(0x4DB4E6), gc(0x7A9A5A), alg)
        val cC = gmix(gc(0x2F8FCF), gc(0x587A4A), alg)
        val cD = gmix(gc(0x1F6AA8), gc(0x3F5F3F), alg)
        for (x in 0 until damX0) {
            val off = (sin(x * 0.42f + t * 2.6f) + sin(x * 0.13f - t * 1.4f) * 0.9f).roundToInt()
            val y0 = surf + off
            if (y0 >= bedY) continue
            fill(x, y0, 1, min(y0 + 2, bedY) - y0, cA)
            fill(x, y0 + 2, 1, min(y0 + 6, bedY) - (y0 + 2), cB)
            fill(x, y0 + 6, 1, min(y0 + 12, bedY) - (y0 + 6), cC)
            fill(x, y0 + 12, 1, bedY - (y0 + 12), cD)
            if (off < 0) px(x, y0, gc(0xEAF8FF))
        }
        if (surf < bedY - 2) {
            for (k in 0 until 8) {
                val gx = ghash(k * 13) % damX0
                val gy = surf + 2 + ghash(k * 17) % 6
                if (sin(t * 4f + k * 1.7f) > 0.75f && gy < bedY) px(gx, gy, G_WHITE)
            }
        }
        if (bedY - surf > 12) {
            val span = (damX0 - 26).toFloat()
            for (k in 0 until 3) {
                val dk = if (k % 2 == 0) 1 else -1
                var u = (t * 5f * dk + k * 23f) % span
                if (u < 0f) u += span
                val fx = 12 + u.toInt()
                val fy = surf + 8 + k * 4 + (sin(t + k) * 1.5f).toInt()
                if (fy < bedY - 3) {
                    fill(fx, fy, 3, 1, gc(0xFF9A4D))
                    val tail = if (dk > 0) fx - 1 else fx + 3
                    px(tail, fy - 1, gc(0xFF9A4D)); px(tail, fy + 1, gc(0xFF9A4D))
                    px(if (dk > 0) fx + 2 else fx, fy, G_WHITE)
                }
            }
        }
        if (t < restoreUntil) {
            val top = lakeTop - 20
            fill(8, top, 8, 3, gc(0x6B717C))
            fill(8, top, 8, 1, gc(0x9AA0AC))
            val al = (255 * ((restoreUntil - t) / 0.8f).coerceIn(0f, 1f)).toInt()
            var yy = top + 3
            while (yy < surf) {
                val ph = (yy + (t * 45f).toInt()) % 6
                fillA(13, yy, 3, 1, if (ph < 3) gc(0xCFF1FF) else gc(0x6EC1EE), al)
                yy++
            }
        }
        val mud = gmix(gc(0x6B5A44), gc(0xA88A5E), dry)
        fill(0, bedY, damX0, 4, mud)
        for (k in 0 until 9) px(ghash(k * 5) % damX0, bedY + ghash(k * 3) % 3, gc(0x4A3D2E))
        if (shownDam < 20f) {
            val a = ((20f - shownDam) / 20f * 220f).toInt()
            for (k in 0 until 7) {
                var x = 14 + ghash(k * 19) % max(1, damX0 - 20)
                for (s in 0 until 6) {
                    fillA(x, bedY + (s % 2), 1, 1, gc(0x3A2A18), a)
                    x += 1
                }
            }
        }
        for (x in 0 until 11) {
            val hg = (11 - x) * (bedY - (lakeTop - 12)) / 11
            val rock = if ((x + hg) % 3 == 0) gc(0x7A7F8A) else gc(0x666B76)
            fill(x, bedY - hg, 1, hg, rock)
            px(x, bedY - hg, if (health > 0.4f) gc(0x5A9A44) else gc(0x9AA0AC))
        }
    }

    // ---------- dam ----------

    private fun drawDam() {
        val x0 = damX0
        val x1 = damX1
        val dw = x1 - x0
        val surf = surfY()
        val wallBot = bedY + 3
        for (i in 0..3) {
            val top = wallTop + 8 + i * ((bedY - wallTop - 8) / 4)
            fill(x1, top, (i + 1) * 2, wallBot - top, gc(0x8E949D))
            fill(x1, top, (i + 1) * 2, 1, gc(0xB9BFC7))
            fill(x1 + (i + 1) * 2 - 1, top + 1, 1, wallBot - top - 1, gc(0x6E747D))
        }
        fill(x0, wallTop, dw, wallBot - wallTop, gc(0xA2A8B1))
        var y = wallTop
        var row = 0
        while (y < wallBot) {
            fill(x0, y, dw, 1, gc(0x7F858E))
            var bx = x0 + (if (row % 2 == 0) 0 else 4)
            while (bx < x1) {
                if (bx > x0) fill(bx, y + 1, 1, 3, gc(0x8A9099))
                if ((row * 7 + (bx - x0) / 8 * 13) % 5 == 0) {
                    fill(bx + 1, y + 1, min(6, x1 - bx - 1), 3, gc(0xAFB5BE))
                }
                bx += 8
            }
            y += 4
            row++
        }
        fill(x0, wallTop, 1, wallBot - wallTop, gc(0xC9CED6))
        fill(x1 - 2, wallTop, 2, wallBot - wallTop, gc(0x838992))
        if (surf < bedY) fillA(x0, surf, 3, wallBot - surf, gc(0x1F4F7A), 110)

        // walkway, railing
        fill(x0 - 3, wallTop - 3, dw + 6, 3, gc(0x8E949D))
        fill(x0 - 3, wallTop - 3, dw + 6, 1, gc(0xC9CED6))
        fill(x0 - 3, wallTop - 1, dw + 6, 1, gc(0x6E747D))
        var rx = x0 - 2
        while (rx < x1 + 3) { fill(rx, wallTop - 7, 1, 4, gc(0x3E4552)); rx += 4 }
        fill(x0 - 3, wallTop - 7, dw + 6, 1, gc(0x3E4552))
        // control hut and lamp
        fill(x1 - 9, wallTop - 12, 8, 9, gc(0xD7CBB0))
        fill(x1 - 9, wallTop - 12, 1, 9, gc(0xEFE6CF))
        fill(x1 - 10, wallTop - 14, 10, 2, gc(0x8C3F36))
        fill(x1 - 7, wallTop - 9, 3, 3, gc(0x9AD0EE))
        if (nightAmt > 0.3f) addLight(x1 - 7, wallTop - 9, 3, 3, gc(0xFFD98A))
        fill(x0 + 2, wallTop - 14, 1, 11, gc(0x3E4552))
        fill(x0 + 1, wallTop - 15, 3, 2, gc(0xFFE08A))
        if (nightAmt > 0.3f) addLight(x0 + 1, wallTop - 15, 3, 2, gc(0xFFF0B0))

        // level gauge on the wall
        val gx = x1 - 7
        val lvl = if (shownDam > 60f) gc(0x56D96B) else if (shownDam > 30f) gc(0xF2C94C) else gc(0xE5534B)
        fill(gx, lakeTop, 4, bedY - lakeTop, gc(0x1A2236))
        val sy = surf.coerceIn(lakeTop, bedY)
        fill(gx + 1, sy, 2, bedY - sy, lvl)
        for (k in 0..4) fill(gx - 1, lakeTop + k * (bedY - lakeTop) / 4, 6, 1, gc(0xE6ECF5))
        fill(gx - 4, sy, 3, 1, G_WHITE)
        px(gx - 3, sy - 1, G_WHITE)
        px(gx - 3, sy + 1, G_WHITE)

        // sluice gates and outlet
        for (g in 0..1) {
            val gx2 = x0 + 3 + g * 8
            fill(gx2, bedY - 9, 5, 9, gc(0x2B3340))
            fill(gx2 - 1, bedY - 10, 7, 1, gc(0x59627A))
            var bx = gx2 + 1
            while (bx < gx2 + 5) { fill(bx, bedY - 8, 1, 8, gc(0x59627A)); bx += 2 }
        }
        if (shownDam > 0.5f) {
            val ox = x1 + 8
            fill(ox, bedY - 4, 5, 3, gc(0x6E747D))
            val fx = ox + 5
            val fallBot = chanY
            var yy = bedY - 4
            while (yy < fallBot) {
                val ph = (yy + (t * 30f).toInt()) % 4
                fill(fx, yy, 3, 1, if (ph < 2) gc(0xCFF1FF) else gc(0x5FB8EE))
                yy++
            }
        }
    }

    private fun drawPipeline(active: Boolean) {
        val x0 = damX1
        val x1 = facX0
        val len = x1 - x0
        var sx = x0 + 10
        while (sx < x1 - 4) {
            fill(sx, pipeY + 3, 2, groundY + 10 - pipeY - 3, gc(0x6B7280))
            fill(sx, pipeY + 3, 1, groundY + 10 - pipeY - 3, gc(0x8E96A6))
            sx += 24
        }
        fill(x0, pipeY - 1, len, 5, gc(0x3A4658))
        fill(x0, pipeY, len, 3, gc(0x1B2536))
        fill(x0, pipeY - 1, len, 1, gc(0x8FA0B8))
        var jx = x0 + 12
        while (jx < x1) { fill(jx, pipeY - 2, 2, 7, gc(0x56627D)); jx += 20 }
        fill(x0 - 1, pipeY - 3, 4, 9, gc(0x56627D))
        if (shownDam > 0.5f) {
            fill(x0, pipeY + 1, len, 1, gmix(gc(0x2B7FC4), gc(0x57D7FF), if (active) 1f else 0.3f))
            val sp = if (active) 46f else 9f
            val n = max(1, len / 9)
            for (i in 0 until n) {
                val dx = ((t * sp + i * 9f) % len).toInt()
                fill(x0 + dx, pipeY + 1, 2, 1, gc(0xDFF8FF))
            }
        }
        fill(facX0 - 2, pipeY - 2, 3, 7, gc(0x56627D))
    }

    // ---------- village ----------

    private fun tree(x: Int, base: Int, hgt: Int, seed: Int) {
        val trunk = gc(0x5B3A21)
        fill(x, base - hgt / 2, 2, hgt / 2, trunk)
        fill(x, base - hgt / 2, 1, hgt / 2, gc(0x7A5233))
        val sw = (sin(t * 1.2f + seed) * 0.6f).roundToInt()
        val hi = gmix(gc(0x5DBB4A), gc(0xB5984E), dry)
        val mid = gmix(gc(0x3F9B3F), gc(0x8C7A3A), dry)
        val dk = gmix(gc(0x2A7035), gc(0x6C5A2E), dry)
        if (health > 0.18f) {
            val start = if (health < 0.45f) 1 else 0
            for (k in start..2) {
                val w = 9 - k * 2
                val y = base - hgt + k * 3
                val lx = x + 1 - w / 2 + sw
                fill(lx, y, w, 3, mid)
                fill(lx, y, w, 1, hi)
                fill(lx + w - 2, y + 1, 2, 2, dk)
            }
        } else {
            val bare = gc(0x4A3322)
            fill(x, base - hgt, 1, hgt / 2, bare)
            px(x - 1, base - hgt + 1, bare); px(x - 2, base - hgt, bare)
            px(x + 2, base - hgt + 2, bare); px(x + 3, base - hgt + 1, bare)
        }
    }

    private fun drawTrees() {
        tree(damX1 + 6, groundY + 11, 16, 1)
        tree(vx1 + 2, groundY + 11, 18, 2)
        if (lw > 200) tree(damX1 + 52, groundY + 11, 14, 3)
    }

    private fun house(x: Int, base: Int, w: Int, h: Int, wall: Int, roof: Int) {
        val top = base - h
        fill(x, top, w, h, wall)
        fill(x, top, 1, h, gmix(wall, G_WHITE, 0.35f))
        fill(x + w - 2, top, 2, h, gmix(wall, G_BLACK, 0.25f))
        fill(x, base - 2, w, 2, gc(0x7A7F86))
        val rows = w / 2 + 2
        for (r in 0 until rows) {
            val rw = w + 4 - r * 2
            if (rw <= 0) break
            fill(x - 2 + r, top - 1 - r, rw, 1, if (r % 2 == 0) roof else gmix(roof, G_BLACK, 0.18f))
            px(x - 2 + r, top - 1 - r, gmix(roof, G_WHITE, 0.35f))
        }
        fillA(x, top, w, 1, G_BLACK, 90)
        fill(x + 2, base - 7, 3, 5, gc(0x5B3A21))
        px(x + 4, base - 5, gc(0xE8C33A))
        val wx = x + w - 6
        fill(wx, top + 3, 4, 4, gc(0x4A3A2A))
        fill(wx + 1, top + 4, 2, 2, gc(0x9AD0EE))
        px(wx + 1, top + 4, G_WHITE)
        if (nightAmt > 0.3f) addLight(wx + 1, top + 4, 2, 2, gc(0xFFD98A))
        fill(x + w - 5, top - 6, 2, 4, gc(0x8A5A44))
    }

    private fun drawVillage() {
        for (i in 0 until houseN) {
            house(houseX(i), groundY + 10, houseW(i), houseH(i), WALLS[i % 4], ROOFS[i % 4])
        }
        if (health > 0.5f) {
            for (i in 0 until 8) {
                val fx = vx0 + i * 9
                if (fx < vx1 + 6) px(fx, groundY + 12, if (i % 2 == 0) gc(0xFF8FB1) else gc(0xFFE066))
            }
        }
    }

    private fun drawPath() {
        val dust = gmix(gc(0xC9AA7C), gc(0xB99869), dry)
        fill(vx0 - 6, pathY, vx1 - vx0 + 12, 3, dust)
        fill(vx0 - 6, pathY, vx1 - vx0 + 12, 1, gmix(dust, G_WHITE, 0.25f))
        for (i in 0 until 10) px(vx0 - 4 + ghash(i * 5) % max(1, vx1 - vx0 + 8), pathY + 1 + i % 2, gc(0x8E7650))
    }

    // ---------- villagers and animals ----------

    private fun drawVillager(v: Villager) {
        val x = v.x.roundToInt()
        val md = mood()
        val shock = t < shockUntil
        val joy = t < restoreUntil
        val moving = !shock
        val stepA = ((t * 4f + v.phase * 3f) % 1f) < 0.5f
        val hop = if (joy) (abs(sin(t * 7f + v.phase * 6f)) * 3f).toInt() else 0
        val y = feetY - hop
        val bob = if (moving && !stepA && !joy) 1 else 0
        val slump = if (md == 2 && !joy) 1 else 0
        val pants = gc(0x3B4A6B)
        fillA(x - 2, feetY, 5, 1, G_BLACK, 70)
        if (moving && !joy) {
            if (stepA) { fill(x - 1, y - 2, 1, 2, pants); fill(x, y - 2, 1, 1, pants) }
            else { fill(x - 1, y - 2, 1, 1, pants); fill(x, y - 2, 1, 2, pants) }
        } else {
            fill(x - 1, y - 2, 1, 2, pants); fill(x, y - 2, 1, 2, pants)
        }
        px(x - 1, y - 1, gc(0x2B2118)); px(x, y - 1, gc(0x2B2118))
        fill(x - 2, y - 6 + bob, 4, 4, v.shirt)
        fill(x + 1, y - 6 + bob, 1, 4, gmix(v.shirt, G_BLACK, 0.25f))
        if (joy) {
            fill(x - 3, y - 9, 1, 3, v.skin); fill(x + 2, y - 9, 1, 3, v.skin)
        } else if (md == 2) {
            fill(x - 3, y - 6 + bob, 1, 3, v.skin); fill(x + 2, y - 6 + bob, 1, 3, v.skin)
        } else {
            fill(x - 3, y - 6 + bob, 1, if (stepA) 3 else 2, v.skin)
            fill(x + 2, y - 6 + bob, 1, if (stepA) 2 else 3, v.skin)
        }
        val hy = y - 10 + bob + slump
        fill(x - 2, hy, 4, 4, v.skin)
        fill(x - 2, hy, 4, 1, v.hair)
        if (v.dir > 0) px(x - 2, hy + 1, v.hair) else px(x + 1, hy + 1, v.hair)
        val eye = gc(0x1E1E26)
        px(x - 1, hy + 1, eye); px(x + 1, hy + 1, eye)
        if (md >= 2 || shock) { px(x - 1, hy, v.hair); px(x + 1, hy, v.hair) }
        when {
            joy || md == 0 -> fill(x - 1, hy + 2, 2, 1, gc(0xB5483A))
            md == 1 -> px(x, hy + 2, gc(0x9A5A4A))
            shock -> px(x, hy + 2, gc(0x6A2A2A))
            else -> px(x, hy + 2, gc(0x7A3A3A))
        }
        val by = y - 17
        if (shock) spr(S_EXCL, x - 1, by, gc(0xFFD65C))
        else if (joy) spr(S_HEART, x - 2, by, gc(0xFF5B7A))
        else if (md == 2 && ((t + v.phase * 10f) % 6f) < 2.5f) spr(S_DROP, x - 2, by, gc(0x6EC1EE))
    }

    private fun drawAnimals() {
        val white = gc(0xF4F1EA)
        for (i in 0 until 2) {
            val cx = vx0 + 6 + i * ((vx1 - vx0) / 2) + (sin(t * 0.5f + i * 2f) * 4f).toInt()
            val peck = ((t * 1.3f + i) % 1f) < 0.3f
            val cy = pathY
            fill(cx, cy - 3, 4, 2, white)
            px(cx - 1, cy - 4, white)
            val hx = cx + 4
            px(hx, cy - (if (peck) 2 else 4), white)
            px(hx, cy - (if (peck) 3 else 5), gc(0xD9453F))
            px(hx + 1, cy - (if (peck) 2 else 4), gc(0xF2B33D))
            px(cx + 1, cy - 1, gc(0xE8A33A)); px(cx + 2, cy - 1, gc(0xE8A33A))
        }
        val cowBase = groundY + 38
        if (cowBase <= hudY - 3 && damX0 > 40) {
            val rail = gc(0x8A6A44)
            var fx = 3
            while (fx < damX0 + 4) { fill(fx, groundY + 31, 1, 7, rail); fx += 9 }
            fill(3, groundY + 32, damX0 + 2, 1, rail)
            fill(3, groundY + 35, damX0 + 2, 1, rail)
            val rangeW = max(1, damX0 - 28)
            val u = sin(t * 0.2f) * 0.5f + 0.5f
            val cx = 8 + (u * rangeW).toInt()
            val dir = if (kotlin.math.cos(t * 0.2f) >= 0f) 1 else -1
            val graze = ((t * 0.5f) % 1f) < 0.6f
            drawCow(cx, cowBase, dir, graze)
        }
    }

    private fun drawCow(x: Int, y: Int, dir: Int, graze: Boolean) {
        val body = gc(0xF2EFE6)
        val patch = gc(0x6A4A34)
        val dk = gc(0x3A2E26)
        val hd = if (graze) 2 else 0
        if (dir > 0) {
            fill(x, y - 5, 8, 4, body)
            fill(x + 1, y - 5, 2, 2, patch); fill(x + 5, y - 4, 2, 2, patch)
            fill(x + 1, y - 1, 1, 2, dk); fill(x + 3, y - 1, 1, 2, dk)
            fill(x + 5, y - 1, 1, 2, dk); fill(x + 7, y - 1, 1, 2, dk)
            fill(x + 8, y - 6 + hd, 3, 3, body)
            px(x + 10, y - 4 + hd, gc(0xE8A0A0))
            px(x + 9, y - 7 + hd, dk); px(x + 8, y - 5 + hd, dk)
            fill(x - 1, y - 5, 1, 3, dk)
        } else {
            fill(x + 3, y - 5, 8, 4, body)
            fill(x + 8, y - 5, 2, 2, patch); fill(x + 4, y - 4, 2, 2, patch)
            fill(x + 4, y - 1, 1, 2, dk); fill(x + 6, y - 1, 1, 2, dk)
            fill(x + 8, y - 1, 1, 2, dk); fill(x + 10, y - 1, 1, 2, dk)
            fill(x, y - 6 + hd, 3, 3, body)
            px(x, y - 4 + hd, gc(0xE8A0A0))
            px(x + 1, y - 7 + hd, dk); px(x + 2, y - 5 + hd, dk)
            fill(x + 11, y - 5, 1, 3, dk)
        }
    }

    // ---------- channel and fields ----------

    private fun drawChannel() {
        val x0 = damX1 + 11
        val w = lw - x0
        fill(x0, chanY - 1, w, 1, gc(0x9A8F7A))
        fill(x0, chanY, w, 5, gc(0x4A3A2A))
        fill(x0, chanY + 5, w, 1, gc(0x3A2E22))
        if (shownDam > 0.5f) {
            val wl = 1 + (min(1f, shownDam / 30f) * 3f).toInt()
            val top = chanY + 5 - wl
            fill(x0, top, w, wl, gc(0x3F9BD8))
            fill(x0, chanY + 4, w, 1, gc(0x2B7FC4))
            for (sx in x0 until lw) {
                if (((sx + (t * 14f).toInt()) % 9) < 2) px(sx, top, gc(0xCFF1FF))
            }
        } else {
            var cx = x0 + 3
            while (cx < lw) { px(cx, chanY + 2, gc(0x2E2318)); px(cx + 1, chanY + 3, gc(0x2E2318)); cx += 11 }
        }
    }

    private fun drawFields() {
        val top = groundY + 30
        val bot = hudY - 3
        if (bot - top < 8) return
        val h = bot - top
        val rows = if (h >= 26) 2 else 1
        val ph = (h - (rows - 1) * 3) / rows
        val x0 = damX0 + 6
        val pw = 22
        val gap = 4
        val n = (lw - 6 - x0) / (pw + gap)
        for (r in 0 until rows) {
            for (cI in 0 until n) {
                val x = x0 + cI * (pw + gap)
                val y = top + r * (ph + 3)
                if (r == 0 && shownDam > 0.5f && top > chanY + 5) fill(x + pw / 2, chanY + 5, 1, top - chanY - 5, gc(0x3F9BD8))
                plot(x, y, pw, ph, r * 7 + cI)
            }
        }
    }

    private fun plot(x: Int, y: Int, w: Int, h: Int, seed: Int) {
        val soil = gmix(gc(0xB89868), gc(0x5A3B22), health)
        fill(x, y, w, h, soil)
        fill(x, y, w, 1, gmix(soil, G_WHITE, 0.15f))
        fill(x, y + h - 1, w, 1, gmix(soil, G_BLACK, 0.3f))
        var fy = y + 3
        while (fy < y + h - 1) { fill(x, fy, w, 1, gmix(soil, G_BLACK, 0.15f)); fy += 4 }
        if (health < 0.4f) {
            val a = ((0.4f - health) * 2.5f * 200f).toInt()
            for (k in 0 until 3) {
                fillA(x + 3 + k * 7, y + 2 + k % 2, 1, 3, gc(0x3A2A18), a)
                fillA(x + 4 + k * 7, y + 5, 1, 2, gc(0x3A2A18), a)
            }
        }
        var fx = x + 2
        var idx = 0
        while (fx < x + w - 1) {
            val factor = 0.8f + 0.2f * ((seed + idx) % 3) / 2f
            val hgt = ((1f + health * (h - 4)) * factor).toInt().coerceAtLeast(1)
            val sway = if (health > 0.5f) (sin(t * 1.5f + idx + seed) * 0.6f).roundToInt() else 0
            plant(fx + sway, y + h - 2, hgt)
            fx += 3
            idx++
        }
    }

    private fun plant(x: Int, base: Int, hgt: Int) {
        val stem = gmix(gc(0x8A6A3A), gc(0x56B03E), health)
        val leaf = gmix(gc(0xA08040), gc(0x7ED957), health)
        fill(x, base - hgt, 1, hgt, stem)
        if (hgt >= 3) {
            if (health < 0.35f) {
                px(x - 1, base - hgt + 1, leaf); px(x + 1, base - hgt + 1, leaf); px(x + 1, base - hgt, stem)
            } else {
                px(x - 1, base - hgt / 2, leaf); px(x + 1, base - hgt / 2 - 1, leaf)
            }
        }
        if (health > 0.75f && hgt >= 5) {
            px(x, base - hgt - 1, gc(0xE8C33A)); px(x + 1, base - hgt, gc(0xE8C33A))
        }
    }

    // ---------- AI facility ----------

    private fun drawFacility(active: Boolean) {
        val fx = facX0
        val base = facBase
        fill(fx - 2, base, 62, 3, gc(0x59616F))
        fill(fx - 2, base, 62, 1, gc(0x7B8493))
        val bw = 36
        val bh = 26
        val bt = base - bh
        fill(fx, bt, bw, bh, gc(0x2E3748))
        fill(fx, bt, bw, 2, gc(0x56627D))
        fill(fx + bw - 3, bt, 3, bh, gc(0x222A38))
        var sx = fx + 9
        while (sx < fx + bw) { fill(sx, bt + 2, 1, bh - 2, gc(0x242C3B)); sx += 9 }
        val wx = fx + 3
        val wy = bt + 5
        fill(wx - 1, wy - 1, 28, 16, gc(0x56627D))
        fill(wx, wy, 26, 14, gc(0x0C1626))
        for (r in 0 until 5) {
            val rx = wx + 1 + r * 5
            fill(rx, wy + 1, 4, 12, gc(0x1B2840))
            fill(rx, wy + 1, 4, 1, gc(0x2E405E))
            for (row in 0 until 4) {
                val ly = wy + 3 + row * 3
                val blink = sin(t * (2.5f + r * 0.7f + row) + r * 3f)
                val on = blink > (if (active) -0.6f else 0.2f)
                val col = when ((r + row) % 3) { 0 -> gc(0x37E6FF); 1 -> gc(0x5BFF8A); else -> gc(0xFF5BD1) }
                if (on) addLight(rx + 1, ly, 2, 1, col) else fill(rx + 1, ly, 2, 1, gc(0x14213A))
            }
        }
        fill(fx + bw / 2 - 2, base - 7, 4, 7, gc(0x1A2030))
        fill(fx + 3, bt - 3, 5, 3, gc(0x424B5E))
        fill(fx + 10, bt - 8, 34, 7, gc(0x0C1626))
        fill(fx + 10, bt - 8, 34, 1, gc(0x56627D))
        fill(fx + bw - 2, bt - 14, 1, 6, gc(0x8892A6))
        if (sin(t * 3f) > 0f) addLight(fx + bw - 2, bt - 15, 1, 1, gc(0xFF4A4A))

        val tcx = intArrayOf(fx + 43, fx + 54)
        val ty0 = base - 24
        for (cx in tcx) {
            for (k in 0 until 24) {
                val frac = k / 23f
                val w = if (frac < 0.35f) {
                    val q = (0.35f - frac) / 0.35f
                    3f + 1.5f * q * q
                } else {
                    val q = (frac - 0.35f) / 0.65f
                    3f + 3f * q * q
                }
                val lx = cx - w.roundToInt()
                val wd = 2 * w.roundToInt()
                val y = ty0 + k
                fill(lx, y, wd, 1, gc(0xB3BCC9))
                fill(lx, y, 2, 1, gc(0xD2D9E3))
                fill(lx + wd - 2, y, 2, 1, gc(0x8791A1))
                if (k % 6 == 5) fill(lx, y, wd, 1, gc(0x9AA4B3))
            }
            fill(cx - 5, ty0, 10, 1, if (active) gc(0x4FC3FF) else gc(0x6B7686))
            if (active) addLight(cx - 3, ty0, 6, 1, gc(0x6FD6FF))
        }
    }

    // ---------- particles and weather ----------

    private fun drawPuffs() {
        for (pf in puffs) {
            val k = pf.age / pf.life
            val a = ((1f - k) * (if (pf.kind == 0) 150f else 110f)).toInt().coerceIn(0, 255)
            val col = when (pf.kind) {
                0 -> gc(0xF4F8FF)
                1 -> gc(0xB8BEC8)
                2 -> gc(0xC9A26B)
                else -> gc(0xCFF1FF)
            }
            val s = if (pf.kind == 0) 1 + (k * 3f).toInt() else if (pf.kind == 1) 1 + (k * 2f).toInt() else 1
            fillA(pf.x.toInt(), pf.y.toInt(), s, s, col, a)
        }
    }

    private fun drawRain() {
        if (t >= restoreUntil) return
        val fade = ((restoreUntil - t) / 0.8f).coerceIn(0f, 1f)
        val a = (150 * fade).toInt()
        for (i in 0 until 70) {
            val rx = (i * 37) % lw
            val ry = ((t * 80f + i * 23f) % (groundY + 20)).toInt()
            fillA(rx, ry, 1, 3, gc(0xBFE8FF), a)
        }
    }

    // ---------- lights pass (after the day/night tint) ----------

    private fun drawLightsPass(active: Boolean) {
        for (i in 0 until lightN) {
            val b = i * 5
            fill(lights[b], lights[b + 1], lights[b + 2], lights[b + 3], lights[b + 4])
            if (nightAmt > 0.3f && lights[b + 2] * lights[b + 3] >= 4) {
                glowRect(lights[b] - 1, lights[b + 1] - 1, lights[b + 2] + 2, lights[b + 3] + 2, lights[b + 4], (nightAmt * 30f).toInt())
            }
        }
        if (celestial == 1) glowDisc(sunX, sunY, 12, gc(0xFFD27A), 14)
        if (celestial == 2) glowDisc(sunX, sunY, 8, gc(0x7F9BFF), 12)
        if (active) {
            val pulse = (sin(t * 3f) * 0.5f + 0.5f)
            glowRect(facX0 - 2, facBase - 40, 62, 40, gc(0x2FA8FF), (10 + 14 * pulse).toInt())
        }
        if (nightAmt > 0.35f && health > 0.3f) {
            for (i in 0 until 12) {
                val fx = (ghash(i * 5) % lw) + (sin(t * 0.7f + i) * 8f).toInt()
                val fy = groundY + 12 + ghash(i * 9) % max(1, lh - groundY - 30) + (sin(t * 0.9f + i * 2f) * 4f).toInt()
                if (sin(t * 2f + i * 1.3f) > -0.3f) {
                    px(fx, fy, gc(0xE8FF7A))
                    glowDisc(fx, fy, 3, gc(0xE8FF7A), (nightAmt * 40f).toInt())
                }
            }
        }
        // AI sign
        val signCol = if (active) gc(0x7BE8FF) else gc(0x5A6B86)
        txtC(if (active) "AI ACTIVE" else "AI IDLE", (facX0 + 27).toFloat(), (facBase - 26 - 3).toFloat(), 5.5f, signCol, false)
        // dam plate
        val px0 = damX0 + 1
        val py0 = wallTop + 2
        fill(px0, py0, 20, 14, gc(0x10192B))
        fill(px0, py0, 20, 1, gc(0x57C7FF))
        fill(px0, py0 + 13, 20, 1, gc(0x1B2A44))
        fill(px0, py0, 1, 14, gc(0x2E4A72))
        fill(px0 + 19, py0, 1, 14, gc(0x2E4A72))
        val pct = shownDam.roundToInt()
        val pc = if (pct > 60) gc(0x7CFF9B) else if (pct > 30) gc(0xFFD65C) else gc(0xFF6B5E)
        txtC("$pct%", (px0 + 10).toFloat(), (py0 + 8).toFloat(), 7.5f, pc, false)
        val used = dedToday.toInt()
        if (used > 0) txtC("-$used", (px0 + 10).toFloat(), (py0 + 13).toFloat(), 5f, gc(0xFF8A7A), false)
        else txtC("DAM", (px0 + 10).toFloat(), (py0 + 13).toFloat(), 5f, gc(0x8FA8D8), false)
        // cooling readout under the facility
        val cy = facBase + 4
        fill(facX0 - 2, cy, 62, 9, gc(0x10192B))
        fill(facX0 - 2, cy, 62, 1, gc(0x2E4A72))
        txt("COOL ${dedToday.toInt()}%", (facX0 + 1).toFloat(), (cy + 7).toFloat(), 5.5f, gc(0x7BE8FF), false)
        val led = if (active) gmix(gc(0x1F5A2E), gc(0x5BFF8A), sin(t * 4f) * 0.5f + 0.5f) else gc(0x4A5568)
        fill(facX0 + 52, cy + 3, 4, 3, led)
    }

    // ---------- HUD ----------

    private fun drawHud() {
        val y = hudY
        panel(2, y, lw - 4, 12)
        val slots = 4
        val sw = (lw - 8) / slots
        val happyShown = happy
        val env = (health - ovfToday / 100f).coerceIn(0f, 1f)
        val vals = floatArrayOf(shownDam / 100f, health, happyShown, env)
        val icons = arrayOf(S_DROP, S_SPROUT, S_HEART, S_LEAF)
        val cols = intArrayOf(gc(0x57C7FF), gc(0x7BD65A), gc(0xFF6B8A), gc(0x59C67A))
        for (i in 0 until slots) {
            val sx = 4 + i * sw
            spr(icons[i], sx + 1, y + 3, cols[i])
            val bx = sx + 8
            val bw = sw - 12
            fill(bx, y + 4, bw, 4, gc(0x0A0F1B))
            val v = vals[i].coerceIn(0f, 1f)
            val bc = if (v > 0.6f) gc(0x5CD65C) else if (v > 0.3f) gc(0xF2C94C) else gc(0xE55353)
            fill(bx, y + 4, (bw * v).toInt(), 4, bc)
            fill(bx, y + 4, (bw * v).toInt(), 1, gmix(bc, G_WHITE, 0.35f))
            var tk = bx + 4
            while (tk < bx + bw) { fill(tk, y + 4, 1, 4, gc(0x0A0F1B)); tk += 4 }
        }
    }

    private fun drawInfoPanel() {
        val l1 = "AI ${todayMin}m USED -${dedToday.toInt()}%"
        val l2 = "BACK +${resToday.toInt()}% QUIZ $quizLeft/${GameState.MAX_QUIZZES}"
        val l3 = if (ovfToday >= 1f) "EXTRA DEMAND ${ovfToday.toInt()}%" else ""
        val lines = if (l3.isEmpty()) 2 else 3
        tp.textSize = 5.5f
        var w = max(tp.measureText(l1), tp.measureText(l2))
        if (l3.isNotEmpty()) w = max(w, tp.measureText(l3))
        val pw = w.toInt() + 6
        val ph = lines * 6 + 4
        val x = lw - pw - 3
        panel(x, 3, pw, ph)
        txt(l1, (x + 3).toFloat(), 9f, 5.5f, gc(0xCFE3FF), false)
        txt(l2, (x + 3).toFloat(), 15f, 5.5f, gc(0x9FE8B0), false)
        if (l3.isNotEmpty()) txt(l3, (x + 3).toFloat(), 21f, 5.5f, gc(0xFF9A8A), false)
    }

    private fun drawFloats() {
        for (f in floats) {
            val age = t - f.born
            val y = f.y - (age * 9f).toInt()
            val a = (255 * (1f - age / 3.5f)).toInt().coerceIn(0, 255)
            txtC(f.s, f.x.toFloat(), y.toFloat(), 8f, (f.col and 0x00FFFFFF) or (a shl 24), false)
        }
    }

    companion object {
        private val K_H = floatArrayOf(0f, 5f, 6.5f, 8f, 17f, 18.5f, 20f, 24f)
        private val K_TOP = intArrayOf(
            gc(0x0B1330), gc(0x0B1330), gc(0x3A3F85), gc(0x3E86D6),
            gc(0x3E86D6), gc(0x4B3A86), gc(0x121A40), gc(0x0B1330)
        )
        private val K_BOT = intArrayOf(
            gc(0x1B2450), gc(0x1B2450), gc(0xF2A56E), gc(0xBFE4F4),
            gc(0xBFE4F4), gc(0xF2824F), gc(0x2B2A5C), gc(0x1B2450)
        )
        private val K_AMB = intArrayOf(
            gc(0x6A7AB8), gc(0x6A7AB8), gc(0xFFCBA6), gc(0xFFFFFF),
            gc(0xFFFFFF), gc(0xFFB682), gc(0x7A84C0), gc(0x6A7AB8)
        )
        private val MT_AMP = floatArrayOf(36f, 26f, 17f)
        private val MT_PH = floatArrayOf(0.7f, 2.1f, 4.0f)
        private val MT_F = floatArrayOf(0.035f, 0.05f, 0.075f)
        private val MT_PAR = floatArrayOf(0.3f, 0.6f, 1f)
        private val CL_X = floatArrayOf(10f, 90f, 160f, 40f, 200f)
        private val CL_V = floatArrayOf(3f, 2f, 4f, 1.5f, 2.5f)
        private val CL_Y = floatArrayOf(0.12f, 0.22f, 0.08f, 0.30f, 0.17f)
        private val CLOUDS = arrayOf(
            arrayOf("....XXX........", "..XXXXXXX..XX..", ".XXXXXXXXXXXXXX", "XXXXXXXXXXXXXXX"),
            arrayOf("...XX......", ".XXXXXX.XX.", "XXXXXXXXXXX")
        )
        private val WALLS = intArrayOf(gc(0xE8D7B5), gc(0xC9D6E8), gc(0xEFC9A8), gc(0xD8E6C4))
        private val ROOFS = intArrayOf(gc(0xB5483A), gc(0x3F6F9B), gc(0xD9822B), gc(0x4E8F7A))
        private val S_HEART = arrayOf(".X.X.", "XXXXX", "XXXXX", ".XXX.", "..X..")
        private val S_DROP = arrayOf("..X..", ".XXX.", "XXXXX", "XXXXX", ".XXX.")
        private val S_SPROUT = arrayOf("X...X", ".X.X.", "..X..", "..X..", "..X..")
        private val S_LEAF = arrayOf("...XX", "..XXX", ".XXX.", "XXX..", "X....")
        private val S_EXCL = arrayOf(".X.", ".X.", ".X.", "...", ".X.")
    }
}
