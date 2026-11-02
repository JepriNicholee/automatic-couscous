package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Uji persistensi sesi seperti Tes 5B.3: dua bash TERPISAH harus berbagi variabel export dan folder kerja. */
class SessionShellTest {
    private fun bash(script: String): Pair<Int, String> {
        val p = ProcessBuilder("bash", "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS))
        return p.exitValue() to out.trim()
    }

    private fun tmpDir(): String = File.createTempFile("mythos", "sess").let { it.delete(); it.mkdirs(); it.absolutePath }

    @Test
    fun idValidasi() {
        assertEquals("abc_1-X", SessionShell.cleanId(" abc_1-X "))
        assertNull(SessionShell.cleanId("a b"))
        assertNull(SessionShell.cleanId("../x"))
        assertNull(SessionShell.cleanId("x".repeat(33)))
        assertFalse(SessionShell.isBadId(""))
        assertTrue(SessionShell.isBadId("a;rm"))
    }

    @Test
    fun variabelDanFolderBertahanAntarProses() {
        val d = tmpDir()
        val work = tmpDir()
        val a = bash("cd $work; " + SessionShell.wrap("export MYTHOS_SESSION_PROBE=ACTIVE; mkdir -p s; cd s; echo \$\$", "t1", d))
        val b = bash("cd $work; " + SessionShell.wrap("echo \${MYTHOS_SESSION_PROBE:-UNSET}; pwd; echo \$\$", "t1", d))
        val lines = b.second.lines()
        assertEquals("ACTIVE", lines[0])
        assertTrue(lines[1].endsWith("/s"))
        assertTrue(a.second.trim() != lines[2]) // PID berbeda: proses memang baru
    }

    @Test
    fun sesiBerbedaTerpisah() {
        val d = tmpDir()
        bash(SessionShell.wrap("export V=1", "a", d))
        assertEquals("UNSET", bash(SessionShell.wrap("echo \${V:-UNSET}", "b", d)).second)
    }

    @Test
    fun kodeKeluarDipertahankanTermasukExit() {
        val d = tmpDir()
        assertEquals(7, bash(SessionShell.wrap("export Z=1; exit 7", "c", d)).first)
        assertEquals("1", bash(SessionShell.wrap("echo \$Z", "c", d)).second)
        assertEquals(1, bash(SessionShell.wrap("false", "c", d)).first)
    }
}
