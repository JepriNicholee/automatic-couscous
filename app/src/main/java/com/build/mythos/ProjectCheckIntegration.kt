package com.build.mythos

import org.json.JSONObject

// Sisi Android dari tool validate_project: memeriksa struktur proyek SEBELUM build_apk (hanya membaca, tanpa persetujuan).
// Logika skrip dan laporan ada di ToolGuidance.kt (ProjectCheck, murni dan teruji).

internal fun MainActivity.toolValidateProject(args: JSONObject, card: MainActivity.StepCard): String {
    val raw = args.optString("folder")
    val problem = folderArgProblem("validate_project", raw)
    val dir = BuildApk.safeFolder(raw)
    if (dir == null || problem != null) {
        sawFail = true
        card.finish(false, "folder tidak valid", state = StepState.FAIL)
        return "exit=1 " + (problem ?: "Folder tidak valid: pakai path relatif terhadap folder kerja (huruf, angka, _ . / -), tanpa '..' dan tanpa awalan /. Gunakan '.' untuk folder kerja.")
    }
    val cmd = ProjectCheck.command(dir)
    val out = if (sandboxEnabled()) runInSandbox(cmd, false, null) else termux(cmd, limit = 8000, timeoutSec = 60)
    val report = ProjectCheck.parse(out)
    if (!report.done) {
        sawFail = true
        val msg = "Validasi tidak selesai.\n" + out.take(1200)
        card.finish(false, "validasi gagal", msg, StepState.FAIL)
        return "exit=1 $msg"
    }
    // Fakta autentikasi: build_apk menerima token aplikasi atau sesi gh CLI yang sudah login di Termux.
    val extra = ArrayList<String>()
    if (isProtectedReferencePath(absPath(dir))) {
        extra.add("folder ini di MYTHOS/reference (hanya baca): salin ke projects/aktif sebelum mengubah atau membangun")
    }
    if (prefs.getString("github_token", "").isNullOrBlank()) {
        // build_apk mendukung autentikasi gh CLI yang sudah ada di Termux; jangan salah menandainya
        // belum siap hanya karena token tidak disimpan ulang di preferensi Android.
        val termuxGhReady = if (!sandboxEnabled()) {
            try {
                termux(
                    "command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1",
                    limit = 200, timeoutSec = 10, useWorkdir = false, killable = false
                ).startsWith("exit=0")
            } catch (_: Exception) { false }
        } else false
        if (!termuxGhReady) {
            extra.add("Autentikasi GitHub belum tersedia: isi Token GitHub di Pengaturan atau jalankan gh auth login di Termux")
        }
    }
    val text = ProjectCheck.render(report, extra)
    val ok = report.ready
    if (!ok) sawFail = true
    card.finish(ok, if (ok) "struktur siap" else "${report.fails.size} masalah", text, if (ok) null else StepState.FAIL)
    return (if (ok) "exit=0\n" else "exit=1\n") + text
}
