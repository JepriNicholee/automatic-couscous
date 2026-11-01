package com.build.mythos

import android.app.AlertDialog
import android.util.Base64
import android.widget.TextView
import java.io.File

// Path, cadangan, undo, dan penulisan file lewat Termux.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

/** Isi file lama: "" kalau belum ada, null kalau tidak bisa dibaca penuh. */
internal fun MainActivity.readOld(path: String): String? {
    val r = termux("test -f ${sh(path)} && cat ${sh(path)}", 300000)
    return when {
        // Persis exit=1 (file tidak ada). startsWith("exit=1") juga cocok dengan exit=124/127/137.
        r == "exit=1" || r.startsWith("exit=1\n") -> ""
        r.startsWith("exit=0\n…(dipotong") -> null
        r.startsWith("exit=0\n") -> r.removePrefix("exit=0\n")
        else -> null
    }
}

/** Perluas awalan ~ (tanda kutip pada perintah mencegah shell memperluasnya sendiri). */
internal fun MainActivity.normPath(p: String): String {
    val t = p.trim()
    return when {
        t == "~" -> termuxHome
        t.startsWith("~/") -> termuxHome + "/" + t.removePrefix("~/")
        else -> t
    }
}

internal fun MainActivity.sh(s: String) = "'" + s.replace("'", "'\\''") + "'"

/** Workspace operasional mengikuti pengaturan; default tetap ~/MYTHOS. */
internal fun MainActivity.workspaceRoot(): String = WorkspacePaths.resolve(workdir(), termuxHome)

/** Source resmi tetap berada pada lokasi migrasi HOME/MYTHOS dan tidak ikut berpindah saat workdir diganti. */
internal fun MainActivity.referenceRoot(): String = "$termuxHome/MYTHOS/reference"

internal fun MainActivity.isProtectedReferencePath(path: String): Boolean {
    val p = collapsePath(if (path.startsWith("/")) path else workspaceRoot() + "/" + path)
    val r = collapsePath(referenceRoot())
    return p == r || p.startsWith("$r/")
}

/**
 * Pemeriksaan filesystem tambahan untuk write_file/build/create. realpath -m menyelesaikan symlink pada
 * parent path, termasuk saat target akhir belum ada. Gagal memverifikasi berarti ditolak (fail closed).
 */
internal fun MainActivity.referencePathGuard(path: String): String? = referencePathsGuard(listOf(path))

/** Periksa beberapa tujuan dalam satu proses Termux agar startup tidak melakukan satu panggilan per folder. */
internal fun MainActivity.referencePathsGuard(paths: Collection<String>): String? {
    val absolutePaths = paths.map { absPath(it) }.distinct()
    if (absolutePaths.isEmpty()) return null
    if (absolutePaths.any { isProtectedReferencePath(it) }) {
        return "Ditolak: target berada di MYTHOS/reference yang dilindungi. Salin source ke proyek aktif sebelum mengubahnya."
    }
    val cmd = WorkspacePaths.referenceGuardCommand(absolutePaths, referenceRoot()) { sh(it) }
    val result = try { termux(cmd, limit = 500, timeoutSec = 10, useWorkdir = false, killable = false) }
    catch (e: Exception) { "guard error: ${e.message}" }
    return when {
        result.startsWith("exit=0") && result.contains("MYTHOS_REFERENCE_PATH_OK") -> null
        result.contains("MYTHOS_REFERENCE_BLOCKED") -> "Ditolak: target mengarah melalui symlink ke MYTHOS/reference yang dilindungi."
        else -> "Ditolak: keamanan path tidak dapat diverifikasi oleh Termux (realpath). File tidak diubah."
    }
}

/** Tolak perubahan eksplisit pada reference. Copy hanya diizinkan bila sumber berada di reference dan tujuan di luarnya. */
internal fun MainActivity.touchesProtectedReferenceWrite(cmd: String): Boolean {
    val ref = referenceRoot()
    val workspace = workspaceRoot()
    if (!ReferencePolicy.mentionsProtectedReference(cmd, ref, workspace)) return false
    if (ReferencePolicy.isAllowedAlatCall(cmd, ref, workspace)) return false
    val copyOutTarget = ReferencePolicy.safeCopyOutDestination(cmd, ref, workspace)
    if (copyOutTarget != null) {
        // Jangan percaya tujuan berdasarkan teks saja: symlink tujuan bisa menunjuk kembali ke reference.
        return referencePathGuard(copyOutTarget) != null
    }
    return true
}

/** Path absolut yang dinormalkan terhadap workspace terpilih. */
internal fun MainActivity.absPath(p: String): String =
    collapsePath(if (p.startsWith("/")) p else workspaceRoot() + "/" + p)

/** Salin file lama ke workspace/backups/.kita_bak sebelum ditimpa (simpan 200 terakhir). Null kalau file belum ada. */
internal fun MainActivity.backupFile(path: String): String? {
    val dir = "${workspaceRoot()}/backups/.kita_bak"
    if (referencePathGuard(dir) != null) return null
    val bak = "$dir/${System.currentTimeMillis()}_" + path.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
    val r = try {
        termux(
            "mkdir -p ${sh(dir)} && if [ -f ${sh(path)} ]; then cp -p ${sh(path)} ${sh(bak)} && echo HAD_FILE; else echo NO_FILE; fi; " +
                "(cd ${sh(dir)} && ls -1t | tail -n +201 | xargs -r rm -f --) 2>/dev/null"
        )
    } catch (e: Cancelled) {
        throw e
    } catch (e: Exception) {
        return null
    }
    return if (r.contains("HAD_FILE")) bak else null
}

internal fun MainActivity.undoWrite(path: String, bak: String, btn: TextView, label: String) {
    AlertDialog.Builder(this)
        .setMessage("Kembalikan $path ke versi sebelum diubah?")
        .setPositiveButton("Kembalikan") { _, _ ->
            btn.isEnabled = false
            btn.text = "Mengembalikan..."
            bg {
                val r = try {
                    val blocked = referencePathGuard(path)
                    if (blocked != null) "exit=1 $blocked" else termux("cp -p ${sh(bak)} ${sh(path)} && echo OK")
                } catch (e: Exception) {
                    "gagal: ${e.message}"
                }
                val ok = r.startsWith("exit=0") && r.trim().endsWith("OK")
                runOnUiThread {
                    if (ok) {
                        btn.text = "Sudah dikembalikan"
                        toast("File dikembalikan")
                    } else {
                        btn.isEnabled = true
                        btn.text = label
                        toast("Gagal mengembalikan: " + r.take(80))
                    }
                }
            }
        }
        .setNegativeButton("Batal", null)
        .show()
}


/** Buat struktur operasional workspace terpilih tanpa mengubah atau memindahkan isi yang sudah ada. */
internal fun MainActivity.ensureWorkspaceLayout() {
    val root = workspaceRoot()
    // Periksa setiap target karena symlink di dalam workspace (mis. projects/ atau logs/) juga bisa menunjuk reference.
    val relativeDirs = listOf("projects/aktif", "projects/arsip", "artifacts", "attachments", "backups", "logs", "memory")
    referencePathsGuard(relativeDirs.map { "$root/$it" })?.let { reason ->
        logQuiet("TermuxFiles.kt", IllegalStateException("Workspace tidak aman: $reason"))
        return
    }
    val command = "mkdir -p " + relativeDirs.joinToString(" ") { sh("$root/$it") }
    val result = termux(command, limit = 500, timeoutSec = 30, useWorkdir = false, killable = false)
    if (!result.startsWith("exit=0")) logQuiet("TermuxFiles.kt", RuntimeException("Gagal menyiapkan workspace: ${result.take(180)}"))
}

/** Path absolut folder kerja di Termux, atau null kalau memakai home. */
internal fun MainActivity.wdTarget(home: String): String? {
    val wd = WorkspacePaths.normalizeSetting(workdir()) ?: "MYTHOS"
    return when {
        wd.isEmpty() || wd == "~" -> null
        else -> WorkspacePaths.resolve(wd, home)
    }
}

/** Awalan perintah yang pindah ke folder kerja yang dipilih di Pengaturan. */
internal fun MainActivity.cdPrefix(home: String): String {
    val selectedTarget = wdTarget(home)
    val target = selectedTarget ?: home
    val reference = referenceRoot()
    val guardedDirs = listOf("projects/aktif", "projects/arsip", "artifacts", "attachments", "backups", "logs", "memory")
    val checks = guardedDirs.mapIndexed { index, relative ->
        "candidate$index=\$(realpath -m -- ${sh("$target/$relative")}) || { echo MYTHOS_PATH_GUARD_ERROR; exit 1; }; " +
            "case \"\$candidate$index\" in \"\$reference_real\"|\"\$reference_real\"/*) echo MYTHOS_REFERENCE_BLOCKED; exit 1;; esac"
    }.joinToString("\n")
    val createAndCd = if (selectedTarget == null) "" else
        "mkdir -p " + guardedDirs.joinToString(" ") { sh("$target/$it") } + " 2>/dev/null || { echo ${sh("Gagal membuat folder kerja: $target")}; exit 1; }; " +
            "cd ${sh(target)} 2>/dev/null || { echo ${sh("Folder kerja tidak ditemukan: $target")}; exit 1; }; "
    return "command -v realpath >/dev/null 2>&1 || { echo MYTHOS_PATH_GUARD_UNAVAILABLE; exit 1; }; " +
        "reference_real=\$(realpath -m -- ${sh(reference)}) || { echo MYTHOS_PATH_GUARD_ERROR; exit 1; }; " +
        checks + "\n" + createAndCd
}

/**
 * Tulis file lewat potongan base64 kecil (satu argumen perintah dibatasi ±128 KB), ke file sementara,
 * lalu ganti file tujuan dengan mv. File lama tidak terkosongkan kalau gagal; izin (chmod) file lama dipertahankan.
 * Hasil berakhiran "OK" kalau berhasil.
 */
internal fun MainActivity.writeFileChunked(path: String, content: String): String {
    referencePathGuard(path)?.let { return "exit=1 $it" }
    val b64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
    val tmpDir = "/data/data/com.termux/files/usr/tmp"
    val tmpB = "$tmpDir/kita_${System.nanoTime()}.b64"
    val tmpOut = path + ".kita-tmp"
    fun cleanup() { try { termux("rm -f ${sh(tmpB)} ${sh(tmpOut)}") } catch (e: Exception) { logQuiet("TermuxFiles.kt", e) } }
    var r = termux("mkdir -p \"\$(dirname ${sh(path)})\" && mkdir -p ${sh(tmpDir)} && : > ${sh(tmpB)}")
    if (!r.startsWith("exit=0")) return r
    val size = 60000
    var i = 0
    while (i < b64.length) {
        if (cancelled) { cleanup(); throw Cancelled() }
        val part = b64.substring(i, minOf(i + size, b64.length))
        r = termux("printf '%s' '$part' >> ${sh(tmpB)}")
        if (!r.startsWith("exit=0")) { cleanup(); return r }
        i += size
    }
    return termux(
        "base64 -d ${sh(tmpB)} > ${sh(tmpOut)} && " +
            "{ [ ! -e ${sh(path)} ] || chmod --reference=${sh(path)} ${sh(tmpOut)} 2>/dev/null; true; } && " +
            "mv -f ${sh(tmpOut)} ${sh(path)} && echo OK; rm -f ${sh(tmpB)} ${sh(tmpOut)}"
    )
}
