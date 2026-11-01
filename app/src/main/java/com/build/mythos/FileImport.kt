package com.build.mythos

import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

// Lampirkan file dari penyimpanan HP ke MYTHOS/attachments.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.pickFile() {
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
    }
    try {
        startActivityForResult(i, 78)
    } catch (e: Exception) {
        toast("Pemilih file tidak tersedia")
    }
}

/** Pilih gambar dari galeri/pemilih dokumen tanpa meminta akses penyimpanan luas. */
internal fun MainActivity.pickImage() {
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
    }
    try {
        startActivityForResult(i, 79)
    } catch (e: Exception) {
        toast("Pemilih gambar tidak tersedia")
    }
}

internal fun MainActivity.displayName(uri: Uri): String {
    var n = "file"
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val v = c.getString(0)
            if (!v.isNullOrBlank()) n = v
        }
    }
    return n.replace(Regex("[^A-Za-z0-9._ -]"), "_")
}

internal class StagedAttachment(val path: String, val cleanup: () -> Unit)

/** Tahap pertama: salin file pilihan ke Download/MYTHOS/attachments agar dapat dibaca Termux. */
internal fun MainActivity.copyToDownload(uri: Uri, name: String): StagedAttachment {
    val src = contentResolver.openInputStream(uri) ?: throw RuntimeException("File tidak bisa dibaca")
    return src.use { ins ->
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MYTHOS/attachments")
            }
            val dest = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw RuntimeException("Gagal membuat file di Download")
            val out = contentResolver.openOutputStream(dest) ?: throw RuntimeException("Gagal menulis file")
            out.use { o -> ins.copyTo(o) }
            var finalName = name
            contentResolver.query(dest, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) finalName = c.getString(0)
            }
            StagedAttachment("/sdcard/Download/MYTHOS/attachments/$finalName") {
                try { contentResolver.delete(dest, null, null) } catch (ignored: Exception) { logQuiet("FileImport.kt", ignored) }
            }
        } else {
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "MYTHOS/attachments")
            dir.mkdirs()
            val f = File(dir, name)
            f.outputStream().use { o -> ins.copyTo(o) }
            StagedAttachment("/sdcard/Download/MYTHOS/attachments/$name") { try { f.delete() } catch (ignored: Exception) { logQuiet("FileImport.kt", ignored) } }
        }
    }
}

/** Tahap kedua: salin dari penyimpanan bersama ke workspace Termux MYTHOS/attachments. */
internal fun MainActivity.copyToMythosAttachments(uri: Uri, name: String): String {
    val staged = copyToDownload(uri, name)
    val finalName = staged.path.substringAfterLast('/')
    val target = "$termuxHome/MYTHOS/attachments/$finalName"
    try {
        val r = termux(
            "mkdir -p ${sh(termuxHome + "/MYTHOS/attachments")} && " +
                "cp -f ${sh(staged.path)} ${sh(target)} && echo MYTHOS_ATTACHMENT=ok",
            limit = 2000, timeoutSec = 120
        )
        if (!r.startsWith("exit=0") || !r.contains("MYTHOS_ATTACHMENT=ok")) {
            throw RuntimeException("Gagal menyalin ke MYTHOS/attachments")
        }
        return target
    } finally {
        // File perantara tidak lagi diperlukan setelah percobaan penyalinan selesai.
        staged.cleanup()
    }
}

internal fun MainActivity.makeImagePreview(uri: Uri, name: String): String? {
    if (!isImageName(name)) return null
    return try {
        val dir = File(filesDir, "chat_images").apply { mkdirs() }
        val out = File(dir, UUID.randomUUID().toString() + ".jpg")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
        bitmap.recycle()
        out.absolutePath
    } catch (ignored: Exception) { null }
}

internal fun MainActivity.importFile(uri: Uri) {
    if (Build.VERSION.SDK_INT < 29 &&
        checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    ) {
        pendingImport = uri
        requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
        return
    }
    val name = displayName(uri)
    val att = Attachment(name, previewUri = if (isImageName(name)) uri else null)
    attachments.add(att)
    refreshAttachments()   // kartu langsung tampil (redup selama disalin)
    focusInput()           // keyboard terbuka: ketik perintah untuk file ini
    if (VideoFrames.isVideoName(name)) attachVideoFrames(uri, name)   // video juga dilampirkan sebagai beberapa gambar (frame)
    bg {
        try {
            val copied = copyToMythosAttachments(uri, name)
            val preview = makeImagePreview(uri, name)
            // Isi file dibaca SEKARANG (bukan nanti oleh agen) supaya ikut tersimpan di riwayat percakapan.
            // path diisi paling akhir: kartu baru "siap" setelah analisis selesai.
            try { analyzeAttachment(att, uri, copied) } catch (e: Exception) { logQuiet("FileImport.kt", e) }
            att.previewPath = preview
            att.path = copied
            runOnUiThread { refreshAttachments() }
        } catch (e: Exception) {
            runOnUiThread {
                attachments.remove(att)
                refreshAttachments()
                toast("Gagal melampirkan: " + (e.message ?: "").take(80))
            }
        }
    }
}

// ---------------------------------------------------------------- Baca isi lampiran

/** Batas karakter isi file teks yang disimpan per lampiran; jatah akhir per pesan ditentukan saat kirim (fitDigests). */
private const val ATT_TEXT_READ_MAX = 24_000
private const val ATT_DOCX_MAX_BYTES = 30L * 1024L * 1024L

private fun readBytesLimited(input: java.io.InputStream, max: Long): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(16384)
    var total = 0L
    while (true) {
        val n = input.read(chunk)
        if (n < 0) break
        total += n
        if (total > max) return null
        out.write(chunk, 0, n)
    }
    return out.toByteArray()
}

/** Badan keluaran termux() bila exit=0, selain itu null. */
private fun termuxBody(r: String): String? = if (r.startsWith("exit=0")) r.substringAfter('\n', "") else null

/**
 * Isi att.digest, att.summary, dan att.extractedDir dari isi file: teks dibaca langsung, docx diekstrak teksnya,
 * pdf lewat pdftotext di Termux, zip dipindai (manifest) lalu diekstrak ke MYTHOS/projects/aktif. Dipanggil di thread latar.
 */
internal fun MainActivity.analyzeAttachment(att: Attachment, uri: Uri, termuxPath: String) {
    val name = att.name
    when {
        isImageName(name) -> att.summary = "gambar; dikirim ke model sebagai gambar di beberapa pesan terakhir"
        isZipName(name) -> analyzeZip(att, uri, termuxPath)
        isDocxName(name) -> {
            val bytes = contentResolver.openInputStream(uri)?.use { readBytesLimited(it, ATT_DOCX_MAX_BYTES) }
            val text = bytes?.let { docxToText(it, ATT_TEXT_READ_MAX) }
            if (text.isNullOrBlank()) {
                att.summary = "dokumen Word; teks tidak bisa diekstrak otomatis, buka dari path dengan alat"
            } else {
                att.digest = text
                att.summary = "dokumen Word, ${text.length} karakter teks sudah dikirim di pesan"
            }
        }
        isPdfName(name) -> {
            val r = termux(
                "command -v pdftotext >/dev/null 2>&1 || { echo MYTHOS_NO_PDFTOTEXT; exit 0; }; " +
                    "pdftotext -layout -l 40 ${sh(termuxPath)} - 2>/dev/null | head -c $ATT_TEXT_READ_MAX",
                limit = ATT_TEXT_READ_MAX + 500, timeoutSec = 90
            )
            val body = termuxBody(r)
            when {
                body == null -> att.summary = "PDF; teks tidak bisa dibaca otomatis"
                body.contains("MYTHOS_NO_PDFTOTEXT") -> att.summary = "PDF; pdftotext belum terpasang di Termux (pkg install poppler)"
                body.isBlank() -> att.summary = "PDF tanpa lapisan teks (mungkin hasil scan)"
                else -> {
                    att.digest = body.trim()
                    att.summary = "PDF, teks halaman awal sudah dikirim di pesan"
                }
            }
        }
        else -> {
            val res = contentResolver.openInputStream(uri)?.use { readTextLimited(it, ATT_TEXT_READ_MAX) }
            if (res == null) {
                att.summary = "file biner; buka dari path dengan alat bila perlu"
            } else {
                att.digest = res.first
                att.summary = if (res.second) "teks panjang; ${res.first.length} karakter awal sudah dikirim, sisanya di path"
                else "teks ${res.first.length} karakter, sudah dikirim utuh di pesan"
            }
        }
    }
}

private fun MainActivity.analyzeZip(att: Attachment, uri: Uri, termuxPath: String) {
    val scan = contentResolver.openInputStream(uri)?.use { scanZip(it) }
    if (scan == null) {
        att.summary = "zip tidak bisa dibuka"
        return
    }
    var dir: String? = null
    var note = ""
    if (scan.safeToExtract && scan.files.isNotEmpty()) {
        try {
            val r = termux(
                buildExtractCommand(termuxPath, "$termuxHome/MYTHOS/projects/aktif", safeFolderName(att.name)) { sh(it) },
                limit = 2000, timeoutSec = 300
            )
            dir = parseExtractDir(r)
            if (dir == null) note = "Ekstraksi otomatis gagal. Ekstrak sendiri dengan unzip dari path zip."
        } catch (e: Exception) {
            note = "Ekstraksi otomatis gagal. Ekstrak sendiri dengan unzip dari path zip."
        }
    } else if (scan.error == null) {
        note = "Tidak diekstrak otomatis (melewati batas ukuran atau berisi path berbahaya). Periksa dulu lewat unzip -l dari path zip."
    }
    att.extractedDir = dir
    att.summary = zipSummary(scan, dir)
    att.digest = renderZipManifest(att.name, scan, dir, note)
}
