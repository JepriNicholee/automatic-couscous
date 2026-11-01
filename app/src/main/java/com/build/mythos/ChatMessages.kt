package com.build.mythos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.style.ImageSpan
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.BitmapFactory
import android.widget.ImageView
import java.io.File
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

// Menambah gelembung pesan, catatan, kartu pasang-APK dan kartu undo ke layar chat.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.insertBeforeStatus(v: View, lp: LinearLayout.LayoutParams) {
    val idx = if (statusRoot.parent === chatBox) chatBox.indexOfChild(statusRoot) else -1
    val zoneIdx = if (zone.parent === chatBox) chatBox.indexOfChild(zone) else -1
    val insertAt = listOf(idx, zoneIdx).filter { it >= 0 }.minOrNull()
    if (insertAt != null) chatBox.addView(v, insertAt, lp) else chatBox.addView(v, lp)
}

/**
 * Dulu menambah header "AI Agent · status" di tiap balasan; itu mengulang baris aktivitas dan membuat layar ramai.
 * Sekarang balasan tampil bersih; status hanya ada di satu pil aktivitas. Fungsi dipertahankan agar pemanggil lama aman.
 */
@Suppress("UNUSED_PARAMETER")
internal fun MainActivity.decorateAgentBubble(box: LinearLayout, active: Boolean) {
    box.findViewWithTag<View>("ai_action_header")?.let { box.removeView(it) }
}

internal fun MainActivity.refreshAgentBubbleHeader() {
    streamBox?.let { decorateAgentBubble(it, busy) }
}

/** Waktu muncul tiap huruf yang sedang mengalir, untuk efek memudar masuk yang mulus per huruf. */
private object RevealClock {
    private var at = LongArray(2048)
    private var n = 0
    private var lastRevealAt = 0L

    fun reset() { n = 0; lastRevealAt = 0L }

    fun mark(count: Int, now: Long) {
        if (count < n) { n = count; return }
        if (count > at.size) at = at.copyOf(maxOf(count, at.size * 2))
        for (i in n until count) at[i] = now
        if (count > n) lastRevealAt = now
        n = count
    }

    fun ageAt(i: Int, now: Long): Long = if (i in 0 until n) now - at[i] else Long.MAX_VALUE

    /** Masih ada huruf yang belum selesai memudar? Dipakai agar huruf terakhir tidak tiba-tiba "pop" penuh. */
    fun animating(now: Long): Boolean = n > 0 && now - lastRevealAt < StreamSmoother.FADE_MS
}

private var smoothFromCodex = false

private fun MainActivity.updateStreamingText(box: LinearLayout, text: String, fade: Boolean = false) {
    // Tampilkan hanya teks yang benar-benar diterima. Penanda Markdown yang belum ditutup disembunyikan
    // (hideOpenMarkup) supaya "**" mentah tidak berkedip lalu berubah jadi tebal.
    val tv = box.findViewWithTag<TextView>("ai_stream_text") ?: TextView(this).apply {
        tag = "ai_stream_text"
        styleAssistantText(this)
        movementMethod = android.text.method.LinkMovementMethod.getInstance()
        box.addView(this, LinearLayout.LayoutParams(MATCH, WRAP))
    }
    val spanned = android.text.SpannableStringBuilder(mdToSpannable(hideOpenMarkup(text)))
    if (fade) {
        // Tiap huruf memudar masuk menurut umurnya sendiri (ease-out ~260 ms), bukan tiga tingkat kaku.
        val now = SystemClock.uptimeMillis()
        val n = spanned.length
        var d = 0
        while (d < n && d < 90) {
            val age = RevealClock.ageAt(text.length - 1 - d, now)
            if (age >= StreamSmoother.FADE_MS) break
            val pos = n - 1 - d
            if (!spanned[pos].isWhitespace()) {
                spanned.setSpan(
                    android.text.style.ForegroundColorSpan((Palette.INK and 0x00FFFFFF) or (StreamSmoother.fadeAlpha(age) shl 24)),
                    pos, pos + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            d++
        }
    }
    tv.text = spanned
}

private var logoBmp: Bitmap? = null

/** Logo kursor (ic_logo_cursor.png). Sudut dihitung dari waktu: satu putaran 2,4 detik, tidak bergantung laju ketikan. */
private fun MainActivity.logoCursor(textSizePx: Float): Drawable {
    val bmp = logoBmp ?: BitmapFactory.decodeResource(resources, R.drawable.ic_logo_cursor).also { logoBmp = it }
    val angle = (SystemClock.uptimeMillis() % 2400L) / 2400f * 360f
    val size = (textSizePx * 0.9f).toInt().coerceAtLeast(1)
    return LogoCursorDrawable(bmp, angle, Palette.INK).apply { setBounds(0, 0, size, size) }
}

private class LogoCursorDrawable(private val bmp: Bitmap, private val angle: Float, tintColor: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
    }
    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.rotate(angle, b.exactCenterX(), b.exactCenterY())
        canvas.drawBitmap(bmp, null, b, paint)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal fun MainActivity.addMsg(kind: Int, text: String, images: List<HistImage> = emptyList(), pin: Boolean = true) = runOnUiThread {
    emptyState.visibility = View.GONE
    if (kind == USER) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = MsgMeta(USER, text)
            setOnLongClickListener { showMsgMenu(this); true }
            background = GradientDrawable().apply {
                setColor(Palette.BUBBLE)
                cornerRadius = dp(12).toFloat()
                setStroke(1, Palette.BUBBLE_BORDER)
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        images.forEach { image ->
            val p = image.previewPath
            if (!p.isNullOrBlank() && File(p).isFile) {
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(BitmapFactory.decodeFile(p))
                    contentDescription = "Gambar terlampir"
                }
                box.addView(iv, LinearLayout.LayoutParams(dp(220), dp(170)).apply { bottomMargin = dp(6) })
            } else {
                box.addView(TextView(this).apply {
                    this.text = "[Gambar sudah dihapus dari penyimpanan]"
                    textSize = 12f
                    setTextColor(Palette.INK)
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(6) })
            }
        }
        if (text.isNotBlank()) {
            box.addView(TextView(this).apply {
                this.text = text
                textSize = 15f
                setTextColor(Palette.INK)
                setLineSpacing(0f, 1.25f)
            }, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        val lp = LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(16)
            gravity = Gravity.END
            marginStart = dp(39)
        }
        insertBeforeStatus(box, lp)
        if (pin) {
            pinToTop(box)
            return@runOnUiThread
        }
    } else {
        val box = newAiBox()
        fillMarkdown(box, text)
        decorateAgentBubble(box, active = false)
    }
    scrollDown()
}

internal fun MainActivity.newAiBox(): LinearLayout {
    // Balasan teks menutup kartu aktivitas yang sedang berjalan; langkah berikutnya membuka kartu baru.
    zone.feed.seal()
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = MsgMeta(AI, "")
        background = null
        setPadding(dp(12), dp(4), dp(12), dp(2))
    }
    box.setOnLongClickListener { showMsgMenu(box); true }
    insertBeforeStatus(box, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
    return box
}

/**
 * Jawaban lengkap (jalur agen / fallback) ditampilkan lewat penghalus yang sama dengan stream Codex: huruf mengalir
 * dengan kecepatan adaptif dan memudar masuk, bukan muncul sekaligus atau dalam gumpalan besar.
 */
internal fun MainActivity.streamUpdate(text: String) = runOnUiThread {
    emptyState.visibility = View.GONE
    // Jawaban sebelumnya masih menunggu selesai diketik: tuntaskan dulu agar tidak tertimpa.
    if (streamBox != null && streamEnded && streamDirectRendering) finalizeDirectStream()
    if (streamBox == null) {
        typewriterHandler.removeCallbacksAndMessages(null)
        typewriterRunnable = null
        typewriterShown = ""
        typewriterTarget = ""
        streamText = ""
        streamEnded = false
        streamCopyAdded = false
        streamBox = newAiBox()
    }
    val box = streamBox ?: return@runOnUiThread
    streamDirectRendering = true
    streamText = text
    typewriterTarget = text
    // Bila teks baru bukan kelanjutan teks yang sudah tampil, mundur ke awalan yang sama.
    if (!text.startsWith(typewriterShown)) {
        val m = minOf(text.length, typewriterShown.length)
        var i = 0
        while (i < m && text[i] == typewriterShown[i]) i++
        typewriterShown = text.substring(0, i)
    }
    streamEnded = false
    decorateAgentBubble(box, active = true)
    smoothFromCodex = false
    startSmoothing()
}

/**
 * Codex mengirim delta nyata. Gambar tepat teks yang baru diterima, tanpa menambahkan efek ketik sintetis
 * yang dapat tertinggal dari output model. Elemen pesan tetap sama dan hanya isi TextView yang berubah.
 */
internal fun MainActivity.streamAppend(delta: String) {
    if (delta.isEmpty()) return
    // Tandai sebelum dispatch ke main thread agar jalur fallback final tidak membuat respons duplikat.
    codexStreamOutputStarted = true
    runOnUiThread {
        emptyState.visibility = View.GONE
        if (streamBox == null) {
            typewriterHandler.removeCallbacksAndMessages(null)
            typewriterRunnable = null
            typewriterShown = ""
            typewriterTarget = ""
            streamText = ""
            streamEnded = false
            streamCopyAdded = false
            streamBox = newAiBox()
        }
        val box = streamBox ?: return@runOnUiThread
        streamDirectRendering = true
        codexStreamOutputStarted = true
        streamText += delta
        typewriterTarget += delta
        streamEnded = false
        decorateAgentBubble(box, active = true)
        // Teks diterima bergelombang; tampilkan per frame dengan kecepatan adaptif (StreamSmoother).
        codexActivityUi.onTextActive()
        smoothFromCodex = true
        startSmoothing()
    }
}

/** Menjalankan satu loop frame yang mengejar [typewriterTarget] secara halus. Aman dipanggil berulang. */
internal fun MainActivity.startSmoothing() {
    if (typewriterRunnable != null) return
    if (typewriterShown.isEmpty()) RevealClock.reset()
    var last = SystemClock.uptimeMillis()
    val pacer = StreamSmoother.Pacer()
    val tick = object : Runnable {
        override fun run() {
            val box = streamBox
            if (box == null) { typewriterRunnable = null; return }
            val now = SystemClock.uptimeMillis()
            val dt = (now - last).coerceAtLeast(1L)
            last = now
            val target = typewriterTarget
            val shown = typewriterShown.length.coerceAtMost(target.length)
            val next = StreamSmoother.nextShown(pacer, target, shown, dt)
            val caughtUp = next >= target.length
            if (next > shown) {
                typewriterShown = target.substring(0, next)
                RevealClock.mark(next, now)
                updateStreamingText(box, typewriterShown, fade = true)
                scrollDown()
            } else if (RevealClock.animating(now)) {
                // Teks sudah habis dikejar, tetapi huruf terakhir masih memudar masuk: lanjutkan sampai tuntas.
                updateStreamingText(box, typewriterShown, fade = true)
            }
            if (!caughtUp || RevealClock.animating(now)) {
                typewriterHandler.postDelayed(this, StreamSmoother.FRAME_MS)
            } else {
                typewriterRunnable = null
                updateStreamingText(box, typewriterShown, fade = false)
                if (smoothFromCodex || codexActivityUi.hasQueuedEvents()) codexActivityUi.onTextSettled()
                // streamEnd() sudah dipanggil saat huruf masih mengalir: selesaikan sekarang, tanpa memotong ketikan.
                if (streamEnded && streamDirectRendering) finalizeDirectStream()
            }
        }
    }
    typewriterRunnable = tick
    typewriterHandler.post(tick)
}

/**
 * Akhiri satu item pesan Codex sebelum baris aktivitas alat berikutnya. Pesan selesai tetap berada di urutan
 * transcript; balasan berikutnya membuat pesan baru setelah baris aktivitas, seperti alur video referensi.
 */
internal fun MainActivity.streamSegmentEnd() = runOnUiThread {
    val box = streamBox
    if (box == null) return@runOnUiThread
    typewriterHandler.removeCallbacksAndMessages(null)
    typewriterRunnable = null
    val finalText = typewriterTarget
    if (finalText.isNotBlank()) {
        fillMarkdown(box, finalText, withCopy = false, streaming = false)
        decorateAgentBubble(box, active = false)
    } else {
        (box.parent as? android.view.ViewGroup)?.removeView(box)
    }
    streamBox = null
    streamText = ""
    typewriterShown = ""
    typewriterTarget = ""
    streamEnded = false
    streamCopyAdded = false
    scrollDown()
}

/** Ganti teks mengalir dengan hasil akhir berformat penuh (blok kode, tabel, tombol Salin) dan bersihkan keadaan stream. */
internal fun MainActivity.finalizeDirectStream() {
    typewriterHandler.removeCallbacksAndMessages(null)
    typewriterRunnable = null
    val box = streamBox
    val finalText = typewriterTarget.ifBlank { streamText }
    if (box != null && finalText.isNotBlank()) {
        fillMarkdown(box, finalText, withCopy = false, streaming = false)
        decorateAgentBubble(box, active = false)
        if (!streamCopyAdded) {
            streamCopyAdded = true
            addCopyRow(box, finalText)
        }
    }
    streamBox = null
    streamText = ""
    typewriterShown = ""
    typewriterTarget = ""
    streamEnded = false
    streamCopyAdded = false
    streamDirectRendering = false
    scrollDown()
}

internal fun MainActivity.streamEnd() = runOnUiThread {
    if (streamDirectRendering) {
        // Masih ada huruf yang mengalir: biarkan penghalus menyelesaikannya, lalu ia memanggil finalizeDirectStream().
        if (typewriterRunnable != null && streamBox != null) {
            streamEnded = true
            return@runOnUiThread
        }
        finalizeDirectStream()
        return@runOnUiThread
    }
    streamEnded = true
    if (streamBox == null || streamText.isBlank()) {
        typewriterHandler.removeCallbacksAndMessages(null)
        typewriterRunnable = null
        streamBox = null
        streamText = ""
        typewriterShown = ""
        typewriterTarget = ""
    } else if (typewriterRunnable == null) {
        // Pengaman jika streamEnd tiba setelah ticker terhenti akibat siklus UI.
        streamUpdate(typewriterTarget)
        streamEnded = true
    }
}

internal fun MainActivity.addNote(t: String) = runOnUiThread {
    val tv = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Palette.INK_SOFT)
        setPadding(dp(4), dp(8), 0, dp(2))
    }
    insertBeforeStatus(tv, LinearLayout.LayoutParams(WRAP, WRAP))
    scrollDown()
}

internal fun MainActivity.addInstallCard(path: String) = runOnUiThread {
    val btn = TextView(this).apply {
        text = "Pasang APK"
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = pressable(rounded(Palette.BRAND, 24))
        setPadding(dp(24), dp(12), dp(24), dp(12))
    }
    btn.setOnClickListener { installApk(path, btn) }
    insertBeforeStatus(btn, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(14) })
    scrollDown()
}

/** Tombol "Urungkan" tidak lagi ditampilkan di obrolan. */
@Suppress("UNUSED_PARAMETER")
internal fun MainActivity.addUndoCard(path: String, bak: String) { /* sengaja kosong */ }

/** Versi lebih gelap dari [c] untuk teks di atas latar pastel. */
internal fun darkOf(c: Int): Int {
    val r = ((c shr 16) and 0xFF) * 6 / 10
    val g = ((c shr 8) and 0xFF) * 6 / 10
    val b = (c and 0xFF) * 6 / 10
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
