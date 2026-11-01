package com.build.mythos

// Pengaman keberhasilan agen (logika murni, tanpa android.*, diuji di AgentGuardTest):
// 1) verifikasi: agen tidak boleh berhenti bila build terakhir gagal / kode baru diubah tanpa build yang berhasil sesudahnya;
// 2) error berulang: error yang sama muncul lagi dan lagi -> peringatan ganti pendekatan, lalu berhenti;
// 3) ringkasan error: keluaran gagal yang panjang dipadatkan ke bagian errornya sebelum dikirim ke model.
// Semua keputusan memakai fakta nyata dari RunLog / keluaran perintah, bukan tebakan.

/** Awalan pesan sistem di riwayat: tidak ditampilkan sebagai gelembung pengguna saat riwayat dipulihkan. */
internal const val SYS_PREFIX = "[SISTEM] "

/** Berapa kali agen boleh diminta memverifikasi dalam satu tugas (cegah putaran tanpa akhir dan boros kuota). */
internal const val MAX_VERIFY_NUDGES = 3

/** Error yang sama: peringatan di kemunculan ke-2 dan ke-3, berhenti di ke-4. */
internal const val WARN_SAME_ERROR_AT = 2
internal const val STOP_SAME_ERROR_AT = 4

private val CODE_EXT = setOf("kt", "java", "kts", "gradle")

internal fun isCodeFile(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in CODE_EXT

/**
 * Pesan pengingat bila agen belum boleh dianggap selesai, atau null bila boleh.
 * (a) build terakhir gagal dan belum ada build berhasil sesudahnya; atau
 * (b) file kode (kt/java/kts/gradle) berhasil ditulis dan belum ada build berhasil sesudahnya.
 */
internal fun verificationNudge(
    steps: List<StepRecord>,
    lastFailOut: String? = null,
    nudgesUsed: Int = 0,
    sandbox: Boolean = false
): String? {
    val lastBuildOk = steps.indexOfLast { it.kind == ActivityKind.BUILD && it.state == StepState.OK }
    val lastBuildFail = steps.indexOfLast { it.kind == ActivityKind.BUILD && it.state == StepState.FAIL }
    if (lastBuildFail > lastBuildOk) {
        // Model kecil mudah menyerah bila diberi jalan keluar "jelaskan mengapa tidak bisa". Jalan keluar itu
        // baru dibuka di dorongan terakhir; sebelumnya agen wajib memanggil alat dengan perbaikan hasil diagnosis.
        val dx = lastFailOut?.let { diagnoseFailure("./gradlew assembleDebug", it, sandbox) }
        val finalChance = nudgesUsed >= MAX_VERIFY_NUDGES - 1
        return SYS_PREFIX + "Build terakhir GAGAL dan belum ada build yang berhasil sesudahnya. " +
            (if (dx != null) "Penyebab yang terdeteksi dari error aslinya: ${dx.cause}. Kerjakan sekarang: ${dx.fix} "
            else "Kutip baris error pertama yang sebenarnya, perbaiki akar masalahnya, lalu jalankan build lagi. ") +
            "Jangan menjawab dengan teks saja: panggil alat sekarang. " +
            (if (finalChance) "Ini kesempatan terakhir. Bila setelah dicoba tetap gagal, laporkan error aslinya, bukti, dan apa saja yang sudah dicoba."
            else "Jangan menyatakan mustahil atau tidak bisa sebelum perbaikan di atas benar-benar dijalankan dan hasilnya dibaca.")
    }
    val lastCodeWrite = steps.indexOfLast { it.tool == "write_file" && it.state == StepState.OK && isCodeFile(it.target) }
    if (lastCodeWrite >= 0 && lastCodeWrite > lastBuildOk) {
        return SYS_PREFIX + "Kamu mengubah file kode tetapi belum menjalankan build yang berhasil sesudahnya. " +
            "Jalankan build/tes proyek untuk membuktikan perubahan bekerja, lalu laporkan hasil sebenarnya. " +
            "Bila build memang tidak bisa dijalankan di lingkungan ini (mis. mode sandbox tanpa alat build), " +
            "katakan dengan jujur bahwa perubahan belum terverifikasi."
    }
    return null
}

/** Baris pembungkus generik (Gradle dll.) yang muncul pada SEMUA kegagalan; tidak boleh jadi identitas error. */
private val GENERIC_ERR = Regex("""^(>\s*task\b|failure:|\*\s*(what went wrong|try)|build failed|execution failed for task)""", RegexOption.IGNORE_CASE)

// "^e:\s" = baris galat kompiler Kotlin ("e: A.kt: (12, 5): Type mismatch ..."), yang sering tanpa kata "error".
private val ERR_LINE = Regex("""error|exception|fail|cannot|can't|not found|no such|denied|unresolved|tidak ditemukan|gagal|^e:\s|mismatch|ambiguity|unexpected|missing""", RegexOption.IGNORE_CASE)

/** Tanda tangan error dari keluaran perintah gagal (angka dan path dinormalkan), atau null bila perintah tidak gagal. */
internal fun errorSignature(out: String): String? {
    val lines = out.lines()
    val head = lines.firstOrNull()?.trim().orEmpty()
    if (!head.startsWith("exit=") || head == "exit=0" || head.startsWith("exit=0 ")) return null
    val body = lines.drop(1).map { it.trim() }.filter { it.isNotEmpty() }
    // exit=1 tanpa keluaran sama sekali = jawaban "tidak ada" (grep tanpa hasil, diff berbeda, test -f salah), bukan error.
    // Dulu semuanya bertanda tangan sama ("exit=#") dan, karena hitungan kumulatif, menghentikan agen yang sehat.
    if (body.isEmpty() && head == "exit=1") return null
    // Utamakan baris error yang spesifik. Tanpa ini, setiap kegagalan Gradle bertanda tangan sama
    // ("FAILURE: Build failed") sehingga agen yang sedang memperbaiki error BERBEDA ikut dihentikan di kegagalan ke-4.
    val key = body.firstOrNull { ERR_LINE.containsMatchIn(it) && !GENERIC_ERR.containsMatchIn(it) }
        ?: body.firstOrNull { ERR_LINE.containsMatchIn(it) }
        ?: body.lastOrNull()
        ?: head
    return key.lowercase()
        .replace(Regex("""(/[\w.\-]+)+"""), "<p>")
        .replace(Regex("""\d+"""), "#")
        .take(120)
}

/** Menghitung kemunculan error yang sama dalam satu tugas. */
internal class ErrorTracker {
    private val counts = HashMap<String, Int>()

    /** Catat keluaran satu perintah; kembalikan berapa kali error ini sudah muncul (0 bila tidak gagal). */
    fun record(out: String): Int {
        val sig = errorSignature(out) ?: return 0
        val n = (counts[sig] ?: 0) + 1
        counts[sig] = n
        return n
    }

    /**
     * Kemajuan nyata (perintah yang mengubah keadaan berhasil, mis. memasang alat): hitungan error lama dikosongkan.
     * Menyiapkan lingkungan build di HP wajar melewati beberapa error yang sama sebelum berhasil; tanpa ini agen
     * dihentikan padahal sudah maju. Perintah yang hanya membaca tidak dihitung kemajuan (lihat [isProbeCommand]).
     */
    fun noteProgress() = counts.clear()
}

private val PROBE_CMDS = setOf(
    "ls", "cat", "head", "tail", "grep", "egrep", "fgrep", "rg", "find", "test", "[", "which", "whereis", "type", "command",
    "stat", "file", "wc", "diff", "cmp", "pwd", "echo", "printf", "printenv", "uname", "df", "du", "free", "ps",
    "pgrep", "java", "javac", "id", "whoami", "nproc", "date", "sort", "uniq", "cut", "tr", "readlink", "realpath"
)

/**
 * True untuk perintah yang hanya memeriksa keadaan (ls, cat, grep, java -version, ...). Kegagalannya adalah jawaban
 * ("tidak ada"), bukan kegagalan kerja, jadi tidak memicu protokol diagnosis dan tidak dianggap kemajuan bila berhasil.
 * Konservatif: penyambung perintah, redirect, atau substitusi membuat hasilnya false.
 */
internal fun isProbeCommand(command: String): Boolean {
    val c = command.trim()
    if (c.isEmpty()) return false
    if (c.any { it in ";&><`\n" } || c.contains("$(")) return false
    if (c.contains("-delete") || c.contains("-exec")) return false
    return c.split("|").all { seg ->
        val first = seg.trim().split(Regex("\\s+")).firstOrNull().orEmpty().substringAfterLast('/')
        first in PROBE_CMDS
    }
}

/** Apakah hasil alat yang gagal ini dihitung ke `failStreak` (pemicu protokol diagnosis). */
internal fun countsAsFailure(command: String, exitCode: Int?, stdout: String): Boolean =
    !(exitCode == 1 && stdout.isBlank()) && !isProbeCommand(command)

/**
 * Pipa yang ditutup lebih awal oleh penerimanya (`cat besar | head`) membuat penulis mati SIGPIPE: exit=141 karena
 * pipefail aktif. Itu bukan kegagalan. Hanya diubah bila perintahnya memang memakai pipa.
 */
internal fun softenSigpipe(command: String, out: String): String =
    if (out.startsWith("exit=141") && command.contains('|'))
        "exit=0 (SIGPIPE diabaikan: pipa ditutup lebih awal oleh perintah penerima, mis. head)" + out.removePrefix("exit=141")
    else out

/** Peringatan yang ditempelkan ke hasil alat untuk model bila error yang sama muncul berulang. */
internal fun repeatWarning(n: Int): String =
    "\n\n[Peringatan sistem: error yang sama sudah muncul $n kali. Jangan ulangi perintah serupa. " +
        "Ganti pendekatan: baca file/log penyebabnya, atau periksa asumsi awalmu. Bila buntu, jelaskan kendalanya dengan jujur.]"

/**
 * Padatkan keluaran GAGAL yang panjang: baris judul + baris error beserta 2 baris sekitarnya + ekor keluaran.
 * Keluaran sukses atau yang pendek tidak diubah. Kartu di layar tetap menampilkan keluaran penuh.
 */
internal fun condenseFailure(out: String, maxChars: Int = 3000): String {
    if (out.length <= maxChars || errorSignature(out) == null) return out
    val lines = out.lines()
    val keep = java.util.TreeSet<Int>()
    keep.add(0)
    lines.forEachIndexed { i, l ->
        if (i > 0 && ERR_LINE.containsMatchIn(l)) for (j in (i - 2)..(i + 2)) if (j in 1 until lines.size) keep.add(j)
    }
    for (j in maxOf(1, lines.size - 25) until lines.size) keep.add(j)
    val sb = StringBuilder()
    sb.append("[Keluaran gagal diringkas: ").append(out.length).append(" karakter -> bagian error dan ekor. Jalankan ulang dengan grep/tail bila perlu detail lain.]\n")
    var prev = -1
    for (i in keep) {
        if (prev >= 0 && i > prev + 1) sb.append("...\n")
        sb.append(lines[i]).append('\n')
        prev = i
    }
    val s = sb.toString().trimEnd()
    return if (s.length > maxChars) s.take(maxChars / 2) + "\n...\n" + s.takeLast(maxChars / 2 - 5) else s
}

/**
 * Hasil gagal untuk galat tak terduga pada satu alat. Diawali "exit=" agar dikenali [errorSignature]
 * dan dihitung [ErrorTracker] seperti galat perintah biasa.
 */
internal fun recoverableToolResult(e: Throwable): String =
    "exit=1 Alat gagal: ${(e.message ?: e.javaClass.simpleName).lineSequence().first().take(200)}\n" +
        "Baca penyebabnya, lalu coba pendekatan lain."

/** Hasil untuk perintah yang melewati batas waktu. Diawali "exit=" agar pengaman bisa mengenalinya. */
internal fun timeoutResult(timeoutSec: Long): String =
    "exit=124 Timeout: perintah melewati batas $timeoutSec detik dan dihentikan paksa (prosesnya sudah mati).\n" +
        "Cek dulu hasil sebagian di log atau cache. Unduhan dan hasil kompilasi yang sudah selesai biasanya tersimpan, " +
        "jadi untuk build panggil build_apk lagi (melanjutkan, bukan mulai dari nol); jangan mengubah kode hanya karena timeout. " +
        "Untuk perintah lain, pecah pekerjaan menjadi langkah lebih kecil."

/**
 * Mendeteksi agen yang macet: panggilan alat yang SAMA dengan hasil yang SAMA berturut-turut.
 * [record] dipanggil sesudah alat dijalankan dan mengembalikan true pada kemunculan ke-[limit].
 * (Sebelumnya hasil dibandingkan sebelum putaran ini selesai, sehingga baru berhenti di panggilan ke-5, bukan ke-3.)
 */
internal class RepeatDetector(private val limit: Int = MAX_SAME_CALLS) {
    private var lastCall = ""
    private var lastOut = ""
    private var count = 0

    /** [max] boleh dilonggarkan per panggilan: pemantauan log dan build yang dilanjutkan wajar mengulang panggilan yang sama. */
    fun record(callSig: String, outSig: String, max: Int = limit): Boolean {
        count = if (callSig.isNotEmpty() && callSig == lastCall && outSig == lastOut) count + 1 else 1
        lastCall = callSig
        lastOut = outSig
        return count >= max
    }
}


// ---- Batas tulis ulang per file dan respons kosong (logika murni, diuji di WriteTrackerTest) ----

/** Penimpaan file yang sama tanpa tes/build berhasil di antaranya: peringatan di ke-3, berhenti di ke-5 (penulisan pertama tidak dihitung). */
internal const val WARN_REWRITE_AT = 3
internal const val STOP_REWRITE_AT = 5

/** Tes/build sungguhan yang berhasil (bukan perintah baca seperti `cat test_all.py`) adalah bukti kemajuan. */
internal fun clearsRewriteCount(kind: VerificationKind, command: String): Boolean =
    (kind == VerificationKind.TESTS || kind == VerificationKind.APK) && !isProbeCommand(command)

/** Respons model kosong (tanpa teks dan tanpa panggilan alat): diulang sekali pada model yang sama, lalu dialihkan ke model lain. */
internal const val MAX_EMPTY_RETRIES = 1

/**
 * Menghitung berapa kali satu file ditimpa. Tes/build yang berhasil adalah bukti kemajuan: hitungan dikosongkan.
 * Pola A->B->A->B lolos dari [RepeatDetector] (hanya membandingkan dengan panggilan terakhir) dan dari [ErrorTracker]
 * (penulisan yang berhasil bukan error), sehingga agen bisa menimpa file yang sama puluhan kali tanpa terdeteksi.
 */
internal class WriteTracker {
    private val counts = HashMap<String, Int>()

    private fun norm(path: String) = path.trim().removePrefix("./")

    /** Catat satu penulisan yang BERHASIL; kembalikan jumlah penimpaan (0 untuk penulisan pertama). */
    fun record(path: String): Int {
        val key = norm(path)
        if (key.isEmpty()) return 0
        val n = (counts[key] ?: 0) + 1
        counts[key] = n
        return n - 1
    }

    /** Tes atau build berhasil: semua hitungan dikosongkan. */
    fun noteVerified() = counts.clear()
}

/** Peringatan yang ditempelkan ke hasil write_file untuk model saat satu file sudah ditimpa berulang kali. */
internal fun rewriteWarning(path: String, overwrites: Int): String =
    "\n\n[Peringatan sistem: $path sudah ditimpa $overwrites kali tanpa tes yang berhasil di antaranya. " +
        "Berhenti menulis ulang. Jalankan tes sekarang, baca output aslinya, lalu pastikan dulu apakah yang salah " +
        "implementasi atau tesnya. Ubah satu hal saja per langkah dan jangan menimpa seluruh file bila hanya satu fungsi yang salah.]"

internal fun rewriteStopMessage(path: String, overwrites: Int): String =
    "Berhenti: $path ditimpa $overwrites kali tanpa tes yang berhasil di antaranya. Jalankan tes sendiri atau beri arahan lain, lalu kirim 'lanjutkan'."
