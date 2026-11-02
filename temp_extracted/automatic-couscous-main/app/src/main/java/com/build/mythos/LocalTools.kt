package com.build.mythos

// Model lokal (Ollama di HP): daftar alat dan awal prompt. Murni Kotlin supaya bisa diuji sebagai tes JVM (LocalToolsTest).

/**
 * Alat yang dikirim ke model lokal. Model 1,5 miliar parameter jarang memakai build_apk, self_upgrade, dst., tetapi setiap
 * definisi alat harus dibaca CPU HP di setiap giliran. Hitungan dari kode (deskripsi sudah dipadatkan): definisi 10 alat ±4.900 karakter,
 * 3 alat ±1.300 karakter, dari total prompt ±6.600 karakter. Alat lain tetap dikenali pelaksana (panggilan teks yang dipulihkan
 * `recoverTextToolCalls` tetap dijalankan) dan tetap dikirim ke penyedia internet.
 */
internal val LOCAL_TOOL_NAMES = listOf("run_command", "write_file", "ask_user")

/** Pilih nama alat yang dikirim: semua untuk penyedia internet; untuk model lokal hanya [LOCAL_TOOL_NAMES] (semua bila tak satu pun cocok). */
internal fun toolNamesFor(local: Boolean, all: List<String>): List<String> {
    if (!local) return all
    val kept = all.filter { it in LOCAL_TOOL_NAMES }
    return if (kept.isEmpty()) all else kept
}

/**
 * Pemantau awal prompt (sistem + alat) antar giliran agen di model lokal. Ollama memakai ulang cache hanya untuk awalan yang
 * persis sama; bila sistem atau alat berubah di tengah tugas, seluruh prompt dibaca ulang. Giliran terisolasi (sapaan, ringkasan,
 * penalaran) memakai prompt berbeda dengan sengaja, jadi tidak dibandingkan.
 */
internal class PrefixWatch {
    private var sysHash = 0
    private var toolsHash = 0
    private var seen = false

    /** Null bila pertama kali atau sama; bila berubah, penjelasan singkat bagian yang berubah. */
    @Synchronized
    fun check(system: String, toolsJson: String): String? {
        val s = system.hashCode()
        val t = toolsJson.hashCode()
        val changed = seen && (s != sysHash || t != toolsHash)
        val what = listOfNotNull(
            if (seen && s != sysHash) "prompt sistem" else null,
            if (seen && t != toolsHash) "daftar alat" else null
        ).joinToString(" dan ")
        sysHash = s
        toolsHash = t
        seen = true
        return if (changed) "Awal prompt berubah ($what): model lokal membaca ulang seluruh prompt giliran ini." else null
    }

    @Synchronized
    fun reset() { seen = false }
}
