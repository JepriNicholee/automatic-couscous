package com.kita.agent

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Percobaan ulang saat membuka koneksi (jaringan seluler sering goyah) dan saat stream putus di tengah jalan. */
private const val OPEN_RETRIES = 5
private const val STREAM_RETRIES = 5

// Pemanggilan model umum: pilih penyedia, ulangi saat gagal, stream, parsing argumen, CLI.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.callModel(key: String, onText: (String) -> Unit, onFlush: () -> Unit): Hist? =
    when (preset(prov).fmt) {
        "openai" -> callOpenAI(key, onText, onFlush)
        "anthropic" -> callAnthropic(key, onText, onFlush)
        else -> callGemini(key, onText, onFlush)
    }

/**
 * Ulangi (maks $STREAM_RETRIES x) kalau koneksi putus/timeout di tengah stream. Teks parsial diganti oleh percobaan baru
 * karena kotak balasan digambar ulang dari awal. Tidak diulang kalau teks sudah di-flush (agar tidak dobel).
 */
internal fun MainActivity.callModelRetry(key: String, onText: (String) -> Unit, onFlush: () -> Unit): Hist? {
    var attempt = 0
    while (true) {
        var flushed = false
        streamOpen = false
        try {
            return callModel(key, onText, { flushed = true; onFlush() })
        } catch (e: java.io.IOException) {
            if (cancelled) throw Cancelled()
            if (!streamOpen || flushed || attempt >= STREAM_RETRIES) {
                val part = lastPartial
                if (part.isNotBlank()) {
                    synchronized(hist) { hist.add(Hist(1, part)) }
                    lastPartial = ""
                }
                throw e
            }
            attempt++
            for (sec in minOf(2 shl (attempt - 1), 15) downTo 1) {
                setStatus(IconView.CLOCK, "Koneksi terputus, mengulang dalam $sec dtk ($attempt/$STREAM_RETRIES)")
                Thread.sleep(1000)
                if (cancelled) throw Cancelled()
            }
            lastPartial = ""
        }
    }
}

/**
 * Buka koneksi POST streaming. Untuk error sementara (429, 502, 503, 504, 529, atau jaringan putus)
 * otomatis dicoba lagi sampai 5 kali dengan jeda 2, 4, 8, 16 detik (atau sesuai header Retry-After, maks 20 detik).
 */
internal fun MainActivity.openStream(url: String, headers: Map<String, String>, body: JSONObject): HttpURLConnection {
    checkUrl(url)
    val payload = body.toString().toByteArray()
    var attempt = 0
    while (true) {
        if (cancelled) throw Cancelled()
        var code = -1
        var err = ""
        var retryAfter: Int? = null
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            activeConn = c
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 30000
            c.readTimeout = 180000 // model berpikir/konteks besar bisa lama sebelum token pertama
            c.useCaches = false
            // Jangan pakai ulang koneksi lama: setelah tool berjalan lama (mis. build) koneksi idle sering sudah
            // dimatikan server/NAT dan menghasilkan "unexpected end of stream".
            c.setRequestProperty("Connection", "close")
            c.setRequestProperty("Content-Type", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(payload) }
            code = c.responseCode
            if (code < 400) { streamOpen = true; return c }
            err = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            retryAfter = c.getHeaderField("Retry-After")?.trim()?.toIntOrNull()
            c.disconnect()
        } catch (e: java.io.IOException) {
            c.disconnect()
            if (cancelled) throw Cancelled()
            if (attempt >= OPEN_RETRIES) {
                throw java.io.IOException(
                    "Tidak bisa terhubung ke server AI setelah $OPEN_RETRIES percobaan (${e.message ?: e.javaClass.simpleName}). " +
                        "Periksa sinyal/VPN, lalu kirim \"Lanjutkan\".", e
                )
            }
            err = e.message ?: "koneksi gagal"
            code = -1
        }
        if ((code != -1 && code !in retryable) || attempt >= OPEN_RETRIES) {
            throw RuntimeException("API $code: ${err.take(300)}")
        }
        attempt++
        val wait = minOf(retryAfter ?: (2 shl (attempt - 1)), 20)
        val snap = saveStatus()
        val why = if (code > 0) "Server sibuk ($code)" else "Koneksi terputus"
        for (sec in wait downTo 1) {
            setStatus(IconView.CLOCK, "$why, coba lagi dalam $sec dtk ($attempt/$OPEN_RETRIES)")
            Thread.sleep(1000)
            if (cancelled) throw Cancelled()
        }
        restoreStatus(snap)
    }
}

internal fun MainActivity.str(o: JSONObject, k: String): String = if (o.isNull(k)) "" else o.optString(k, "")

internal fun MainActivity.declList(): List<JSONObject> {
    val decls = tools.getJSONObject(0).getJSONArray("functionDeclarations")
    return (0 until decls.length()).map { decls.getJSONObject(it) }
}

internal fun MainActivity.parseArgs(s: String): JSONObject =
    try { JSONObject(if (s.isBlank()) "{}" else s) } catch (e: Exception) { JSONObject().put("__error", truncMsg) }

/** Argumen kosong pada respons yang terpotong dianggap rusak, bukan "tanpa argumen". */
internal fun MainActivity.argsOf(s: String, truncated: Boolean): JSONObject =
    if (truncated && s.isBlank()) JSONObject().put("__error", truncMsg) else parseArgs(s)

internal fun MainActivity.cliChat(userMsg: String) {
    val lg = cliLoginOf(prov)
    if (lg != null && !cliLoggedIn(lg)) {
        runOnUiThread { showSettings() }
        addMsg(AI, "Belum masuk dengan ${lg.account}. Ketuk \"Masuk dengan ${lg.account}\" di Pengaturan, lalu kirim pesanmu lagi.")
        return
    }
    var t = cfgBase().ifBlank { "gemini -p {prompt} 2>/dev/null" }
    if (!t.contains("{prompt}")) t += " {prompt}"
    val cmd = t.replace("{prompt}", sh(userMsg))
    synchronized(hist) { hist.add(Hist(0, userMsg)) }
    setStatus(IconView.TERMINAL, "Menjalankan CLI di Termux", "$ " + cmd.take(300))
    val approved = cliAllowedTpl == t || confirm(
        "Jalankan di Termux:\n\n$cmd", cmd, "Mengirim pesan Anda ke CLI di Termux",
        onAll = { cliAllowedTpl = t }, allLabel = "Ya, selalu"
    )
    if (!approved) {
        if (cancelled) throw Cancelled()
        val msg = "Ditolak pengguna. CLI tidak dijalankan."
        addMsg(AI, msg)
        synchronized(hist) { hist.add(Hist(1, msg)) }
        saveHist()
        return
    }
    // stdin ditutup: CLI yang membaca stdin (mis. codex) akan menunggu tanpa henti di Termux.
    val out = termux("$cmd < /dev/null", timeoutSec = 300)
    val lines = out.lines()
    val ei = lines.indexOfFirst { it.startsWith("exit=") }
    val body = (if (ei >= 0) lines.take(ei) + lines.drop(ei + 1) else lines).joinToString("\n").trim()
    val shown = when {
        body.isNotEmpty() -> body
        cliLoginOf(prov) != null -> "CLI tidak menjawab. Mungkin belum masuk: buka Pengaturan lalu ketuk \"Masuk dengan ${cliLoginOf(prov)?.account}\"."
        else -> "(CLI tidak mengeluarkan teks)"
    }
    addMsg(AI, shown)
    showChips(shown)
    synchronized(hist) { hist.add(Hist(1, shown)) }
    saveHist()
}
