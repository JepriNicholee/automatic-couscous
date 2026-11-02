package com.build.mythos

/** Validasi dan resolusi path workspace. Murni JVM agar aturan yang sama bisa diuji tanpa Android/Termux. */
internal object WorkspacePaths {
    /**
     * Normalisasi input pengaturan tanpa mengakses filesystem.
     * Null berarti input berbahaya/ambigu; string kosong dan "~" berarti HOME Termux.
     */
    fun normalizeSetting(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value == "~") return value
        if (value.any { it == '\u0000' || Character.isISOControl(it) }) return null
        if (value.startsWith("~") && !value.startsWith("~/")) return null

        val prefix = when {
            value.startsWith("~/") -> "~/"
            value.startsWith("/") -> "/"
            else -> ""
        }
        val body = value.removePrefix(prefix)
        val parts = body.split('/')
        if (parts.any { it == ".." }) return null
        val clean = parts.filter { it.isNotEmpty() && it != "." }.joinToString("/")
        return when {
            prefix == "~/" -> if (clean.isEmpty()) "~" else "~/$clean"
            prefix == "/" -> if (clean.isEmpty()) "/" else "/$clean"
            else -> clean
        }
    }

    /** Ubah preferensi menjadi path absolut yang konsisten. Preferensi lama tidak valid kembali ke ~/MYTHOS. */
    fun resolve(raw: String, home: String): String {
        val value = normalizeSetting(raw) ?: "MYTHOS"
        return when {
            value.isEmpty() || value == "~" -> collapsePath(home)
            value.startsWith("~/") -> collapsePath(home.trimEnd('/') + "/" + value.removePrefix("~/"))
            value.startsWith("/") -> collapsePath(value)
            else -> collapsePath(home.trimEnd('/') + "/" + value)
        }
    }

    /** Tolak root filesystem dan area internal paket Termux; root HOME tetap boleh dipilih secara eksplisit. */
    fun validationError(raw: String, home: String): String? {
        val normalized = normalizeSetting(raw)
            ?: return "Path tidak valid. Jangan gunakan '..', karakter kontrol, atau awalan '~nama'."
        val resolved = resolve(normalized, home)
        if (resolved == "/") return "Root '/' tidak boleh dijadikan folder kerja. Pilih folder khusus."

        val reference = collapsePath(home.trimEnd('/') + "/MYTHOS/reference")
        if (isWithin(resolved, reference)) {
            return "Folder kerja tidak boleh berada di MYTHOS/reference karena direktori itu dilindungi sebagai sumber hanya-baca."
        }

        val termuxUsr = collapsePath(home.substringBeforeLast("/home", home).trimEnd('/') + "/usr")
        if (isWithin(resolved, termuxUsr)) {
            return "Folder kerja tidak boleh berada di direktori sistem Termux (/usr). Pilih folder data pengguna."
        }
        return null
    }


    /**
     * Read-only workspace probe. A normal Git workspace is allowed; only a checkout whose remote
     * identifies the protected MYTHOS source repository is rejected.
     */
    fun gitRootProbeCommand(workspaceRoot: String, quote: (String) -> String): String =
        "target=\$(realpath -m -- ${quote(workspaceRoot)}) || { echo MYTHOS_WORKDIR_CHECK_FAILED; exit 90; }; " +
            "probe=\$target; found_git=0; protected_source=0; while [ -n \"\$probe\" ] && [ \"\$probe\" != / ]; do " +
            "if [ -d \"\$probe/.git\" ] || [ -f \"\$probe/.git\" ]; then found_git=1; " +
            "remotes=\$(git -C \"\$probe\" remote -v 2>/dev/null || true); " +
            "if printf '%s\\n' \"\$remotes\" | grep -Eqi 'github\\.com[:/]JepriNicholee/automatic-couscous(\\.git)?([[:space:]]|$)'; then protected_source=1; fi; fi; " +
            "probe=\$(dirname \"\$probe\"); done; " +
            "if [ \"\$protected_source\" = 1 ]; then echo MYTHOS_WORKDIR_PROTECTED_SOURCE; " +
            "elif [ \"\$found_git\" = 1 ]; then echo MYTHOS_WORKDIR_IN_GIT_REPO; " +
            "else echo MYTHOS_WORKDIR_NOT_IN_GIT_REPO; fi"


    /** Skrip pemeriksaan Termux: semua target di-resolve melalui realpath sebelum operasi tulis. */
    fun referenceGuardCommand(paths: List<String>, referenceRoot: String, quote: (String) -> String): String {
        val checks = paths.mapIndexed { index, path ->
            "target$index=\$(realpath -m -- ${quote(path)}) || { echo MYTHOS_PATH_GUARD_ERROR; exit 91; }; " +
                "case \"\$target$index\" in \"\$reference_real\"|\"\$reference_real\"/*) echo MYTHOS_REFERENCE_BLOCKED; exit 92;; esac"
        }.joinToString("\n")
        return """
            command -v realpath >/dev/null 2>&1 || { echo MYTHOS_PATH_GUARD_UNAVAILABLE; exit 90; }
            reference_real=${'$'}(realpath -m -- ${quote(referenceRoot)}) || { echo MYTHOS_PATH_GUARD_ERROR; exit 91; }
            $checks
            echo MYTHOS_REFERENCE_PATH_OK
        """.trimIndent()
    }

    fun isWithin(path: String, root: String): Boolean {
        val p = collapsePath(path).trimEnd('/').ifEmpty { "/" }
        val r = collapsePath(root).trimEnd('/').ifEmpty { "/" }
        if (p == r) return true
        return if (r == "/") p.startsWith("/") else p.startsWith("$r/")
    }
}
