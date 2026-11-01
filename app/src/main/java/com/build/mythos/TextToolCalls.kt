package com.build.mythos

import org.json.JSONObject

// Model open source kecil (Llama, Qwen, Mistral) kadang menulis pemanggilan alat sebagai TEKS, bukan lewat
// function-call native: <tool_call>{...}</tool_call>, <function=nama>{...}</function>, atau satu objek JSON.
// Tanpa pemulihan ini teks itu hanya disembunyikan dan agen berhenti. Murni Kotlin (tanpa Android), diuji di TextToolCallsTest.
// Hanya nama alat yang dikenal yang dipulihkan; semua alat tetap lewat kebijakan perintah dan dialog persetujuan yang sama.

private val TAG_CALL = Regex("(?is)<tool_call>\\s*(.*?)\\s*</tool_call>")
private val FUNC_CALL = Regex("(?is)<function=([\\w.\\-]+)>\\s*(.*?)\\s*</function>")
private val FENCE = Regex("(?is)^```(?:json)?\\s*(.*?)\\s*```$")

private fun argsFrom(v: Any?): JSONObject? = when (v) {
    null, JSONObject.NULL -> JSONObject()
    is JSONObject -> v
    is String -> try { JSONObject(if (v.isBlank()) "{}" else v) } catch (e: Exception) { null }
    else -> null
}

/** [strict] = true: wajib ada "arguments" atau "parameters" (dipakai untuk JSON polos agar tidak salah kenal). */
private fun callFromJson(o: JSONObject, known: Set<String>, id: String, strict: Boolean): Call? {
    val src = o.optJSONObject("function") ?: o
    val name = src.optString("name")
    if (name !in known) return null
    val key = when {
        src.has("arguments") -> "arguments"
        src.has("parameters") -> "parameters"
        else -> null
    }
    if (key == null && strict) return null
    val args = argsFrom(if (key == null) null else src.opt(key)) ?: return null
    return Call(id, name, args)
}

// ---- Format DSML (DeepSeek): <｜DSML｜invoke name="LS"><｜DSML｜parameter name="path" string="true">.</｜DSML｜parameter>...
// Model kadang menulisnya sebagai TEKS (tanda | bisa ASCII atau lebar penuh, spasi bisa terselip), sehingga dulu tampil mentah
// sebagai jawaban dan agen berhenti. Nama alat milik model (LS, Read, Bash, Write) dipetakan ke alat aplikasi.
private const val DP = "[|\uFF5C]"
private val DSML_WRAP = Regex("(?is)<\\s*/?\\s*$DP\\s*DSML\\s*$DP\\s*(?:function_)?calls\\s*>")
private val DSML_INVOKE = Regex("(?is)<\\s*$DP\\s*DSML\\s*$DP\\s*invoke\\s+name\\s*=\\s*\"([^\"]+)\"\\s*>(.*?)<\\s*/\\s*$DP\\s*DSML\\s*$DP\\s*invoke\\s*>")
private val DSML_PARAM = Regex("(?is)<\\s*$DP\\s*DSML\\s*$DP\\s*parameter\\s+name\\s*=\\s*\"([^\"]+)\"([^>]*)>(.*?)<\\s*/\\s*$DP\\s*DSML\\s*$DP\\s*parameter\\s*>")

private fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

/** Petakan satu panggilan DSML ke alat aplikasi; null bila tidak ada padanan yang dikenal. */
private fun mapDsmlCall(rawName: String, p: JSONObject, known: Set<String>): Pair<String, JSONObject>? {
    val n = rawName.trim().lowercase()
    if (rawName in known) return Pair(rawName, p)
    fun cmd(desc: String, command: String): Pair<String, JSONObject>? =
        if ("run_command" in known) Pair("run_command", JSONObject().put("deskripsi", desc).put("command", command)) else null
    val path = p.optString("path").ifBlank { p.optString("file_path") }.ifBlank { "." }
    return when (n) {
        "bash", "shell", "sh", "run_command", "execute", "exec" -> {
            val c = p.optString("command").ifBlank { p.optString("cmd") }
            if (c.isBlank()) null else cmd(p.optString("description").ifBlank { "Menjalankan perintah" }, c)
        }
        "ls", "list", "list_dir", "listdir", "list_files" -> {
            val depth = p.optString("depth").toIntOrNull()?.coerceIn(1, 6) ?: 1
            val c = if (depth <= 1) "ls -la ${shQuote(path)}"
            else "find ${shQuote(path)} -maxdepth $depth -not -path '*/.git/*' | sort | head -300"
            cmd("Melihat isi folder ${path.substringAfterLast('/').ifBlank { path }}", c)
        }
        "read", "cat", "read_file", "view" -> cmd("Membaca ${path.substringAfterLast('/')}", "cat ${shQuote(path)}")
        "write", "write_file", "create_file" ->
            if ("write_file" in known) Pair("write_file", JSONObject().put("deskripsi", "Menulis ${path.substringAfterLast('/')}")
                .put("path", path).put("content", p.optString("content"))) else null
        else -> null
    }
}

/** Pulihkan panggilan DSML dari [text]; hasil: teks sisa tanpa blok DSML dan daftar panggilan (kosong bila tak ada yang cocok). */
internal fun recoverDsmlCalls(text: String, known: Set<String>): Pair<String, List<Call>> {
    if (!DSML_WRAP.containsMatchIn(text) && !DSML_INVOKE.containsMatchIn(text)) return Pair(text, emptyList())
    val calls = ArrayList<Call>()
    for (m in DSML_INVOKE.findAll(text)) {
        val params = JSONObject()
        for (pm in DSML_PARAM.findAll(m.groupValues[2])) {
            val raw = pm.groupValues[3].trim('\n', '\r')
            val isString = !Regex("(?i)string\\s*=\\s*\"false\"").containsMatchIn(pm.groupValues[2])
            val v: Any = if (isString) raw else try { org.json.JSONTokener(raw.trim()).nextValue() ?: raw } catch (e: Exception) { raw }
            params.put(pm.groupValues[1], v)
        }
        val mapped = mapDsmlCall(m.groupValues[1], params, known) ?: continue
        calls.add(Call("dsml${calls.size}", mapped.first, mapped.second))
    }
    if (calls.isEmpty()) return Pair(text, emptyList())
    val rest = DSML_WRAP.replace(DSML_INVOKE.replace(text, ""), "").trim()
    return Pair(rest, calls)
}

/**
 * Cari pemanggilan alat yang tertulis di [text]. Hasil: teks yang tersisa (tanpa pemanggilan) dan daftar panggilan.
 * Tanpa temuan, teks dikembalikan apa adanya dengan daftar kosong.
 */
internal fun recoverTextToolCalls(text: String, known: Set<String>): Pair<String, List<Call>> {
    if (text.isBlank() || known.isEmpty()) return Pair(text, emptyList())
    val dsml = recoverDsmlCalls(text, known)
    if (dsml.second.isNotEmpty()) return dsml
    val calls = ArrayList<Call>()
    var rest = TAG_CALL.replace(text) { m ->
        val o = try { JSONObject(m.groupValues[1]) } catch (e: Exception) { null }
        val c = if (o == null) null else callFromJson(o, known, "txt${calls.size}", false)
        if (c != null) { calls.add(c); "" } else m.value
    }
    rest = FUNC_CALL.replace(rest) { m ->
        val name = m.groupValues[1]
        val args = if (name in known) argsFrom(m.groupValues[2]) else null
        if (args != null) { calls.add(Call("txt${calls.size}", name, args)); "" } else m.value
    }
    if (calls.isEmpty()) {
        val t = text.trim()
        val body = FENCE.find(t)?.groupValues?.get(1) ?: t
        if (body.startsWith("{") && body.endsWith("}")) {
            val o = try { JSONObject(body) } catch (e: Exception) { null }
            val c = if (o == null) null else callFromJson(o, known, "txt0", true)
            if (c != null) return Pair("", listOf(c))
        }
        return Pair(text, emptyList())
    }
    return Pair(rest.trim(), calls)
}
