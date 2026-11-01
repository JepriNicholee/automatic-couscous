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
    // Fakta dari pengaturan aplikasi (bukan dari folder): token dan repo GitHub dibutuhkan build_apk.
    val extra = ArrayList<String>()
    if (isProtectedReferencePath(absPath(dir))) {
        extra.add("folder ini di MYTHOS/reference (hanya baca): salin ke projects/aktif sebelum mengubah atau membangun")
    }
    if (prefs.getString("github_token", "").isNullOrBlank()) {
        extra.add("Token GitHub belum diisi di Pengaturan: build_apk akan gagal pada pemeriksaan autentikasi")
    }
    val text = ProjectCheck.render(report, extra)
    val ok = report.ready
    if (!ok) sawFail = true
    card.finish(ok, if (ok) "struktur siap" else "${report.fails.size} masalah", text, if (ok) null else StepState.FAIL)
    return (if (ok) "exit=0\n" else "exit=1\n") + text
}
