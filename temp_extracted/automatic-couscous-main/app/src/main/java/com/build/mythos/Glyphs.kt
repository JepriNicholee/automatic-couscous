package com.build.mythos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Ikon garis tipis untuk drawer, lembar "Tambahkan ke chat", dan layar kosong.
 * Digambar pada kisi 24x24 lalu diskalakan, jadi tebal garis sama di semua ukuran.
 */
class GlyphView(ctx: Context, kind: Int, c: Int = Palette.INK) : View(ctx) {
    var kind: Int = kind
        set(v) { field = v; invalidate() }
    var color: Int = c
        set(v) { field = v; invalidate() }

    /** Tebal garis dalam dp. */
    var strokeDp: Float = 1.6f
        set(v) { field = v; invalidate() }

    /** Warna mata untuk hantu padat ([GHOST_SOLID]). */
    var eyeColor: Int = Palette.BG
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    companion object {
        const val CAMERA = 0
        const val IMAGE = 1
        const val FILE_UP = 2
        const val PHOTOS = 3
        const val PROJECT = 4
        const val CODE = 5
        const val ARTIFACT = 6
        const val CHATS = 7
        const val BUBBLE = 8
        const val CHEVRON_R = 9
        const val CLOSE = 10
        const val PLUS = 11
        const val GHOST_SOLID = 12
        const val BRAIN = 13
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val sc = min(width, height) / 24f
        if (sc <= 0f) return
        c.save()
        c.translate((width - 24f * sc) / 2f, (height - 24f * sc) / 2f)
        c.scale(sc, sc)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = strokeDp * resources.displayMetrics.density / sc
        p.color = color
        draw24(c)
        c.restore()
    }

    private fun poly(c: Canvas, vararg xy: Float) {
        val path = Path()
        path.moveTo(xy[0], xy[1])
        var i = 2
        while (i < xy.size) { path.lineTo(xy[i], xy[i + 1]); i += 2 }
        c.drawPath(path, p)
    }

    private fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float) =
        c.drawRoundRect(RectF(l, t, r, b), rad, rad, p)

    private fun draw24(c: Canvas) {
        when (kind) {
            CAMERA -> {
                rr(c, 3f, 7.5f, 21f, 19.5f, 3f)
                poly(c, 8f, 7.5f, 9.4f, 5f, 14.6f, 5f, 16f, 7.5f)
                c.drawCircle(12f, 13.5f, 3.3f, p)
            }
            IMAGE -> {
                rr(c, 3.5f, 3.5f, 20.5f, 20.5f, 3f)
                c.drawCircle(9f, 9f, 1.4f, p)
                poly(c, 4f, 17f, 9.5f, 12.5f, 13f, 15.5f, 15.5f, 13f, 20f, 17f)
            }
            FILE_UP -> {
                poly(c, 6.5f, 2.8f, 13.8f, 2.8f, 18.7f, 7.7f, 18.7f, 19.5f)
                poly(c, 6.5f, 2.8f, 5.3f, 3.2f, 5.3f, 20.8f, 6.5f, 21.2f, 17.5f, 21.2f, 18.7f, 20.5f, 18.7f, 19.5f)
                poly(c, 13.8f, 2.8f, 13.8f, 7.7f, 18.7f, 7.7f)
                poly(c, 12f, 17.5f, 12f, 10.8f)
                poly(c, 9.3f, 13.4f, 12f, 10.7f, 14.7f, 13.4f)
            }
            PHOTOS -> {
                rr(c, 8f, 3f, 21f, 16f, 2.6f)
                rr(c, 3f, 8f, 16f, 21f, 2.6f)
                c.drawCircle(7.3f, 12.3f, 1.1f, p)
                poly(c, 3.6f, 18.6f, 7.5f, 15.2f, 10.2f, 17.4f, 12.3f, 15.5f, 15.6f, 18.6f)
            }
            PROJECT -> {
                poly(c, 8.2f, 3.4f, 15.8f, 3.4f)
                poly(c, 6f, 6.8f, 18f, 6.8f)
                rr(c, 4.2f, 9.6f, 19.8f, 20.6f, 2.8f)
            }
            CODE -> {
                poly(c, 8f, 7f, 3.5f, 12f, 8f, 17f)
                poly(c, 16f, 7f, 20.5f, 12f, 16f, 17f)
                poly(c, 13.6f, 5.2f, 10.4f, 18.8f)
            }
            ARTIFACT -> {
                c.drawCircle(7.8f, 16f, 4.2f, p)
                rr(c, 14f, 12.6f, 20.6f, 20.4f, 1.6f)
                val tri = Path().apply {
                    moveTo(8.2f, 9.6f); lineTo(12.2f, 3.4f); lineTo(16.4f, 9.6f)
                }
                c.drawPath(tri, p)
            }
            CHATS -> {
                c.drawCircle(9.8f, 9.8f, 6.3f, p)
                poly(c, 4.9f, 14.2f, 3.4f, 17.4f, 7f, 16.2f)
                val arc = Path()
                arc.addArc(RectF(9f, 9f, 20.6f, 20.6f), -35f, 160f)
                c.drawPath(arc, p)
                poly(c, 17.6f, 19.6f, 20.2f, 21.2f, 19.8f, 18.2f)
            }
            BUBBLE -> {
                c.drawCircle(12f, 11.4f, 8f, p)
                poly(c, 5.8f, 16.6f, 4.2f, 20.4f, 8.8f, 18.9f)
            }
            CHEVRON_R -> poly(c, 9.5f, 5.5f, 16f, 12f, 9.5f, 18.5f)
            CLOSE -> {
                poly(c, 6f, 6f, 18f, 18f)
                poly(c, 18f, 6f, 6f, 18f)
            }
            PLUS -> {
                poly(c, 12f, 5f, 12f, 19f)
                poly(c, 5f, 12f, 19f, 12f)
            }
            BRAIN -> {
                c.drawCircle(8.8f, 12f, 4.8f, p)
                c.drawCircle(15.2f, 12f, 4.8f, p)
                poly(c, 12f, 7.4f, 12f, 16.6f)
            }
            GHOST_SOLID -> {
                p.style = Paint.Style.FILL
                val s = 24f
                val cx = 12f
                val cy = 12f
                val ghost = Path().apply {
                    moveTo(cx - s * 0.36f, cy + s * 0.38f)
                    lineTo(cx - s * 0.36f, cy - s * 0.04f)
                    arcTo(RectF(cx - s * 0.36f, cy - s * 0.42f, cx + s * 0.36f, cy + s * 0.30f), 180f, 180f)
                    lineTo(cx + s * 0.36f, cy + s * 0.38f)
                    lineTo(cx + s * 0.18f, cy + s * 0.28f)
                    lineTo(cx, cy + s * 0.38f)
                    lineTo(cx - s * 0.18f, cy + s * 0.28f)
                    close()
                }
                c.drawPath(ghost, p)
                p.color = eyeColor
                c.drawCircle(cx - s * 0.12f, cy - s * 0.07f, s * 0.05f, p)
                c.drawCircle(cx + s * 0.12f, cy - s * 0.07f, s * 0.05f, p)
            }
        }
    }
}

/**
 * Logo bintang oranye pada layar kosong. Selalu bergerak mengikuti waktu: sinar-sinar berputar pelan,
 * tiap sinar memanjang-memendek dengan kecepatan dan fase sendiri, tebal garisnya berdenyut,
 * dan sebuah cincin bola kecil ikut berdenyut. Gerakan dihitung dari jam (uptime), jadi mulus dan tidak pernah berulang persis sama.
 */
class BurstView(ctx: Context, private val tone: Int = Palette.BRAND_2) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private val count = 16
    // Panjang dasar tiap sinar (0..1), sengaja tidak seragam.
    private val base = floatArrayOf(0.98f, 0.82f, 0.94f, 0.78f, 0.96f, 0.84f, 0.92f, 0.80f, 0.97f, 0.83f, 0.93f, 0.79f, 0.95f, 0.85f, 0.91f, 0.81f)

    private fun phase(i: Int) = i * 2.399963f            // sudut emas: fase tersebar rata
    private fun speed(i: Int) = 1.1f + 0.37f * (i % 5)   // radian per detik

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()   // minimal satu gambar; berikutnya hanya bila gerakan tidak dikurangi
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val r = min(width, height) / 2f
        val cx = width / 2f
        val cy = height / 2f
        val t = (android.os.SystemClock.uptimeMillis() % 3_600_000L) / 1000f
        val spin = Math.toRadians((t * 9f).toDouble())   // putaran pelan, 9 derajat per detik
        val pulse = 0.5f + 0.5f * sin(t * 1.6f)

        p.color = tone
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = r * (0.15f + 0.04f * pulse)
        for (i in 0 until count) {
            val a = spin + i * (2.0 * Math.PI / count) + 0.12 * sin((t * 0.7f + phase(i)).toDouble())
            val len = r * 0.8f * base[i] * (0.72f + 0.28f * sin(t * speed(i) + phase(i)))
            val inner = r * 0.14f
            val outer = (len - p.strokeWidth / 2f).coerceAtLeast(inner + 1f)
            c.drawLine(
                cx + (cos(a) * inner).toFloat(), cy + (sin(a) * inner).toFloat(),
                cx + (cos(a) * outer).toFloat(), cy + (sin(a) * outer).toFloat(), p
            )
        }
        p.style = Paint.Style.FILL
        c.drawCircle(cx, cy, r * (0.17f + 0.04f * pulse), p)
        // Cincin bola kecil di tepi: ukuran tiap bola naik-turun bergantian.
        for (i in 0 until count) {
            val a = -spin * 0.6 + i * (2.0 * Math.PI / count)
            val k = 0.5f + 0.5f * sin(t * 2.2f + phase(i))
            c.drawCircle(
                cx + (cos(a) * r * 0.94f).toFloat(), cy + (sin(a) * r * 0.94f).toFloat(),
                r * (0.02f + 0.035f * k), p
            )
        }
        if (isAttachedToWindow && !Motion.reduced) postInvalidateOnAnimation()
    }
}
