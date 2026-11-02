package com.build.mythos

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

// Sisi Android dari audit dan upgrade diri (fungsi-fungsi ini ekstensi MainActivity). Logika keputusan ada di
// SelfAudit.kt, SelfUpgrade.kt, ApkVerify.kt, dan StartupHealth.kt (murni, teruji). Penjelasan utuh: SELF_UPGRADE.md.

internal const val SELF_ASSET = "kita-self.sh"

private const val PREF_BASE_HASH = "upg_base_hash"
private const val PREF_BASE_DIR = "upg_base_dir"
private const val PREF_PENDING = "upg_pending_code"
private const val PREF_BOOT_LAST = "boot_last_code"
private const val PREF_BOOT_UNHEALTHY = "boot_unhealthy"
private const val PREF_BOOT_GOOD = "boot_good_code"

internal fun MainActivity.selfScript(): String = assets.open(SELF_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }

// ---------------------------------------------------------------- fakta APK dan aplikasi terpasang

private fun sha256Hex(b: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

@Suppress("DEPRECATION")
private fun sigFlags(): Int =
    if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

@Suppress("DEPRECATION")
private fun certsOf(info: PackageInfo?): Set<String> {
    if (info == null) return emptySet()
    val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    return sigs?.map { sha256Hex(it.toByteArray()) }?.toSet() ?: emptySet()
}

@Suppress("DEPRECATION")
private fun versionOf(info: PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

@Suppress("DEPRECATION")
internal fun MainActivity.installedFacts(): ApkFacts {
    val info = packageManager.getPackageInfo(packageName, sigFlags())
    return ApkFacts(packageName, versionOf(info), certsOf(info))
}

/** Fakta APK di [f], atau null bila tidak terbaca. */
@Suppress("DEPRECATION")
internal fun MainActivity.apkFileFacts(f: File): ApkFacts? = try {
    val info = packageManager.getPackageArchiveInfo(f.absolutePath, sigFlags())
    if (info == null) null else ApkFacts(info.packageName ?: "", versionOf(info), certsOf(info))
} catch (e: Exception) {
    null
}

/** Dipakai installApk untuk SEMUA APK: hanya APK paket aplikasi ini yang diperiksa (APK aplikasi lain lolos). */
internal fun MainActivity.verifyApkFile(f: File, requireOwn: Boolean = false): ApkCheck {
    val installed = try {
        installedFacts()
    } catch (e: Exception) {
        return ApkCheck(emptyList(), listOf("Versi terpasang tidak terbaca; pemeriksaan upgrade dilewati."))
    }
    return evaluateApk(packageName, installed, apkFileFacts(f), requireOwn)
}

internal fun apkCheckText(c: ApkCheck): String =
    (c.problems.map { "- $it" } + c.warnings.map { "- (catatan) $it" }).joinToString("\n")

internal fun MainActivity.statusFacts(): StatusFacts {
    val info = try {
        packageManager.getPackageInfo(packageName, 0)
    } catch (e: Exception) {
        null
    }
    val termux = try {
        packageManager.getPackageInfo("com.termux", 0)
        true
    } catch (e: Exception) {
        false
    }
    return StatusFacts(
        versionName = info?.versionName ?: "?",
        versionCode = if (info != null) versionOf(info) else -1L,
        canInstall = packageManager.canRequestPackageInstalls(),
        termux = termux,
        runCommand = checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED,
        sandbox = sandboxEnabled(),
        readOnly = readOnlyMode(),
        autonomous = isAutonomous(),
        minimalConfirm = minimalConfirm(),
        boot = loadBootState()
    )
}

// ---------------------------------------------------------------- kesehatan startup

internal fun MainActivity.loadBootState(): BootState = BootState(
    prefs.getLong(PREF_BOOT_LAST, 0L), prefs.getInt(PREF_BOOT_UNHEALTHY, 0),
    prefs.getLong(PREF_BOOT_GOOD, 0L), prefs.getLong(PREF_PENDING, 0L)
)

internal fun MainActivity.saveBootState(s: BootState) {
    prefs.edit()
        .putLong(PREF_BOOT_LAST, s.lastBootCode)
        .putInt(PREF_BOOT_UNHEALTHY, s.unhealthyBoots)
        .putLong(PREF_BOOT_GOOD, s.lastGoodCode)
        .putLong(PREF_PENDING, s.pendingCode)
        .apply()
}

/** Dipanggil di akhir onCreate (setelah UI siap): catat bukaan, tampilkan peringatan bila perlu, dan jadwalkan tanda "sehat". */
internal fun MainActivity.startBootHealth() {
    val code = try {
        installedFacts().versionCode
    } catch (e: Exception) {
        return
    }
    val d = decideBoot(loadBootState(), code)
    saveBootState(d.next)
    d.message?.let { addNote(it) }
    window.decorView.postDelayed({ saveBootState(markHealthy(loadBootState(), code)) }, HEALTHY_AFTER_MS)
}

// ---------------------------------------------------------------- menjalankan skrip

internal const val SELF_SCRIPT_TIMEOUT_SEC = 900L

private fun MainActivity.runSelfScript(mode: String, dir: String, vararg args: String): String {
    val cmd = SelfScript.command(selfScript(), mode, dir, *args)
    // Audit mendalam memindai seluruh proyek: 300 dtk terlalu pendek (sering exit=124). Naikkan ke 900 dtk;
    // di sandbox pakai batas panjang (1800 dtk) lewat jalur build.
    return if (sandboxEnabled()) runInSandbox(cmd, true, null) else termux(cmd, limit = 24000, timeoutSec = SELF_SCRIPT_TIMEOUT_SEC)
}

/** Seperti runSelfScript, tetapi keluaran (termasuk baris KITA_PROGRESS) tampil live di kartu langkah selama audit berjalan. */
private fun MainActivity.runSelfScriptLive(card: MainActivity.StepCard, mode: String, dir: String, vararg args: String): String {
    val cmd = SelfScript.command(selfScript(), mode, dir, *args)
    val liveFile = "$termuxTmp/kita_live_${UUID.randomUUID()}.txt"
    val tail = LiveTail(this, liveFile, pollMs = 2000L) { card.liveOutput(it) }
    tail.start()
    return try {
        if (sandboxEnabled()) runInSandbox(cmd, true, liveFile)
        else termux(cmd, limit = 24000, timeoutSec = SELF_SCRIPT_TIMEOUT_SEC, liveFile = liveFile)
    } finally {
        tail.stop()
    }
}

private fun MainActivity.failTool(card: MainActivity.StepCard, note: String, msg: String): String {
    sawFail = true
    card.finish(false, note, msg, StepState.FAIL)
    return "exit=1 $msg"
}

private fun MainActivity.denyTool(card: MainActivity.StepCard, note: String, msg: String): String {
    card.finish(false, note, state = StepState.DENIED)
    return msg
}

private const val BAD_FOLDER =
    "Folder tidak valid: pakai path relatif terhadap folder kerja (huruf, angka, _ . / -), tanpa '..' dan tanpa awalan /. Gunakan '.' untuk folder kerja."

// ---------------------------------------------------------------- tool self_audit

internal fun MainActivity.toolSelfAudit(args: JSONObject, card: MainActivity.StepCard): String {
    // Validasi sebelum skrip dijalankan: folder harus direktori (bukan path file) dan aman.
    folderArgProblem("self_audit", args.optString("folder"))?.let { return failTool(card, "folder bukan direktori", it) }
    val dir = BuildApk.safeFolder(args.optString("folder")) ?: return failTool(card, "folder tidak valid", BAD_FOLDER)
    val started = System.currentTimeMillis()
    val out = runSelfScriptLive(card, "audit", dir, packageName)
    val facts = parseSelfFacts(out)
    if (!facts.done) {
        val hint = selfScriptFailureHint(out, SELF_SCRIPT_TIMEOUT_SEC)
        return failTool(card, "audit gagal", "Audit tidak selesai.\n" + (if (hint != null) "$hint\n" else "") + out.take(1500))
    }
    val secs = (System.currentTimeMillis() - started) / 1000
    val text = renderAudit(facts, renderStatus(statusFacts())) +
        "\n\nDurasi audit: $secs dtk (batas $SELF_SCRIPT_TIMEOUT_SEC dtk). Ini audit STATIS: tidak membangun, tidak menjalankan tes, dan tidak memeriksa checksum atau tanda tangan APK."
    card.finish(true, auditNote(facts), text)
    return "exit=0\n" + text
}

// ---------------------------------------------------------------- tool self_upgrade

internal fun MainActivity.toolSelfUpgrade(args: JSONObject, card: MainActivity.StepCard): String {
    val stage = UpgradeStage.parse(args.optString("tahap"))
        ?: return failTool(card, "tahap tidak dikenal", "Tahap harus salah satu dari: mulai, periksa, pasang, pulihkan.")
    folderArgProblem("self_upgrade", args.optString("folder"))?.let { return failTool(card, "folder bukan direktori", it) }
    val dir = BuildApk.safeFolder(args.optString("folder")) ?: return failTool(card, "folder tidak valid", BAD_FOLDER)
    val desc = args.optString("deskripsi")
    return when (stage) {
        UpgradeStage.MULAI -> selfMulai(dir, desc, card)
        UpgradeStage.PERIKSA -> selfPeriksa(dir, card)
        UpgradeStage.PASANG -> selfPasang(dir, desc, card)
        UpgradeStage.PULIHKAN -> selfPulihkan(dir, desc, card)
    }
}

private fun MainActivity.selfMulai(dir: String, desc: String, card: MainActivity.StepCard): String {
    if (readOnlyMode()) return denyTool(card, "mode hanya baca", "Ditolak: mode hanya baca aktif. Upgrade diri menulis cadangan dan mengubah kode.")
    // Periksa dulu (hanya membaca) bahwa folder ini memang kode aplikasi ini, sebelum menulis apa pun ke folder itu.
    val pre = parseSelfFacts(runSelfScript("audit", dir, packageName))
    if (!pre.done) return failTool(card, "audit gagal", "Folder $dir tidak bisa diaudit. Pastikan path proyeknya benar.")
    val id = pre.meta["app_id"].orEmpty()
    if (id != packageName) {
        return failTool(
            card, "bukan kode aplikasi ini",
            "Folder $dir bukan kode sumber aplikasi ini (applicationId \"$id\", seharusnya \"$packageName\"). Upgrade diri hanya untuk kode aplikasi ini; cari foldernya dengan self_audit."
        )
    }
    if (!confirm("Mulai upgrade diri di folder $dir.\n\nMembuat cadangan kode di .kita-upgrade/ dan mencatat kondisi awal.", null, desc)) {
        card.finish(false, if (lastDenyUnanswered) "tidak dijawab" else "ditolak", state = StepState.DENIED)
        return deniedResult()
    }
    val out = runSelfScript("baseline", dir, packageName)
    val b = parseBaseline(out)
    if (!b.ok) return failTool(card, "baseline gagal", "Baseline gagal: " + (b.error ?: out.take(600)))
    prefs.edit().putString(PREF_BASE_HASH, b.hash).putString(PREF_BASE_DIR, dir).apply()
    val installed = try {
        installedFacts().versionCode
    } catch (e: Exception) {
        -1L
    }
    val text = "UPGRADE DIRI DIMULAI\n" +
        "Cadangan: ${b.snapshot ?: "?"} (di .kita-upgrade/ pada folder $dir)\n" +
        "Versi terpasang: kode $installed. Di berkas: kode ${b.facts.meta["version_code"] ?: "?"}, ${b.facts.meta["tests"] ?: "?"} tes.\n" +
        "Kondisi awal: " + auditNote(b.facts) + " (skor arah ${auditScore(b.facts.findings)}/100).\n\n" +
        "Langkah berikutnya: ubah kode, NAIKKAN versionCode di build.gradle.kts di atas $installed, tambah atau perbarui tes, " +
        "jalankan build_apk (dan tes unit bila bisa), lalu self_upgrade tahap=periksa."
    card.finish(true, "baseline dicatat", text)
    return "exit=0\n" + text
}

private fun MainActivity.gateFor(dir: String): Pair<DiffFacts, GateResult>? {
    val out = runSelfScript("diff", dir, packageName)
    val d = parseDiff(out)
    val installed = try {
        installedFacts().versionCode
    } catch (e: Exception) {
        return null
    }
    val g = evaluateUpgrade(UpgradeInput(installed, packageName, d, prefs.getString(PREF_BASE_HASH, null)))
    return Pair(d, g)
}

private fun MainActivity.selfPeriksa(dir: String, card: MainActivity.StepCard): String {
    val (d, g) = gateFor(dir) ?: return failTool(card, "versi terpasang tidak terbaca", "Versi aplikasi yang terpasang tidak terbaca; gerbang tidak bisa dinilai.")
    if (!d.ok) return failTool(card, "diff gagal", "Pemeriksaan perubahan gagal: " + (d.error ?: "tidak diketahui"))
    val text = renderGate(g, d.changes)
    card.finish(g.verdict != Verdict.BLOCK, when (g.verdict) {
        Verdict.PASS -> "lulus"
        Verdict.NEEDS_USER -> "perlu persetujuan"
        Verdict.BLOCK -> "diblokir"
    }, text, if (g.verdict == Verdict.BLOCK) StepState.FAIL else null)
    return "exit=0\n" + text
}

private fun MainActivity.selfPasang(dir: String, desc: String, card: MainActivity.StepCard): String {
    if (readOnlyMode()) return denyTool(card, "mode hanya baca", "Ditolak: mode hanya baca aktif.")
    if (sandboxEnabled()) {
        return denyTool(
            card, "mode sandbox",
            "Ditolak: pasang upgrade diri belum didukung di mode sandbox karena APK berada di dalam sandbox, tidak terlihat oleh pemasang. " +
                "Matikan sandbox untuk tahap ini, atau bangun lewat GitHub Actions."
        )
    }
    val (d, g) = gateFor(dir) ?: return failTool(card, "versi terpasang tidak terbaca", "Versi aplikasi yang terpasang tidak terbaca; gerbang tidak bisa dinilai.")
    if (!d.ok) return failTool(card, "diff gagal", "Pemeriksaan perubahan gagal: " + (d.error ?: "tidak diketahui"))
    val gateText = renderGate(g, d.changes)
    if (g.verdict == Verdict.BLOCK) {
        card.finish(false, "diblokir", gateText, StepState.DENIED)
        return "exit=0\n" + gateText + "\n\nAPK TIDAK disiapkan. Perbaiki alasan di atas, lalu self_upgrade tahap=periksa lagi."
    }
    if (g.verdict == Verdict.NEEDS_USER) {
        val ok = confirm(
            "Upgrade diri butuh persetujuanmu:\n\n" + g.reviews.joinToString("\n") { "- $it" },
            null, desc,
            warning = "Perubahan menyentuh pengaman, izin, dependensi, atau belum diuji penuh. Baca daftarnya sebelum setuju.",
            force = true, routine = false
        )
        if (!ok) {
            card.finish(false, if (lastDenyUnanswered) "tidak dijawab" else "ditolak", gateText, StepState.DENIED)
            return deniedResult()
        }
    }
    val apkPath = d.apk.path ?: return failTool(card, "APK tidak ada", "APK tidak ditemukan.")
    val f = try {
        fetchApk(apkPath)
    } catch (e: Exception) {
        return failTool(card, "ambil APK gagal", "Gagal mengambil APK dari Termux: " + (e.message ?: "").take(160))
    }
    val facts = apkFileFacts(f)
    val check = verifyApkFile(f, requireOwn = true)
    if (!check.ok) {
        return failTool(card, "APK ditolak", "APK TIDAK lolos pemeriksaan:\n" + apkCheckText(check))
    }
    // Catat versi yang ditunggu supaya bukaan berikutnya dipantau (StartupHealth) sebagai hasil upgrade ini.
    if (facts != null) prefs.edit().putLong(PREF_PENDING, facts.versionCode).apply()
    addInstallCard(apkPath)
    val tail = if (check.warnings.isEmpty()) "" else "\n" + apkCheckText(check)
    card.finish(true, "siap dipasang", gateText + tail)
    return "exit=0\n" + gateText + tail + "\n\nAPK lolos pemeriksaan paket, versi, dan tanda tangan. Kartu 'Pasang APK' sudah tampil di chat: " +
        "pengguna sendiri yang harus mengetuknya dan menyetujui dialog pemasang Android. Jangan mengklaim sudah terpasang; aplikasi akan restart saat pemasangan selesai."
}

private fun MainActivity.selfPulihkan(dir: String, desc: String, card: MainActivity.StepCard): String {
    if (readOnlyMode()) return denyTool(card, "mode hanya baca", "Ditolak: mode hanya baca aktif.")
    if (!confirm(
            "Pulihkan kode di folder $dir ke kondisi sebelum upgrade (cadangan .kita-upgrade/).\n\nPerubahan sejak mulai akan DITIMPA, berkas baru dihapus, lalu versionCode dinaikkan agar bisa dipasang di atas versi sekarang.",
            null, desc,
            warning = "Menimpa kode di folder proyek dengan cadangan.",
            force = true, routine = false
        )
    ) {
        card.finish(false, if (lastDenyUnanswered) "tidak dijawab" else "ditolak", state = StepState.DENIED)
        return deniedResult()
    }
    val out = runSelfScript("restore", dir)
    val ok = out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("KITA_RESTORE=") }
    if (ok == null || !ok.startsWith("KITA_RESTORE=ok")) {
        val why = out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("KITA_ERR|") }?.removePrefix("KITA_ERR|") ?: (ok ?: out.take(400))
        return failTool(card, "pemulihan gagal", "Pemulihan gagal atau tidak tuntas: $why")
    }
    val installed = try {
        installedFacts().versionCode
    } catch (e: Exception) {
        return failTool(card, "versi terpasang tidak terbaca", "Versi aplikasi yang terpasang tidak terbaca.")
    }
    val a = parseSelfFacts(runSelfScript("audit", dir, packageName))
    val code = nextVersionCode(installed, a.metaLong("version_code"))
    val name = rollForwardName(a.meta["version_name"].orEmpty(), code)
    val bump = runSelfScript("bump", dir, code.toString(), name)
    if (!bump.contains("KITA_BUMP=ok")) return failTool(card, "naik versi gagal", "Kode dipulihkan tetapi versionCode gagal dinaikkan: " + bump.take(300))
    val text = "KODE DIPULIHKAN\nVersi di berkas sekarang: kode $code ($name), di atas versi terpasang ($installed).\n" +
        "Langkah berikutnya: build_apk, lalu self_upgrade tahap=periksa dan tahap=pasang. Gerbang akan meminta persetujuan karena build.gradle.kts berubah."
    card.finish(true, "kode dipulihkan", text)
    return "exit=0\n" + text
}
