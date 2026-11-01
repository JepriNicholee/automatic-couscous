package com.build.mythos

import org.json.JSONArray
import org.json.JSONObject

// Alat buatan agen sendiri (create_tool / delete_tool). Logika murni di objek CustomTools (diuji di CustomToolsTest);
// penyimpanan dan eksekusi di ekstensi MainActivity di bawah.
// Kebebasan: membuat alat TIDAK meminta persetujuan. Menjalankannya lewat jalur run_command yang sama, jadi kebijakan
// perintah, sandbox, mode hanya baca, persetujuan sesuai pengaturan, dan folder reference yang terkunci tetap berlaku.

internal data class CustomTool(val name: String, val description: String, val params: List<String>, val script: String)

internal object CustomTools {
    const val MAX_TOOLS = 20
    const val MAX_PARAMS = 6
    const val MAX_SCRIPT = 8000

    /** Nama alat bawaan: tidak boleh ditimpa alat buatan. */
    val BUILTIN = setOf(
        "run_command", "write_file", "create_android_project", "validate_project",
        "build_apk", "self_audit", "self_upgrade", "ask_user", "create_tool", "delete_tool", "cari_web", "baca_web"
    ) + CONNECTOR_TOOL_NAMES

    private val NAME_RE = Regex("^[a-z][a-z0-9_]{2,30}$")
    private val PARAM_RE = Regex("^[a-z][a-z0-9_]{0,20}$")

    /** Nama parameter dipisah koma/spasi/garis tegak; null bila ada yang tidak valid. */
    fun parseParams(raw: String): List<String>? {
        val l = raw.split(",", " ", "|").map { it.trim() }.filter { it.isNotEmpty() }
        if (l.size > MAX_PARAMS) return null
        if (l.any { !PARAM_RE.matches(it) || it == "deskripsi" }) return null
        if (l.toSet().size != l.size) return null
        return l
    }

    /** Buat alat baru (atau versi baru dengan nama sama). Melempar IllegalArgumentException berisi alasan bila ditolak. */
    fun create(name: String, description: String, paramsRaw: String, script: String, existing: List<CustomTool>): CustomTool {
        val n = name.trim()
        require(NAME_RE.matches(n)) { "Nama alat harus huruf kecil/angka/garis bawah, diawali huruf, 3-31 karakter (mis. cari_todo)." }
        require(n !in BUILTIN) { "Nama '$n' dipakai alat bawaan; pilih nama lain." }
        require(description.isNotBlank()) { "Penjelasan alat tidak boleh kosong." }
        require(script.isNotBlank()) { "Skrip tidak boleh kosong." }
        require(script.length <= MAX_SCRIPT) { "Skrip terlalu panjang (maks $MAX_SCRIPT karakter); pecah jadi beberapa alat." }
        val params = parseParams(paramsRaw)
        require(params != null) { "Parameter tidak valid: huruf kecil/angka/garis bawah, maks $MAX_PARAMS, tanpa duplikat dan bukan 'deskripsi'." }
        require(existing.any { it.name == n } || existing.size < MAX_TOOLS) { "Sudah $MAX_TOOLS alat buatan; hapus yang tidak terpakai dengan delete_tool." }
        return CustomTool(n, description.trim().take(300), params!!, script)
    }

    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Perintah shell untuk menjalankan alat. Parameter tersedia sebagai $1, $2, ... dan ARG_NAMA.
     * Skrip tampil apa adanya (dikutip) supaya kebijakan perintah dan dialog persetujuan menilai isi sebenarnya.
     */
    fun command(t: CustomTool, args: JSONObject): String {
        val vals = t.params.map { args.optString(it) }
        val env = t.params.zip(vals).joinToString(" ") { (n, v) -> "ARG_" + n.uppercase() + "=" + shq(v) }
        val pos = vals.joinToString(" ") { shq(it) }
        return (if (env.isEmpty()) "" else "$env ") + "bash -c " + shq(t.script) + " " + shq(t.name) + (if (pos.isEmpty()) "" else " $pos")
    }

    fun toJson(list: List<CustomTool>): String {
        val a = JSONArray()
        for (t in list) a.put(
            JSONObject().put("name", t.name).put("description", t.description)
                .put("params", JSONArray(t.params)).put("script", t.script)
        )
        return a.toString()
    }

    fun parse(s: String): List<CustomTool> = try {
        val a = JSONArray(if (s.isBlank()) "[]" else s)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val ps = o.optJSONArray("params")
            val params = if (ps == null) emptyList() else (0 until ps.length()).map { ps.optString(it) }
            val name = o.optString("name")
            if (!NAME_RE.matches(name) || name in BUILTIN) null
            else CustomTool(name, o.optString("description"), params, o.optString("script"))
        }
    } catch (e: Exception) {
        emptyList()
    }
}

internal fun MainActivity.loadCustomTools(): List<CustomTool> = CustomTools.parse(prefs.getString("custom_tools", "") ?: "")

internal fun MainActivity.saveCustomTools(list: List<CustomTool>) {
    prefs.edit().putString("custom_tools", CustomTools.toJson(list)).apply()
}

/** Deklarasi function-call untuk alat buatan; ditambahkan ke daftar alat di setiap permintaan ke model. */
internal fun MainActivity.customToolDecls(): List<JSONObject> = loadCustomTools().map { t ->
    val ps = mutableListOf("deskripsi" to desk)
    for (p in t.params) ps += p to "Nilai parameter $p"
    decl(t.name, t.description + " (alat buatan agen)", *ps.toTypedArray())
}

internal fun MainActivity.toolCreateTool(args: JSONObject, card: MainActivity.StepCard): String {
    if (readOnlyMode()) {
        card.finish(false, "mode hanya baca", state = StepState.DENIED)
        return "Ditolak: mode hanya baca aktif, alat baru tidak boleh dibuat."
    }
    return try {
        val existing = loadCustomTools()
        val t = CustomTools.create(
            args.optString("nama"), args.optString("penjelasan"), args.optString("parameter"), args.optString("skrip"), existing
        )
        saveCustomTools(existing.filter { it.name != t.name } + t)
        logApproved("perintah", "create_tool ${t.name}", args.optString("deskripsi"), Risk.WRITE, ApprovalMode.AUTO_MINIMAL, true, "alat dibuat", decision = "otomatis")
        card.finish(true, "", "Alat ${t.name}(${t.params.joinToString(", ")})\n\n" + t.script.take(1500))
        "Alat ${t.name} dibuat dan tersedia sebagai function-call mulai langkah berikutnya. " +
            "Dijalankan lewat jalur run_command yang sama (kebijakan, sandbox, dan persetujuan tetap berlaku)."
    } catch (e: IllegalArgumentException) {
        card.finish(false, "ditolak", e.message ?: "")
        "Ditolak: ${e.message}"
    }
}

internal fun MainActivity.toolDeleteTool(args: JSONObject, card: MainActivity.StepCard): String {
    val n = args.optString("nama").trim()
    val existing = loadCustomTools()
    if (existing.none { it.name == n }) {
        card.finish(false, "tidak ada", "Alat buatan '$n' tidak ditemukan.")
        return "Tidak ada alat buatan bernama '$n'. Alat bawaan tidak bisa dihapus."
    }
    saveCustomTools(existing.filter { it.name != n })
    logApproved("perintah", "delete_tool $n", args.optString("deskripsi"), Risk.WRITE, ApprovalMode.AUTO_MINIMAL, true, "alat dihapus", decision = "otomatis")
    card.finish(true, "", "Alat $n dihapus.")
    return "Alat $n dihapus."
}

/** Jalankan alat buatan lewat run_command agar semua pengaman jalur itu berlaku. */
internal fun MainActivity.runCustomTool(t: CustomTool, args: JSONObject, card: MainActivity.StepCard): String =
    runTool("run_command", JSONObject().put("deskripsi", args.optString("deskripsi")).put("command", CustomTools.command(t, args)), card)
