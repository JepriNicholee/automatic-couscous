package com.build.mythos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Ikon titik "hidup" pada baris status live. Diukur dari rekaman layar Claude (Screen_Recording_20261026_183644.mp4,
// kerapatan layar ~2,75) dengan perpindahan "menyusut ke satu titik -> bertahan -> mengembang". Gerak SENGAJA tidak berulang:
//  - ORBIT: tiga titik mengorbit dengan kecepatan sendiri-sendiri, kemiringan orbit bergelombang, fase awal acak tiap kali
//    muncul, sehingga pola tidak pernah sama persis. Ukuran: orbit mendatar 7,5dp, titik 1,1-2,0dp (lebih besar saat di depan).
//  - GRID: empat titik di sudut persegi; tiap "denyut" memilih acak pasangan diagonal mana yang berputar 180 derajat sambil
//    merapat ke pusat (arah, kedalaman, durasi, dan jeda acak; kadang kedua pasangan berputar berlawanan).

internal object ClaudeDotsMath {
    const val DENSITY_REF = 2.75f          // kerapatan layar rekaman, untuk mengubah piksel -> dp
    const val GRID_R_DP = 21.2f / DENSITY_REF
    const val GRID_DOT_DP = 1.9f
    const val PERIOD_MS = 630L             // satu putaran pasangan titik
    const val PULSE_DELAY_MS = 360L        // jeda setelah mengembang sebelum putaran pertama

    private val U = floatArrayOf(0f, .11f, .19f, .29f, .365f, .44f, .51f, .59f, .67f, .83f, 1f)
    private val ROT = floatArrayOf(0f, .04f, .08f, .19f, .36f, .70f, .78f, .87f, .90f, .96f, 1f)
    private val RADIUS_PX = floatArrayOf(21.2f, 21.2f, 15.5f, 10.2f, 8.5f, 12.6f, 16.1f, 20.2f, 20.9f, 21.2f, 21.2f)

    private fun table(ys: FloatArray, x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        for (i in 1 until U.size) {
            if (v <= U[i]) {
                val f = (v - U[i - 1]) / (U[i] - U[i - 1])
                return ys[i - 1] + (ys[i] - ys[i - 1]) * f
            }
        }
        return ys.last()
    }

    /** Sudut (derajat, sumbu y ke bawah) pasangan titik yang berputar; u = 0..1 dalam satu putaran. */
    fun pairAngleDeg(u: Float): Float = 45f - 180f * table(ROT, u)

    /** Jarak pasangan itu dari pusat (dp). */
    fun pairRadiusDp(u: Float): Float = table(RADIUS_PX, u) / DENSITY_REF

    /** Posisi dalam putaran (0..1) untuk waktu [sinceExpandMs] sejak mengembang; -1 bila belum mulai berputar. */
    fun pulsePhase(sinceExpandMs: Long): Float {
        if (sinceExpandMs < PULSE_DELAY_MS) return -1f
        return ((sinceExpandMs - PULSE_DELAY_MS) % PERIOD_MS).toFloat() / PERIOD_MS
    }

    /** Kemajuan putaran 0..1 (0 = belum berputar, 1 = sudah setengah putaran penuh). */
    fun rot(u: Float): Float = table(ROT, u)

    /** Seberapa dalam titik merapat ke pusat pada posisi u: 0 = jarak penuh, 1 = paling dekat. */
    fun bump(u: Float): Float = (RADIUS_PX[0] - table(RADIUS_PX, u)) / (RADIUS_PX[0] - RADIUS_PX[4])

    /** Titik orbit ke-[i]: kembalikan (x dp, y dp, jari-jari dp) relatif ke pusat pada detik ke-[ts]; [ph] = fase awal, [w] = kecepatan (rad/dtk). */
    fun orbitDot(i: Int, ts: Float, ph: FloatArray, w: FloatArray, tiltPh: Float): FloatArray {
        val th = ph[i] + w[i] * ts + 0.35f * sin(0.9f * ts + i * 2.1f)
        val tiltWave = 0.5f - 0.5f * cos(2f * PI.toFloat() * ts / 1.8f + tiltPh)
        val tiltMax = 4.4f * (0.55f + 0.45f * sin(2f * PI.toFloat() * ts / 7.3f + tiltPh))
        val z = sin(th)
        return floatArrayOf(7.5f * cos(th), tiltMax * tiltWave * z, 1.55f + 0.45f * z)
    }
}

internal class ClaudeDotsView(ctx: Context) : View(ctx) {
    companion object {
        const val ORBIT = 0
        const val GRID = 1
        private const val COLLAPSE_MS = 120L
        private const val MERGE_MS = 100L
        private const val HOLD_MS = 260L
        private const val EXPAND_MS = 300L
        private const val ORANGE = 0xFFD67658.toInt()
    }

    private enum class Phase { SHOW, COLLAPSE, HOLD, EXPAND }

    private class Pulse(val start: Long, val dur: Long, val pair: Int, val dir: Float, val rMin: Float, val both: Boolean)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ORANGE }
    private val rnd = java.util.Random()
    private var pulse: Pulse? = null
    private var orbitPh = FloatArray(3)
    private var orbitW = FloatArray(3)
    private var tiltPh = 0f
    private var mode = -1
    private var target = -1
    private var phase = Phase.HOLD
    private var phaseStart = 0L
    private var modeStart = 0L

    /** Ganti jenis ikon. Bila berbeda dari yang tampil: menyusut ke satu titik, bertahan sebentar, lalu mengembang. */
    fun setMode(m: Int) {
        if (m == target) return
        target = m
        val now = SystemClock.uptimeMillis()
        when {
            mode == -1 -> startExpand(m, now)
            phase == Phase.SHOW || phase == Phase.EXPAND -> { phase = Phase.COLLAPSE; phaseStart = now }
            else -> {} // sedang menyusut / bertahan: akan mengembang ke target terbaru
        }
        invalidate()
    }

    private fun startExpand(m: Int, now: Long) {
        mode = m
        phase = Phase.EXPAND
        phaseStart = now
        modeStart = now
        // Setiap kali ikon muncul, gerak dibuat baru: fase, kecepatan, dan denyut acak.
        orbitPh = floatArrayOf(PI.toFloat(), 0f, (rnd.nextFloat() * 2f - 1f) * 1.2f + PI.toFloat() / 2f)
        orbitW = floatArrayOf(
            3.6f + rnd.nextFloat() * 1.8f,
            3.0f + rnd.nextFloat() * 2.2f,
            4.0f + rnd.nextFloat() * 2.4f
        )
        tiltPh = rnd.nextFloat() * 2f * PI.toFloat()
        pulse = nextPulse(now + ClaudeDotsMath.PULSE_DELAY_MS)
    }

    private fun nextPulse(start: Long) = Pulse(
        start = start,
        dur = 520L + rnd.nextInt(260),
        pair = rnd.nextInt(2),
        dir = if (rnd.nextBoolean()) 1f else -1f,
        rMin = 6.5f + rnd.nextFloat() * 3.5f,
        both = rnd.nextInt(4) == 0
    )

    private fun tick(now: Long) {
        if (Motion.reduced) {
            // Tanpa gerakan: langsung ke ikon tujuan dalam keadaan jadi, tanpa menyusut / mengembang.
            if (target != -1 && (mode != target || phase != Phase.SHOW)) { startExpand(target, now); phase = Phase.SHOW }
            return
        }
        val el = now - phaseStart
        when (phase) {
            Phase.COLLAPSE -> if (el >= COLLAPSE_MS + MERGE_MS) { phase = Phase.HOLD; phaseStart = now }
            Phase.HOLD -> if (el >= HOLD_MS) startExpand(target, now)
            Phase.EXPAND -> if (el >= EXPAND_MS) phase = Phase.SHOW
            Phase.SHOW -> {}
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (mode == -1) return
        val now = SystemClock.uptimeMillis()
        tick(now)
        val dp = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val el = now - phaseStart
        when (phase) {
            Phase.COLLAPSE -> {
                if (el < COLLAPSE_MS) {
                    val f = el.toFloat() / COLLAPSE_MS
                    val s = 1f - f * f
                    drawShape(c, mode, cx, cy, dp, s, 1.9f, now)
                } else {
                    val f = ((el - COLLAPSE_MS).toFloat() / MERGE_MS).coerceIn(0f, 1f)
                    paint.alpha = 255
                    c.drawCircle(cx, cy, (2.4f + (1.4f - 2.4f) * f) * dp, paint)
                }
            }
            Phase.HOLD -> {
                val h = (el.toFloat() / HOLD_MS).coerceIn(0f, 1f)
                paint.alpha = 255
                c.drawCircle(cx, cy, (1.4f - 0.6f * sin(PI.toFloat() * h)) * dp, paint)
            }
            Phase.EXPAND -> {
                val f = (el.toFloat() / EXPAND_MS).coerceIn(0f, 1f)
                val s = 1f - (1f - f) * (1f - f) * (1f - f)
                drawShape(c, mode, cx, cy, dp, s, 1.4f + 0.5f * s, now)
            }
            Phase.SHOW -> drawShape(c, mode, cx, cy, dp, 1f, ClaudeDotsMath.GRID_DOT_DP, now)
        }
        if (!Motion.reduced) postInvalidateOnAnimation()
    }

    private fun drawShape(c: Canvas, m: Int, cx: Float, cy: Float, dp: Float, scale: Float, dotDp: Float, now: Long) {
        if (m == GRID) drawGrid(c, cx, cy, dp, scale, dotDp, now) else drawOrbit(c, cx, cy, dp, scale, now)
    }

    private fun drawGrid(c: Canvas, cx: Float, cy: Float, dp: Float, scale: Float, dotDp: Float, now: Long) {
        paint.alpha = 255
        var pl = pulse ?: nextPulse(now).also { pulse = it }
        while (now >= pl.start + pl.dur) { pl = nextPulse(pl.start + pl.dur + 80L + rnd.nextInt(800)); pulse = pl }
        val u = if (now < pl.start) -1f else (now - pl.start).toFloat() / pl.dur
        val full = ClaudeDotsMath.GRID_R_DP
        val r = dotDp * dp
        fun dot(deg: Float, rad: Float) {
            val a = Math.toRadians(deg.toDouble())
            c.drawCircle(cx + (rad * dp * scale * cos(a)).toFloat(), cy + (rad * dp * scale * sin(a)).toFloat(), r, paint)
        }
        // pair 0 = sudut 45/225, pair 1 = 135/315
        for (pair in 0..1) {
            val base = if (pair == 0) 45f else 135f
            val active = u >= 0f && (pair == pl.pair || pl.both)
            var ang = base
            var rad = full
            if (active) {
                val d = if (pair == pl.pair) pl.dir else -pl.dir
                ang = base + d * 180f * ClaudeDotsMath.rot(u)
                rad = full - (full - pl.rMin / ClaudeDotsMath.DENSITY_REF) * ClaudeDotsMath.bump(u)
            }
            dot(ang, rad)
            dot(ang + 180f, rad)
        }
    }

    private fun drawOrbit(c: Canvas, cx: Float, cy: Float, dp: Float, scale: Float, now: Long) {
        paint.alpha = 255
        val ts = (now - modeStart) / 1000f
        for (i in 0..2) {
            val d = ClaudeDotsMath.orbitDot(i, ts, orbitPh, orbitW, tiltPh)
            c.drawCircle(cx + d[0] * dp * scale, cy + d[1] * dp * scale, d[2] * dp, paint)
        }
    }
}
