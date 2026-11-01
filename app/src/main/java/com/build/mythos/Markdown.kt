package com.build.mythos

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

// Render markdown dan blok kode di gelembung chat.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.fillMarkdown(box: LinearLayout, text: String, withCopy: Boolean = true, streaming: Boolean = false) {
    box.removeAllViews()
    (box.tag as? MsgMeta)?.text = text
    var last = 0
    for (m in fenceRe.findAll(text)) {
        addTextBlock(box, text.substring(last, m.range.first), streaming)
        box.addView(
            codeBlock(m.groupValues[1], m.groupValues[2].trimEnd('\n')),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10); bottomMargin = dp(6) }
        )
        last = m.range.last + 1
    }
    if (last < text.length) addTextBlock(box, text.substring(last), streaming)
    if (withCopy && text.isNotBlank()) addCopyRow(box, text)
}

internal fun MainActivity.copyText(label: String, content: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, content))
}

/** Tombol "Salin" di bawah setiap balasan. */
internal fun MainActivity.addCopyRow(box: LinearLayout, content: String) {
    val tv = TextView(this).apply {
        text = "Salin"
        textSize = 12.5f
        setTextColor(Palette.INK_SOFT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(2), dp(8), dp(16), dp(8))
        setOnClickListener {
            copyText("balasan", content)
            text = "Tersalin ✓"
            postDelayed({ text = "Salin" }, 1500)
        }
    }
    box.addView(tv, LinearLayout.LayoutParams(WRAP, WRAP))
}

/**
 * Gaya teks jawaban AI, ala aplikasi Claude: huruf serif, ukuran 16,5 sp, jarak baris lega, warna tinta hangat.
 * Dipakai oleh teks yang sedang mengalir DAN hasil akhirnya supaya tidak ada lompatan huruf/ukuran saat selesai.
 */
internal const val ASSISTANT_TEXT_SP = 16.5f
internal const val ASSISTANT_LINE_SPACING = 1.42f

internal fun styleAssistantText(tv: TextView) {
    tv.textSize = ASSISTANT_TEXT_SP
    tv.typeface = Typeface.create("serif", Typeface.NORMAL)
    tv.setLineSpacing(0f, ASSISTANT_LINE_SPACING)
    tv.letterSpacing = 0.004f
    tv.setTextColor(Palette.INK)
}

internal fun MainActivity.addTextBlock(box: LinearLayout, seg: String, streaming: Boolean = false) {
    val t = seg.trim('\n')
    if (t.isBlank()) return
    for (blk in splitMdBlocks(t)) {
        when (blk) {
            is MdBlock.Table -> box.addView(
                tableView(blk.table) { showMsgMenu(box) },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8); bottomMargin = dp(8) }
            )
            is MdBlock.Text -> {
                val part = blk.text.trim('\n')
                if (part.isBlank()) continue
                val tv = (if (streaming) ShimmerText(this) else TextView(this)).apply {
                    text = mdToSpannable(part)
                    setOnLongClickListener { showMsgMenu(box); true }
                    styleAssistantText(this)
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                    if (this is ShimmerText) setShimmer(false)
                }
                box.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }
    }
}

/** Tabel Markdown: kolom melebar dan teks membungkus supaya muat di layar ponsel; baris judul tebal. */
internal fun MainActivity.tableView(t: MdTable, onLongPress: () -> Unit): View {
    val cols = t.header.size
    val tl = android.widget.TableLayout(this).apply {
        isStretchAllColumns = true
        isShrinkAllColumns = true
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), Palette.DIVIDER)
            cornerRadius = dp(8).toFloat()
        }
        setPadding(dp(1), dp(1), dp(1), dp(1))
    }
    val ctx = this
    fun addRow(cells: List<String>, header: Boolean) {
        val row = android.widget.TableRow(ctx)
        if (header) row.setBackgroundColor(Palette.CHIP)
        for (i in 0 until cols) {
            val sp = inline(cells.getOrElse(i) { "" })
            if (header && sp.isNotEmpty()) sp.setSpan(StyleSpan(Typeface.BOLD), 0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val cell = TextView(ctx).apply {
                text = sp
                textSize = 14f
                setTextColor(Palette.INK)
                setLineSpacing(0f, 1.2f)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnLongClickListener { onLongPress(); true }
            }
            row.addView(cell, android.widget.TableRow.LayoutParams(0, WRAP, 1f))
        }
        tl.addView(row, android.widget.TableLayout.LayoutParams(MATCH, WRAP))
    }
    addRow(t.header, true)
    for (r in t.rows) {
        tl.addView(View(ctx).apply { setBackgroundColor(Palette.DIVIDER) }, android.widget.TableLayout.LayoutParams(MATCH, dp(1)))
        addRow(r, false)
    }
    return tl
}

/** Markdown ringan: judul (#), daftar (-, *), **tebal**, *miring*, `kode inline`, dan [tautan](tujuan). */
internal fun MainActivity.mdToSpannable(srcRaw: String): SpannableStringBuilder {
    val src = stripIcons(srcRaw)
    val out = SpannableStringBuilder()
    val lines = src.split("\n")
    for ((i, raw) in lines.withIndex()) {
        val start = out.length
        val h = headRe.find(raw)
        val b = bulletRe.find(raw)
        if (h != null) {
            out.append(inline(h.groupValues[2]))
            val size = when (h.groupValues[1].length) { 1 -> 1.22f; 2 -> 1.14f; else -> 1.06f }
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(size), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (b != null) {
            out.append("    ".repeat(b.groupValues[1].length / 2))
            val bs = out.length
            out.append("•  ")
            out.setSpan(ForegroundColorSpan(Palette.INK_SOFT), bs, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append(inline(b.groupValues[2]))
        } else {
            out.append(inline(raw))
        }
        if (i < lines.size - 1) out.append("\n")
    }
    return out
}

internal fun MainActivity.inline(src: String): SpannableStringBuilder {
    val out = SpannableStringBuilder()
    var last = 0
    for (m in inlineRe.findAll(src)) {
        out.append(src, last, m.range.first)
        val st = out.length
        if (m.groups[1] != null) {
            out.append(m.groupValues[1])
            out.setSpan(TypefaceSpan("monospace"), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(BackgroundColorSpan(Palette.FIELD), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(Palette.INK), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(0.9f), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (m.groups[2] != null) {
            out.append(inline(m.groupValues[2]))
            out.setSpan(StyleSpan(Typeface.BOLD), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (m.groups[4] != null) {
            // Tautan [teks](tujuan): tampilkan hanya teksnya. Hanya http(s) yang bisa diketuk;
            // path berkas lokal (mis. /data/data/com.termux/.../storage.py:23) cukup jadi teks bergaris bawah.
            out.append(inline(m.groupValues[4]))
            val url = m.groupValues[5]
            if (url.startsWith("http://") || url.startsWith("https://")) {
                out.setSpan(android.text.style.URLSpan(url), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                out.setSpan(android.text.style.UnderlineSpan(), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            out.append(m.groupValues[3])
            out.setSpan(StyleSpan(Typeface.ITALIC), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        last = m.range.last + 1
    }
    out.append(src, last, src.length)
    return out
}

/** Pewarnaan sintaks sederhana (komentar, string, angka, kata kunci, fungsi, tipe). Tanpa pustaka. */
internal fun MainActivity.highlight(lang: String, code: String): CharSequence {
    if (code.length > 8000) return code
    val comment = if (lang.trim().lowercase() in hashLangs) "#[^\\n]*"
    else "//[^\\n]*|/\\*[\\s\\S]*?(?:\\*/|\\z)|<!--[\\s\\S]*?(?:-->|\\z)"
    val re = Regex("""($comment)|("(?:\\.|[^"\\\n])*"?|'(?:\\.|[^'\\\n])*'?)|(\b\d+(?:\.\d+)?\b)|([@A-Za-z_][A-Za-z0-9_]*)""")
    val out = SpannableStringBuilder(code)
    fun paint(color: String, a: Int, b: Int, italic: Boolean = false) {
        out.setSpan(ForegroundColorSpan(Color.parseColor(color)), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (italic) out.setSpan(StyleSpan(Typeface.ITALIC), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    for (m in re.findAll(code)) {
        val a = m.range.first
        val b = m.range.last + 1
        when {
            m.groups[1] != null -> paint("#7C8AA5", a, b, true)   // komentar
            m.groups[2] != null -> paint("#86EFAC", a, b)         // string
            m.groups[3] != null -> paint("#FDBA74", a, b)         // angka
            else -> {
                val w = m.value
                when {
                    w in kwSet -> paint("#C4B5FD", a, b)                                  // kata kunci
                    b < code.length && code[b] == '(' -> paint("#7DD3FC", a, b)           // fungsi
                    w[0].isUpperCase() || w[0] == '@' -> paint("#FCD34D", a, b)           // tipe / anotasi
                }
            }
        }
    }
    return out
}

/** Kotak kode bertema gelap: label bahasa berwarna, tombol Salin, teks monospace yang bisa digeser ke samping. */
internal fun MainActivity.codeBlock(lang: String, code: String): View {
    val label = TextView(this).apply {
        text = if (lang.isBlank()) "kode" else lang.trim().lowercase()
        textSize = 11.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.parseColor("#A5B4FC"))
    }
    val copy = TextView(this).apply {
        text = "Salin"
        textSize = 12.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.parseColor("#E2E8F0"))
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#1E293B"))
            cornerRadius = dp(10).toFloat()
        }
        setOnClickListener {
            val cm = this@codeBlock.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("kode", code))
            text = "Tersalin ✓"
            postDelayed({ text = "Salin" }, 1500)
        }
    }
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(8), dp(8), dp(2))
        addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(copy)
    }
    val codeTv = TextView(this).apply {
        text = highlight(lang, code)
        typeface = Typeface.MONOSPACE
        textSize = 13f
        setTextColor(Color.parseColor("#E2E8F0"))
        setTextIsSelectable(true)
        setPadding(dp(14), dp(6), dp(14), dp(14))
    }
    val hs = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(codeTv)
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Palette.CODE_BG)
            cornerRadius = dp(14).toFloat()
        }
        addView(header)
        addView(hs)
    }
}
