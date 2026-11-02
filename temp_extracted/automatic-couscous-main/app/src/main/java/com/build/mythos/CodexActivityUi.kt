package com.build.mythos

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Merender peristiwa kerja Codex sebagai baris aktivitas ringkas di dalam urutan chat.
 * Hanya tindakan alat yang benar-benar diterima dari App Server yang ditampilkan; teks penalaran mentah tidak pernah ditampilkan.
 */
internal class CodexActivityUi(private val act: MainActivity) {
    private data class Entry(
        val id: String,
        val category: String,
        val title: String,
        var detail: String,
        var status: String = "Berjalan",
        val subject: String = ""
    )

    private data class Group(
        val root: LinearLayout,
        val label: TextView,
        val entries: LinkedHashMap<String, Entry> = LinkedHashMap(),
        var active: Boolean = true,
        var inserted: Boolean = false
    )

    private var current: Group? = null
    /** Teks live terakhir yang tampil (narasi/ringkasan penalaran). Inilah yang ditinggalkan di tempat agen saat ia pindah ke bawah. */
    private var lastLive = ""
    private var fallbackId = 0
    /** Id alat yang benar-benar sedang berjalan; dipakai agar teks status sesuai keadaan nyata. */
    private val running = LinkedHashMap<String, String>()   // id alat -> teks status kerjanya
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    // --- Antrean berurutan: potongan teks dan event alat diproses dalam urutan kedatangan. Event alat TIDAK dijalankan
    // selama teks penjelasan masih diketik; baris live baru tampil setelah teks selesai + jeda SETTLE_MS, supaya mengalir santai.
    private class Step(val delta: String?, val event: JSONObject?)
    private val script = java.util.ArrayDeque<Step>()
    private var settledAt = 0L
    private var awaitingText = false
    private var turnDone = false
    private val drain = Runnable { drainScript() }
    @Volatile private var backlog = 0
    // Pengaman: message_start menyembunyikan baris live sampai teks tiba. Bila teks tak kunjung datang (pesan kosong,
    // aliran macet), tampilkan lagi baris live supaya layar tidak kosong tanpa indikator.
    private val awaitWatch = Runnable { if (awaitingText) { awaitingText = false; showLive() } }

    /** Ada teks yang masih diketik atau kejadian yang tertahan? Dipakai agar akhir giliran tidak memotong aliran. */
    fun hasPendingWork(): Boolean = backlog > 0 || act.typewriterRunnable != null

    /** Ada event/teks yang menunggu giliran? Dipakai agar onTextSettled() tetap dipanggil di jalur mana pun. */
    fun hasQueuedEvents(): Boolean = script.isNotEmpty()

    private fun textBusy(): Boolean =
        act.typewriterRunnable != null || act.typewriterShown.length < act.typewriterTarget.length

    private fun showLive() {
        if (turnDone || awaitingText) return
        act.zone.setLiveVisible(true)
        // Bila ada alat yang masih berjalan (mis. build panjang yang sedang ditunggu), status tetap sesuai alat itu.
        act.zone.setCodexIdle(running.values.lastOrNull() ?: "Proses berpikir")
    }

    private fun scheduleDrain() {
        if (textBusy()) return   // onTextSettled() yang menjadwalkan begitu teks selesai
        val wait = (SETTLE_MS - (android.os.SystemClock.uptimeMillis() - settledAt)).coerceAtLeast(60L)
        ui.removeCallbacks(drain)
        ui.postDelayed(drain, wait)
    }

    private fun drainScript() {
        ui.removeCallbacks(drain)
        while (true) {
            if (textBusy()) return
            val st = script.pollFirst() ?: break
            backlog = script.size
            val ev = st.event
            if (ev != null) { handle(ev); continue }
            // Gabungkan potongan teks berurutan agar tidak ada jeda buatan di tengah satu pesan.
            val sb = StringBuilder(st.delta ?: "")
            while (script.peekFirst()?.delta != null) sb.append(script.removeFirst().delta)
            backlog = script.size
            act.streamAppend(sb.toString())
            return
        }
        showLive()
    }

    /** Potongan teks jawaban dari Codex. Dipanggil dari thread mana pun; urutannya terhadap event alat dijaga. */
    fun delta(text: String) {
        if (text.isEmpty()) return
        // Tandai sebelum pindah ke main thread agar jalur fallback akhir tidak menduplikasi jawaban.
        act.codexStreamOutputStarted = true
        act.runOnUiThread {
            if (script.isNotEmpty()) { script.addLast(Step(text, null)); backlog = script.size } else act.streamAppend(text)
        }
    }

    /** Teks jawaban sedang mengalir: baris status disembunyikan (seperti rekaman acuan). */
    fun onTextActive() {
        awaitingText = false
        ui.removeCallbacks(awaitWatch)
        ui.removeCallbacks(drain)
        act.zone.setLiveVisible(false)
    }

    /** Teks selesai diketik: tunggu SETTLE_MS, lalu lanjutkan event yang tertahan atau tampilkan baris status. */
    fun onTextSettled() {
        settledAt = android.os.SystemClock.uptimeMillis()
        ui.removeCallbacks(drain)
        ui.postDelayed(drain, SETTLE_MS)
    }

    /** Akhir giliran: jalankan semua yang tertahan sekaligus (tanpa menunggu), lalu pasang ringkasan yang tertunda. */
    fun flushAll() {
        ui.removeCallbacks(drain)
        while (true) {
            val st = script.pollFirst() ?: break
            val ev = st.event
            if (ev != null) handle(ev) else act.streamAppend(st.delta ?: "")
        }
        backlog = 0
        finishPending()
    }

    fun reset() {
        ui.removeCallbacks(drain)
        ui.removeCallbacks(awaitWatch)
        script.clear()
        backlog = 0
        settledAt = 0L
        awaitingText = false
        turnDone = false
        current?.active = false
        current = null
        lastLive = ""
        fallbackId = 0
        running.clear()
        // Putaran baru: tampilkan lagi baris status live. onTextActive() menyembunyikannya selama jawaban putaran sebelumnya
        // mengalir, dan panel aktivitas yang masih tampil membuat status di dalam chat ikut disembunyikan; tanpa ini pesan
        // kedua dan seterusnya kosong tanpa indikator sampai alat pertama berjalan.
        act.runOnUiThread { act.zone.setLiveVisible(true) }
    }

    fun accept(event: JSONObject) {
        val now = android.os.SystemClock.uptimeMillis()
        if (script.isNotEmpty() || textBusy() || now - settledAt < SETTLE_MS) {
            script.addLast(Step(null, event))
            backlog = script.size
            scheduleDrain()
            return
        }
        handle(event)
    }

    private fun handle(event: JSONObject) {
        when (event.optString("type")) {
            "message_start" -> {
                finishGroup()
                act.streamSegmentEnd()
                // Seperti rekaman acuan: selama teks diketik, baris status disembunyikan; muncul lagi saat alat berikutnya mulai.
                awaitingText = true
                act.zone.setLiveVisible(false)
                ui.removeCallbacks(awaitWatch)
                ui.postDelayed(awaitWatch, AWAIT_TEXT_MS)
            }
            "item_started" -> {
                val item = event.optJSONObject("item") ?: return
                val entry = classify(item) ?: return
                awaitingText = false
                act.zone.setLiveVisible(true)
                // Item tool membatasi pesan AI sebelumnya. Pesan berikutnya akan dibuat setelah baris aktivitas ini.
                act.streamSegmentEnd()
                val narration = narrationOf(item, entry)
                if (entry.category == "thinking") {
                    // Penalaran tidak meninggalkan baris jam di layar (rekaman acuan hanya punya satu baris ringkasan per
                    // rangkaian alat); cukup status kerja "Proses berpikir" pada baris live. Isi mentah tidak ditampilkan.
                    // Tidak menutup rangkaian alat: satu ringkasan mencakup beberapa langkah di antara penalaran.
                    act.zone.setCodexIdle("Proses berpikir")
                } else {
                    running[entry.id] = idleOf(entry)
                    upsert(entry)
                    if (narration != null) {
                        lastLive = narration
                        act.zone.showCodexNarration(narration, idleOf(entry))
                    }
                }
            }
            "item_completed" -> {
                val item = event.optJSONObject("item") ?: return
                if (item.optString("type") == "reasoning") {
                    val line = summaryLine(item.optString("summary"))
                    if (line.isNotBlank()) {
                        lastLive = line
                        act.zone.showCodexNarration(line, "Proses berpikir")
                    }
                    return
                }
                val id = item.optString("id").ifBlank { "unknown" }
                running.remove(id)
                // Semua alat selesai: model sedang memproses hasilnya, bukan lagi menjalankan perintah.
                if (running.isEmpty()) act.zone.setCodexIdle("Proses berpikir")
                current?.entries?.get(id)?.let { old ->
                    current!!.entries[id] = old.copy(
                        detail = detailOf(item),
                        status = completionStatus(item)
                    )
                    render(current!!)
                }
            }
            "turn_completed" -> {
                turnDone = true
                finishGroup()
            }
        }
    }

    private fun classify(item: JSONObject): Entry? {
        val id = item.optString("id").ifBlank { "codex-${++fallbackId}" }
        return when (item.optString("type")) {
            "commandExecution", "command_execution" -> {
                val command = item.optString("command")
                val read = looksLikeRead(command)
                Entry(id, if (read) "read" else "command",
                    if (read) "Membaca file" else "Menjalankan perintah", detailOf(item),
                    subject = if (read) commandSubject(command) else shellCore(command).take(34))
            }
            "fileChange", "file_change" -> Entry(id, "file", "Mengubah file", detailOf(item),
                subject = item.optJSONArray("changes")?.optJSONObject(0)?.optString("path").orEmpty().substringAfterLast('/').take(34))
            "webSearch", "web_search" -> Entry(id, "search", "Mencari di web", detailOf(item), subject = item.optString("query").trim().take(34))
            "mcpToolCall", "mcp_tool_call", "dynamicToolCall", "dynamic_tool_call", "collabToolCall", "collab_tool_call" ->
                Entry(id, "tool", "Menggunakan alat", detailOf(item))
            "imageView", "image_view" -> Entry(id, "read", "Memeriksa gambar", detailOf(item))
            // Tampilkan status umum saja; jangan pernah menampilkan isi reasoning mentah.
            "reasoning" -> Entry(id, "thinking", "Menganalisis langkah berikutnya", "Aktivitas penalaran internal disembunyikan.")
            else -> null
        }
    }

    /** Status umum singkat yang tampil di antara dua narasi (seperti "Sedang menjalankan perintah" / "Proses berpikir" di video). */
    private fun idleOf(entry: Entry): String = when (entry.category) {
        "read" -> "Sedang membaca file"
        "command" -> "Sedang menjalankan perintah"
        "file" -> "Sedang mengubah file"
        "search" -> "Sedang mencari di web"
        "tool" -> "Sedang menjalankan alat"
        else -> "Proses berpikir"
    }

    /** Narasi satu baris pendek berbentuk ringkasan langkah (seperti rekaman acuan), memuat nama file/perintah/kueri aktual. */
    private fun narrationOf(item: JSONObject, entry: Entry): String? {
        return when (entry.category) {
            "read" -> {
                if (entry.title == "Memeriksa gambar") "Memeriksa gambar"
                else {
                    val subject = commandSubject(item.optString("command"))
                    if (subject.isBlank()) "Membaca file proyek" else "Membaca $subject"
                }
            }
            "command" -> {
                val core = shellCore(item.optString("command"))
                if (core.isBlank()) "Menjalankan perintah" else "Menjalankan ${core.take(40)}"
            }
            "file" -> {
                val changes = item.optJSONArray("changes")
                val path = changes?.optJSONObject(0)?.optString("path").orEmpty()
                val leaf = path.substringAfterLast('/').take(36)
                if (leaf.isBlank()) "Menerapkan perubahan file" else "Memperbarui $leaf"
            }
            "search" -> {
                val q = item.optString("query").trim().take(46)
                if (q.isBlank()) "Mencari referensi" else "Mencari $q"
            }
            "tool" -> {
                val tool = item.optString("tool").ifBlank { item.optString("server") }.trim().take(32)
                if (tool.isBlank()) "Menjalankan alat pendukung" else "Menggunakan alat $tool"
            }
            "thinking" -> "Menimbang langkah berikutnya"
            else -> null
        }
    }

    /**
     * Ringkasan penalaran (summary) yang memang dikirim server untuk ditampilkan, bukan isi penalaran mentah.
     * Diambil judul/kalimat pertamanya saja, satu baris pendek.
     */
    private fun summaryLine(raw: String): String {
        val clean = raw.replace("**", "").replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return ""
        val first = clean.split(Regex("(?<=[.!?])\\s")).first().trim()
        return first.take(80)
    }

    /** Buang pembungkus `bash -lc '...'` supaya yang tampil adalah perintah sebenarnya. */
    private fun shellCore(command: String): String {
        var c = command.trim()
        val m = Regex("^(?:/\\S*/)?(?:ba|z)?sh\\s+-l?c\\s+(['\"])(.*)\\1$", RegexOption.DOT_MATCHES_ALL).find(c)
        if (m != null) c = m.groupValues[2].trim()
        return c.replace(Regex("\\s+"), " ")
    }

    /** Nama file yang disebut dalam perintah baca (maksimal dua); bila tidak ada, potongan perintahnya. */
    private val FILE_EXTS = setOf(
        "kt", "kts", "java", "py", "js", "mjs", "ts", "tsx", "jsx", "json", "jsonl", "yml", "yaml", "md", "txt", "xml",
        "gradle", "properties", "toml", "sh", "bat", "html", "htm", "css", "sql", "csv", "tsv", "cfg", "ini", "conf",
        "lock", "log", "c", "h", "cpp", "hpp", "go", "rs", "rb", "php", "swift", "pro", "cmake", "mk", "env",
        "png", "jpg", "jpeg", "gif", "webp", "svg", "zip", "apk", "aab", "jar", "pdf", "gitignore", "example"
    )

    private fun commandSubject(command: String): String {
        val core = shellCore(command)
        val leaves = Regex("(?:[\\w.-]+/)*[A-Za-z_][\\w-]*\\.[A-Za-z0-9]{1,8}")
            .findAll(core)
            .map { it.value.substringAfterLast('/') }
            // Buang token kode yang mirip nama file (mis. `pathlib.Path`, `os.path`): hanya ekstensi file sungguhan.
            .filter { it.isNotBlank() && it.substringAfterLast('.').lowercase() in FILE_EXTS }
            .distinct()
            .take(2)
            .toList()
        return when (leaves.size) {
            0 -> core.take(40)
            1 -> leaves[0]
            else -> leaves[0] + " dan " + leaves[1]
        }
    }

    private fun looksLikeRead(command: String): Boolean {
        // Lepas pembungkus `bash -lc '...'` dulu: tanda kutip di depan perintah (mis. 'cat file') sebelumnya
        // membuat deteksi gagal sehingga label tampil mentah "Menjalankan cat ..." alih-alih "Membaca ...".
        val lower = shellCore(command).lowercase()
        // Perintah yang menulis (redirect, sed -i, tee) bukan pembacaan murni.
        if (Regex("""(^|[^<>\d])>{1,2}(?!&)|\bsed\s+(-\S*\s+)*-i|\btee\b""").containsMatchIn(lower.replace(Regex("""\d*>\s*/dev/null|\d*>&\d"""), " "))) return false
        val known = setOf("cat", "head", "tail", "sed", "grep", "rg", "find", "ls", "tree", "stat", "wc", "file", "pwd", "du", "realpath", "which")
        val head = lower.substringBefore(' ').substringAfterLast('/')
        if (head in known) return true
        return known.any { Regex("(^|[;&|\\s'\"(])" + Regex.escape(it) + "(\\s|$)").containsMatchIn(lower) }
    }

    private fun detailOf(item: JSONObject): String {
        val parts = ArrayList<String>()
        val type = item.optString("type")
        if (item.has("command")) parts += "Perintah: ${item.optString("command")}" 
        if (item.has("cwd")) parts += "Direktori: ${item.optString("cwd")}" 
        if (item.has("query")) parts += "Pencarian: ${item.optString("query")}" 
        if (item.has("tool")) parts += "Alat: ${item.optString("tool")}" 
        if (item.has("server")) parts += "Server: ${item.optString("server")}" 
        val changes = item.optJSONArray("changes")
        if (changes != null) {
            for (i in 0 until minOf(changes.length(), 12)) {
                val c = changes.optJSONObject(i) ?: continue
                val path = c.optString("path")
                if (path.isNotBlank()) parts += "File: $path (${c.optString("kind")})"
            }
        }
        if (item.has("exitCode") && !item.isNull("exitCode")) parts += "Kode keluar: ${item.optInt("exitCode")}" 
        if (item.has("status")) parts += "Status: ${item.optString("status")}" 
        val output = item.optString("aggregatedOutput").ifBlank { item.optString("output") }
        if (output.isNotBlank()) parts += "Keluaran:\n${output.take(2400)}"
        if (parts.isEmpty()) parts += "Jenis aktivitas: $type"
        return parts.joinToString("\n")
    }

    private fun completionStatus(item: JSONObject): String {
        val status = item.optString("status")
        if (status.contains("fail", true) || (item.has("exitCode") && item.optInt("exitCode", 0) != 0)) return "Gagal"
        return if (status.isBlank()) "Selesai" else status
    }

    private fun upsert(entry: Entry) {
        var g = current
        if (g == null) {
            g = createGroup()
            current = g
            act.zone.codexToolBatchStarted()
        }
        g.entries[entry.id] = entry
        render(g)
    }

    private fun rowLabel(): TextView = TextView(act).apply {
        textSize = 14f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(Live.TEXT_BASE)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        maxWidth = (act.resources.displayMetrics.widthPixels - act.dp(40 + 20 + 12 + 28)).coerceAtLeast(act.dp(80))
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Baris ringkasan alat yang sudah selesai: teks abu-abu + ikon centang kecil + panah (tanpa ikon di kiri), seperti rekaman acuan. */
    private fun createGroup(): Group {
        val label = rowLabel()
        val check = IconView(act, IconView.CHECK, Live.CHEVRON)
        val chevron = IconView(act, IconView.CHEVRON, Live.CHEVRON)
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = act.dp(40)
            setPadding(act.dp(4), act.dp(4), act.dp(10), act.dp(4))
            addView(label, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(check, LinearLayout.LayoutParams(act.dp(16), act.dp(16)).apply { marginStart = act.dp(6) })
            addView(chevron, LinearLayout.LayoutParams(act.dp(20), act.dp(20)).apply { marginStart = act.dp(2) })
        }
        val g = Group(row, label)
        row.setOnClickListener { showDetails(g) }
        // Baris ringkasan sengaja BELUM dipasang: selama agen bekerja hanya baris live yang tampil. Ringkasan baru
        // dipasang di finishGroup() saat agen pindah ke teks berikutnya (seperti rekaman acuan), lalu ditinggal diam.
        return g
    }

    /** Baris penalaran: ikon jam abu-abu + teks abu-abu + panah. Hanya status umum; isi penalaran mentah tidak pernah ditampilkan. */
    private fun addThinkingRow() {
        val slot = android.widget.FrameLayout(act).apply {
            addView(IconView(act, IconView.CLOCK, Live.CHEVRON), android.widget.FrameLayout.LayoutParams(act.dp(24), act.dp(24), Gravity.CENTER))
        }
        val label = rowLabel().apply { text = "Menyiapkan langkah berikutnya…" }
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = act.dp(40)
            setPadding(0, act.dp(4), act.dp(10), act.dp(4))
            addView(slot, LinearLayout.LayoutParams(act.dp(40), act.dp(32)))
            addView(label, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(IconView(act, IconView.CHEVRON, Live.CHEVRON), LinearLayout.LayoutParams(act.dp(20), act.dp(20)))
            setOnClickListener { act.showDetailDialog(label.text.toString(), "Aktivitas penalaran internal disembunyikan.") }
        }
        act.insertBeforeStatus(row, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = act.dp(5) })
        act.scrollDown()
    }

    private fun render(g: Group) {
        val values = g.entries.values.toList()
        val read = values.count { it.category == "read" }
        val commands = values.count { it.category == "command" }
        val files = values.count { it.category == "file" }
        val searches = values.count { it.category == "search" }
        val tools = values.count { it.category == "tool" }
        val parts = ArrayList<String>()
        if (read > 0) parts += if (read == 1) "Membaca sebuah file" else "Membaca $read file"
        if (commands > 0) parts += if (parts.isEmpty()) "Menjalankan $commands perintah" else "menjalankan $commands perintah"
        if (files > 0) parts += if (parts.isEmpty()) "Mengubah $files file" else "mengubah $files file"
        if (searches > 0) parts += if (parts.isEmpty()) "Mencari di web" else "mencari di web"
        if (tools > 0) parts += if (parts.isEmpty()) "Menggunakan $tools alat" else "menggunakan $tools alat"
        g.label.text = parts.joinToString(", ").ifBlank { "Sedang dikerjakan…" }
        if (g.inserted) act.scrollDown()
    }

    private fun showDetails(g: Group) {
        val body = g.entries.values.mapIndexed { i, e ->
            "${i + 1}. ${e.title} — ${e.status}\n${e.detail}"
        }.joinToString("\n\n").ifBlank { "Tidak ada detail aktivitas." }
        act.showDetailDialog(g.label.text.toString(), body)
    }

    private fun finishGroup() {
        current?.let { g ->
            g.active = false
            render(g)
            if (!g.inserted && g.entries.isNotEmpty()) {
                g.inserted = true
                // Yang ditinggalkan di tempat agen adalah teks live terakhirnya (mis. "Membaca README.md"), dalam keadaan
                // mati (tidak berkilau) tetapi tetap bisa diketuk untuk membuka detail langkahnya.
                if (lastLive.isNotBlank()) g.label.text = lastLive
                act.insertBeforeStatus(g.root, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = act.dp(5) })
                act.scrollDown()
            }
        }
        current = null
        lastLive = ""
    }

    /** Dipanggil saat giliran berakhir (termasuk dihentikan) agar ringkasan alat yang tertunda tetap dipasang. */
    fun finishPending() {
        ui.removeCallbacks(drain)
        finishGroup()
    }

    companion object {
        private const val AWAIT_TEXT_MS = 5000L   // batas menunggu teks setelah message_start sebelum indikator tampil lagi
        private const val SETTLE_MS = 600L   // jeda setelah teks selesai diketik sebelum live berikutnya tampil
        private const val WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT
    }
}
