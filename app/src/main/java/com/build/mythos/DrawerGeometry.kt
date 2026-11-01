package com.build.mythos

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Perhitungan murni untuk drawer: lebar (dibatasi di layar lebar) dan gestur geser yang mengikuti jari.
// Dipisah dari Drawer.kt supaya bisa dites di JVM.

internal object DrawerGeometry {
    /** Lebar drawer maksimum (dp), supaya di tablet atau lanskap tidak melebar mengikuti layar. */
    const val MAX_WIDTH_DP = 360

    /** Lebar drawer: [fraction] dari lebar layar, tetapi tidak lebih dari [MAX_WIDTH_DP] dan tidak lebih dari layar. */
    fun widthPx(screenPx: Int, density: Float, fraction: Float = DRAWER_FRACTION): Int =
        min(min((screenPx * fraction).toInt(), (MAX_WIDTH_DP * density).toInt()), screenPx)

    /**
     * Gestur geser dianggap dimulai bila jarak mendatar melewati [slopPx], lebih dominan daripada vertikal,
     * dan arahnya masuk akal: drawer tertutup hanya dibuka dari tepi kiri ke kanan; drawer terbuka hanya ditutup ke kiri.
     */
    fun shouldStartDrag(dx: Float, dy: Float, slopPx: Float, open: Boolean, downX: Float, edgePx: Float): Boolean {
        if (abs(dx) <= slopPx || abs(dx) <= abs(dy) * 1.5f) return false
        return if (open) dx < 0f else (downX < edgePx && dx > 0f)
    }

    /** Kemajuan drawer (0..1) untuk jari yang sudah bergeser [dx] sejak drawer berada di [startProgress]. */
    fun progressFor(startProgress: Float, dx: Float, widthPx: Int): Float {
        if (widthPx <= 0) return startProgress
        return (startProgress + dx / widthPx).coerceIn(0f, 1f)
    }

    /** Terbuka atau tertutup setelah jari diangkat: lemparan cepat menentukan arah, selain itu setengah jalan. */
    fun settle(progress: Float, velocityPxPerSec: Float, flingPx: Float): Boolean = when {
        velocityPxPerSec > flingPx -> true
        velocityPxPerSec < -flingPx -> false
        else -> progress >= 0.5f
    }

    /** Durasi animasi sisa jarak: sebanding dengan jarak yang tinggal, minimal 40% dari [baseMs]. */
    fun animMs(from: Float, to: Float, baseMs: Long): Long = (baseMs * max(0.4f, abs(to - from))).toLong()
}
