package com.build.mythos

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

// "Kurangi gerakan": animasi yang terus berulang (logo titik, kilau teks, pemutar langkah, kedip) berhenti dan tampil statis.
// Aktif bila pengguna menyalakannya di Pengaturan, atau bila sistem mematikan animasi (Opsi pengembang / "Hapus animasi").
// Hemat baterai juga, karena tidak ada lagi redraw per frame.

internal object Motion {
    const val PREF = "reduce_motion"

    /** Diatur sekali per Activity lewat [applyMotion]. */
    @Volatile var reduced = false
}

/** Murni: gerakan dikurangi bila pengguna memintanya, atau skala animasi sistem 0 (animasi dimatikan). */
internal fun isMotionReduced(systemAnimatorScale: Float, userPref: Boolean): Boolean =
    userPref || systemAnimatorScale == 0f

/** Baca pilihan pengguna dan skala animasi sistem, lalu isi [Motion.reduced]. */
internal fun applyMotion(context: Context, prefs: SharedPreferences) {
    val scale = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (e: Exception) {
        1f
    }
    Motion.reduced = isMotionReduced(scale, prefs.getBoolean(Motion.PREF, false))
}

/** Mulai animator berulang hanya bila gerakan tidak dikurangi. */
internal fun ValueAnimator.startUnlessReduced() {
    if (!Motion.reduced) start()
}
