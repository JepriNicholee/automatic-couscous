package com.build.mythos

/**
 * Penghalus teks stream. Murni Kotlin supaya bisa diuji sebagai tes JVM.
 *
 * Teks dari Codex datang bergelombang (satu potongan, lalu diam). Kelas [Pacer] menampilkannya per frame dengan
 * kecepatan yang terlihat seperti mengetik: dasar [MIN_CPS] karakter per detik, naik seiring teks yang tertunda
 * ([GAIN] x backlog) sampai batas [MAX_CPS], supaya jawaban panjang tidak tertinggal terlalu jauh.
 * Kalimat pendek (~80 karakter) selesai dalam sekitar satu detik, sejalan dengan video acuan.
 */
internal object StreamSmoother {
    /** Jeda antar frame (ms), sekitar 60 fps. */
    const val FRAME_MS = 16L

    /** Kecepatan dasar (karakter/detik) saat backlog kecil. */
    const val MIN_CPS = 45.0

    /** Tambahan karakter/detik per satu karakter backlog. */
    const val GAIN = 0.6

    /** Batas atas kecepatan (karakter/detik). */
    const val MAX_CPS = 260.0

    /** Konstanta waktu (ms) penghalusan kecepatan: laju naik/turun bertahap, bukan melonjak tiap potongan teks baru tiba. */
    const val RATE_TAU_MS = 220.0

    /** Lama (ms) satu huruf memudar dari nyaris transparan sampai penuh setelah muncul. */
    const val FADE_MS = 260L

    /** Alpha (0..255) huruf yang sudah muncul selama [ageMs]: memudar masuk dengan percepatan lembut (ease-out). */
    fun fadeAlpha(ageMs: Long): Int {
        if (ageMs >= FADE_MS) return 255
        val t = (ageMs.coerceAtLeast(0L).toDouble() / FADE_MS)
        val eased = 1.0 - (1.0 - t) * (1.0 - t)
        return (24 + (255 - 24) * eased).toInt().coerceIn(0, 255)
    }

    /**
     * Pengatur laju dengan sisa pecahan, sehingga kecepatan di bawah 1 karakter per frame tetap halus.
     * Kecepatan sasaran (dasar + gain x backlog) tidak dipakai langsung: ia dikejar secara eksponensial
     * ([RATE_TAU_MS]) supaya aliran terasa mengalir, tidak tersendat saat potongan teks tiba bergelombang.
     */
    class Pacer {
        private var carry = 0.0
        private var cps = MIN_CPS

        /** Jumlah karakter yang ditampilkan pada satu frame sepanjang [dtMs] bila tertinggal [backlog] karakter. */
        fun step(backlog: Int, dtMs: Long = FRAME_MS): Int {
            if (backlog <= 0) { carry = 0.0; cps = MIN_CPS; return 0 }
            val dt = dtMs.coerceIn(1L, 100L)
            val goal = (MIN_CPS + backlog * GAIN).coerceAtMost(MAX_CPS)
            cps += (goal - cps) * (1.0 - Math.exp(-dt / RATE_TAU_MS))
            carry += cps * dt / 1000.0
            val n = carry.toInt()
            carry -= n
            return n.coerceAtMost(backlog)
        }

        fun reset() { carry = 0.0; cps = MIN_CPS }
    }

    /** Titik potong [n] yang tidak memecah pasangan surrogate (emoji, aksara langka) di tengah. */
    fun safeCut(text: String, n: Int): Int = StreamPacing.safeCut(text, n)

    /** Panjang prefiks [target] yang layak ditampilkan setelah [shown] karakter. */
    fun nextShown(pacer: Pacer, target: String, shown: Int, dtMs: Long = FRAME_MS): Int {
        val s = safeCut(target, shown.coerceIn(0, target.length))
        val n = pacer.step(target.length - s, dtMs)
        return if (n == 0) s else safeCut(target, s + n)
    }
}
