package com.build.mythos

import java.io.File

// Catatan file yang ditulis agen (write_file dan APK hasil build) untuk halaman Artefak.
// Satu entri JSON per baris di filesDir/artifact_log.jsonl; logika murninya ada di WorkspaceList.

private fun MainActivity.artifactLogFile() = File(filesDir, "artifact_log.jsonl")

@Synchronized
internal fun MainActivity.artifactLogLoad(): List<ArtifactRec> = try {
    val f = artifactLogFile()
    if (f.exists()) WorkspaceList.decode(f.readLines()) else emptyList()
} catch (e: Exception) { emptyList() }

@Synchronized
private fun MainActivity.artifactLogSave(list: List<ArtifactRec>) {
    try {
        val tmp = File(filesDir, "artifact_log.jsonl.tmp")
        tmp.writeText(list.joinToString("\n") { WorkspaceList.encode(it) })
        tmp.renameTo(artifactLogFile())
    } catch (e: Exception) { logQuiet("ArtifactLog.kt", e) }
}

/** Catat [path] (absolut di Termux) sebagai file buatan agen. Dipanggil dari thread latar; gagal tulis diabaikan. */
@Synchronized
internal fun MainActivity.recordArtifact(path: String, kind: String) {
    if (!path.startsWith("/") || isProtectedReferencePath(path)) return
    noteIncognitoWrite(path)   // di Chat samaran: ingatkan bahwa file ini tetap ada di folder kerja
    artifactLogSave(WorkspaceList.addRec(artifactLogLoad(), ArtifactRec(path, kind, System.currentTimeMillis())))
}

@Synchronized
internal fun MainActivity.forgetArtifacts(paths: Collection<String>) {
    if (paths.isEmpty()) return
    val cur = artifactLogLoad()
    val keep = cur.filter { it.path !in paths }
    if (keep.size != cur.size) artifactLogSave(keep)
}
