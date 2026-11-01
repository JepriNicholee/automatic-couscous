package com.build.mythos

/**
 * Izin memanggil skrip alat di MYTHOS/reference/alat/ walau folder reference terkunci.
 * Hanya skrip yang dikenal, dengan argumen yang tidak menulis ke reference. Murni JVM (teruji).
 */
internal object ReferencePolicy {
    private val CHAIN = Regex("""[;&<>`\n]|\$\(""")
    private val SCRIPT = Regex("""^\s*(?:time\s+)?(?:bash|sh)\s+(\S*MYTHOS/reference/alat/([A-Za-z0-9._-]+\.sh))(?:\s+(.*))?$""", RegexOption.DOT_MATCHES_ALL)
    private val PIPE = Regex("""\s\|\s*(?:tail|head|wc|grep)\b""")
    private val BAD_AFTER_PIPE = Regex("""\b(?:tee|xargs|sh|bash|rm|mv|cp|chmod|dd|sed|awk|python|perl)\b""")
    private val KITA_SELF_ARGS = Regex("""^(?:audit|diff)\s+\S+(?:\s+[A-Za-z0-9_.]+)?$""")

    fun isAllowedAlatCall(cmd: String): Boolean {
        val c = cmd.trim()
        if (CHAIN.containsMatchIn(c)) return false
        val m = SCRIPT.matchEntire(c) ?: return false
        val name = m.groupValues[2]
        var args = m.groupValues[3].trim()
        // pisahkan pipa penyaring keluaran di akhir (| tail / head / wc / grep)
        val p = PIPE.find(args)
        if (p != null) {
            val rest = args.substring(p.range.first)
            if (rest.contains("MYTHOS/reference") || BAD_AFTER_PIPE.containsMatchIn(rest)) return false
            args = args.substring(0, p.range.first).trim()
        } else if (args.contains("|")) return false
        return when (name) {
            "kita-self.sh" -> KITA_SELF_ARGS.matches(args)          // hanya mode baca; dir boleh di reference
            "salin-ke-aktif.sh" -> !args.contains("/") && !args.contains("reference")
            "build-github.sh", "build-termux.sh" -> !args.contains("reference")
            else -> false
        }
    }
}
