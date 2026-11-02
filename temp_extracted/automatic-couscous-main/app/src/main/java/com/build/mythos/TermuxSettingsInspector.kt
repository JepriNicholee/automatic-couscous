package com.build.mythos

/** Small, bounded, read-only RUN_COMMAND probes used by the Termux settings status screen. */
internal object TermuxSettingsInspector {
    const val RUN_COMMAND_TIMEOUT_SEC = 8L
    const val COMMAND_TIMEOUT_SEC = 3

    private data class Tool(val name: String, val label: String, val versionCommand: String?)

    private val tools = listOf(
        Tool("git", "Git", "git --version"),
        Tool("node", "Node.js", "node --version"),
        Tool("npm", "npm", "npm --version"),
        Tool("python3", "Python 3", "python3 --version"),
        Tool("java", "Java", "java -version"),
        Tool("proot-distro", "proot-distro", null),
        Tool("gemini", "Gemini CLI", "gemini --version"),
        Tool("codex", "Codex CLI", "codex --version"),
        Tool("claude", "Claude CLI", "claude --version")
    )

    /** Each probe gets its own RUN_COMMAND deadline; version commands also have a Termux-side timeout. */
    fun inspect(
        bridgeCommand: String,
        configCommand: String,
        run: (command: String, timeoutSec: Long) -> TermuxCommandResult
    ): String {
        val ready = execute(run, "printf 'MYTHOS_TERMUX_READY\\n'")
        if (!ready.succeeded || !ready.stdout.contains("MYTHOS_TERMUX_READY")) {
            return buildString {
                appendLine("Termux belum tersedia atau tidak merespons.")
                appendLine("Pemeriksaan koneksi: ${describeFailure(ready, RUN_COMMAND_TIMEOUT_SEC)}")
                appendLine("stdout: ${streamState(ready.stdout)}; stderr: ${streamState(ready.stderr)}")
                append("Pemeriksaan lain dilewati karena RUN_COMMAND belum siap.")
            }
        }

        val bridge = execute(run, bridgeCommand)
        val config = execute(run, configCommand)
        val gh = execute(run, "command -v gh >/dev/null 2>&1")
        val timeoutTool = execute(run, "command -v timeout >/dev/null 2>&1")

        val output = StringBuilder("Koneksi Termux aktif.\n")
        output.append("Jembatan MYTHOS: ").append(readOnlyValue(bridge, "terpasang", "belum terpasang")).append('\n')
        output.append("Berkas konfigurasi: ").append(readOnlyValue(config, "ada", "belum ada")).append('\n')

        if (gh.exitCode == 0 && gh.transportError == null && !gh.timedOut) {
            val auth = execute(run, bounded("gh auth status"))
            output.append("Status autentikasi GitHub: ").append(commandStatus(auth, "terhubung", "tidak terhubung"))
                .append("; stdout: ").append(streamState(auth.stdout)).append("; stderr: ").append(streamState(auth.stderr)).append('\n')
        } else {
            val ghState = when {
                gh.timedOut || gh.transportError != null -> "tidak dapat diperiksa (${describeFailure(gh, RUN_COMMAND_TIMEOUT_SEC)})"
                else -> "gh belum tersedia (exit=${gh.exitCode ?: "tidak diketahui"})"
            }
            output.append("Status autentikasi GitHub: ").append(ghState)
                .append("; gh auth tidak dijalankan karena CLI tidak siap. stdout: ").append(streamState(gh.stdout))
                .append("; stderr: ").append(streamState(gh.stderr)).append('\n')
        }

        val available = LinkedHashMap<String, TermuxCommandResult>()
        for (tool in tools) {
            available[tool.name] = execute(run, "command -v ${tool.name} >/dev/null 2>&1")
        }

        val timeoutAvailable = timeoutTool.exitCode == 0 && timeoutTool.transportError == null && !timeoutTool.timedOut
        for (tool in tools) {
            val availability = available[tool.name] ?: continue
            output.append("• ${tool.label}: ")
            when {
                availability.timedOut || availability.transportError != null -> {
                    output.append("pemeriksaan gagal (${describeFailure(availability, RUN_COMMAND_TIMEOUT_SEC)})")
                }
                availability.exitCode != 0 -> output.append("tidak tersedia")
                tool.versionCommand == null -> output.append("tersedia")
                !timeoutAvailable -> output.append("tersedia; versi tidak diperiksa karena timeout tidak tersedia")
                else -> {
                    val result = execute(run, bounded(tool.versionCommand))
                    output.append("tersedia; ").append(versionStatus(result))
                        .append(" (stdout: ").append(streamState(result.stdout))
                        .append("; stderr: ").append(streamState(result.stderr)).append(')')
                }
            }
            output.append('\n')
        }
        return output.toString().trim()
    }

    private fun execute(
        run: (String, Long) -> TermuxCommandResult,
        command: String
    ): TermuxCommandResult = try {
        run(command, RUN_COMMAND_TIMEOUT_SEC)
    } catch (e: Exception) {
        TermuxCommandResult(transportError = e.javaClass.simpleName)
    }

    private fun bounded(command: String): String =
        "timeout -k 1s ${COMMAND_TIMEOUT_SEC}s $command </dev/null"

    private fun readOnlyValue(result: TermuxCommandResult, success: String, missing: String): String = when {
        result.timedOut || result.transportError != null -> "tidak dapat diperiksa (${describeFailure(result, RUN_COMMAND_TIMEOUT_SEC)})"
        result.exitCode == 0 && result.stdout.trim() in setOf("installed", "present") -> success
        result.exitCode == 0 && result.stdout.trim() in setOf("not_installed", "absent") -> missing
        result.exitCode == 0 -> "hasil tidak dikenal"
        else -> "pemeriksaan gagal (exit=${result.exitCode ?: "tidak diketahui"}; stdout: ${streamState(result.stdout)}; stderr: ${streamState(result.stderr)})"
    }

    private fun commandStatus(result: TermuxCommandResult, success: String, failure: String): String = when {
        result.timedOut || result.transportError != null -> "tidak dapat dipastikan (${describeFailure(result, RUN_COMMAND_TIMEOUT_SEC)})"
        result.exitCode == 0 -> success
        else -> "$failure (exit=${result.exitCode ?: "tidak diketahui"})"
    }

    private fun versionStatus(result: TermuxCommandResult): String = when {
        result.timedOut -> "timeout RUN_COMMAND (${RUN_COMMAND_TIMEOUT_SEC} dtk)"
        result.transportError != null -> "RUN_COMMAND gagal"
        result.exitCode == 124 || result.exitCode == 137 -> "perintah melewati batas ${COMMAND_TIMEOUT_SEC} dtk (exit=${result.exitCode})"
        result.exitCode != 0 -> "gagal (exit=${result.exitCode ?: "tidak diketahui"})"
        else -> "versi berhasil diperiksa${safeVersion(result)?.let { ": $it" }.orEmpty()}"
    }

    private fun safeVersion(result: TermuxCommandResult): String? {
        val pattern = Regex("(?<![A-Za-z0-9])v?\\d+(?:\\.\\d+){1,3}(?:[-+][A-Za-z0-9.-]+)?")
        return sequenceOf(result.stdout, result.stderr)
            .flatMap { it.lineSequence() }
            .mapNotNull { pattern.find(it)?.value }
            .firstOrNull()
    }

    private fun describeFailure(result: TermuxCommandResult, timeoutSec: Long): String = when {
        result.timedOut -> "timeout RUN_COMMAND (${timeoutSec} dtk)"
        result.transportError != null -> "RUN_COMMAND gagal"
        else -> "exit=${result.exitCode ?: "tidak diketahui"}"
    }

    private fun streamState(value: String): String = if (value.isBlank()) "kosong" else "ada"
}
