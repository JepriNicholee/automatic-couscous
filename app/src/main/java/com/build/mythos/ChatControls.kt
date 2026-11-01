package com.build.mythos

import java.io.File
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.speech.RecognizerIntent
import android.view.View

// Kontrol chat: hapus, chat baru, suara, tombol aksi kirim/suara/stop.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.confirmDelete() {
    if (busy) { toast("Tunggu proses selesai"); return }
    if (hist.isEmpty() && chatBox.childCount == 0) { toast("Belum ada percakapan"); return }
    AlertDialog.Builder(this)
        .setMessage("Hapus percakapan ini?")
        .setPositiveButton("Hapus") { _, _ -> deleteChat(currentChatId()) }
        .setNegativeButton("Batal", null)
        .show()
}

/**
 * Kosongkan layar dan semua status milik chat yang sedang dibuka.
 * [saveOld] true: chat lama disimpan dulu (tetap ada di daftar). false: chat lama dibuang.
 */
private fun MainActivity.resetChatState(saveOld: Boolean) {
    clearAttachments()
    AttachState.incognito.clear()
    if (saveOld && !incognito) {
        // Chat disimpan dan bisa dibuka lagi: file dan gambarnya dibiarkan. Hanya muatan base64 di memori yang dilepas.
        synchronized(hist) { hist.forEach { h -> h.images.forEach { it.cachedPayload = null } } }
    } else {
        releaseTaskCopies() // chat dibuang: tandai gambar "released" di memori dan hapus salinannya
    }
    val doomedImages = if (saveOld && !incognito) emptyList() else synchronized(hist) { hist.flatMap { it.images } }.flatMap { listOfNotNull(it.path, it.previewPath) }
    if (saveOld) saveHist()
    hist.clear()
    clearSummary()
    // Checkpoint milik percakapan yang ditinggalkan; kalau dibiarkan, mengetik "lanjutkan" akan menghidupkan tugas lama tanpa riwayat.
    clearAgentCheckpoint()
    pendingInterrupt = null
    cliAllowedTpl = null
    deleteChatImagePaths(doomedImages)
    zone.reset()
    runLog.reset(System.currentTimeMillis())
    chatBox.removeAllViews()
    emptyState.visibility = View.VISIBLE
}

/** Mulai chat kosong. Chat sebelumnya tidak dihapus: ia tersimpan dan muncul di daftar drawer. */
internal fun MainActivity.newChat() {
    if (busy) { toast("Tunggu proses selesai"); return }
    resetChatState(saveOld = true)
    prefs.edit().remove("chat_id").apply() // id baru dibuat saat pesan pertama disimpan
}

/** Buka chat tersimpan: chat sekarang disimpan dulu, lalu isi [id] dimuat ulang ke layar. */
internal fun MainActivity.openChat(id: String) {
    if (ChatStore.safeId(id) == null) return
    if (incognito) { toast("Keluar dari Chat samaran dulu"); return }
    if (busy) { toast("Tunggu proses selesai"); return }
    if (id == currentChatId()) return
    if (!chatFile(id).exists()) { toast("Chat tidak ditemukan"); return }
    resetChatState(saveOld = true)
    prefs.edit().putString("chat_id", id).apply()
    restoreHistory()
    scrollDown()
}

/** Hapus satu chat dari daftar dan dari penyimpanan. Bila itu chat yang sedang dibuka, layar dikosongkan. */
internal fun MainActivity.deleteChat(id: String) {
    if (ChatStore.safeId(id) == null) return
    val isCurrent = id == currentChatId()
    if (isCurrent && busy) { toast("Tunggu proses selesai"); return }
    if (isCurrent) {
        resetChatState(saveOld = false)
        prefs.edit().remove("chat_id").apply()
    }
    eraseChatFiles(id)
    drawerRefresh?.invoke()
}

internal fun MainActivity.voice() {
    voiceTurnPending = true
    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
    try {
        startActivityForResult(i, 77)
    } catch (e: Exception) {
        voiceTurnPending = false
        toast("Input suara tidak tersedia")
    }
}

internal fun MainActivity.onAction() {
    if (busy) stopAgent() else if (input.text.isNullOrBlank() && attachments.isEmpty()) toast("Tulis pesan dulu") else send()
}

internal fun MainActivity.updateAction() {
    val has = !input.text.isNullOrBlank() || attachments.isNotEmpty()
    when {
        busy -> {
            actionBtn.visibility = View.VISIBLE
            actionIcon.type = IconView.STOP_BOX
            actionBtn.contentDescription = getString(R.string.cd_stop)
            actionIcon.color = Palette.ICON
            actionBg.setColor(Palette.CHIP)
        }
        has -> {
            actionBtn.visibility = View.VISIBLE
            actionIcon.type = IconView.SEND
            actionBtn.contentDescription = getString(R.string.cd_send)
            actionIcon.color = Color.WHITE
            actionBg.setColor(Palette.BRAND)
        }
        else -> {
            // Seperti Claude: tombol kirim oranye selalu terlihat, juga saat kolom masih kosong.
            actionBtn.visibility = View.VISIBLE
            actionIcon.type = IconView.SEND
            actionBtn.contentDescription = getString(R.string.cd_send)
            actionIcon.color = Color.WHITE
            actionBg.setColor(Palette.BRAND)
        }
    }
}
