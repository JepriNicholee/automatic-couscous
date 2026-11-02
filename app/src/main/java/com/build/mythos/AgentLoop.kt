package com.build.mythos

import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import org.json.JSONObject

// Loop agen: kirim pesan, jalankan worker, panggil model, jalankan alat, berhenti.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

/** Tutup keyboard dan lepas fokus dari kolom input setelah pesan dikirim, agar tata letak kembali normal. */
/** Mode otonom: agen menjalankan tindakan tanpa dialog persetujuan dan tanpa menunggu jawaban pengguna. Harus opt-in. */
internal fun MainActivity.isAutonomous(): Boolean = prefs.getBoolean("autonomous", false)

internal fun MainActivity.hideKeyboard() {
    val close = {
        try {
            input.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(input.windowToken, 0)
        } catch (e: Exception) {
        }
    }
    close()
    // Setelah dialog ditutup, Android sering mengembalikan fokus ke kolom input; tutup lagi sebentar kemudian.
    input.postDelayed({ close() }, 300)
}

/**
 * Hentikan agen. Tombol Berhenti ([keepPending] = false) juga membatalkan pesan yang menunggu giliran; kalau tidak,
 * agen berhenti lalu langsung berjalan lagi dengan pesan itu. send() memakai keepPending = true karena memang ingin melanjutkan.
 */
internal fun MainActivity.stopAgent(keepPending: Boolean = false) {
    if (!busy) return
    if (!keepPending && pendingInterrupt != null) {
        pendingInterrupt = null
        addNote("Pesan yang menunggu dibatalkan.")
    }
    cancelled = true
    try { activeConn?.disconnect() } catch (e: Exception) { logQuiet("AgentLoop.kt", e) }
    worker?.cancel()
    confirmDialog?.dismiss()
    setStatus(IconView.STOP, "Menghentikan...")
}

internal fun MainActivity.onCancelled() {
    runLog.outcome = RunOutcome.CANCELLED
    trimDangling()
    val p = lastPartial
    if (p.isNotBlank()) synchronized(hist) { hist.add(Hist(1, p)) }
    lastPartial = ""
    addNote("Dihentikan.")
}

internal fun MainActivity.send() {
    val typed = input.text.toString().trim()
    if (busy) {
        if (typed.isNotEmpty()) {
            pendingInterrupt = typed
            input.setText("")
            hideKeyboard()
            addNote("Pesan baru diterima. Menghentikan jawaban saat ini lalu melanjutkan dengan pesan tersebut...")
            stopAgent(keepPending = true)
        }
        return
    }
    if (typed.isEmpty() && attachments.isEmpty()) return
    if (attachments.any { it.path == null }) { toast("Tunggu file selesai disalin"); return }
    val imageRefs = attachments.filter { isImageName(it.name) }.mapNotNull { a ->
        a.path?.let { HistImage(it, a.previewPath) }
    }
    val msg = typed.ifEmpty { "Periksa lampiran dan gunakan sebagai konteks tugas." } +
        attachments.joinToString("") {
            "\n${if (isImageName(it.name)) "Gambar" else "File"} terlampir di \"${it.path}\""
        }
    registerTaskCopies(attachments.mapNotNull { it.path })
    // Isi lampiran (teks, docx, pdf, manifest zip) dibaca saat file dipilih; di sini ditempel ke pesan di riwayat agar tidak hilang.
    val sentAtts = attachments.toList()
    val attBlock = buildAttachmentBlock(
        sentAtts.filter { it.digest.isNotBlank() }.map { AttDigest(it.name, it.kind, it.digest) },
        digestTotalLimit(ctxBudget, isLocalUrl(cfgBase()))
    )
    val checkpoint = loadAgentCheckpoint()
    val resume = typed.equals("lanjutkan", ignoreCase = true) && checkpoint != null
    val key = cfgKey()
    if (needsKey() && key.isEmpty()) { showSettings(); return }
    if (preset(prov).fmt != "cli" && cfgModel().isBlank()) {
        toast("Isi nama model dulu")
        pickModel()
        return
    }
    // Offline dengan AI internet: simpan pesan di antrean, kirim otomatis saat koneksi pulih (Chat samaran tidak menyimpan apa pun ke disk).
    if (shouldQueueOffline(NetState.online, isLocalUrl(cfgBase()), attachments.isNotEmpty(), typed)) {
        if (incognito) toast("Offline: Chat samaran tidak menyimpan antrean. Coba lagi saat online.") else queueOffline(typed)
        return
    }
    // Lampiran yang dikirim dicatat per chat; catatannya masuk prompt sistem di setiap giliran (lihat sysText).
    rememberActiveAttachments(sentAtts)
    // Sapaan baku, atau (model lokal saja) obrolan pendek tanpa kata kerja tindakan: jalur ringan tanpa alat dan tanpa riwayat tugas.
    val lastAiAsked = synchronized(hist) { endsWithQuestion(hist.lastOrNull { it.role == 1 }?.text) }
    // Mode obrolan (selalu aktif, hanya model lokal): semua pesan jadi obrolan murni tanpa alat. AI internet tidak terpengaruh.
    val localChat = isLocalUrl(cfgBase())
    val isolateGreeting = attachments.isEmpty() && !resume && (localChat || useLightPath(typed, isLocalUrl(cfgBase()), lastAiAsked))
    lightChat = isolateGreeting
    busy = true
    input.setText("")
    hideKeyboard()
    clearAttachments()
    if (resume) {
        addNote("Melanjutkan tugas terakhir dari checkpoint (putaran ${checkpoint!!.turn}).")
        startWorker(checkpoint.userMessage, key, addUser = false, images = emptyList())
    } else {
        // Riwayat sebelum pesan baru masuk: dipakai mode obrolan untuk konteks beberapa pesan terakhir.
        val chatPrev: List<Hist> = if (isolateGreeting && localChat) synchronized(hist) { ArrayList(hist) } else emptyList()
        addMsg(USER, msg, imageRefs)
        if (isolateGreeting && localChat) {
            ctxOverride = localChatContext(chatPrev, typed, recallBlock(typed)) // memori obrolan lama (ChatMemory.kt)
            sysOverride = localChatSystem(cfgModel(), prefs.getString("sapaan", SAPAAN_BAWAAN)) + activeAttNoteText(tools = false)
        } else if (isolateGreeting) {
            ctxOverride = listOf(Hist(0, typed))
            // Model lokal: prompt ringkas (baseSystem ±8 rb karakter harus dibaca CPU HP, menit untuk sekadar "Halo").
            sysOverride = (if (isLocalUrl(cfgBase())) compactSystem() else baseSystem()) + "\nPesan ini adalah sapaan atau obrolan singkat, bukan perintah kerja. Jawab langsung dengan ramah dan singkat. Jangan membahas proyek lama, Android, GitHub, atau memanggil tool." + activeAttNoteText(tools = false)
        }
        startWorker(msg, key, addUser = true, images = imageRefs, extra = attBlock)
    }
}

internal fun isSimpleGreeting(message: String): Boolean =
    Regex("(?i)^(halo|hai|hi|hello|assalamualaikum|selamat pagi|selamat siang|selamat sore|selamat malam)[!. ]*$")
        .matches(message.trim())

/** Jalankan satu putaran agen di thread latar. `busy` sudah true saat dipanggil. */
internal fun MainActivity.startWorker(
    msg: String,
    key: String,
    addUser: Boolean,
    images: List<HistImage> = emptyList(),
    extra: String = ""
) {
    cancelled = false
    if (addUser) activeStudioProjectFolder = ""
    agentState = AgentState.Planning
    // Melanjutkan: pertahankan id tugas dan nomor putaran dari checkpoint, jangan direset ke 0.
    val prevCp = if (!addUser) loadAgentCheckpoint() else null
    if (addUser || agentTaskId.isBlank()) agentTaskId = prevCp?.taskId?.takeIf { it.isNotBlank() } ?: "task-${System.currentTimeMillis()}"
    saveAgentCheckpoint(AgentCheckpoint(agentTaskId, msg, prevCp?.turn ?: 0, prevCp?.lastTool.orEmpty(), "planning"))
    allowAll = false
    try { startForegroundService(Intent(this, AgentService::class.java)) } catch (e: Exception) { logQuiet("AgentLoop.kt", e) }
    sawBuild = false
    sawFail = false
    clearChips()
    clearStandby()
    lastPartial = ""
    resetFailover()
    runLog.reset(System.currentTimeMillis())
    agentSteps.clear()
    runOnUiThread {
        codexActivityUi.reset()
        zone.beginRun()
        zone.renderSteps(agentSteps.groups.value)
    }
    updateAction()
    setStatus(KIND_DOTS, THINK_TEXT)
    // Pembatalan lewat Job.cancel() (runInterruptible menginterupsi thread yang sedang memblokir, sama seperti
    // Thread.interrupt() sebelumnya). onFinally SELALU jalan, juga bila dibatalkan sebelum agentLoop sempat mulai.
    worker = bgCancellable(
        onError = { e ->
            try {
                if (cancelled || e is Cancelled || e is InterruptedException || e is kotlinx.coroutines.CancellationException) {
                    Thread.interrupted()
                    agentState = AgentState.Cancelled
                    onCancelled()
                } else {
                    // Giliran model yang memanggil alat tapi belum dapat hasil harus dibuang, kalau tidak
                    // permintaan berikutnya ditolak API ("function call tanpa response").
                    trimDangling()
                    runLog.note = e.message ?: e.javaClass.simpleName
                    runLog.outcome = RunOutcome.ERROR
                    agentState = AgentState.Failed(runLog.note)
                    val lastCp = loadAgentCheckpoint()
                    saveAgentCheckpoint(AgentCheckpoint(agentTaskId, msg, lastCp?.turn ?: 0, lastCp?.lastTool.orEmpty(), "failed", runLog.note))
                    addMsg(AI, "Error: ${e.message ?: e.javaClass.simpleName}")
                }
            } catch (e2: Throwable) {
            }
        },
        onFinally = {
            Thread.interrupted()
            // Sapaan memakai konteks satu pesan agar AI tetap menjawab sendiri tanpa terseret tugas lama.
            ctxOverride = null
            sysOverride = null
            lightChat = false
            try {
                // Tutup aliran aktivitas: selesaikan antrean yang tertahan dan pasang ringkasan langkah.
                runOnUiThread {
                    codexActivityUi.accept(JSONObject().put("type", "turn_completed").put("item", JSONObject().put("status", "completed")))
                    codexActivityUi.flushAll()
                }
                streamEnd()
                hideStatus()
                saveHist()
            } catch (e: Throwable) {
            }
            try { rememberLastExchange() } catch (e: Throwable) { logQuiet("AgentLoop.kt", e) } // simpan tanya-jawab ini ke memori obrolan
            // Lepaskan payload base64 dari memori setelah tugas selesai; path tetap tersimpan untuk riwayat.
            synchronized(hist) { hist.forEach { h -> h.images.forEach { it.cachedPayload = null } } }
                runOnUiThread {
                sealFeed()
                // Laporan akhir hanya setelah semua langkah selesai dan riwayat tersimpan.
                try { showRunReport() } catch (e: Throwable) { logQuiet("AgentLoop.kt", e) }
                    busy = false
                    if (runLog.outcome == RunOutcome.COMPLETED) {
                        agentState = AgentState.Completed(buildReport(runLog, System.currentTimeMillis() - runLog.startedAt))
                        clearAgentCheckpoint()
                        // Lampiran TIDAK lagi dihapus di sini: dulu foto dilepas dan file dihapus begitu satu tugas selesai,
                        // sehingga pesan berikutnya ("lanjutkan", "perbaiki lagi") kehilangan file yang sudah dikirim.
                        // Salinan baru dibersihkan saat chat dihapus (lihat purgeChatAttachments / resetChatState).
                    }
                    if (runLog.outcome == RunOutcome.CANCELLED) agentState = AgentState.Cancelled
                    worker = null
                    val next = pendingInterrupt
                    pendingInterrupt = null
                    if (next != null && !isFinishing) {
                        busy = true
                        addMsg(USER, next)
                        startWorker(next, cfgKey(), addUser = true)
                    }
                    // Pesan yang diantre saat offline dikirim satu per satu setelah tugas ini selesai (bukan bila pengguna menekan Berhenti).
                    if (!busy && !isFinishing && runLog.outcome != RunOutcome.CANCELLED) drainOfflineQueue()
                    // Tugas berhenti sebelum selesai (gagal/terpotong/dihentikan): tawarkan lanjut. Pada tugas yang
                    // selesai normal, chip sudah ditampilkan sesuai isi jawaban AI (pilihan, ya/tidak, perbaikan).
                    if (!busy && runLog.outcome != RunOutcome.COMPLETED) showChips(runLog.finalText, unfinished = true)
                updateAction()
                // Setelah selesai AI tidak dimatikan: layanan tetap hidup dalam mode siaga dan indikator terus bergerak.
                // Jeda singkat agar tidak berebut dengan startForeground() bila pekerjaan selesai seketika.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!busy && !isFinishing) {
                        enterStandbyService()
                        showStandby()
                    }
                }, 1500)
            }
        }
    ) {
        agentLoop(msg, key, addUser, images, extra)
    }
}

internal fun MainActivity.statusFor(name: String, args: JSONObject): String {
    if (name == "build_apk") return "Membangun APK dari seluruh kode proyek"
    if (name == "cari_web") return "Mencari informasi terbaru di internet"
    if (name == "baca_web") return "Membaca isi halaman web untuk mengambil informasi"
    if (name == "source_repo_tree") return "Memetakan file repository sumber MYTHOS melalui GitHub"
    if (name == "source_repo_read") return "Membaca kode sumber MYTHOS dari GitHub"
    if (name == "agent_git_check") return "Memeriksa repository tujuan Git agen"
    if (name == "agent_git_push") return "Mengirim file proyek ke repository tujuan agen"
    if (name in CONNECTOR_TOOL_NAMES) return connectorStatus(name)
    if (name == "validate_project") return "Memeriksa struktur dan kelengkapan file proyek"
    if (name == "self_audit") return "Mengaudit kode aplikasi ini mencari masalah"
    if (name == "self_upgrade") return "Memproses pembaruan untuk aplikasi ini"
    if (name == "write_file") return describeWrite(args.optString("path"))
    if (name == "berangkas_baca") return "Membaca data dari berangkas aplikasi"
    if (name == "berangkas_salin") return "Menyalin berkas dari berangkas aplikasi"
    if (name == "ask_user") return "Menunggu jawaban Anda untuk melanjutkan pekerjaan"
    val c = args.optString("command")
    describeCommand(c)?.let { return it }
    return when {
        c.contains("gradlew") || c.contains("gradle ") -> "Menjalankan gradle untuk memeriksa dan membangun proyek"
        Regex("""\b(grep|find|rg|locate|which|whereis|pwd|tree)\b""").containsMatchIn(c) -> "Menelusuri struktur dan isi folder proyek"
        Regex("""\b(cat|head|tail|sed|wc|diff)\b""").containsMatchIn(c) -> "Membaca dan menganalisis kode yang berkaitan"
        Regex("""\b(test|check|lint|verify)\b""").containsMatchIn(c) -> "Memvalidasi hasil pekerjaan sebelum dilanjutkan"
        else -> "Menjalankan perintah untuk melanjutkan pekerjaan"
    }
}


/**
 * Judul langkah untuk baris status live: kalimat 'deskripsi' yang ditulis AI sendiri (mis. "Memeriksa isi arsip ZIP")
 * agar pengguna melihat apa yang sebenarnya dikerjakan; bila kosong/terlalu pendek, pakai judul generik statusFor().
 */
internal fun MainActivity.stepTitleFor(name: String, args: JSONObject): String {
    val d = tidyLine(args.optString("deskripsi"), 90)
    return if (d.length >= 6 && !isGenericStepText(d)) d else statusFor(name, args)
}

internal fun MainActivity.detailFor(name: String, args: JSONObject): String =
    if (name == "cari_web") {
        "Cari di web: " + args.optString("kueri").take(200)
    } else if (name == "baca_web") {
        "Baca halaman: " + args.optString("url").take(300)
    } else if (name == "build_apk") {
        BuildApk.displayDetail(args.optString("folder").ifBlank { "." })
    } else if (name == "source_repo_tree") {
        "Tree repository sumber MYTHOS: " + args.optString("prefix").ifBlank { "(seluruh repository)" }
    } else if (name == "source_repo_read") {
        "Baca source MYTHOS: " + args.optString("path").take(220)
    } else if (name == "agent_git_check") {
        "Periksa akses repository tujuan agen"
    } else if (name == "agent_git_push") {
        "Push ${args.optString("folder").ifBlank { "." }} → repository tujuan agen"
    } else if (name in CONNECTOR_TOOL_NAMES) {
        connectorDetail(name, args)
    } else if (name == "validate_project") {
        "Validasi proyek: " + args.optString("folder").ifBlank { "." }
    } else if (name == "self_audit") {
        "Audit diri: " + args.optString("folder").ifBlank { "." }
    } else if (name == "berangkas_baca") {
        "Berangkas: " + args.optString("jalur").ifBlank { "(daftar isi)" }
    } else if (name == "berangkas_salin") {
        "Salin dari berangkas: " + args.optString("sumber").ifBlank { "(semua)" }
    } else if (name == "self_upgrade") {
        "Upgrade diri (" + args.optString("tahap") + "): " + args.optString("folder").ifBlank { "." }
    } else if (name == "write_file") {
        args.optString("path") + "\n\n" + args.optString("content").take(1500)
    } else {
        "$ " + args.optString("command")
    }

/** Batas putaran model dalam satu permintaan: pengaman agar agen yang berputar-putar tidak menghabiskan kuota tanpa henti. */
// Batas pengaman saja: satu chat berjalan sampai tugas selesai. Hanya berhenti bila model sudah
// selesai memanggil alat, pengguna menekan Hentikan, atau batas ini tercapai.
internal const val MAX_AGENT_TURNS = 300

/** Panggilan alat yang persis sama DENGAN hasil yang sama: pada kemunculan ke-sekian ini agen dianggap macet. */
internal const val MAX_SAME_CALLS = 3

/** Batas timeout build_apk yang sama berturut-turut (tiap putaran menambah cache, jadi lebih longgar dari error biasa). */
internal const val STOP_SAME_TIMEOUT_AT = 8

/** Provider tertentu mengirim alasan berpikir sebagai tag teks; itu bukan jawaban untuk pengguna. */
internal fun stripModelThinking(text: String): String {
    var out = text.replace(Regex("(?is)<(thinking|think)>.*?</\\1>"), "")
    out = out.replace(Regex("(?is)<(thinking|think)>.*$"), "")
    out = out.replace(Regex("(?i)</(thinking|think)>"), "")
    return out.trim()
}

/** Model kadang menulis protokol tool sebagai chat biasa; protokol internal tidak boleh bocor ke pengguna. */
/**
 * Tutup segmen teks live giliran ini: tunggu ketikan selesai (maks 15 dtk) agar tidak melompat, finalkan kotaknya
 * tanpa tombol Salin, lalu tampilkan lagi baris aktivitas (disembunyikan selama teks mengalir).
 */
internal fun MainActivity.endLiveSegment() {
    val start = System.currentTimeMillis()
    while (!cancelled && typewriterRunnable != null && System.currentTimeMillis() - start < 15_000L) Thread.sleep(40)
    streamSegmentEnd()
    runOnUiThread { zone.setLiveVisible(true) }
}

/**
 * Ubah panggilan alat agen API menjadi item berbentuk Codex, supaya CodexActivityUi yang sama (antrean berurutan,
 * narasi berjeda, ringkasan "Membaca sebuah file, menjalankan 2 perintah") dipakai untuk semua penyedia.
 */
internal fun agentToolItem(c: Call): JSONObject {
    val a = c.args
    val item = JSONObject().put("id", c.id.ifBlank { "agent-" + System.nanoTime() })
    return when (c.name) {
        "run_command" -> item.put("type", "commandExecution").put("command", a.optString("command"))
        "build_apk" -> item.put("type", "commandExecution").put("command", "./gradlew assembleDebug")
        "source_repo_tree", "source_repo_read", "agent_git_check", "agent_git_push" -> item.put("type", "dynamicToolCall").put("tool", c.name)
        "write_file" -> item.put("type", "fileChange").put(
            "changes", org.json.JSONArray().put(JSONObject().put("path", a.optString("path")).put("kind", "update"))
        )
        "cari_web" -> item.put("type", "webSearch").put("query", a.optString("query"))
        "baca_web" -> item.put("type", "webSearch").put("query", a.optString("url"))
        else -> item.put("type", "dynamicToolCall").put("tool", c.name.ifBlank { "alat" })
    }
}

/** Kirim event aktivitas ke UI bersama; urutannya dijaga karena dipost berurutan ke main thread. */
internal fun MainActivity.emitActivity(type: String, item: JSONObject) {
    runOnUiThread { codexActivityUi.accept(JSONObject().put("type", type).put("item", item)) }
}

internal fun sanitizeAssistantText(text: String): String {
    val clean = stripModelThinking(text)
    val hasToolProtocol = Regex("(?is)[\\\"'](?:command|deskripsi|description)[\\\"']\\s*:").containsMatchIn(clean)
    val hasInternalPlan = Regex("(?im)^\\s*[-*]?\\s*(langkah selanjutnya|langkah rutin untuk verifikasi|rencana kerja)\\s*:?").containsMatchIn(clean)
    val looksLikeToolPayload = clean.trimStart().startsWith("{") || clean.contains("\"command\"")
    return if (hasToolProtocol && (hasInternalPlan || looksLikeToolPayload)) {
        "Agen belum menjalankan pemeriksaan karena format perintah internal tidak terbaca sebagai tool. Silakan kirim perintahnya lagi."
    } else clean
}

internal fun MainActivity.agentLoop(
    userMsg: String,
    key: String,
    addUser: Boolean = true,
    images: List<HistImage> = emptyList(),
    extra: String = ""
) {
    if (preset(prov).fmt == "cli") { cliChat(userMsg); return }
    if (addUser) {
        // [extra] = blok isi lampiran: ikut tersimpan di riwayat (disembunyikan dari gelembung chat), jadi agen tidak lupa isi file.
        synchronized(hist) { hist.add(Hist(0, userMsg + extra, images = images)) }
        saveHist()
        taskBrief = "" // catatan pemahaman milik tugas sebelumnya tidak boleh terbawa ke tugas baru
        deepNotes = ""
        deepStageDone = false
    } else {
        // Melanjutkan dari checkpoint: riwayat sering berakhir di giliran model (catatan berhenti/dihentikan), padahal
        // permintaan berikutnya harus berakhir di giliran pengguna atau hasil alat agar tidak ditolak API.
        val added = synchronized(hist) {
            when (hist.lastOrNull()?.role) {
                null -> { hist.add(Hist(0, userMsg)); true }
                1 -> {
                    hist.add(Hist(0, SYS_PREFIX + "Lanjutkan tugas sebelumnya dari titik terakhir. Permintaan awal: $userMsg. " +
                        "Periksa dulu apa yang sudah selesai, jangan mengulang langkah yang sudah berhasil." + recentActionsNote()))
                    true
                }
                else -> false
            }
        }
        if (added) saveHist()
    }
    if (addUser && !isSimpleGreeting(userMsg) && !lightChat && prefs.getBoolean("understand", false)) {
        val brief = try { understandTask(key, userMsg) } catch (e: Cancelled) { throw e } catch (e: Exception) { Brief.Kosong }
        when (brief) {
            is Brief.Paham -> taskBrief = brief.text
            Brief.Kosong -> taskBrief = ""
        }
    }
    val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    var first = true
    var turns = 0
    val repeats = RepeatDetector()
    val errTracker = ErrorTracker()
    val writeTracker = WriteTracker()
    var verifyNudges = 0
    var lastBuildFailOut: String? = null // keluaran build gagal terakhir, untuk diagnosis di dorongan verifikasi
    var failStreak = 0 // kegagalan alat beruntun; memicu protokol diagnosis
    while (true) {
        if (cancelled) throw Cancelled()
        if (++turns > MAX_AGENT_TURNS) {
            stopWithNote("Berhenti: sudah $MAX_AGENT_TURNS putaran tanpa selesai. Kirim pesan lagi untuk melanjutkan.")
            return
        }
        // Penalaran mendalam dibaca ulang tiap putaran: bila dinyalakan di tengah tugas, tahap berpikir dijalankan
        // untuk sisa pekerjaan; bila dimatikan, catatannya dibuang. Pengaturan penyedia ikut berubah di panggilan berikutnya.
        when (deepStageAction(deepOn(), deepStageDone, isSimpleGreeting(userMsg) || lightChat)) {
            DeepAction.RUN -> {
                val fresh = addUser && turns == 1
                deepNotes = try { deepReason(key, userMsg, fresh) } catch (e: Cancelled) { throw e } catch (e: Exception) { "" }
                deepStageDone = true
                if (!fresh && deepNotes.isNotBlank()) addNote("Analisis mendalam disusun untuk sisa pekerjaan.")
            }
            DeepAction.CLEAR -> { deepNotes = ""; deepStageDone = false }
            DeepAction.NONE -> {}
        }
        agentState = AgentState.CallingModel(turns)
        saveAgentCheckpoint(AgentCheckpoint(agentTaskId, userMsg, turns, "", "calling_model"))
        val phase = THINK_TEXT
        setStatus(if (first) KIND_DOTS else IconView.GRID, phase)
        first = false
        compactIfNeeded(key)
        var wrote = false
        var liveShown = ""   // bagian teks giliran ini yang sudah ditampilkan live
        lastPartial = ""
        // Status tunggu yang jujur: hanya muncul bila model benar-benar belum mengirim token setelah 1,8 detik.
        val waitTask = Runnable {
            if (busy && !cancelled && !wrote && curTitle == phase) setStatus(KIND_DOTS, "Menunggu respons model AI")
        }
        uiHandler.postDelayed(waitTask, 5000)
        val rawTurn = try {
            try {
            callModelRetry(
                key,
                onText = { t ->
                    val visible = sanitizeAssistantText(t)
                    lastPartial = visible
                    if (!wrote) {
                        wrote = true
                        setStatus(IconView.NEW, "Menulis jawaban")
                    }
                    // Alur acuan: teks model mengalir LIVE di chat, lalu baris aktivitas muncul sesudahnya.
                    if (visible.startsWith(liveShown)) {
                        val delta = visible.substring(liveShown.length)
                        if (delta.isNotEmpty()) { liveShown = visible; streamAppend(delta) }
                    } else if (visible.isNotBlank()) {
                        // Percobaan ulang/ganti model menulis dari awal: gambar ulang kotak yang sama (tanpa dobel).
                        liveShown = visible
                        streamUpdate(visible)
                    }
                },
                onFlush = {
                    // Kotak jawaban sengaja TIDAK ditutup di sini: ditutup sesudah giliran jelas (alat -> segmen,
                    // jawaban akhir -> streamEnd) supaya teks pembuka dan jawaban akhir tidak tampil dua kali.
                    lastPartial = ""
                }
            )
            } catch (e: EmptyResponseException) {
                null // semua model menjawab kosong: ditangani cabang turn == null di bawah
            }
        } finally {
            uiHandler.removeCallbacks(waitTask)
        }
        val turn = rawTurn?.let { h ->
            Hist(h.role, sanitizeAssistantText(h.text), h.calls, h.results, h.raw, h.images)
        }
        if (turn == null) {
            if (liveShown.isNotEmpty()) endLiveSegment()
            if (cancelled) throw Cancelled()
            val projectCreated = synchronized(hist) {
                hist.lastOrNull()?.role == 2 && hist.lastOrNull()?.results?.any { (call, result) ->
                    call.name == "create_android_project" && result.startsWith("exit=0")
                } == true
            }
            if (projectCreated && turns < 3) {
                synchronized(hist) {
                    hist.add(Hist(0, SYS_PREFIX + "Proyek baru sudah berhasil dibuat. Lanjutkan sekarang tanpa bertanya: baca file proyek yang relevan, implementasikan fitur pengguna, lalu jalankan test dan build. Jangan mengulang create_android_project."))
                }
                saveHist()
                addNote("Proyek sudah dibuat; agen melanjutkan pekerjaan secara otomatis.")
                continue
            }
            runLog.note = "Model tidak memberi jawaban"
            runLog.outcome = RunOutcome.INCOMPLETE
            addNote("Model mengembalikan respons kosong dan tidak ada model cadangan yang menjawab. Silakan kirim ‘lanjutkan’ atau ganti model.")
            // Jaga selang-seling riwayat (pengguna/model) supaya permintaan berikutnya tidak ditolak API.
            synchronized(hist) { if (hist.lastOrNull()?.role != 1) hist.add(Hist(1, "(respons model kosong)")) }
            saveHist()
            return
        }
        // Tanda tangan panggilan putaran ini; dibandingkan dengan putaran sebelumnya SESUDAH hasilnya ada (lihat RepeatDetector).
        val sig = turn.calls.joinToString("|") { it.name + sigArgs(it.args) }
        synchronized(hist) { hist.add(turn) }
        saveHist()
        // Giliran dengan alat: tuntaskan ketikan teks pembukanya, tutup segmennya (tanpa tombol Salin), lalu
        // tampilkan lagi baris aktivitas di bawahnya, seperti rekaman acuan.
        if (turn.calls.isNotEmpty() && liveShown.isNotEmpty()) endLiveSegment()
        if (turn.calls.isEmpty()) {
            runLog.finalText = turn.text
            // Jangan anggap selesai bila build terakhir gagal / kode diubah tanpa build berhasil (maks MAX_VERIFY_NUDGES kali).
            agentState = AgentState.Verifying
            saveAgentCheckpoint(AgentCheckpoint(agentTaskId, userMsg, turns, "", "verifying"))
            val nudge = if (verifyNudges < MAX_VERIFY_NUDGES && !cancelled) verificationNudge(runLog.steps, lastBuildFailOut, verifyNudges, sandboxEnabled()) else null
            if (nudge != null) {
                verifyNudges++
                synchronized(hist) { hist.add(Hist(0, nudge)) }
                saveHist()
                if (liveShown.isNotEmpty()) endLiveSegment()
                addNote("Hasil belum terbukti berhasil, agen diminta memeriksa dulu.")
                continue
            }
            if (turn.text.isNotBlank()) {
                // Jawaban akhir baru ditampilkan setelah tugas selesai, lalu dianimasikan seperti sedang mengetik.
                streamUpdate(turn.text)
                streamEnd()
                showChips(turn.text)
                if (voiceTurnPending) {
                    voiceTurnPending = false
                    speakReply(turn.text)
                }
            }
            return
        }
        val results = ArrayList<Pair<Call, String>>()
        var repeatStop = false
        var stopSeen = STOP_SAME_ERROR_AT
        var rewriteStopMsg: String? = null
        for (c in turn.calls) {
            if (cancelled) throw Cancelled()
            agentState = AgentState.RunningTool(c.name)
            saveAgentCheckpoint(AgentCheckpoint(agentTaskId, userMsg, turns, c.name, "running_tool"))
            val title = stepTitleFor(c.name, c.args)
            lastStepTitle = title
            val seq = ++stepSeq
            val activityItem = agentToolItem(c)
            emitActivity("item_started", activityItem)
            val card = onUi {
                val icon = iconFor(c.name, c.args, title)
                val detail = detailFor(c.name, c.args)
                // Seperti Claude: mulai dengan "Sedang menjalankan perintah" (ikon orbit), lalu kalimat langkah yang spesifik.
                // quiet: kartu Aktivitas Agen sudah menampilkan langkah ini secara langsung
                setStatus(KIND_DOTS, RUNNING_TEXT, quiet = true)
                uiHandler.postDelayed({
                    if (stepSeq == seq && busy && curTitle == RUNNING_TEXT) setStatus(icon, title, quiet = true)
                }, RUNNING_HOLD_MS)
                newStepCard(title, detail, icon, c.name, c.args.optString("path"))
            }
            activeStep = card
            val rawOut = try {
                runTool(c.name, c.args, card)
            } catch (e: Exception) {
                val stopped = cancelled || e is Cancelled || e is InterruptedException
                card.finish(false, if (stopped) "dihentikan" else (e.message ?: "error").take(30),
                    state = if (stopped) StepState.STOPPED else StepState.FAIL)
                if (stopped) throw e
                // Galat tak terduga pada satu alat tidak boleh menggugurkan seluruh tugas.
                // Dikembalikan sebagai hasil gagal agar model bisa membaca errornya dan memperbaiki diri.
                recoverableToolResult(e)
            } finally {
                activeStep = null
            }
            // exit=141 pada pipa yang ditutup lebih awal (| head) bukan kegagalan; lihat softenSigpipe.
            val out = if (c.name == "run_command") softenSigpipe(c.args.optString("command"), rawOut) else rawOut
            // Keluaran gagal yang panjang dipadatkan; error yang sama berulang diberi peringatan ke model.
            // ask_user tidak mengeluarkan "exit=": jangan dilabeli gagal. Hasil "Ditolak..."/"Diblokir..." dikenali sebagai USER_DENIED.
            val structured = if (c.name == "ask_user" && !out.startsWith("Error:")) ToolResult(exitCode = 0, stdout = out) else parseToolResult(out)
            emitActivity("item_completed", JSONObject(activityItem.toString())
                .put("status", if (structured.ok) "completed" else "failed")
                .put("exitCode", if (structured.ok) 0 else (structured.exitCode ?: 1)))
            // build_apk tidak punya argumen "command": pakai perintah build yang setara agar verifikasi dan diagnosis bekerja.
            val command = if (c.name == "build_apk") "./gradlew assembleDebug" else c.args.optString("command")
            val verifyKind = verificationKind(c.name, command)
            var forModel = condenseFailure(out)
            if (!structured.ok) {
                val retryable = retryPolicy(c.name, command, structured)
                val hint = when (structured.errorKind) {
                    ErrorKind.COMMAND_TIMEOUT -> "Cek proses dan hasil sebelum mengulang."
                    ErrorKind.NETWORK_AUTH -> "Periksa API key; jangan mengulang tanpa perubahan."
                    ErrorKind.TERMUX_UNAVAILABLE -> "Pastikan Termux tersedia dan izinkan RUN_COMMAND."
                    ErrorKind.USER_DENIED -> "Tindakan tidak dijalankan (ditolak atau diblokir). Jangan mengulang tindakan yang sama; ganti pendekatan atau jelaskan kendalanya."
                    else -> if (!retryable) "Jangan mengulang tanpa mengubah pendekatan." else "Boleh mencoba lagi setelah jeda singkat."
                }
                forModel += "\n\n[Kategori error: ${structured.errorKind}. $hint]"
                // Cari akar masalah, jangan menebak: beri hipotesis penyebab + langkah berbeda dari mengulang.
                if (structured.errorKind != ErrorKind.USER_DENIED && structured.errorKind != ErrorKind.CANCELLED) {
                    diagnoseFailure(command, out, sandboxEnabled())?.let { dx ->
                        forModel += "\n\n[Diagnosis otomatis: ${dx.cause}. Langkah yang disarankan: ${dx.fix}]"
                    }
                    // exit=1 tanpa keluaran (grep tanpa hasil, dll.) dan kegagalan perintah pemeriksaan (ls, cat, java -version, ...)
                    // adalah jawaban, bukan kegagalan kerja: jangan memicu protokol diagnosis.
                    if (countsAsFailure(command, structured.exitCode, structured.stdout)) failStreak++
                    if (failStreak >= FAIL_STREAK_PROTOCOL_AT) forModel += DEBUG_PROTOCOL
                    // Log pusat logs/error_summary.log pada workspace aktif: bahan analisis akar masalah untuk agen.
                    logErrorSummary(c.name, structured.errorKind.name, structured.stdout.ifBlank { out })
                }
                if (verifyKind == VerificationKind.APK) lastBuildFailOut = out
                saveAgentCheckpoint(AgentCheckpoint(agentTaskId, userMsg, turns, c.name, "tool_failed", structured.errorKind.name))
            } else {
                failStreak = 0
                if (verifyKind == VerificationKind.APK) lastBuildFailOut = null
                // Tes/build berhasil = bukti kemajuan: hitungan tulis ulang dikosongkan.
                if (clearsRewriteCount(verifyKind, command)) writeTracker.noteVerified()
                if (c.name == "write_file") {
                    val wPath = c.args.optString("path")
                    val over = writeTracker.record(wPath)
                    if (over >= WARN_REWRITE_AT) forModel += rewriteWarning(wPath, over)
                    if (over >= STOP_REWRITE_AT && rewriteStopMsg == null) rewriteStopMsg = rewriteStopMessage(wPath, over)
                }
                // Perintah yang mengubah keadaan berhasil (pasang alat, dll.) = kemajuan: hitungan error sama dikosongkan.
                if (c.name != "write_file" && c.name != "ask_user" && !isProbeCommand(command)) errTracker.noteProgress()
                if (verifyKind != VerificationKind.NONE) {
                    forModel += "\n\n[Verifikasi yang disarankan: ${verifyKind.name}. Pastikan hasil nyata sebelum menyatakan selesai.]"
                }
            }
            val seen = errTracker.record(out)
            // Timeout build_apk berulang tetap membuat kemajuan (cache unduhan dan kompilasi bertambah): beri kelonggaran lebih besar.
            val stopAt = if (c.name == "build_apk" && structured.timedOut) STOP_SAME_TIMEOUT_AT else STOP_SAME_ERROR_AT
            if (seen in WARN_SAME_ERROR_AT until stopAt) forModel += repeatWarning(seen)
            results.add(Pair(c, forModel))
            if (seen >= stopAt) { repeatStop = true; stopSeen = seen }
        }
        if (cancelled) throw Cancelled()
        synchronized(hist) { hist.add(Hist(2, results = results)) }
        saveHist()
        if (repeatStop) {
            stopWithNote("Berhenti: error yang sama muncul $stopSeen kali. Beri arahan lain atau coba model lain.")
            return
        }
        rewriteStopMsg?.let { msg ->
            stopWithNote(msg)
            return
        }
        // Panggilan yang sama dengan hasil yang sama berturut-turut = macet. Hentikan, jangan bakar kuota.
        // Pemantauan (ls, tail log, java -version) dan build_apk yang dilanjutkan wajar mengulang panggilan yang sama.
        val lenient = turn.calls.isNotEmpty() && turn.calls.all {
            it.name == "build_apk" || (it.name == "run_command" && isProbeCommand(it.args.optString("command")))
        }
        if (repeats.record(sig, results.joinToString("\n") { it.second }, if (lenient) MAX_SAME_CALLS * 2 else MAX_SAME_CALLS)) {
            stopWithNote("Berhenti: AI mengulang langkah yang sama dengan hasil yang sama $MAX_SAME_CALLS kali berturut-turut. Beri arahan lain atau coba model lain.")
            return
        }
    }
}

/** Akhiri putaran dengan catatan, dan pastikan riwayat berakhir di giliran model (bukan hasil alat yang menggantung). */
private fun MainActivity.stopWithNote(msg: String) {
    runLog.note = msg
    runLog.outcome = RunOutcome.INCOMPLETE
    synchronized(hist) { if (hist.lastOrNull()?.role != 1) hist.add(Hist(1, msg)) }
    saveHist()
    addNote(msg)
}

/** Argumen tanpa 'deskripsi' (kalimat bebas model): perintah sama dengan deskripsi berbeda tetap dianggap berulang. */
internal fun sigArgs(a: JSONObject): String {
    val o = JSONObject()
    val keys = a.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        if (k != "deskripsi") o.put(k, a.opt(k))
    }
    return o.toString()
}

/**
 * Perintah yang tercatat berjalan dalam 30 menit terakhir. Giliran model yang terputus dibuang dari riwayat bersama hasilnya,
 * padahal alatnya mungkin sudah jalan; tanpa catatan ini model bisa mengulang aksi yang tidak idempoten (git commit, >>, pkg install).
 */
internal fun MainActivity.recentActionsNote(): String = try {
    val since = System.currentTimeMillis() - 30 * 60 * 1000L
    val items = CommandLog.load(this).filter { it.time >= since }.take(6).reversed()
    if (items.isEmpty()) "" else
        "\nTercatat berjalan baru-baru ini (mungkin sudah dieksekusi sebelum terputus, verifikasi dulu sebelum mengulang):\n" +
            items.joinToString("\n") { "- ${if (it.ok) "berhasil" else "gagal"}: ${it.command.lineSequence().first().take(120)}" }
} catch (e: Exception) { "" }
