package com.build.mythos

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Pembangun tampilan utama dan inisialisasi awal, dipecah dari MainActivity.onCreate (isi tidak berubah).
// Urutan pemanggilan di onCreate dipertahankan: bilah atas, area tengah, baris status, kolom input.

/** Baca pengaturan, jalankan migrasi sekali jalan, dan pulihkan pilihan penyedia / ringkasan konteks. */
internal fun MainActivity.initPrefsAndMigrations() {
    prefs = getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
    applyMotion(this, prefs)
    if (!prefs.getBoolean("mythos_workspace_migrated", false)) {
        val old = prefs.getString("workdir", "")?.trim().orEmpty()
        if (old.isEmpty() || old == "kita") prefs.edit().putString("workdir", "MYTHOS").apply()
        prefs.edit().putBoolean("mythos_workspace_migrated", true).apply()
    }
    startLegacyWorkspaceMigration()
    // Versi lama pernah mengaktifkan mode otonom secara default; minta opt-in eksplisit setelah upgrade.
    if (prefs.getBoolean("autonomous", false) && !prefs.getBoolean("autonomous_ack", false)) {
        prefs.edit().putBoolean("autonomous", false).apply()
    }
    prov = initialProvider(prefs.getString("prov", null), !prefs.getString("key", "").isNullOrEmpty())
    migrateKeys()
    summaryText = prefs.getString("sum_text", "") ?: ""
    sumUpto = prefs.getInt("sum_upto", 0)
}

/** Bilah atas: tombol menu (drawer), judul "Chat samaran", tombol hantu. Mengisi ghostIcon, ghostCircleBg, topTitle. */
internal fun MainActivity.buildTopBar(): LinearLayout {
    // Bilah atas: tombol menu bulat di kiri (membuka drawer), judul "Chat samaran" di tengah,
    // tombol hantu bulat di kanan (menghidupkan Chat samaran).
    val menuBtn = circleButton(getString(R.string.cd_history), IconView(this, IconView.MENU3, Palette.ICON)) { toggleDrawer() }
    ghostIcon = IconView(this, IconView.GHOST, Palette.INK)
    ghostCircleBg = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Palette.NAV)
        setStroke(dp(1), Palette.BORDER)
    }
    val ghostBtn = FrameLayout(this).apply {
        contentDescription = getString(R.string.cd_menu)
        background = pressable(ghostCircleBg)
        addView(ghostIcon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        setOnClickListener { setIncognito(!incognito) }
    }
    topTitle = TextView(this).apply {
        text = "Chat samaran"
        textSize = 16f
        setTextColor(Palette.INK)
        gravity = Gravity.CENTER
        maxLines = 1
        visibility = View.INVISIBLE
    }
    val topBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
        elevation = 0f
        setPadding(dp(10), dp(8), dp(10), dp(8))
        addView(menuBtn, LinearLayout.LayoutParams(dp(46), dp(46)))
        addView(topTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(ghostBtn, LinearLayout.LayoutParams(dp(46), dp(46)))
    }
    return topBar
}

/** Area tengah: daftar chat, sapaan kosong, dan tombol gulir ke bawah. Mengisi chatBox, scroll, emptyState, dst. */
internal fun MainActivity.buildCenter(): FrameLayout {
    // Area tengah: daftar chat + sapaan kosong
    chatBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(12), dp(10), dp(24))
    }
    scroll = ScrollView(this).apply { addView(chatBox) }
    // Ruang kosong di bawah isi chat (hampir setinggi layar) agar pesan pengguna bisa dinaikkan ke atas dan layar tetap diam
    // selama jawaban bertambah, seperti rekaman acuan. Ruangnya mengikuti tinggi layar chat.
    scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
        val want = (bottom - top - dp(96)).coerceAtLeast(dp(24))
        if (chatBox.paddingBottom != want) {
            chatBox.setPadding(chatBox.paddingLeft, chatBox.paddingTop, chatBox.paddingRight, want)
        }
    }
    emptyNormal = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(BurstView(context), LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(TextView(context).apply {
            val nama = (prefs.getString("nama_tampil", NAMA_TAMPIL_BAWAAN) ?: NAMA_TAMPIL_BAWAAN).trim().ifBlank { NAMA_TAMPIL_BAWAAN }
            text = sapaanKosong(nama)
            textSize = 32f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.NORMAL)
            letterSpacing = -0.03f
            setTextColor(Palette.INK)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(20) })
    }
    emptyIncognito = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
        addView(GlyphView(context, GlyphView.GHOST_SOLID, Palette.INK), LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(TextView(context).apply {
            val lead = "Chat samaran tidak masuk ke riwayat dan tidak disimpan di perangkat ini. "
            val link = "Pelajari selengkapnya"
            val sp = android.text.SpannableString(lead + link)
            sp.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) { showIncognitoInfo() }
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = Palette.INK
                    ds.isUnderlineText = true
                }
            }, lead.length, lead.length + link.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = sp
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
            textSize = 15.5f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setTextColor(Palette.INK)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) })
    }
    val emptyContent = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
        addView(emptyNormal, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(emptyIncognito, LinearLayout.LayoutParams(MATCH, WRAP))
    }
    emptyState = ScrollView(this).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(emptyContent, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
    }
    // Panah digambar IconView, bukan glyph "↓": sebagian font perangkat tidak punya glyph itu (tampil sebagai tanda petik).
    scrollDownBtn = FrameLayout(this).apply {
        contentDescription = getString(R.string.cd_scroll_down)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Palette.CARD)
            setStroke(dp(1), Palette.DIVIDER)
        }
        elevation = dp(3).toFloat()
        visibility = View.GONE
        addView(IconView(context, IconView.CHEVRON_DOWN, Palette.TEXT_MAIN), FrameLayout.LayoutParams(MATCH, MATCH))
        setOnClickListener { forceScrollDown() }
    }
    scroll.setOnScrollChangeListener { _, _, y, _, _ ->
        val content = scroll.getChildAt(0)
        val atBottom = content == null || contentEnd() - (y + scroll.height) <= dp(36)
        // Gerakan pengikut otomatis tertinggal sedikit di belakang isi; itu bukan pengguna yang menggulir ke atas.
        if (!autoScrolling) {
            userScrolledUp = !atBottom
            scrollDownBtn.visibility = if (atBottom) View.GONE else View.VISIBLE
        }
    }
    val center = FrameLayout(this).apply {
        addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(emptyState, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(scrollDownBtn, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(18), dp(18))
        })
    }
    return center
}

/** Kolom input: teks, tombol tambah, pil model, mikrofon, dan tombol kirim. Mengisi input, modelLabel, deepBtn, actionBtn, dst. */
internal fun MainActivity.buildInputPill(): LinearLayout {
    // Kolom input gaya Claude: teks di atas; baris bawah: + | pil model | ... | mikrofon | kirim/berhenti
    val plusBtn = FrameLayout(this).apply {
        contentDescription = getString(R.string.cd_attach)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.CHIP) }
        addView(IconView(context, IconView.PLUS, Palette.ICON), FrameLayout.LayoutParams(MATCH, MATCH))
        setOnClickListener { showAddSheet() }
    }
    input = EditText(this).apply {
        hint = "Mythos..."
        setHintTextColor(Palette.HINT)
        setTextColor(Palette.INK)
        textSize = 17f
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        background = null
        maxLines = 6
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setPadding(dp(6), dp(10), dp(6), dp(10))
    }
    modelLabel = TextView(this).apply {
        textSize = 14f
        typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        setTextColor(Palette.INK)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    val modelChip = FrameLayout(this).apply {
        contentDescription = getString(R.string.cd_model)
        background = pressable(rounded(Palette.CHIP, 18))
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { pickModel() }
        addView(modelLabel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    }
    updateModelLabel()
    deepBtn = FrameLayout(this).apply {
        contentDescription = getString(R.string.cd_deep_off)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tint(Palette.BRAND, 0.84f)) }
        visibility = if (deepOn()) View.VISIBLE else View.GONE
        addView(IconView(context, IconView.BRAIN, Palette.BRAND), FrameLayout.LayoutParams(MATCH, MATCH))
        setOnClickListener { setDeep(false); toast("Penalaran mendalam dimatikan") }
    }
    val micBtn = FrameLayout(this).apply {
        contentDescription = getString(R.string.cd_mic)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.CHIP) }
        addView(IconView(context, IconView.MIC, Palette.ICON), FrameLayout.LayoutParams(MATCH, MATCH))
        setOnClickListener { voice() }
    }
    actionIcon = IconView(this, IconView.SEND, Color.WHITE)
    actionBg = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Palette.BRAND)
    }
    actionBtn = FrameLayout(this).apply {
        contentDescription = getString(R.string.cd_send)
        background = actionBg
        visibility = View.GONE
        addView(actionIcon, FrameLayout.LayoutParams(MATCH, MATCH))
        setOnClickListener { onAction() }
    }
    input.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun afterTextChanged(s: Editable?) {
            updateAction()
        }
    })
    val bottomRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(plusBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(deepBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) })
        // Pembungkus mengisi sisa ruang (tidak pernah mendorong mikrofon keluar layar); pil di dalamnya
        // selebar teks model dan teks panjang dipotong dengan "…".
        addView(FrameLayout(context).apply {
            addView(modelChip, FrameLayout.LayoutParams(WRAP, dp(36), Gravity.START or Gravity.CENTER_VERTICAL))
        }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
        addView(micBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(actionBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) })
    }
    val pill = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.NAV, 26, Palette.FIELD_BORDER, 1)
        elevation = dp(4).toFloat()
        if (Build.VERSION.SDK_INT >= 28) {
            outlineAmbientShadowColor = 0x22000000
            outlineSpotShadowColor = 0x22000000
        }
        setPadding(dp(7), dp(8), dp(7), dp(7))
        addView(input, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(bottomRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
    }
    return pill
}
