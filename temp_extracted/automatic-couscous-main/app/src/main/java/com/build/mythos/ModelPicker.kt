package com.build.mythos

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

// Memilih model: label, daftar, uji (probe), ketik manual, templat.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.updateModelLabel() {
    val switched = fallbackProv != null || modelOverride != null
    val n = activeModelText(
        preset(prov).fmt == "cli", prov, pvOf(prov, "model").ifEmpty { preset(prov).model },
        fallbackProv, fallbackModel, modelOverride
    )
    val cut = if (switched) -1 else n.indexOf(' ')
    val sp = android.text.SpannableString(n)
    if (cut > 0) {
        sp.setSpan(android.text.style.ForegroundColorSpan(Palette.INK_SOFT), cut, n.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    runOnUiThread { modelLabel.text = sp }
}

internal fun MainActivity.pickModel() {
    if (preset(prov).fmt == "cli") { showSettings(); return }
    if (lockedModelFor(prov) != null) { toast(lockedModelMessage(prov)); return }
    if (needsKey() && cfgKey().isEmpty()) { showSettings(); return }
    if (modelSetupBusy) { toast("Sedang menyiapkan model, tunggu sebentar..."); return }
    toast("Memuat daftar model...")
    bg {
        val list = try {
            fetchModels()
        } catch (e: Exception) {
            runOnUiThread { toast("Gagal memuat daftar: " + (e.message ?: "").take(80)) }
            emptyList<String>()
        }
        runOnUiThread {
            if (list.isEmpty()) {
                typeModel()
            } else {
                val c = loadProbed()
                if (c != null) showProbed(c.first, c.second, list) else autoSetupModels(force = true)
            }
        }
    }
}

/** Satu baris model: nama, penanda terpilih (centang), dan keterangan kecil bila model sedang sibuk. */
private fun MainActivity.modelRow(name: String, selected: Boolean, busyNote: Boolean, onPick: () -> Unit): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(if (selected) rounded(Palette.BUBBLE, 14) else null)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        addView(TextView(context).apply {
            text = name
            textSize = 15.5f
            setTextColor(Palette.INK)
            if (selected) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (busyNote) addView(TextView(context).apply {
            text = "sibuk"
            textSize = 12f
            setTextColor(Palette.INK_SOFT)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        if (selected) addView(
            IconView(context, IconView.CHECK, Palette.BRAND),
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(8) }
        )
        setOnClickListener { onPick() }
    }

/** Lembar pilihan model: daftar bersih + tautan "Semua model" / "Ketik manual" di bawah. */
private fun MainActivity.showModelSheet(
    names: List<String>, busy: Set<String>, footerLabel: String, onFooter: () -> Unit
) {
    var dlg: Dialog? = null
    val cur = cfgModel()
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        for (n in names) {
            addView(modelRow(n.removePrefix("models/"), n == cur, n in busy) {
                prefs.edit().putString("p_${prov}_model", n).apply()
                updateModelLabel()
                dlg?.dismiss()
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }
    val footer = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(softButton(footerLabel) { dlg?.dismiss(); onFooter() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(softButton("Ketik manual") { dlg?.dismiss(); typeModel() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
    }
    dlg = showSheet("Pilih model", prov, list, footer)
}

internal fun MainActivity.showModelList(list: List<String>) {
    showModelSheet(list, emptySet(), "Kembali") { pickModel() }
}

internal fun MainActivity.showProbed(good: List<String>, busy: List<String>, all: List<String>) {
    showModelSheet(good + busy, busy.toSet(), "Semua model") { showModelList(all) }
}

internal fun MainActivity.typeModel() {
    if (lockedModelFor(prov) != null) { toast(lockedModelMessage(prov)); return }
    val et = EditText(this).apply {
        hint = "Nama model"
        inputType = InputType.TYPE_CLASS_TEXT
        setText(cfgModel())
        setSelection(text.length)
    }
    val box = LinearLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(et, LinearLayout.LayoutParams(MATCH, WRAP))
    }
    AlertDialog.Builder(this)
        .setTitle("Model ($prov)")
        .setView(box)
        .setPositiveButton("Simpan") { _, _ ->
            prefs.edit().putString("p_${prov}_model", et.text.toString().trim()).apply()
            updateModelLabel()
        }
        .setNegativeButton("Batal", null)
        .show()
}

@Suppress("UNUSED_PARAMETER")
internal fun MainActivity.showTemplates(anchor: View) {
    // Menu (+): lampiran dan Penalaran mendalam. Semua tindakan proyek dijalankan lewat instruksi chat.
    val ref = arrayOfNulls<Dialog>(1)
    fun row(title: String, hint: String, trailing: View?, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(null)
        setPadding(dp(4), dp(12), dp(4), dp(12))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(Palette.INK)
            })
            if (hint.isNotEmpty()) addView(TextView(context).apply {
                text = hint
                textSize = 13f
                setTextColor(Palette.INK_SOFT)
            })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (trailing != null) addView(trailing, LinearLayout.LayoutParams(WRAP, WRAP))
        setOnClickListener { onClick() }
    }
    val deepSwitch = android.widget.Switch(this).apply {
        isChecked = deepOn()
        isClickable = false
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(row("Upload File", "Lampirkan file untuk dikerjakan agen", null) { ref[0]?.dismiss(); pickFile() })
        addView(row("Upload Gambar", "Lampirkan gambar ke pesan", null) { ref[0]?.dismiss(); pickImage() })
        addView(row("Penalaran mendalam", "Penalaran mendalam untuk masalah kompleks", deepSwitch) {
            val on = !deepOn()
            setDeep(on)
            deepSwitch.isChecked = on
            toast(if (on) "Penalaran mendalam aktif" else "Penalaran mendalam dimatikan")
        })
    }
    ref[0] = showSheet("Tambahkan", null, content)
}

@Volatile private var modelSetupBusy = false

/** Pilihan model terbaik dari daftar yang lolos uji: sonnet > flash > gpt-4o > mini > haiku; versi lite dihindari, versi tertinggi dipilih. Di penyedia open source, model open source terbaik yang dipilih. */
internal fun bestModel(good: List<String>, provider: String = ""): String? {
    // Penyedia open source (OpenRouter, Groq, ...): model open source didahulukan (OpenSource.kt).
    if (provider in OPEN_SOURCE_HOSTS) bestOpenSource(good)?.let { return it }
    val pref = listOf("sonnet", "flash", "gpt-4o", "mini", "haiku")
    for (w in pref) {
        val hit = good.filter { it.contains(w, true) }
        if (hit.isEmpty()) continue
        val full = hit.filter { !it.contains("lite", true) }
        return (if (full.isNotEmpty()) full else hit).maxOrNull()
    }
    return good.firstOrNull()
}

/**
 * Setelah Simpan (atau saat daftar belum pernah diuji): ambil daftar model, uji otomatis di latar,
 * simpan hasilnya, lalu pilih model terbaik yang bisa dipakai. Pengguna cukup mengisi API key.
 * Kalau hasil uji untuk key ini sudah ada dan model sudah terisi, tidak diulang.
 */
internal fun MainActivity.autoSetupModels(force: Boolean = false, silent: Boolean = false) {
    if (preset(prov).fmt == "cli") return
    // Model terkunci: tidak ada daftar yang diambil atau diuji; cukup pastikan label benar.
    lockedModelFor(prov)?.let { locked ->
        prefs.edit().putString("p_${prov}_model", locked).apply()
        updateModelLabel()
        return
    }
    if (needsKey() && cfgKey().isEmpty()) return
    if (modelSetupBusy) return
    if (!force && cfgModel().isNotBlank() && loadProbed() != null) return
    if (silent) {
        // Penyegaran diam-diam di latar: jangan ganggu tugas berjalan, dan jangan ulang tiap saat bila gagal.
        if (busy) return
        val tryKey = "p_${prov}_probe_try"
        val now = System.currentTimeMillis()
        if (!retryDue(prefs.getLong(tryKey, 0L), now)) return
        prefs.edit().putLong(tryKey, now).apply()
    }
    val p = prov
    modelSetupBusy = true
    if (!silent) runOnUiThread {
        modelLabel.text = "Menyiapkan model…"
        toast("Menyiapkan model otomatis, tunggu sekitar 1 menit...")
    }
    bg {
        var msg = ""
        try {
            val cand = probeCandidates(p, fetchModels().filter { n -> skipWords.none { w -> n.contains(w, true) } }, 30)
            if (cand.isEmpty()) {
                msg = if (p == "Ollama lokal") "Belum ada model di Ollama. Unduh lewat Siapkan Termux."
                else "Tidak ada model yang cocok. Cek API key atau langganan."
                if (p == "Ollama lokal") runOnUiThread { showSetup() }
            } else {
                // Model lokal: jangan diuji paralel (memuat beberapa model sekaligus menghabiskan RAM HP); semua yang terpasang dianggap bisa.
                val (good, busy) = if (p == "Ollama lokal") Pair(cand, emptyList<String>()) else probeBlocking(cand)
                if (good.isEmpty() && busy.isEmpty()) {
                    msg = "Tidak ada model yang lolos uji. Cek API key atau langganan."
                } else if (p == prov) {
                    saveProbed(good, busy)
                    val cur = cfgModel()
                    val pick = if (cur in good) cur else bestModel(good, p) ?: bestModel(busy, p)
                    if (pick != null && pick != cur) prefs.edit().putString("p_${p}_model", pick).apply()
                    msg = if (good.isNotEmpty()) "Siap ✓ ${good.size} model bisa dipakai. Model: ${pick ?: cur}"
                    else "Semua model sedang sibuk. Dipilih sementara: ${pick ?: cur}"
                }
            }
        } catch (e: Exception) {
            msg = "Gagal menyiapkan model: " + (e.message ?: "").take(80)
            if (p == "Ollama lokal") runOnUiThread { showSetup() } // server belum jalan: tuntun lewat Siapkan Termux
        } finally {
            modelSetupBusy = false
        }
        runOnUiThread {
            updateModelLabel()
            if (msg.isNotEmpty() && !silent) toast(msg)
        }
    }
}
