package com.build.mythos

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Log pusat kegagalan alat agen di MYTHOS/logs/error_summary.log (satu baris per kegagalan).
 * Agen bisa membacanya (cat/tail) untuk analisis akar masalah tanpa menebak dari potongan keluaran.
 * Bagian murni JVM supaya bisa diuji; penulisan ke Termux ada di MainActivity.logErrorSummary.
 */
internal object ErrorSummaryLog {
    const val DIR = "MYTHOS/logs"
    const val FILE = "$DIR/error_summary.log"
    const val MAX_LINES = 500
    private const val MAX_DETAIL = 400

    /** Satu baris: "2026-10-24T03:20:00Z | alat | kategori | ringkasan" (tanpa baris baru). */
    fun line(timeMs: Long, tool: String, kind: String, detail: String): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val flat = detail.replace(Regex("\\s+"), " ").trim().take(MAX_DETAIL)
        return "${fmt.format(Date(timeMs))} | ${tool.ifBlank { "?" }} | ${kind.ifBlank { "?" }} | $flat"
    }

    /** Perintah shell: tambahkan satu baris lalu pangkas ke MAX_LINES terakhir. [absFile] sudah absolut. */
    fun appendCommand(absFile: String, line: String): String {
        val b64 = java.util.Base64.getEncoder().encodeToString((line + "\n").toByteArray(Charsets.UTF_8))
        val f = shq(absFile)
        val tmp = shq("$absFile.tmp")
        return "mkdir -p \"\$(dirname $f)\" && printf %s '$b64' | base64 -d >> $f && " +
            "tail -n $MAX_LINES $f > $tmp && mv $tmp $f"
    }

    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

/** Catat kegagalan alat. Tidak boleh pernah mengganggu alur agen. */
internal fun MainActivity.logErrorSummary(tool: String, kind: String, detail: String) {
    try {
        val l = ErrorSummaryLog.line(System.currentTimeMillis(), tool, kind, detail)
        termux(
            ErrorSummaryLog.appendCommand("$termuxHome/${ErrorSummaryLog.FILE}", l),
            limit = 200, timeoutSec = 10, useWorkdir = false, killable = false, silentRetry = true
        )
    } catch (ignored: Exception) {
    }
}
