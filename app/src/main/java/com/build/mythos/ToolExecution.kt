package com.build.mythos

import java.io.File
import java.util.UUID
import org.json.JSONObject

// Eksekusi alat: persetujuan perintah, catatan, mode baca-saja, runTool.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

/** True hanya untuk perintah baca-saja yang jelas aman. Ragu sedikit pun -> minta persetujuan. */
internal fun MainActivity.isReadOnly(cmd: String): Boolean {
    val c = cmd.trim()
    if (c.isEmpty()) return false
    // Tanpa penyambung perintah, redirect, substitusi, variabel, atau escape.
    if (c.any { it in ";&><`$\n\r\\(){}" }) return false
    val segs = c.split("|")
    for (seg in segs) {
        val tok = seg.trim().split(Regex("\\s+"))
        val name = tok[0]
        if (name.isEmpty() || name !in roCmds) return false
        val args = tok.drop(1)
        if (name == "find" && args.any { it in findBad }) return false
        if (name == "tail" && args.any { (it.startsWith("-") && !it.startsWith("--") && (it.contains('f') || it.contains('F'))) || it.startsWith("--follow") || it == "--retry" }) return false
        if (name == "tree" && args.any { it == "-o" || it.startsWith("--output") || (it.startsWith("-") && !it.startsWith("--") && it.contains('o')) }) return false
    }
    return true
}

internal fun MainActivity.readOnlyMode() = prefs.getBoolean("readonly", false)

/** Konfirmasi minimal: selalu aktif (saklarnya dihapus dari Pengaturan). Hanya pertanyaan AI, hapus data, dan area sensitif yang meminta jawaban. */
@Suppress("UnusedReceiverParameter")
internal fun MainActivity.minimalConfirm() = true

/**
 * Persetujuan lewat dialog. Tidak ada tindakan yang ditolak otomatis: area sensitif hanya diberi peringatan merah.
 * "Ya, semua" berlaku untuk sisa tugas ini. Tanpa jawaban dalam 10 menit dianggap tidak.
 */
internal fun MainActivity.confirm(
    msg: String, cmd: String? = null, desc: String = "",
    onAll: (() -> Unit)? = null, allLabel: String = "Ya, semua", warning: String? = null,
    force: Boolean = false,  // true: tidak bisa dilewati oleh mode otonom / "Ya, semua" (mis. perintah berjaringan di sandbox)
    routine: Boolean = true  // false: selalu bertanya, tidak ikut konfirmasi minimal (mis. menjalankan CLI)
): Boolean {
    // "Ya, semua" tidak membungkam peringatan area sensitif: tindakan bertanda peringatan tetap bertanya.
    // Hanya mode otonom (opt-in eksplisit di Pengaturan) yang melewatinya. [force] tidak bisa dilewati keduanya.
    if (bypassesApproval(allowAll && warning == null, isAutonomous(), force)) { lastApprovalMode = ApprovalMode.ALL_SESSION; return true }
    // Konfirmasi minimal (bawaan): langkah rutin jalan tanpa dialog; hanya hapus data, peringatan, dan [force] yang bertanya.
    val risk = if (cmd != null) classify(cmd) else Risk.WRITE // tulis file selalu dicadangkan dulu, jadi diperlakukan WRITE
    if (routine && minimalAutoApproves(minimalConfirm(), risk, warning, force)) { lastApprovalMode = ApprovalMode.AUTO_MINIMAL; return true }
    agentState = AgentState.AwaitingApproval(cmd ?: "approval")
    lastDenyUnanswered = false
    val latch = java.util.concurrent.CountDownLatch(1)
    val answer = java.util.concurrent.atomic.AtomicInteger(0) // 0 belum, 1 ya, 2 ya semua, 3 tidak (eksplisit), 4 tidak ada jawaban
    pendingWaits.add(latch)
    runOnUiThread {
        val d = showApprovalDialog(
            this,
            ApprovalRequest(cmd ?: msg, desc),
            onYes = { answer.set(1); latch.countDown() },
            onYesAll = { onAll?.invoke(); answer.set(2); latch.countDown() },
            onNo = { answer.set(3); latch.countDown() },
            offerAll = !force && (!minimalConfirm() || onAll != null),
            simple = minimalConfirm(),
            allLabel = allLabel,
            warning = warning
        )
        d.setOnDismissListener {
            if (answer.get() == 0) { answer.set(4); latch.countDown() }
            hideKeyboard()
        }
        confirmDialog = d
    }
    try {
        if (!latch.await(10, java.util.concurrent.TimeUnit.MINUTES)) {
            // Tanpa jawaban: anggap "tidak" dan tutup dialognya, supaya tombol "Ya" yang masih tampil tidak menipu.
            answer.compareAndSet(0, 4)
            runOnUiThread { try { confirmDialog?.dismiss() } catch (e: Exception) { logQuiet("ToolExecution.kt", e) } }
        }
    } finally {
        pendingWaits.remove(latch)
        activeStep?.setWaiting(false)
    }
    val a = answer.get()
    if (a == 2) { allowAll = true; lastApprovalMode = ApprovalMode.ALL_BUTTON }
    else if (a == 1) lastApprovalMode = ApprovalMode.MANUAL
    // 0 = pemanggil dilepas (Activity ditutup/diputar), 4 = dialog ditutup atau 10 menit habis: bukan penolakan eksplisit.
    lastDenyUnanswered = a == 0 || a == 4
    return a == 1 || a == 2
}

/**
 * Hasil alat bila persetujuan tidak didapat. Membedakan penolakan eksplisit dari "tidak ada jawaban",
 * karena yang pertama tidak boleh diulang, sedangkan yang kedua boleh diajukan lagi sekali.
 * Diawali "Ditolak" agar dikenali parseToolResult (USER_DENIED) dan stateOfResult (DENIED).
 */
internal fun MainActivity.deniedResult(): String =
    if (lastDenyUnanswered)
        "Ditolak: tidak ada jawaban persetujuan (dialog ditutup, layar diputar, atau 10 menit tanpa respons). " +
            "Tindakan TIDAK dijalankan. Ini belum tentu penolakan: boleh mengajukannya sekali lagi bila memang perlu, atau pilih pendekatan lain."
    else
        "Ditolak pengguna. Tindakan TIDAK dijalankan. Jangan mengulang tindakan yang sama; pilih pendekatan lain" +
            (if (isAutonomous()) " atau jelaskan kendalanya di jawaban akhir." else ", tanyakan alasannya lewat ask_user, atau jelaskan kendalanya.")

/** Catat perintah/penulisan yang sudah disetujui. Tidak boleh pernah mengganggu alur agen. */
internal fun MainActivity.logApproved(
    kind: String, command: String, desc: String, risk: Risk,
    mode: ApprovalMode, ok: Boolean, result: String,
    exitCode: Int? = null, decision: String = "disetujui", sandbox: Boolean = false, network: Boolean = false
) {
    try {
        CommandLog.append(this, LogEntry(System.currentTimeMillis(), kind, command, desc, risk, mode, ok, result, exitCode, decision, sandbox, network))
    } catch (e: Exception) {
    }
}

internal fun MainActivity.runTool(name: String, args: JSONObject, card: MainActivity.StepCard): String = if (args.has("__error")) {
    val msg = args.optString("__error")
    sawFail = true
    card.finish(false, "args rusak", msg)
    "Error: $msg"
} else when (name) {
    "run_command" -> {
        val cmd = args.optString("command")
        val sb = sandboxVerdict(cmd) // null bila mode sandbox mati
        val warn = sensitiveReason(cmd) ?: if (TERMUX_SYSTEM.containsMatchIn(cmd) && classify(cmd) != Risk.READ) "folder sistem Termux (perubahan)" else null
        val care = needsExtraCare(cmd) // WRITE yang berbahaya: tetap bertanya walau konfirmasi minimal aktif
        val auto = prefs.getBoolean("autoread", true) && isReadOnly(cmd) && warn == null &&
            (sb == null || sb.decision == Decision.SAFE)
        if (buildRe.containsMatchIn(cmd)) sawBuild = true
        val toolInShell = detectToolCodeInShell(cmd) // alat dipanggil seperti fungsi shell/Python (mis. tool_api)
        val rawSid = args.optString("session_id")
        val sid = SessionShell.cleanId(rawSid)
        if (SessionShell.isBadId(rawSid)) {
            card.finish(false, "session_id tidak valid", state = StepState.DENIED)
            "Ditolak: session_id hanya boleh huruf, angka, _ dan -, maksimal 32 karakter. Perintah TIDAK dijalankan."
        } else if (toolInShell != null) {
            card.finish(false, "bukan perintah shell", state = StepState.DENIED)
            toolInShell
        } else if (sb != null && sb.decision == Decision.BLOCKED) {
            logSandboxOutcome(cmd, args.optString("deskripsi"), sb, "diblokir")
            card.finish(false, "diblokir sandbox", state = StepState.DENIED)
            "Diblokir oleh sandbox: ${sb.reasons.joinToString("; ")}. Perintah TIDAK dijalankan. Gunakan pendekatan lain di dalam workdir sandbox."
        } else if (classify(cmd) != Risk.READ && touchesProtectedReferenceWrite(cmd)) {
            card.finish(false, "source reference terkunci", state = StepState.DENIED)
            "Ditolak: folder MYTHOS/reference (source dan alat referensi) terkunci. Agen hanya boleh membaca atau menyalin source itu ke MYTHOS/projects/aktif."
        } else if (readOnlyMode() && classify(cmd) != Risk.READ) {
            card.finish(false, "mode hanya baca", state = StepState.DENIED)
            "Ditolak: mode hanya baca aktif. Hanya perintah yang membaca yang diizinkan."
        } else if (!auto && !confirm(
                "Jalankan di Termux:\n\n$cmd", cmd, args.optString("deskripsi"),
                warning = listOfNotNull(warn, care, sb?.warning()).joinToString("\n").ifBlank { null },
                force = sb?.network == true
            )
        ) {
            if (sb != null) logSandboxOutcome(cmd, args.optString("deskripsi"), sb, "ditolak")
            card.finish(false, if (lastDenyUnanswered) "tidak dijawab" else "ditolak", state = StepState.DENIED)
            deniedResult()
        } else {
            val desc = args.optString("deskripsi")
            val risk = classify(cmd)
            val mode = if (auto) ApprovalMode.AUTO_READ else lastApprovalMode
            val liveFile = "$termuxTmp/kita_live_${UUID.randomUUID()}.txt"
            // Definisi "build" disamakan dengan yang dipakai verifikasi dan laporan (activityKindOf). Sebelumnya
            // `./gradlew build` atau `testDebugUnitTest` dihitung BUILD untuk verifikasi tetapi hanya diberi batas 600 dtk.
            val isBuild = activityKindOf(cmd) == ActivityKind.BUILD
            val tail = LiveTail(this, liveFile, pollMs = if (isBuild) 5000L else LiveTail.POLL_MS) { card.liveOutput(it) }
            tail.start()
            val res = try {
                // Kebijakan dan persetujuan memeriksa [cmd] asli; pembungkus sesi baru ditambahkan SESUDAH itu.
                val runCmd = if (sid == null) cmd else SessionShell.wrap(cmd, sid, if (sb != null) SessionShell.DIR_SANDBOX else SessionShell.DIR_TERMUX)
                if (sb != null) runInSandbox(runCmd, isBuild, liveFile)
                else termux(runCmd, limit = if (isBuild) 8000 else 4000, timeoutSec = if (isBuild) 1800 else 600, liveFile = liveFile)
            } catch (c: Cancelled) {
                // perintah sudah disetujui dan mungkin sudah mulai jalan: tetap dicatat
                logApproved("perintah", cmd, desc, risk, mode, false, "Dihentikan sebelum selesai")
                throw c
            } finally {
                tail.stop()
            }
            val ok = res.startsWith("exit=0")
            if (!ok) setStatus(IconView.CLOCK, "Menemukan masalah", res.lineSequence().first().take(120), quiet = true)
            logApproved(
                "perintah", cmd, desc, risk, mode, ok, summarizeResult(res, hideOutput = warn != null),
                exitCode = parseTermuxResult(res).first, decision = if (auto || mode == ApprovalMode.AUTO_MINIMAL) "otomatis" else "disetujui",
                sandbox = sb != null, network = sb?.network == true
            )
            if (!ok) sawFail = true
            card.finish(ok, if (ok) "" else res.lineSequence().first().take(24), "$ $cmd\n\n" + res.substringAfter("\n", ""))
            if (ok && buildRe.containsMatchIn(cmd)) {
                setStatus(IconView.CHECK, "Memvalidasi hasil", quiet = true)
                findApk()?.let { runLog.apkPath = it; recordArtifact(it, "apk"); addInstallCard(it) }
            }
            res
        }
    }
    "write_file" -> {
        val path = normPath(args.optString("path"))
        val content = args.optString("content")
        val old = if (readOnlyMode() || sandboxEnabled()) null else readOld(path)
        val diff = if (old != null) buildDiff(old, content) else null
        val info = diff?.second ?: "${content.length} karakter"
        val abs = absPath(path)
        val wdBase = (wdTarget(termuxHome) ?: termuxHome).trimEnd('/')
        val warnW = sensitiveReason(abs) ?: if (TERMUX_SYSTEM.containsMatchIn(abs)) "folder sistem Termux" else null
            ?: if (abs == wdBase || abs.startsWith("$wdBase/")) null else "File ini di luar folder kerja"
        if (sandboxEnabled()) {
            card.finish(false, "mode sandbox", state = StepState.DENIED)
            "Ditolak: mode sandbox aktif, write_file menulis ke Termux utama (di luar sandbox). Tulis file lewat run_command di dalam workdir sandbox (mis. printf atau tee)."
        } else if (isProtectedReferencePath(abs)) {
            card.finish(false, "source reference terkunci", state = StepState.DENIED)
            "Ditolak: folder MYTHOS/reference (source dan alat referensi) hanya untuk dibaca dan disalin. Buat perubahan di MYTHOS/projects/aktif."
        } else if (readOnlyMode()) {
            card.finish(false, "mode hanya baca", state = StepState.DENIED)
            "Ditolak: mode hanya baca aktif. Jangan menulis atau mengubah file."
        } else if (!confirm("Tulis file: $path ($info)", warning = warnW)) {
            card.finish(false, if (lastDenyUnanswered) "tidak dijawab" else "ditolak", diff?.first, StepState.DENIED)
            deniedResult()
        } else {
            val mode = lastApprovalMode
            val wDesc = args.optString("deskripsi")
            val wCmd = "Tulis file: $path ($info)"
            // readOld() mengembalikan "" untuk file yang belum ada (file baru = WRITE) dan null bila file ada tapi
            // terlalu besar untuk dibaca penuh (tetap dianggap menimpa data). Dulu "" ikut dihitung DELETE.
            val wRisk = if (old == null || old.isNotEmpty()) Risk.DELETE else Risk.WRITE
            val bak = try {
                backupFile(path)
            } catch (c: Cancelled) {
                logApproved("tulis file", wCmd, wDesc, wRisk, mode, false, "Dihentikan sebelum selesai")
                throw c
            }
            // File lama ada (wRisk DELETE) tetapi cadangan gagal dibuat: jangan menimpa tanpa jalan kembali.
            if (wRisk == Risk.DELETE && bak == null) {
                sawFail = true
                logApproved("tulis file", wCmd, wDesc, wRisk, mode, false, "Dibatalkan: cadangan file lama gagal dibuat")
                card.finish(false, "cadangan gagal", state = StepState.FAIL)
                "exit=1 Penulisan dibatalkan: file lama tidak bisa dicadangkan (cek ruang penyimpanan dan izin ~/.kita_bak). " +
                    "File TIDAK diubah. Coba lagi, atau tulis lewat run_command bila pengguna memang menginginkannya."
            } else {
                val res = try {
                    writeFileChunked(path, content)
                } catch (c: Cancelled) {
                    logApproved("tulis file", wCmd, wDesc, wRisk, mode, false, "Dihentikan sebelum selesai")
                    throw c
                }
                val ok = res.trim().endsWith("OK")
                logApproved(
                    "tulis file", wCmd, wDesc, wRisk, mode, ok,
                    if (ok) "Berhasil ditulis" else summarizeResult(res, hideOutput = warnW != null)
                )
                if (ok) recordArtifact(absPath(path), "file")
                if (ok && bak != null) addUndoCard(absPath(path), bak)
                if (!ok) sawFail = true
                val note = if (!ok) "gagal" else (diff?.second ?: "${content.lines().size} baris")
                card.finish(ok, note, diff?.first ?: content.take(1500))
                // Perintah penulisan diakhiri `rm -f` sehingga exit-nya selalu 0 walau mv/base64 gagal. Tanpa "OK" berarti gagal:
                // jangan biarkan model membaca "exit=0" untuk penulisan yang gagal.
                if (ok || !res.startsWith("exit=0")) res
                else "exit=1 Gagal menulis file $path: tidak ada konfirmasi OK.\n" + res.substringAfter("\n", "")
            }
        }
    }
    "create_android_project" -> {
        val folder = StudioProject.safeFolder(args.optString("folder"))
        val appName = StudioProject.safeName(args.optString("nama"))
        val pkg = StudioProject.safePackage(args.optString("package"))
        val desc = args.optString("deskripsi").ifBlank { "Membuat proyek Android" }
        if (folder == null || appName == null || pkg == null) {
            sawFail = true
            card.finish(false, "parameter tidak valid", state = StepState.FAIL)
            "exit=1 Parameter tidak valid. folder harus relatif tanpa '..'; nama harus berupa nama aplikasi sederhana; package harus seperti com.example.app."
        } else if (isProtectedReferencePath(absPath(folder))) {
            card.finish(false, "source reference terkunci", state = StepState.DENIED)
            "Ditolak: proyek baru tidak boleh dibuat di source reference. Gunakan folder MYTHOS/projects/aktif."
        } else if (readOnlyMode()) {
            card.finish(false, "mode hanya baca", state = StepState.DENIED)
            "Ditolak: mode hanya baca aktif. Pembuatan proyek menulis banyak file."
        } else if (!confirm(
                "Buat proyek Android baru di folder $folder dengan nama $appName?",
                null, desc, warning = "Akan membuat dan menulis struktur proyek baru"
            )
        ) {
            card.finish(false, if (lastDenyUnanswered) "tidak dijawab" else "ditolak", state = StepState.DENIED)
            deniedResult()
        } else {
            val mode = lastApprovalMode
            val liveFile = "$termuxTmp/kita_live_${UUID.randomUUID()}.txt"
            val tail = LiveTail(this, liveFile, pollMs = 1000L) { card.liveOutput(it) }
            tail.start()
            val out = try {
                val cmd = StudioProject.setupCommand(folder, appName, pkg)
                if (sandboxEnabled()) runInSandbox(cmd, false, liveFile)
                else termux(cmd, limit = 12000, timeoutSec = 120, liveFile = liveFile)
            } finally { tail.stop() }
            val ok = out.contains("STUDIO_PROJECT=ok")
            logApproved("buat proyek", "create_android_project $folder", desc, Risk.WRITE, mode, ok, summarizeResult(out, hideOutput = false))
            if (!ok) sawFail = true
            val visibleOut = out.lineSequence()
                .filterNot { it.startsWith("STUDIO_PROJECT=") || it.startsWith("STUDIO_NEXT=") }
                .joinToString("\n")
                .trim()
            card.finish(ok, if (ok) "proyek siap" else "gagal", "$ create_android_project $folder\n\n" + visibleOut)
            if (ok) {
                // Sumber kebenaran path untuk seluruh langkah berikutnya; model tidak boleh menebak ulang nama folder.
                activeStudioProjectFolder = folder
                "exit=0 Proyek Android berhasil dibuat di $folder. File source, unit test, README, dan workflow GitHub Actions sudah tersedia. Langkah berikutnya: baca file yang relevan, lakukan perubahan, lalu panggil build_apk setelah repo GitHub siap."
            } else "exit=1 Proyek gagal dibuat.\n" + middleTrim(out, 6000)
        }
    }
    "build_apk" -> {
        val dir = BuildApk.safeFolder(args.optString("folder"))
        val bDesc = args.optString("deskripsi").ifBlank { "Membangun APK" }
        val bFolderProblem = folderArgProblem("build_apk", args.optString("folder"))
        if (dir == null || bFolderProblem != null) {
            sawFail = true
            card.finish(false, "folder tidak valid", state = StepState.FAIL)
            "exit=1 " + (bFolderProblem ?: "Folder tidak valid: pakai path relatif terhadap folder kerja (huruf, angka, _ . / -), tanpa '..' dan tanpa awalan /. Gunakan '.' untuk folder kerja.")
        } else if (isProtectedReferencePath(absPath(dir))) {
            card.finish(false, "source reference terkunci", state = StepState.DENIED)
            "Ditolak: build tidak boleh menulis ke source reference. Salin source ke MYTHOS/projects/aktif terlebih dahulu."
        } else if (readOnlyMode()) {
            card.finish(false, "mode hanya baca", state = StepState.DENIED)
            "Ditolak: mode hanya baca aktif. Build menulis file, jadi tidak diizinkan."
        } else if (!confirm(
                "Bangun APK di folder $dir lewat GitHub Actions.\n\nAkan git add + commit + push semua perubahan ke repo GitHub, menunggu workflow selesai, lalu mengunduh APK-nya.",
                null, bDesc,
                warning = "Menyentuh jaringan dan mengirim kode ke GitHub",
                force = sandboxEnabled()
            )
        ) {
            card.finish(false, if (lastDenyUnanswered) "tidak dijawab" else "ditolak", state = StepState.DENIED)
            deniedResult()
        } else {
            val bMode = lastApprovalMode
            val liveFile = "$termuxTmp/kita_live_${UUID.randomUUID()}.txt"
            val tail = LiveTail(this, liveFile, pollMs = 5000L) { card.liveOutput(it) }
            tail.start()
            val out = try {
                // Cukup satu link repository di Pengaturan; pengaturan lama (pemilik + nama repo) tetap terbaca.
                val ref = parseRepoLink(prefs.getString("github_repo_url", "").orEmpty())
                    ?: parseRepoLink(prefs.getString("github_owner", "").orEmpty() + "/" + prefs.getString("github_repo", "").orEmpty())
                val owner = ref?.owner.orEmpty()
                val repo = ref?.repo.orEmpty()
                val privateRepo = true // dipakai hanya bila repo belum ada dan harus dibuat
                val token = when (val saved = Secret.decrypt(prefs.getString("github_token", "") ?: "")) {
                    is SecretDecryptResult.Success -> saved.value
                    else -> ""
                }
                val cmd = BuildApk.setupCommand(dir, owner, repo, privateRepo, token)
                if (sandboxEnabled()) runInSandbox(cmd, true, liveFile)
                else termux(cmd, limit = 12000, timeoutSec = 1800, liveFile = liveFile)
            } catch (c: Cancelled) {
                logApproved("perintah", "build_apk $dir (GitHub Actions)", bDesc, Risk.WRITE, bMode, false, "Dihentikan sebelum selesai")
                throw c
            } finally {
                tail.stop()
            }
            val res = BuildApk.parse(out)
            logApproved(
                "perintah", "build_apk $dir (GitHub Actions)", bDesc, Risk.WRITE, bMode, res.ok, summarizeResult(out, hideOutput = false),
                exitCode = parseTermuxResult(out).first, decision = "disetujui", sandbox = sandboxEnabled(), network = true
            )
            card.finish(
                res.ok,
                if (res.ok) "APK siap" else out.lineSequence().first().take(24),
                "$ build_apk $dir\n\n" + out.substringAfter("\n", "")
            )
            if (!res.ok) sawFail = true
            // Kartu "Pasang APK" juga untuk build_apk: sebelumnya hanya run_command yang menampilkannya, jadi setelah
            // build GitHub Actions sukses pengguna harus mencari file APK sendiri. Path sudah divalidasi BuildApk.parse.
            if (res.ok) res.apk?.let { runLog.apkPath = it; recordArtifact(it, "apk"); addInstallCard(it) }
            // Tanpa penanda KITA_BUILD=ok tetap gagal: jangan biarkan model membaca exit=0 untuk build yang tidak jadi.
            if (!res.ok && out.startsWith("exit=0")) "exit=1 Build berakhir tanpa penanda KITA_BUILD=ok.\n" + out.substringAfter("\n", "")
            else middleTrim(out, 8000)
        }
    }
    "cari_web" -> toolCariWeb(args, card)
    "baca_web" -> toolBacaWeb(args, card)
    "berangkas_baca" -> toolBerangkasBaca(args, card)
    "berangkas_salin" -> toolBerangkasSalin(args, card)
    "create_tool" -> toolCreateTool(args, card)
    "delete_tool" -> toolDeleteTool(args, card)
    "validate_project" -> toolValidateProject(args, card)
    "self_audit" -> toolSelfAudit(args, card)
    "self_upgrade" -> toolSelfUpgrade(args, card)
    in CONNECTOR_TOOL_NAMES -> runConnectorTool(name, args, card) ?: "Tool tidak dikenal: $name"
    "ask_user" -> {
        val q = args.optString("pertanyaan").ifBlank { "Butuh keputusanmu" }
        val opts = args.optString("pilihan").split("|").map { it.trim() }.filter { it.isNotEmpty() }
        val ans = askUser(q, opts)
        card.finish(true, "", "Pertanyaan: $q\n\nJawaban: $ans")
        "Jawaban pengguna: $ans"
    }
    else -> loadCustomTools().firstOrNull { it.name == name }?.let { runCustomTool(it, args, card) } ?: "Tool tidak dikenal: $name"
}
