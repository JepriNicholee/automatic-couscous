package com.build.mythos

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

// "WhatsApp di HP": menangkap pesan masuk dari NOTIFIKASI WhatsApp dan membalas lewat tombol Balas pada notifikasi itu.
// Butuh izin "Akses notifikasi" (satu saklar di pengaturan Android). Hanya paket WhatsApp yang diproses; notifikasi aplikasi lain
// diabaikan seketika dan tidak disimpan. Batasan: hanya pesan yang tiba saat izin aktif, dan hanya bisa membalas percakapan
// yang notifikasinya masih ada. Mengirim ke kontak baru tidak bisa lewat jalur ini (pakai WhatsApp Cloud API).

private val WA_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

/** Tombol Balas dari notifikasi yang masih aktif, per nama percakapan. */
internal object WaReply {
    val actions = ConcurrentHashMap<String, Notification.Action>()

    /** Null = balasan dikirim ke WhatsApp; selain itu alasan gagal. */
    fun send(ctx: Context, conversation: String, text: String): String? {
        val key = actions.keys.firstOrNull { it.equals(conversation.trim(), ignoreCase = true) }
            ?: return "Tidak ada notifikasi balasan aktif untuk \"${conversation.take(60)}\" (notifikasi sudah dibuka atau dihapus, atau belum ada pesan baru)."
        val a = actions[key] ?: return "Notifikasi sudah hilang."
        val inputs = a.remoteInputs
        if (inputs == null || inputs.isEmpty()) return "Notifikasi ini tidak mendukung balasan cepat."
        val results = Bundle()
        results.putCharSequence(inputs[0].resultKey, text)
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try {
            a.actionIntent.send(ctx, 0, intent)
            null
        } catch (e: PendingIntent.CanceledException) {
            actions.remove(key)
            "Notifikasi sudah kedaluwarsa; tunggu pesan baru lalu coba lagi."
        }
    }
}

class WaListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName !in WA_PACKAGES) return
            val n = sbn.notification ?: return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            val ex = n.extras ?: return
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val convTitle = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
            val conv = convTitle.ifBlank { title }.trim()
            if (conv.isEmpty()) return
            val parcels = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
            var added = false
            if (parcels != null && parcels.isNotEmpty()) {
                for (p in parcels) {
                    val b = p as? Bundle ?: continue
                    val text = b.getCharSequence("text")?.toString().orEmpty()
                    if (text.isBlank()) continue
                    val sender = b.getCharSequence("sender")?.toString().orEmpty().ifBlank { title }
                    if (WaInbox.add(WaMsg(conv, sender, text, b.getLong("time", sbn.postTime)))) added = true
                }
            } else {
                val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                if (text.isNotBlank() && WaInbox.add(WaMsg(conv, title, text, sbn.postTime))) added = true
            }
            val reply = n.actions?.firstOrNull { a -> a.remoteInputs != null && a.remoteInputs.isNotEmpty() }
            if (reply != null) WaReply.actions[conv] = reply
            if (added) { /* pesan baru tersimpan di WaInbox; agen membacanya lewat alat whatsapp_baca */ }
        } catch (e: Exception) {
            // Gagal membaca satu notifikasi tidak boleh menjatuhkan layanan.
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Tombol Balas tidak valid lagi setelah notifikasi dihapus (mis. pengguna membuka chat di WhatsApp).
        if (sbn.packageName !in WA_PACKAGES) return
        val ex = sbn.notification?.extras ?: return
        val t = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
            .ifBlank { ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty() }.trim()
        if (t.isNotEmpty()) WaReply.actions.remove(t)
    }
}

/** Izin "Akses notifikasi" untuk aplikasi ini sudah diberikan? */
internal fun Context.waListenerEnabled(): Boolean {
    val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
    return flat.split(':').any { it.substringBefore('/') == packageName }
}
