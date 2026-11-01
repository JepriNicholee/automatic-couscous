package com.build.mythos

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

// Riwayat percakapan: tiap chat satu file di filesDir/chats (history.json lama dipindahkan otomatis).
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.histFile() = File(filesDir, "history.json")

internal fun MainActivity.callJson(c: Call) = JSONObject().put("id", c.id).put("name", c.name).put("args", c.args)

internal fun MainActivity.callFrom(o: JSONObject) =
    Call(o.optString("id"), o.optString("name"), o.optJSONObject("args") ?: JSONObject())

internal fun MainActivity.histToJson(h: Hist): JSONObject {
    val o = JSONObject().put("role", h.role).put("text", h.text)
    if (h.calls.isNotEmpty()) {
        val a = JSONArray()
        for (c in h.calls) a.put(callJson(c))
        o.put("calls", a)
    }
    if (h.results.isNotEmpty()) {
        val a = JSONArray()
        for ((c, r) in h.results) a.put(JSONObject().put("call", callJson(c)).put("out", r))
        o.put("results", a)
    }
    h.raw?.let { o.put("raw", it) }
    if (h.images.isNotEmpty()) {
        val images = JSONArray()
        h.images.forEach { images.put(JSONObject().put("path", it.path).put("preview", it.previewPath ?: "").put("released", it.released)) }
        o.put("images", images)
    }
    return o
}

internal fun MainActivity.histFromJson(o: JSONObject): Hist {
    val calls = ArrayList<Call>()
    o.optJSONArray("calls")?.let { a ->
        for (i in 0 until a.length()) calls.add(callFrom(a.getJSONObject(i)))
    }
    val results = ArrayList<Pair<Call, String>>()
    o.optJSONArray("results")?.let { a ->
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            results.add(Pair(callFrom(r.getJSONObject("call")), r.optString("out")))
        }
    }
    val images = ArrayList<HistImage>()
    o.optJSONArray("images")?.let { a ->
        for (i in 0 until a.length()) {
            val item = a.opt(i)
            if (item is JSONObject) {
                val path = item.optString("path")
                if (path.isNotBlank()) images.add(HistImage(path, item.optString("preview").ifBlank { null }).also { it.released = item.optBoolean("released") })
            } else {
                val path = a.optString(i)
                if (path.isNotBlank()) images.add(HistImage(path))
            }
        }
    }
    return Hist(o.optInt("role"), o.optString("text"), calls, results, o.optJSONObject("raw"), images)
}

// ------------------------------------------------------------------ Penyimpanan banyak chat
// Tiap chat satu file: filesDir/chats/<id>.json; filesDir/chats/index.json menyimpan ringkasannya untuk drawer.

internal const val CHAT_INDEX = "index.json"

internal fun MainActivity.chatsDir(): File = File(filesDir, "chats").also { it.mkdirs() }

internal fun MainActivity.chatFile(id: String): File =
    File(chatsDir(), (ChatStore.safeId(id) ?: throw IllegalArgumentException("id chat tidak valid")) + ".json")

/** Id chat yang sedang dibuka; dibuat saat pertama dibutuhkan. */
@Synchronized
internal fun MainActivity.currentChatId(): String {
    val saved = prefs.getString("chat_id", "").orEmpty()
    if (ChatStore.safeId(saved) != null) return saved
    val id = ChatStore.newId(System.currentTimeMillis())
    prefs.edit().putString("chat_id", id).apply()
    return id
}

@Synchronized
private fun MainActivity.rebuildIndex(): List<ChatMeta> {
    val out = ArrayList<ChatMeta>()
    chatsDir().listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != CHAT_INDEX }?.forEach { f ->
        try {
            val id = f.name.removeSuffix(".json")
            if (ChatStore.safeId(id) != null) out.add(ChatStore.parseFile(f.readText(), id, f.lastModified()).meta)
        } catch (e: Exception) { logQuiet("ChatHistory.kt", e) }
    }
    val sorted = out.sortedByDescending { it.updated }
    writeIndex(sorted)
    return sorted
}

@Synchronized
private fun MainActivity.writeIndex(list: List<ChatMeta>) {
    try {
        val tmp = File(chatsDir(), "$CHAT_INDEX.tmp")
        tmp.writeText(ChatStore.indexToJson(list))
        tmp.renameTo(File(chatsDir(), CHAT_INDEX))
    } catch (e: Exception) { logQuiet("ChatHistory.kt", e) }
}

/** Daftar chat tersimpan, terbaru dulu. Entri yang filenya sudah hilang dibuang. */
@Synchronized
internal fun MainActivity.listChats(): List<ChatMeta> {
    migrateLegacyHistory()
    val f = File(chatsDir(), CHAT_INDEX)
    val idx = if (f.exists()) try { ChatStore.parseIndex(f.readText()) } catch (e: Exception) { null } else null
    val list = idx ?: rebuildIndex()
    return list.filter { chatFile(it.id).exists() }
}

/** history.json lama (satu chat) dipindah menjadi chat pertama di daftar. */
@Synchronized
internal fun MainActivity.migrateLegacyHistory() {
    val legacy = histFile()
    if (!legacy.exists()) return
    val has = chatsDir().listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != CHAT_INDEX }?.isNotEmpty() == true
    if (has) return
    val id = ChatStore.newId(legacy.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis())
    if (legacy.renameTo(chatFile(id))) {
        prefs.edit().putString("chat_id", id).apply()
        File(chatsDir(), CHAT_INDEX).delete() // paksa bangun ulang indeks
    }
}

@Synchronized
internal fun MainActivity.saveHist() {
    if (incognito) return // Chat samaran: tidak ditulis ke disk
    try {
        val arr = JSONArray()
        synchronized(hist) {
            if (hist.none { it.role == 0 && it.text.isNotBlank() && !it.text.startsWith(SYS_PREFIX) }) return // belum ada isi yang layak disimpan
            val resIdx = hist.indices.filter { hist[it].role == 2 }
            val keepFrom = if (resIdx.size > 20) resIdx[resIdx.size - 20] else 0
            for ((i, h) in hist.withIndex()) {
                val hh = if (h.role == 2 && i < keepFrom) {
                    Hist(2, results = h.results.map { (c, r) -> Pair(c, if (r.length > 500) r.take(500) + "\n…(dipangkas)" else r) })
                } else h
                arr.put(histToJson(hh))
            }
        }
        val id = currentChatId()
        val old = File(chatsDir(), CHAT_INDEX).let { f -> if (f.exists()) try { ChatStore.parseIndex(f.readText()) } catch (e: Exception) { null } else null }
            ?.firstOrNull { it.id == id }
        val title = if (old?.custom == true) old.title else ChatStore.titleFrom(ChatStore.firstUserText(arr))
        val meta = ChatMeta(id, title, System.currentTimeMillis(), ChatStore.userCount(arr), old?.custom == true)
        val tmp = File(chatsDir(), "$id.json.tmp")
        tmp.writeText(ChatStore.fileToJson(meta, arr))
        tmp.renameTo(chatFile(id))
        val idx = listChats().let { ChatStore.upsert(it, meta) }
        writeIndex(idx)
    } catch (e: Exception) {
    }
}

/** Ubah nama chat. [name] kosong = kembali ke judul otomatis dari pesan pertama. */
@Synchronized
internal fun MainActivity.renameChat(id: String, name: String) {
    try {
        val f = chatFile(id)
        if (!f.exists()) return
        val parsed = ChatStore.parseFile(f.readText(), id, f.lastModified())
        val clean = ChatStore.cleanRename(name)
        val title = clean ?: ChatStore.titleFrom(ChatStore.firstUserText(parsed.messages))
        val meta = ChatMeta(id, title, parsed.meta.updated, parsed.meta.count, clean != null)
        val tmp = File(chatsDir(), "$id.json.tmp")
        tmp.writeText(ChatStore.fileToJson(meta, parsed.messages))
        tmp.renameTo(f)
        writeIndex(ChatStore.upsert(listChats(), meta))
    } catch (e: Exception) { logQuiet("ChatHistory.kt", e) }
}

/** Hapus file chat beserta gambar pratinjaunya di chat_images dan entrinya di indeks. */
@Synchronized
internal fun MainActivity.eraseChatFiles(id: String) {
    try {
        purgeChatAttachments(id) // salinan lampiran chat ini di folder attachments workspace dan catatan lampiran aktifnya
        val f = chatFile(id)
        if (f.exists()) {
            try { deleteChatImagePaths(ChatStore.imagePaths(ChatStore.parseFile(f.readText(), id, 0L).messages)) } catch (e: Exception) { logQuiet("ChatHistory.kt", e) }
            f.delete()
        }
        writeIndex(ChatStore.remove(listChats(), id))
    } catch (e: Exception) { logQuiet("ChatHistory.kt", e) }
}

/** Hapus hanya file di filesDir/chat_images; salinan di Termux dibersihkan oleh releaseTaskCopies. */
internal fun MainActivity.deleteChatImagePaths(paths: List<String>) {
    val root = File(filesDir, "chat_images").canonicalPath + File.separator
    for (p in paths) {
        try {
            val f = File(p)
            if (f.canonicalPath.startsWith(root)) f.delete()
        } catch (e: Exception) { logQuiet("ChatHistory.kt", e) }
    }
}

/** Buang giliran model yang memanggil tool tapi belum sempat dapat hasil (aplikasi ditutup / dihentikan). */
internal fun MainActivity.trimDangling() {
    synchronized(hist) {
        val last = hist.lastOrNull() ?: return
        if (last.role == 1 && last.calls.isNotEmpty()) {
            hist.removeAt(hist.size - 1)
            if (last.text.isNotBlank()) hist.add(Hist(1, last.text))
        }
    }
}

internal fun MainActivity.restoreHistory() {
    migrateLegacyHistory()
    val id = currentChatId()
    try {
        val f = chatFile(id)
        if (!f.exists()) return
        val arr = ChatStore.parseFile(f.readText(), id, f.lastModified()).messages
        for (i in 0 until arr.length()) {
            try { hist.add(histFromJson(arr.getJSONObject(i))) } catch (e: Exception) { logQuiet("ChatHistory.kt", e) }
        }
    } catch (e: Exception) {
        hist.clear()
        clearSummary() // ringkasan lama menunjuk indeks riwayat yang sudah tidak ada
        try { chatFile(id).renameTo(File(chatsDir(), "$id.json.bad")) } catch (e2: Exception) { logQuiet("ChatHistory.kt", e2) }
    }
    trimDangling()
    for ((i, h) in hist.withIndex()) {
        if (h.text.isNotBlank()) {
            if (h.role == 0 && h.text.startsWith(SYS_PREFIX)) { /* pesan sistem: tidak jadi gelembung */ }
            else if (h.role == 0) addMsg(USER, stripAttachmentBlocks(h.text), h.images, pin = false) else if (h.role == 1) addMsg(AI, h.text)
        }
        // Langkah alat ikut dipulihkan sebagai kartu Aktivitas Agen (diringkas), bukan hilang saat aplikasi dibuka ulang.
        if (h.role == 1 && h.calls.isNotEmpty()) {
            val next = hist.getOrNull(i + 1)
            runOnUiThread { restoreFeed(h.calls, if (next?.role == 2) next.results else emptyList()) }
        }
    }
}
