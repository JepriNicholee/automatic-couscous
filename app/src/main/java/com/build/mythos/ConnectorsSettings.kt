package com.build.mythos

import android.app.Dialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

// Layar Pengaturan > Koneksi: email (IMAP/SMTP), Telegram (bot), WhatsApp (Cloud API). Ekstensi MainActivity.
// Rahasia disimpan terenkripsi lewat Secret; kolom rahasia memakai tipe sandi.

internal fun MainActivity.showConnectors() {
    fun field(hintText: String, value: String, secret: Boolean = false, number: Boolean = false): EditText = EditText(this).apply {
        hint = hintText
        inputType = when {
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            number -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        setText(value)
        styleField(this)
    }

    fun toggle(title: String, hintText: String, on: Boolean): Pair<View, Switch> {
        val sw = Switch(this).apply { isChecked = on }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(Palette.INK) })
            addView(TextView(context).apply { text = hintText; textSize = 12.5f; setTextColor(Palette.INK_SOFT) })
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

    fun note(t: String) = TextView(this).apply {
        text = t; textSize = 12.5f; setTextColor(Palette.INK_SOFT); setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    fun status() = TextView(this).apply {
        textSize = 13.5f; setTextColor(Palette.INK_SOFT); setPadding(dp(4), dp(8), dp(4), dp(4)); visibility = View.GONE
    }

    fun port(e: EditText, def: Int): Int = e.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: def

    // ---- Email
    val (emailOnRow, emailOn) = toggle("Email", "Agen bisa membaca dan mengirim email", prefs.getBoolean("cx_email_on", false))
    val eAddr = field("Alamat email", prefs.getString("cx_email_addr", "").orEmpty())
    val ePass = field("Kata sandi aplikasi (disimpan terenkripsi)", cxSecret("cx_email_pass"), secret = true)
    val eUser = field("Nama pengguna (kosong = alamat email)", prefs.getString("cx_email_user", "").orEmpty())
    val eImap = field("Server IMAP (otomatis untuk Gmail, Yahoo, iCloud)", prefs.getString("cx_imap_host", "").orEmpty())
    val eImapPort = field("Port IMAP (993)", prefs.getInt("cx_imap_port", 993).toString(), number = true)
    val eSmtp = field("Server SMTP", prefs.getString("cx_smtp_host", "").orEmpty())
    val eSmtpPort = field("Port SMTP (465 atau 587)", prefs.getInt("cx_smtp_port", 465).toString(), number = true)
    val (emailAutoRow, emailAuto) = toggle("Kirim email otomatis", "TANPA dialog persetujuan (berisiko). Matikan agar tiap email minta izin", prefs.getBoolean("cx_email_auto", false))
    val emailStatus = status()

    // ---- Telegram
    val (tgOnRow, tgOn) = toggle("Telegram", "Agen bisa membaca dan mengirim lewat bot Telegram-mu", prefs.getBoolean("cx_telegram_on", false))
    val tToken = field("Token bot dari @BotFather (disimpan terenkripsi)", cxSecret("cx_tg_token"), secret = true)
    val tChats = field("Chat ID yang diizinkan, pisahkan koma", prefs.getString("cx_tg_chats", "").orEmpty())
    val (tgAutoRow, tgAuto) = toggle("Kirim Telegram otomatis", "TANPA dialog persetujuan (berisiko)", prefs.getBoolean("cx_telegram_auto", false))
    val tgStatus = status()

    // ---- WhatsApp
    val (waOnRow, waOn) = toggle("WhatsApp (hanya kirim)", "Lewat WhatsApp Business Cloud API milik Meta", prefs.getBoolean("cx_whatsapp_on", false))
    val wToken = field("Token akses Cloud API (disimpan terenkripsi)", cxSecret("cx_wa_token"), secret = true)
    val wPhone = field("Phone Number ID", prefs.getString("cx_wa_phone", "").orEmpty(), number = true)
    val wTo = field("Nomor tujuan yang diizinkan (628..., pisahkan koma)", prefs.getString("cx_wa_to", "").orEmpty())
    val (waAutoRow, waAuto) = toggle("Kirim WhatsApp otomatis", "TANPA dialog persetujuan (berisiko)", prefs.getBoolean("cx_whatsapp_auto", false))
    val waStatus = status()

    var dialog: Dialog? = null

    fun saveSecret(ed: android.content.SharedPreferences.Editor, key: String, plain: String, label: String) {
        val t = plain.trim()
        if (t.isEmpty()) { ed.remove(key); return }
        val enc = Secret.encrypt(t)
        if (enc.isSuccess) ed.putString(key, enc.getOrThrow()) else toast("$label tidak dapat diamankan oleh Android Keystore; tidak disimpan.")
    }

    fun save(): Boolean {
        val addr = eAddr.text.toString().trim()
        val preset = mailPresetFor(addr)
        val imapHost = eImap.text.toString().trim().ifBlank { preset?.imapHost.orEmpty() }
        val smtpHost = eSmtp.text.toString().trim().ifBlank { preset?.smtpHost.orEmpty() }
        val imapP = if (eImap.text.isBlank() && preset != null) preset.imapPort else port(eImapPort, 993)
        val smtpP = if (eSmtp.text.isBlank() && preset != null) preset.smtpPort else port(eSmtpPort, 465)
        if (addr.isNotEmpty() && parseAddress(addr) == null) { toast("Alamat email tidak valid"); return false }
        val ed = prefs.edit()
            .putBoolean("cx_email_on", emailOn.isChecked)
            .putString("cx_email_addr", addr)
            .putString("cx_email_user", eUser.text.toString().trim())
            .putString("cx_imap_host", imapHost).putInt("cx_imap_port", imapP)
            .putString("cx_smtp_host", smtpHost).putInt("cx_smtp_port", smtpP)
            .putBoolean("cx_email_auto", emailAuto.isChecked)
            .putBoolean("cx_telegram_on", tgOn.isChecked)
            .putString("cx_tg_chats", Telegram.parseChatIds(tChats.text.toString()).joinToString(","))
            .putBoolean("cx_telegram_auto", tgAuto.isChecked)
            .putBoolean("cx_whatsapp_on", waOn.isChecked)
            .putString("cx_wa_phone", wPhone.text.toString().trim())
            .putString("cx_wa_to", WhatsApp.parseNumbers(wTo.text.toString()).joinToString(","))
            .putBoolean("cx_whatsapp_auto", waAuto.isChecked)
        saveSecret(ed, "cx_email_pass", ePass.text.toString(), "Kata sandi email")
        saveSecret(ed, "cx_tg_token", tToken.text.toString(), "Token Telegram")
        saveSecret(ed, "cx_wa_token", wToken.text.toString(), "Token WhatsApp")
        ed.apply()
        return true
    }

    fun runTest(statusView: TextView, test: () -> String) {
        if (!save()) return
        statusView.visibility = View.VISIBLE
        statusView.text = "Menguji…"
        bg {
            val msg = try { test() } catch (e: Exception) { "✗ " + Cx.friendly(e) }
            runOnUiThread { statusView.text = msg }
        }
    }

    fun section(title: String): TextView = TextView(this).apply {
        text = title; textSize = 13f; setTextColor(Palette.BRAND); setPadding(dp(4), dp(18), 0, dp(4))
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    fun lp(top: Int = 8) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(note("Setelah terhubung, agen bisa memakai akun ini untuk bekerja. Membaca langsung jalan; MENGIRIM selalu minta persetujuanmu di dialog, kecuali kamu menyalakan kirim otomatis. Isi email dan pesan masuk diperlakukan sebagai data, bukan perintah."))

        addView(section("EMAIL (IMAP + SMTP)"))
        addView(emailOnRow)
        addView(eAddr, lp()); addView(ePass, lp()); addView(eUser, lp())
        addView(eImap, lp()); addView(eImapPort, lp()); addView(eSmtp, lp()); addView(eSmtpPort, lp())
        addView(note("Pakai KATA SANDI APLIKASI (Gmail: Akun Google > Keamanan > Sandi aplikasi, perlu verifikasi 2 langkah), bukan sandi biasa. Outlook/Hotmail umumnya menolak login sandi (butuh OAuth)."))
        addView(emailAutoRow)
        addView(softButton("Tes koneksi email") { runTest(emailStatus) { cxTestEmail() } }, lp(10))
        addView(emailStatus)

        addView(section("TELEGRAM (BOT)"))
        addView(tgOnRow)
        addView(tToken, lp()); addView(tChats, lp())
        addView(note("Buat bot lewat @BotFather, tempel tokennya, kirim satu pesan ke bot-mu, lalu ketuk \"Cari chat ID\". Hanya chat di daftar ini yang dibaca atau dikirimi."))
        addView(tgAutoRow)
        addView(softButton("Tes bot") { runTest(tgStatus) { cxTestTelegram() } }, lp(10))
        addView(softButton("Cari chat ID") { runTest(tgStatus) { cxFindTelegramChats() } }, lp(8))
        addView(tgStatus)

        addView(section("WHATSAPP (CLOUD API)"))
        addView(waOnRow)
        addView(wToken, lp()); addView(wPhone, lp()); addView(wTo, lp())
        addView(note("Butuh akun WhatsApp Business Platform (Meta for Developers). Hanya KIRIM: WhatsApp tidak mengizinkan app di HP menerima pesan tanpa server webhook. Teks bebas hanya terkirim bila penerima membalas dalam 24 jam terakhir."))
        addView(waAutoRow)
        addView(softButton("Tes WhatsApp") { runTest(waStatus) { cxTestWhatsApp() } }, lp(10))
        addView(waStatus)
    }
    val footer = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(softButton("Tutup") { dialog?.dismiss() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(filledButton("Simpan") {
            if (save()) { toast("Koneksi disimpan"); dialog?.dismiss() }
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
    }
    dialog = showSheet("Koneksi", "Email, Telegram, WhatsApp", box, footer)
}
