package com.build.mythos

import org.junit.Assert.*
import org.junit.Test

class TermuxSettingsInspectorTest {
    private val bridge = "bridge-check"
    private val config = "config-check"

    @Test fun slowTermuxStillReturnsResultsForLaterIndependentChecks() {
        val calls = ArrayList<Pair<String, Long>>()
        val report = TermuxSettingsInspector.inspect(bridge, config) { command, timeout ->
            calls += command to timeout
            Thread.sleep(10)
            when {
                command.contains("MYTHOS_TERMUX_READY") -> ok("MYTHOS_TERMUX_READY\n")
                command == bridge -> ok("installed")
                command == config -> ok("present")
                command == "command -v gh >/dev/null 2>&1" -> ok()
                command == "command -v timeout >/dev/null 2>&1" -> ok()
                command.startsWith("command -v git") -> ok()
                command.startsWith("timeout -k 1s 3s gh auth status") ->
                    TermuxCommandResult(exitCode = 0, stdout = "authenticated", stderr = "")
                command.startsWith("timeout -k 1s 3s git --version") ->
                    TermuxCommandResult(exitCode = 0, stdout = "git version 2.45.0", stderr = "")
                else -> missing()
            }
        }

        assertTrue(report.contains("Koneksi Termux aktif"))
        assertTrue(report.contains("Status autentikasi GitHub: terhubung"))
        assertTrue(report.contains("Git: tersedia; versi berhasil diperiksa: 2.45.0"))
        assertTrue(report.contains("Claude CLI: tidak tersedia"))
        assertFalse(report.contains("authenticated"))
        assertTrue(calls.all { it.second == TermuxSettingsInspector.RUN_COMMAND_TIMEOUT_SEC })
    }

    @Test fun hungVersionCommandTimesOutIndividuallyAndRemainingChecksContinue() {
        val commands = ArrayList<String>()
        val report = TermuxSettingsInspector.inspect(bridge, config) { command, _ ->
            commands += command
            when {
                command.contains("MYTHOS_TERMUX_READY") -> ok("MYTHOS_TERMUX_READY\n")
                command == bridge -> ok("installed")
                command == config -> ok("present")
                command == "command -v gh >/dev/null 2>&1" -> missing()
                command == "command -v timeout >/dev/null 2>&1" -> ok()
                command.startsWith("command -v codex") || command.startsWith("command -v claude") -> ok()
                command.startsWith("timeout -k 1s 3s codex --version") ->
                    TermuxCommandResult(exitCode = 124, stdout = "partial", stderr = "terminated")
                command.startsWith("timeout -k 1s 3s claude --version") ->
                    TermuxCommandResult(exitCode = 0, stdout = "2.4.1", stderr = "warning")
                else -> missing()
            }
        }

        assertTrue(commands.any { it == "timeout -k 1s 3s codex --version </dev/null" })
        assertTrue(report.contains("Codex CLI: tersedia; perintah melewati batas 3 dtk (exit=124)"))
        assertTrue(report.contains("stdout: ada; stderr: ada"))
        assertTrue(report.contains("Claude CLI: tersedia; versi berhasil diperiksa: 2.4.1"))
        assertTrue(report.contains("stdout: ada; stderr: ada"))
    }

    @Test fun unavailableTermuxIsReportedWithoutRunningOtherProbes() {
        val commands = ArrayList<String>()
        val report = TermuxSettingsInspector.inspect(bridge, config) { command, _ ->
            commands += command
            TermuxCommandResult(transportError = "service unavailable")
        }

        assertTrue(report.contains("Termux belum tersedia atau tidak merespons"))
        assertTrue(report.contains("RUN_COMMAND gagal"))
        assertEquals(listOf("printf 'MYTHOS_TERMUX_READY\\n'"), commands)
    }

    private fun ok(stdout: String = "") = TermuxCommandResult(exitCode = 0, stdout = stdout)
    private fun missing() = TermuxCommandResult(exitCode = 1)
}
