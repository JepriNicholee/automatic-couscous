package com.build.mythos

import java.io.File

// Penyimpanan memori obrolan di filesDir/memory/chat_memory.json. Semua tetap di perangkat; hanya dibaca oleh mode obrolan
// model lokal (di HP juga), jadi isinya tidak dikirim ke AI internet. Ekstensi MainActivity; logikanya ada di ChatMemory.kt.

private val memLock = Any()

// Cadangan di Termux (~/MYTHOS/memory/chat_memory.json). filesDir aplikasi ikut hilang bila aplikasi dihapus-pasang ulang
// (mis. upgrade dengan tanda tangan berbeda); folder rumah Termux tidak. Dipulihkan otomatis saat aplikasi dibuka.
private val mirrorLock = Any()
private var mirrorBusy = false
private var lastMirrorAt = 0L
private var lastMirrorHash = 0
private var restoredThisProcess = false
// Cadangan baru boleh ditimpa setelah isi lamanya selesai dibaca dan digabung; kalau tidak, memori baru yang masih sedikit bisa menimpa cadangan yang lengkap.
@Volatile private var restoreDone = false
private const val MIRROR_MIN_GAP_MS = 60_000L

internal fun MainActivity.memoryMirrorPath(): String = "$termuxHome/MYTHOS/memory/chat_memory.json"

internal fun MainActivity.memoryFile(): File = File(File(filesDir, "memory").also { it.mkdirs() }, "chat_memory.json")

private fun MainActivity.loadMemoryRaw(): List<MemoryEntry> {
    val f = memoryFile()
    if (!f.exists()) return emptyList()
    return ChatMemory.fromJson(try { f.readText() } catch (e: Exception) { "" })
}

private fun MainActivity.saveMemoryRaw(list: List<MemoryEntry>) {
    try {
        val f = memoryFile()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(ChatMemory.toJson(list))
        tmp.renameTo(f)
    } catch (e: Exception) { logQuiet("ChatMemoryStore.kt", e) }
}

internal fun MainActivity.memoryCount(): Int = synchronized(memLock) { loadMemoryRaw().size }

internal fun MainActivity.clearMemory() {
    synchronized(memLock) { try { memoryFile().delete() } catch (e: Exception) { logQuiet("ChatMemoryStore.kt", e) } }
    // Cadangan ikut dihapus; kalau tidak, memori yang sengaja dihapus akan dipulihkan lagi saat aplikasi dibuka.
    lastMirrorHash = 0
    bg { try { termux("rm -f ${sh(memoryMirrorPath())}") } catch (e: Throwable) { logQuiet("ChatMemoryStore.kt", e) } }
}

/** Salin memori ke Termux di latar. Dibatasi sekali per menit dan dilewati bila isinya tidak berubah; gagal (Termux tidak ada) diam saja. */
internal fun MainActivity.mirrorMemoryAsync() {
    if (incognito || !restoreDone) return
    val now = System.currentTimeMillis()
    synchronized(mirrorLock) {
        if (mirrorBusy || now - lastMirrorAt < MIRROR_MIN_GAP_MS) return
        mirrorBusy = true
    }
    bg {
        try {
            val json = synchronized(memLock) { ChatMemory.toJson(loadMemoryRaw()) }
            val h = json.hashCode()
            if (h != lastMirrorHash && ChatMemory.fromJson(json).isNotEmpty()) {
                val r = writeFileChunked(memoryMirrorPath(), json)
                if (r.trimEnd().endsWith("OK")) lastMirrorHash = h
            }
        } catch (e: Throwable) {
        } finally {
            synchronized(mirrorLock) { mirrorBusy = false; lastMirrorAt = System.currentTimeMillis() }
        }
    }
}

/** Sekali per proses: gabungkan cadangan di Termux ke memori perangkat (mengisi kembali setelah pasang ulang). Bila Termux belum menjawab, dicoba lagi saat aplikasi dibuka berikutnya. */
internal fun MainActivity.restoreMemoryFromMirrorOnce() {
    if (restoredThisProcess) return
    restoredThisProcess = true
    bg {
        try {
            val text = readOld(memoryMirrorPath())
            if (text == null) { restoredThisProcess = false; return@bg } // tidak terbaca: jangan anggap selesai
            if (text.isNotBlank()) {
                val remote = ChatMemory.fromJson(text)
                if (remote.isNotEmpty()) synchronized(memLock) {
                    val local = loadMemoryRaw()
                    val merged = ChatMemory.merge(local, remote, System.currentTimeMillis())
                    if (ChatMemory.toJson(merged) != ChatMemory.toJson(local)) saveMemoryRaw(merged)
                }
            }
            restoreDone = true
        } catch (e: Throwable) { restoredThisProcess = false }
    }
}

/** Simpan pertukaran terakhir (pertanyaan pengguna dan jawaban akhir AI) dari semua penyedia. Chat samaran tidak menyimpan apa pun. */
internal fun MainActivity.rememberLastExchange() {
    if (incognito) return
    val (u, a) = synchronized(hist) { ChatMemory.lastExchange(ArrayList(hist)) } ?: return
    synchronized(memLock) {
        val before = loadMemoryRaw()
        val after = ChatMemory.add(before, u, a, System.currentTimeMillis())
        if (after !== before) saveMemoryRaw(after)
    }
    mirrorMemoryAsync()
}

/** Blok ingatan yang cocok dengan [query] untuk mode obrolan model lokal; kosong bila tidak ada yang relevan. */
internal fun MainActivity.recallBlock(query: String): String {
    if (incognito) return ""
    val found = synchronized(memLock) { ChatMemory.recall(loadMemoryRaw(), query, System.currentTimeMillis()) }
    if (found.isEmpty()) return ""
    // Hitung pemakaian di latar supaya tidak menahan pengiriman pesan.
    bg { synchronized(memLock) { saveMemoryRaw(ChatMemory.markUsed(loadMemoryRaw(), found)) } }
    return ChatMemory.format(found)
}

/** Sekali saja: ambil catatan dari semua chat lama di perangkat, supaya memori langsung terisi. */
internal fun MainActivity.importOldChatsOnce() {
    if (prefs.getBoolean("memory_imported", false)) return
    prefs.edit().putBoolean("memory_imported", true).apply()
    bg {
        try {
            var list = synchronized(memLock) { loadMemoryRaw() }
            val files = chatsDir().listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != CHAT_INDEX }.orEmpty()
            for (f in files.sortedBy { it.lastModified() }) {
                try {
                    val id = f.name.removeSuffix(".json")
                    if (ChatStore.safeId(id) == null) continue
                    val cf = ChatStore.parseFile(f.readText(), id, f.lastModified())
                    for ((u, a) in ChatMemory.pairsFrom(cf.messages)) list = ChatMemory.add(list, u, a, cf.meta.updated)
                } catch (e: Exception) { logQuiet("ChatMemoryStore.kt", e) }
            }
            // Gabung dengan isi terkini (bisa sudah bertambah selama impor atau dipulihkan dari cadangan), bukan menimpa.
            synchronized(memLock) { saveMemoryRaw(ChatMemory.merge(loadMemoryRaw(), list, System.currentTimeMillis())) }
            mirrorMemoryAsync()
        } catch (e: Exception) { logQuiet("ChatMemoryStore.kt", e) }
    }
}
