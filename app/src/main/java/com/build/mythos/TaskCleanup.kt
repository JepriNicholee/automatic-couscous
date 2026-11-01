package com.build.mythos

import java.io.File

// Salinan lampiran (di ruang kerja Termux dan preview di cache aplikasi).
// Dulu dihapus otomatis setiap tugas selesai, sehingga agen "lupa" file/foto yang sudah dikirim pada pesan berikutnya.
// Sekarang releaseTaskCopies() hanya dipanggil saat chat dibuang (resetChatState dengan saveOld = false);
// chat yang disimpan dan bisa dibuka lagi mempertahankan lampirannya. Penghapusan chat memanggil purgeChatAttachments().

private val pendingTermuxCopies: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())

/** Catat salinan milik tugas yang baru dikirim. Dipanggil saat pesan dengan lampiran dikirim. */
internal fun registerTaskCopies(termuxPaths: List<String>) {
    pendingTermuxCopies.addAll(termuxPaths)
}

/** Hapus semua salinan yang tercatat, lepaskan gambar dari riwayat, lalu simpan riwayat. */
internal fun MainActivity.releaseTaskCopies() {
    val images = synchronized(hist) { hist.flatMap { it.images } }
    val termuxPaths = (synchronized(pendingTermuxCopies) { pendingTermuxCopies.toList() } +
        images.map { it.path }).filter { it.startsWith("$termuxHome/MYTHOS/attachments/") }.distinct()
    if (termuxPaths.isEmpty() && images.none { !it.released }) return
    pendingTermuxCopies.clear()
    images.forEach { it.released = true; it.cachedPayload = null }
    bg {
        if (termuxPaths.isNotEmpty()) {
            try {
                termux("rm -f -- " + termuxPaths.joinToString(" ") { sh(it) }, limit = 500, timeoutSec = 30)
            } catch (ignored: Exception) { logQuiet("TaskCleanup.kt", ignored) }
        }
        saveHist()
    }
}
