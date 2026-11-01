package com.build.mythos

/** Kata-kata yang berganti di indikator siaga (mode menunggu). Murni Kotlin supaya bisa diuji sebagai tes JVM. */
internal object StandbyWords {
    val WORDS = listOf("Menunggu…", "Merenung…", "Melamun…", "Memimpikan…", "Menghayal…", "Mentriangulasi…")

    /** Lama tiap kata tampil (ms); sedikit lebih panjang dari satu sapuan gelombang cahaya (2,2 detik). */
    const val WORD_MS = 2600L

    fun at(i: Int): String = WORDS[Math.floorMod(i, WORDS.size)]
    fun next(i: Int): Int = Math.floorMod(i + 1, WORDS.size)
}
