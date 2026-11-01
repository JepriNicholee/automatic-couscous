package com.build.mythos

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.net.URL

// Dialog Pengaturan (penyedia, kunci API, model, folder kerja, mode baca-saja, mode otonom).
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.showSettings() {
    val names = presets.map { it.name }
    val spin = Spinner(this)
    spin.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                textSize = 15f
                setTextColor(Palette.INK)
                setPadding(0, 0, 0, 0)
            }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getDropDownView(position, convertView, parent) as TextView).apply {
                textSize = 15f
                setTextColor(Palette.INK)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
    }
    spin.background = null
    spin.setPopupBackgroundDrawable(rounded(Palette.CARD, 14, Palette.DIVIDER))
    val spinHolder = FrameLayout(this).apply {
        background = rounded(Palette.FIELD, 14)
        setPadding(dp(14), 0, dp(12), 0)
        addView(spin, FrameLayout.LayoutParams(MATCH, dp(48)))
        addView(
            IconView(context, IconView.CHEVRON_DOWN, Palette.INK_SOFT),
            FrameLayout.LayoutParams(dp(18), dp(18), Gravity.END or Gravity.CENTER_VERTICAL)
        )
    }
    val base = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }
    val key = EditText(this).apply {
        hint = "Tempel API key (kosong untuk server lokal)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    val mdl = EditText(this).apply {
        hint = "Nama model"
        inputType = InputType.TYPE_CLASS_TEXT
    }
    val geminiModelLabel = fieldLabel("Model Gemini CLI").apply { visibility = View.GONE }
    val geminiPickBtn = TextView(this).apply {
        text = "Pilih dari daftar ▾"
        textSize = 14f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(Palette.BRAND)
        setPadding(dp(4), dp(10), 0, dp(2))
        visibility = View.GONE
    }
    val geminiModelNote = TextView(this).apply {
        text = "Ketuk \"Pilih dari daftar\", atau ketik sendiri. Alias (auto, pro, flash, flash-lite) otomatis memakai model terbaru di tingkatnya; ID bernomor tetap pada versi itu. Ketersediaan mengikuti akun/API."
        textSize = 12.5f
        setTextColor(Palette.INK_SOFT)
        setPadding(dp(4), dp(4), dp(4), dp(2))
        visibility = View.GONE
    }
    val wdir = EditText(this).apply {
        hint = "Folder kerja di Termux, contoh: MYTHOS"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setText(prefs.getString("workdir", "MYTHOS"))
    }
    val ghToken = EditText(this).apply {
        hint = "Token GitHub (disimpan terenkripsi)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val saved = when (val r = Secret.decrypt(prefs.getString("github_token", "") ?: "")) {
            is SecretDecryptResult.Success -> r.value
            else -> ""
        }
        setText(saved)
    }
    val ghRepo = EditText(this).apply {
        hint = "Link repository GitHub, contoh: https://github.com/username/nama-repo"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        // Pengaturan lama (pemilik + nama repo terpisah) otomatis ditampilkan sebagai link.
        val legacy = parseRepoLink(prefs.getString("github_owner", "").orEmpty() + "/" + prefs.getString("github_repo", "").orEmpty())?.url.orEmpty()
        setText(prefs.getString("github_repo_url", "").orEmpty().ifBlank { legacy })
    }
    val extra = EditText(this).apply {
        hint = "Instruksi tambahan untuk AI (opsional)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = 3
        gravity = Gravity.TOP
        setText(prefs.getString("extra", ""))
    }
    for (f in listOf(base, key, mdl, wdir, ghRepo, ghToken, extra)) styleField(f)

    /** Satu baris pengaturan: judul + keterangan singkat + saklar. */
    fun toggleRow(title: String, hint: String, on: Boolean): Pair<View, Switch> {
        val sw = Switch(this).apply { isChecked = on }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(Palette.INK) })
            addView(TextView(context).apply { text = hint; textSize = 12.5f; setTextColor(Palette.INK_SOFT) })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), 0, dp(8))
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
            setOnClickListener { sw.toggle() }
        }
        return row to sw
    }
    val (autoRow, auto) = toggleRow("Setujui otomatis perintah baca", "ls, cat, grep jalan tanpa bertanya", prefs.getBoolean("autoread", true))
    val (roRow, ro) = toggleRow("Mode hanya baca", "AI tidak boleh menulis atau menghapus", prefs.getBoolean("readonly", false))
    val (autonomousRow, autonomous) = toggleRow("Mode otonom", "AI bertindak tanpa persetujuan (berisiko)", prefs.getBoolean("autonomous", false))
    val (sandboxRow, sandbox) = toggleRow("Mode sandbox", "Perintah jalan di proot-distro, bukan Termux utama", prefs.getBoolean("sandbox", false))
    val (motionRow, motion) = toggleRow("Kurangi gerakan", "Logo dan teks berkilau diam; lebih hemat baterai", prefs.getBoolean(Motion.PREF, false))

    /** Baris tautan: teks + panah, seluruh baris bisa diketuk. */
    fun linkRow(label: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(null)
        setPadding(dp(4), dp(14), dp(4), dp(14))
        addView(TextView(context).apply { text = label; textSize = 15f; setTextColor(Palette.INK) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(TextView(context).apply { text = "›"; textSize = 20f; setTextColor(Palette.INK_SOFT) })
        setOnClickListener { onClick() }
    }
    fun divider(): View = View(this).apply { setBackgroundColor(Palette.DIVIDER) }
    val keyLabel = fieldLabel("API key")
    var cur = prov
    var needBase = false
    var isCliCur = false
    var isGeminiCliCur = false
    var loading = false // true saat kolom diisi dari penyimpanan (bukan ketikan pengguna)
    var pendingKey: String? = null // key hasil tempel yang harus tetap tampil setelah ganti penyedia
    val detectNote = TextView(this).apply {
        textSize = 12.5f
        setTextColor(Palette.INK_SOFT)
        setPadding(dp(4), dp(6), 0, 0)
        visibility = View.GONE
    }
    var advOpen = false
    val reportBtn = linkRow("Salin laporan masalah") { copyReport() }
    val setupBtn = linkRow("Siapkan Termux") { showSetup() }
    val syncTermuxBtn = linkRow("Sinkronisasi dua arah Termux") { showTermuxSettingsSync() }
    val connBtn = linkRow("Konektor: Email, Telegram, WhatsApp") { showConnectorHub() }
    val logBtn = linkRow("Catatan perintah") { startActivity(Intent(this, CommandLogActivity::class.java)) }
    var curLogin: CliLogin? = null
    val loginStatus = TextView(this).apply {
        textSize = 13.5f
        setTextColor(Palette.INK_SOFT)
        setPadding(dp(4), dp(10), 0, 0)
        visibility = View.GONE
    }
    val loginBtn = TextView(this).apply {
        textSize = 15f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = pressable(rounded(Palette.BRAND, 24))
        setPadding(dp(20), dp(12), dp(20), dp(12))
        visibility = View.GONE
        setOnClickListener {
            curLogin?.let { c ->
                cliSetup(c) { msg ->
                    runOnUiThread {
                        if (curLogin === c) {
                            loginStatus.text = msg
                            loginStatus.setTextColor(Palette.INK_SOFT)
                        }
                    }
                }
            }
        }
    }
    fun checkLogin() {
        val c = curLogin ?: return
        loginStatus.text = "Memeriksa login…"
        loginStatus.setTextColor(Palette.INK_SOFT)
        bg {
            val st = cliStatus(c, useCache = false)
            runOnUiThread {
                if (curLogin !== c) return@runOnUiThread
                loginStatus.text = when {
                    st == CliStatus.OK -> "✓ Sudah masuk dengan ${c.account}"
                    st == CliStatus.UNKNOWN -> "Termux belum menjawab. Buka Termux sebentar, lalu ketuk di sini untuk memeriksa lagi."
                    st == CliStatus.NOT_READY && c.name == "Codex (ChatGPT)" -> "CLI Codex Termux yang valid belum siap. Ketuk tombol untuk memasang atau memperbaikinya."
                    st == CliStatus.NOT_READY -> "CLI belum terpasang. Ketuk tombol di bawah untuk memulai."
                    else -> "Belum masuk. Ketuk tombol di bawah untuk memulai."
                }
                loginStatus.setTextColor(Palette.INK_SOFT)
            }
        }
    }
    loginStatus.setOnClickListener { checkLogin() }
    // Opsi yang jarang dipakai disembunyikan di bawah satu tombol supaya layar Lanjutan tidak membingungkan.
    var moreOpen = false
    val more = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(sandboxRow)
        addView(motionRow)
        addView(linkRow("Hapus memori obrolan (${memoryCount()} catatan)") {
            AlertDialog.Builder(this@showSettings)
                .setTitle("Hapus memori obrolan?")
                .setMessage("Catatan yang dipakai model lokal sebagai bahan akan dihapus. Riwayat chat tidak ikut terhapus.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus") { _, _ -> clearMemory(); toast("Memori obrolan dihapus") }
                .show()
        })
    }
    val moreBtn = TextView(this).apply {
        text = "Opsi lain ▾"
        textSize = 14f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(Palette.BRAND)
        setPadding(dp(4), dp(12), 0, dp(8))
        setOnClickListener {
            moreOpen = !moreOpen
            more.visibility = if (moreOpen) View.VISIBLE else View.GONE
            text = if (moreOpen) "Opsi lain ▴" else "Opsi lain ▾"
        }
    }
    val adv = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(fieldLabel("Folder kerja"))
        addView(wdir)
        addView(fieldLabel("GitHub (opsional, untuk build APK)"))
        addView(ghRepo)
        addView(ghToken)
        // "Setujui otomatis perintah baca" (autoRow) tidak ditampilkan: sudah tercakup "Konfirmasi seperlunya".
        // Nilainya tetap dibaca dan disimpan (bawaan aktif), jadi perilaku tidak berubah.
        addView(roRow)
        addView(autonomousRow)
        addView(moreBtn)
        addView(more)
        addView(fieldLabel("Instruksi tambahan"))
        addView(extra)
    }
    val advBtn = TextView(this).apply {
        text = "Lanjutan ▾"
        textSize = 14f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(Palette.BRAND)
        setPadding(dp(4), dp(14), 0, dp(8))
    }
    fun refresh() {
        adv.visibility = if (advOpen) View.VISIBLE else View.GONE
        base.visibility = if (needBase || advOpen) View.VISIBLE else View.GONE
        // Kolom model CLI Gemini tampil langsung agar model dapat diubah; model provider API lain tetap di Lanjutan.
        key.visibility = if (isCliCur) View.GONE else View.VISIBLE
        keyLabel.visibility = key.visibility
        mdl.visibility = if (isGeminiCliCur || (!isCliCur && (advOpen || needBase))) View.VISIBLE else View.GONE
        geminiModelLabel.visibility = if (isGeminiCliCur) View.VISIBLE else View.GONE
        geminiPickBtn.visibility = if (isGeminiCliCur) View.VISIBLE else View.GONE
        geminiModelNote.visibility = if (isGeminiCliCur) View.VISIBLE else View.GONE
        advBtn.text = if (advOpen) "Lanjutan ▴" else "Lanjutan ▾"
    }
    advBtn.setOnClickListener { advOpen = !advOpen; refresh() }
    geminiPickBtn.setOnClickListener {
        // Daftar pilihan model Gemini CLI: pengguna tidak perlu menghafal nama; "Ketik sendiri" tetap tersedia.
        val current = normalizeGeminiCliModel(mdl.text.toString())
        val labels = (GEMINI_CLI_MODEL_CHOICES.map { it.menuLabel() } + "Ketik sendiri…\nModel lain yang tidak ada di daftar.").toTypedArray()
        AlertDialog.Builder(this@showSettings)
            .setTitle("Pilih model Gemini CLI")
            .setSingleChoiceItems(labels, geminiModelChoiceIndex(current)) { dlg, which ->
                dlg.dismiss()
                if (which < GEMINI_CLI_MODEL_CHOICES.size) {
                    mdl.setText(GEMINI_CLI_MODEL_CHOICES[which].value)
                    mdl.error = null
                } else {
                    mdl.requestFocus()
                    mdl.setSelection(mdl.text.length)
                    mdl.post {
                        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.showSoftInput(mdl, 0)
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }
    fun load(n: String) {
        val p = preset(n)
        val isCli = p.fmt == "cli"
        base.hint = if (isCli) "Perintah CLI, pakai {prompt}" else "Alamat API (base URL)"
        base.setText(pvOf(n, "base").ifEmpty { p.base })
        val oldKey = if (n == "Gemini") {
            when (val result = Secret.decrypt(prefs.getString("key", "") ?: "")) {
                is SecretDecryptResult.Success -> result.value
                SecretDecryptResult.Missing -> {
                    prefs.edit().remove("key").apply()
                    keyWarning = "API key lama tidak dapat dibuka karena kunci Keystore hilang. Masukkan ulang API key."
                    ""
                }
                SecretDecryptResult.Corrupt -> {
                    prefs.edit().remove("key").apply()
                    keyWarning = "API key tersimpan rusak dan sudah dihapus. Masukkan ulang API key."
                    ""
                }
                SecretDecryptResult.KeystoreUnavailable -> {
                    keyWarning = "Android Keystore tidak tersedia. API key tidak dibaca; masukkan ulang nanti."
                    ""
                }
            }
        } else ""
        val typed = pendingKey
        pendingKey = null
        loading = true
        key.setText(typed ?: pvOf(n, "key").ifEmpty { oldKey })
        loading = false
        if (typed == null) {
            val warning = keyWarning
            if (warning != null) {
                detectNote.text = warning
                detectNote.visibility = View.VISIBLE
            } else {
                detectNote.visibility = View.GONE
            }
        }
        isGeminiCliCur = n == "Gemini CLI (Google)"
        mdl.hint = if (isGeminiCliCur) "Model terpilih (atau ketik sendiri)" else "Nama model"
        mdl.setText(pvOf(n, "model").ifEmpty {
            if (isGeminiCliCur) DEFAULT_GEMINI_CLI_MODEL else p.model
        })
        mdl.isEnabled = lockedModelFor(n) == null // OpenRouter: model dikunci (ModelLock.kt)
        isCliCur = isCli
        needBase = isCli || n.startsWith("Kustom") || n.startsWith("Ollama")
        refresh()
        curLogin = cliLoginOf(n)
        val hasLogin = curLogin != null
        loginStatus.visibility = if (hasLogin) View.VISIBLE else View.GONE
        loginBtn.visibility = if (hasLogin) View.VISIBLE else View.GONE
        curLogin?.let { loginBtn.text = "Masuk dengan ${it.account}" }
        checkLogin()
    }
    spin.setSelection(names.indexOf(prov).coerceAtLeast(0))
    spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
            cur = names[pos]
            load(cur)
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
    // Deteksi otomatis: tempel key, penyedia ikut berganti sesuai bentuk key (lihat KeyDetect.kt).
    key.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (loading) return
            val typed = s?.toString().orEmpty()
            val hit = detectProvider(typed)
            if (hit == null || hit !in names) { detectNote.visibility = View.GONE; return }
            val custom = cur.startsWith("Kustom")
            detectNote.text = if (custom) "Terdeteksi: $hit (tidak diganti karena memakai Kustom)" else "Terdeteksi: $hit"
            detectNote.visibility = View.VISIBLE
            if (hit != cur && !custom) {
                pendingKey = typed
                spin.setSelection(names.indexOf(hit))
            }
        }
    })
    var dialog: Dialog? = null
    var checkingWorkdir = false
    fun save(workdirConfirmed: Boolean = false) {
        val selectedWorkdir = WorkspacePaths.normalizeSetting(wdir.text.toString())
        val workdirError = WorkspacePaths.validationError(wdir.text.toString(), termuxHome)
        if (selectedWorkdir == null || workdirError != null) {
            wdir.error = workdirError ?: "Folder kerja tidak valid"
            wdir.requestFocus()
            return
        }
        if (!workdirConfirmed) {
            if (checkingWorkdir) return
            checkingWorkdir = true
            wdir.isEnabled = false
            val candidateRoot = WorkspacePaths.resolve(selectedWorkdir, termuxHome)
            bg {
                val probe = try {
                    termux(
                        WorkspacePaths.gitRootProbeCommand(candidateRoot) { sh(it) },
                        limit = 500, timeoutSec = 15, useWorkdir = false, killable = false
                    )
                } catch (e: Exception) { "workdir check failed: ${e.message}" }
                runOnUiThread {
                    checkingWorkdir = false
                    wdir.isEnabled = true
                    if (dialog?.isShowing != true) return@runOnUiThread
                    when {
                        probe.startsWith("exit=0") && probe.contains("MYTHOS_WORKDIR_IN_GIT_REPO") -> {
                            AlertDialog.Builder(this)
                                .setTitle("Folder kerja berada di repository Git")
                                .setMessage(
                                    "Folder yang dipilih berada di dalam repository Git. Agen dapat membuat atau mengubah file di lokasi itu sesuai izin aplikasi. " +
                                        "Memilihnya dapat menyebabkan source aplikasi ikut berubah. Sebaiknya gunakan folder kerja terpisah seperti ~/MYTHOS.\n\nTetap gunakan lokasi ini?"
                                )
                                .setNegativeButton("Batal", null)
                                .setPositiveButton("Tetap gunakan") { _, _ -> save(workdirConfirmed = true) }
                                .show()
                        }
                        probe.startsWith("exit=0") && probe.contains("MYTHOS_WORKDIR_NOT_IN_GIT_REPO") ->
                            save(workdirConfirmed = true)
                        else -> {
                            AlertDialog.Builder(this)
                                .setTitle("Lokasi belum dapat diverifikasi")
                                .setMessage(
                                    "Termux tidak dapat memastikan apakah folder ini berada di dalam repository Git. Periksa kembali path dan izin Termux. Jika kamu yakin ingin tetap menggunakannya, konfirmasi di bawah."
                                )
                                .setNegativeButton("Batal", null)
                                .setPositiveButton("Tetap gunakan") { _, _ -> save(workdirConfirmed = true) }
                                .show()
                        }
                    }
                }
            }
            return
        }
        val selectedGeminiModel = normalizeGeminiCliModel(mdl.text.toString())
        if (cur == "Gemini CLI (Google)" && !isValidGeminiCliModel(selectedGeminiModel)) {
            mdl.error = "Gunakan nama/ID model tanpa spasi atau karakter shell"
            mdl.requestFocus()
            return
        }
        dialog?.dismiss()
        prov = cur
        val wantsAutonomous = autonomous.isChecked
        val normalizedKey = normalizeKey(key.text.toString())
        val encryptedKey = Secret.encrypt(normalizedKey)
        val repoText = ghRepo.text.toString().trim()
        val repoRef = parseRepoLink(repoText)
        if (repoText.isNotEmpty() && repoRef == null) toast("Link repository GitHub tidak valid, tidak disimpan")
        val ed = prefs.edit()
            .putString("prov", cur)
            .putString("p_${cur}_base", base.text.toString().trim())
            .putString("p_${cur}_model", if (cur == "Gemini CLI (Google)") selectedGeminiModel else mdl.text.toString().trim())
            .putString("extra", extra.text.toString().trim())
            .putString("workdir", selectedWorkdir)
            .putString("github_repo_url", repoRef?.url.orEmpty())
            .remove("github_owner")
            .remove("github_repo")
            .remove("github_private")
            .putBoolean("autoread", auto.isChecked)
            .putBoolean("readonly", ro.isChecked)
            .putBoolean("sandbox", sandbox.isChecked)
        if (encryptedKey.isSuccess) {
            ed.putString("p_${cur}_key", encryptedKey.getOrThrow())
        } else {
            toast("API key tidak dapat diamankan oleh Android Keystore. Key tidak disimpan; masukkan ulang nanti.")
        }
        val encryptedGithubToken = Secret.encrypt(ghToken.text.toString().trim())
        if (ghToken.text.toString().trim().isBlank()) {
            ed.remove("github_token")
        } else if (encryptedGithubToken.isSuccess) {
            ed.putString("github_token", encryptedGithubToken.getOrThrow())
        } else {
            toast("Token GitHub tidak dapat diamankan. Token tidak disimpan.")
        }
        ed.putBoolean(Motion.PREF, motion.isChecked)
        ed.apply()
        bg { try { ensureWorkspaceLayout() } catch (ignored: Exception) { logQuiet("SettingsDialog.kt", ignored) } }
        if (prefs.getBoolean("termux_sync_enabled", false)) {
            bg {
                val syncResult = try { pushTermuxSettings() } catch (e: Exception) { "Gagal: ${e.message}" }
                if (!syncResult.startsWith("Berhasil:")) {
                    runOnUiThread { toast("Sinkronisasi Termux gagal. Buka Pengaturan > Sinkronisasi dua arah Termux untuk memeriksa.") }
                }
            }
        }
        applyMotion(this, prefs)
        if (!wantsAutonomous || prefs.getBoolean("autonomous_ack", false)) {
            prefs.edit().putBoolean("autonomous", wantsAutonomous).apply()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Aktifkan mode otonom?")
                .setMessage(
                    "Dalam mode otonom, AI dapat menjalankan perintah shell, menulis file, " +
                        "dan memulai pemasangan APK tanpa dialog persetujuan. Aktifkan hanya " +
                        "jika Anda memahami dan menerima risikonya."
                )
                .setNegativeButton("Batal") { _, _ ->
                    prefs.edit().putBoolean("autonomous", false).apply()
                }
                .setPositiveButton("Saya paham, aktifkan") { _, _ ->
                    prefs.edit()
                        .putBoolean("autonomous", true)
                        .putBoolean("autonomous_ack", true)
                        .apply()
                }
                .show()
        }
        updateModelLabel()
        autoSetupModels()
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(fieldLabel("Penyedia AI"))
        addView(spinHolder, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(keyLabel)
        addView(key, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(detectNote)
        addView(base, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        addView(geminiModelLabel)
        addView(geminiPickBtn)
        addView(mdl, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        addView(geminiModelNote)
        addView(loginStatus)
        addView(loginBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        addView(divider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(18) })
        addView(connBtn)
        addView(syncTermuxBtn)
        addView(logBtn)
        addView(reportBtn)
        addView(setupBtn)
        addView(divider(), LinearLayout.LayoutParams(MATCH, dp(1)))
        addView(advBtn)
        addView(adv)
    }
    val footer = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(softButton("Batal") { dialog?.dismiss() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(filledButton("Simpan") { save() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
    }
    onLoginRefresh = { checkLogin() }
    dialog = showSheet("Pengaturan", null, box, footer).also {
        it.setOnDismissListener { onLoginRefresh = null }
    }
}
