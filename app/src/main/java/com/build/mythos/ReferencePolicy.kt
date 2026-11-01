package com.build.mythos

/** Kebijakan konservatif untuk source resmi MYTHOS/reference. Tidak mengklaim dapat mengurai semua sintaks shell. */
internal object ReferencePolicy {
    private val SCRIPT_NAMES = setOf("kita-self.sh", "salin-ke-aktif.sh", "build-github.sh", "build-termux.sh")
    private val CHAIN_OR_EXPANSION = Regex("[;&<>`\\n]|\\$\\(|\\$[A-Za-z_{]")
    private val PIPE_FILTER = Regex("^\\s*\\|\\s*(tail|head|wc|grep)(?:\\s+[-A-Za-z0-9._/]+)*\\s*$")
    private val KITA_SELF_ARGS = Regex("^(?:audit|diff)\\s+\\S+(?:\\s+[A-Za-z0-9_.-]+)?$")

    /** Tokenizer kecil untuk command allowlist: quote dasar didukung, ekspansi/sintaks shell ditolak. */
    private fun words(input: String): List<String>? {
        if (CHAIN_OR_EXPANSION.containsMatchIn(input)) return null
        val out = ArrayList<String>()
        val word = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var active = false
        for (ch in input.trim()) {
            if (escaped) { word.append(ch); escaped = false; active = true; continue }
            if (quote == '\'' && ch != '\'') { word.append(ch); active = true; continue }
            if (quote == '"' && ch != '"' && ch != '\\') { word.append(ch); active = true; continue }
            when {
                ch == '\\' && quote != '\'' -> { escaped = true; active = true }
                quote == null && (ch == '\'' || ch == '"') -> { quote = ch; active = true }
                quote == ch -> quote = null
                quote == null && ch.isWhitespace() -> {
                    if (active) { out.add(word.toString()); word.setLength(0); active = false }
                }
                else -> { word.append(ch); active = true }
            }
        }
        if (escaped || quote != null) return null
        if (active) out.add(word.toString())
        return out
    }

    private fun resolveArg(path: String, cwd: String, home: String): String? {
        if (path.isBlank() || path.contains('*') || path.contains('?') || path.contains('[') || path.contains(']') || path.contains('$')) return null
        val expanded = when {
            path == "~" -> return null
            path.startsWith("~/") -> home.trimEnd('/') + "/" + path.removePrefix("~/")
            path.startsWith("~") -> return null
            path.startsWith("/") -> path
            else -> "$cwd/$path"
        }
        return collapsePath(expanded)
    }

    private fun inside(path: String, root: String): Boolean =
        path == root || path.startsWith(root.trimEnd('/') + "/")

    fun mentionsProtectedReference(command: String, referenceRoot: String, workingDirectory: String): Boolean {
        val ref = collapsePath(referenceRoot)
        val cwd = collapsePath(workingDirectory)
        if (command.contains(ref) || Regex("(?:^|[/\\s'\"])MYTHOS/reference(?:/|\\b)").containsMatchIn(command)) return true
        val home = ref.removeSuffix("/MYTHOS/reference")
        val tokens = words(command)
        if (tokens != null && tokens.any { token ->
                val path = resolveArg(token, cwd, home) ?: return@any false
                inside(path, ref)
            }) return true
        return cwd == ref.substringBeforeLast('/') && Regex("(?:^|\\s|['\"])reference(?:/|\\b)").containsMatchIn(command)
    }

    /** Tujuan cp yang secara leksikal menyalin satu sumber reference ke luar reference. */
    fun safeCopyOutDestination(command: String, referenceRoot: String, workingDirectory: String): String? {
        val args = words(command) ?: return null
        if (args.isEmpty() || args[0] != "cp") return null
        var i = 1
        while (i < args.size && args[i].startsWith("-") && args[i] != "--") {
            // Hanya opsi copy biasa. Opsi yang mengubah semantik target/multiple-source ditolak.
            val opt = args[i]
            if (opt.any { it !in "-rafpvnLPT" } || opt.contains('t') || opt.contains('T')) return null
            i++
        }
        if (i < args.size && args[i] == "--") i++
        if (args.size - i != 2) return null
        val home = referenceRoot.removeSuffix("/MYTHOS/reference")
        val source = resolveArg(args[i], workingDirectory, home) ?: return null
        val destination = resolveArg(args[i + 1], workingDirectory, home) ?: return null
        val ref = collapsePath(referenceRoot)
        return destination.takeIf { inside(source, ref) && !inside(it, ref) }
    }

    fun isSafeCopyOut(command: String, referenceRoot: String, workingDirectory: String): Boolean =
        safeCopyOutDestination(command, referenceRoot, workingDirectory) != null

    /** Allowlist terbatas untuk empat skrip resmi. Path skrip harus tepat di bawah root reference yang disahkan. */
    fun isAllowedAlatCall(command: String, referenceRoot: String, workingDirectory: String): Boolean {
        val trimmed = command.trim()
        val pipeAt = trimmed.indexOf('|')
        val scriptCommand = if (pipeAt >= 0) trimmed.substring(0, pipeAt).trim() else trimmed
        val suffix = if (pipeAt >= 0) trimmed.substring(pipeAt) else ""
        if (suffix.isNotEmpty() && !PIPE_FILTER.matches(suffix)) return false
        val args = words(scriptCommand) ?: return false
        var i = 0
        if (args.getOrNull(i) == "time") i++
        if (args.getOrNull(i) !in setOf("bash", "sh")) return false
        i++
        val script = args.getOrNull(i) ?: return false
        i++
        val home = referenceRoot.removeSuffix("/MYTHOS/reference")
        val resolvedScript = resolveArg(script, workingDirectory, home) ?: return false
        val ref = collapsePath(referenceRoot)
        val expectedDir = "$ref/alat"
        if (!inside(resolvedScript, expectedDir)) return false
        val name = resolvedScript.removePrefix("$expectedDir/")
        if (name !in SCRIPT_NAMES) return false
        val scriptArgs = args.drop(i)
        val joined = scriptArgs.joinToString(" ")
        return when (name) {
            "kita-self.sh" -> KITA_SELF_ARGS.matches(joined)
            "salin-ke-aktif.sh" -> scriptArgs.size == 1 &&
                scriptArgs[0].isNotBlank() && !scriptArgs[0].contains('/') && !scriptArgs[0].contains("reference")
            "build-github.sh", "build-termux.sh" -> scriptArgs.isNotEmpty() &&
                scriptArgs.none { it.contains("reference") || it.contains("..") }
            else -> false
        }
    }
}
