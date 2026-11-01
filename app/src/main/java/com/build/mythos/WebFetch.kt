package com.build.mythos

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset

// Sisi jaringan dan kartu langkah untuk cari_web dan baca_web. Logika murni ada di WebTools.kt.
// Hanya GET. Pengalihan diikuti manual (maks WebTools.MAX_REDIRECTS) dan tiap tujuan diperiksa ulang,
// termasuk hasil DNS. Catatan: celah DNS-rebinding antara pemeriksaan dan koneksi tidak sepenuhnya tertutup.

private fun webFail(card: MainActivity.StepCard, note: String, msg: String): String {
    card.finish(false, note, msg, StepState.FAIL)
    return "exit=1 $msg"
}

private fun webDone(card: MainActivity.StepCard, note: String, shown: String, forModel: String): String {
    card.finish(true, note, Cx.clip(shown, 1500))
    return "exit=0\n$forModel"
}

private fun readCapped(ins: InputStream, cap: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < cap) {
        val n = ins.read(buf, 0, minOf(buf.size, cap - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

private fun charsetOf(contentType: String): Charset {
    val cs = Regex("charset\\s*=\\s*([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
    return try { if (cs != null) Charset.forName(cs) else Charsets.UTF_8 } catch (e: Exception) { Charsets.UTF_8 }
}

/** GET dengan pengaman; mengembalikan (URL akhir, isi). Melempar ConnectorException berisi alasan yang ramah. */
internal fun webGet(start: String): Pair<String, String> {
    var url = start
    for (hop in 0..WebTools.MAX_REDIRECTS) {
        WebTools.checkUrl(url)?.let { throw ConnectorException(it) }
        val u = URL(url)
        if (InetAddress.getAllByName(u.host).any { WebTools.isPrivateAddress(it) }) {
            throw ConnectorException("Alamat tujuan ada di jaringan lokal; ditolak.")
        }
        val c = u.openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MYTHOS/1.0")
        c.setRequestProperty("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.1")
        c.setRequestProperty("Accept-Language", "id,en;q=0.8")
        try {
            val code = c.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val loc = c.getHeaderField("Location") ?: throw ConnectorException("Pengalihan tanpa tujuan.")
                url = URL(u, loc).toString()
                continue
            }
            if (code !in 200..299) throw ConnectorException("Server menjawab HTTP $code.")
            val type = (c.contentType ?: "").lowercase()
            if (type.isNotEmpty() && !(type.startsWith("text/") || type.contains("json") || type.contains("xml"))) {
                throw ConnectorException("Jenis konten tidak didukung ($type); hanya teks atau HTML.")
            }
            val bytes = c.inputStream.use { readCapped(it, WebTools.MAX_BYTES) }
            return url to String(bytes, charsetOf(type))
        } finally {
            c.disconnect()
        }
    }
    throw ConnectorException("Terlalu banyak pengalihan.")
}

internal fun MainActivity.webToolDecls(): List<JSONObject> = listOf(
    decl(
        "cari_web",
        "Cari informasi di web (hanya baca). Untuk fakta terkini atau memeriksa klaim. Jangan masukkan data pribadi (email, NIK, nomor rekening atau telepon) ke kueri.",
        "deskripsi" to desk, "kueri" to "Kata kunci pencarian, singkat dan spesifik"
    ),
    decl(
        "baca_web",
        "Baca isi satu halaman web sebagai teks (hanya baca). URL harus dari hasil cari_web atau diberikan pengguna.",
        "deskripsi" to desk, "url" to "Alamat lengkap halaman, diawali https://"
    )
)

internal fun MainActivity.toolCariWeb(args: JSONObject, card: MainActivity.StepCard): String {
    val q = args.optString("kueri").trim()
    WebTools.checkQuery(q)?.let { return webFail(card, "kueri ditolak", it) }
    return try {
        val (_, html) = webGet("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8") + "&kl=id-id")
        val res = WebTools.parseResults(html)
        if (res.isEmpty()) {
            webFail(card, "tidak ada hasil", "Tidak ada hasil yang terbaca (mesin pencari mungkin membatasi permintaan). Coba kueri lain, atau katakan ke pengguna bahwa kamu tidak bisa memverifikasi.")
        } else {
            val body = WebTools.formatResults(res)
            webDone(card, "${res.size} hasil", body, Cx.untrusted("hasil pencarian web untuk \"$q\"", body))
        }
    } catch (e: Exception) {
        webFail(card, "pencarian gagal", Cx.friendly(e))
    }
}

internal fun MainActivity.toolBacaWeb(args: JSONObject, card: MainActivity.StepCard): String {
    val raw = args.optString("url").trim()
    WebTools.checkUrl(raw)?.let { return webFail(card, "URL ditolak", it) }
    return try {
        val (finalUrl, body) = webGet(raw)
        val isHtml = body.contains("<html", ignoreCase = true) || body.contains("<body", ignoreCase = true) || body.contains("<p", ignoreCase = true)
        val title = if (isHtml) WebTools.titleOf(body) else ""
        val text = if (isHtml) WebTools.htmlToText(body) else Cx.clip(body.trim(), WebTools.MAX_TEXT)
        if (text.isBlank()) {
            webFail(card, "halaman kosong", "Halaman tidak berisi teks yang bisa dibaca (mungkin butuh JavaScript atau login).")
        } else {
            val shown = (if (title.isNotBlank()) "Judul: $title\n" else "") + text
            webDone(card, "halaman dibaca", shown, Cx.untrusted("halaman web $finalUrl", shown))
        }
    } catch (e: Exception) {
        webFail(card, "gagal membuka halaman", Cx.friendly(e))
    }
}
