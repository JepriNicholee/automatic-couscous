package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolCallsTest {
    private val known = setOf("run_command", "write_file")

    // Teks persis seperti di rekaman layar: tanda | dipisah spasi dan awalan function_ hilang.
    private val dsmlVideo = """
< | DSML |  calls>
< | DSML |  invoke name="LS">
< | DSML |  parameter name="path" string="true">.</ | DSML |  parameter>
< | DSML |  parameter name="depth" string="false">3</ | DSML |  parameter>
</ | DSML |  invoke>
</ | DSML |  calls>
""".trimIndent()

    @Test
    fun dsmlLsDariRekamanDipulihkanMenjadiRunCommand() {
        val (rest, calls) = recoverTextToolCalls(dsmlVideo, known)
        assertEquals("", rest)
        assertEquals(1, calls.size)
        assertEquals("run_command", calls[0].name)
        val cmd = calls[0].args.getString("command")
        assertTrue(cmd, cmd.contains("find '.' -maxdepth 3"))
        assertTrue(calls[0].args.getString("deskripsi").isNotBlank())
    }

    @Test
    fun dsmlTandaLebarPenuhDanBashDipetakan() {
        val t = "Saya cek dulu.\n<\uFF5CDSML\uFF5Cfunction_calls><\uFF5CDSML\uFF5Cinvoke name=\"Bash\">" +
            "<\uFF5CDSML\uFF5Cparameter name=\"command\" string=\"true\">ls -la</\uFF5CDSML\uFF5Cparameter>" +
            "</\uFF5CDSML\uFF5Cinvoke></\uFF5CDSML\uFF5Cfunction_calls>"
        val (rest, calls) = recoverTextToolCalls(t, known)
        assertEquals("Saya cek dulu.", rest)
        assertEquals("ls -la", calls.single().args.getString("command"))
    }

    @Test
    fun dsmlAlatTakDikenalTidakDiubah() {
        val t = "<\uFF5CDSML\uFF5Cinvoke name=\"Telepon\"><\uFF5CDSML\uFF5Cparameter name=\"x\">1</\uFF5CDSML\uFF5Cparameter></\uFF5CDSML\uFF5Cinvoke>"
        val (rest, calls) = recoverTextToolCalls(t, known)
        assertTrue(calls.isEmpty())
        assertEquals(t, rest)
    }

    @Test
    fun teksBiasaTidakTersentuh() {
        val (rest, calls) = recoverTextToolCalls("Halo, ada yang bisa dibantu?", known)
        assertTrue(calls.isEmpty())
        assertEquals("Halo, ada yang bisa dibantu?", rest)
    }
}
