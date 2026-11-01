package com.build.mythos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteTrackerTest {
    @Test
    fun penulisanPertamaTidakDihitungSebagaiPenimpaan() {
        val t = WriteTracker()
        assertEquals(0, t.record("service.py"))
        assertEquals(1, t.record("service.py"))
        assertEquals(2, t.record("./service.py"))
    }

    @Test
    fun fileBerbedaDihitungTerpisah() {
        val t = WriteTracker()
        t.record("a.py"); t.record("a.py")
        assertEquals(0, t.record("b.py"))
    }

    @Test
    fun polaBolakBalikMencapaiBatasPeringatanDanBerhenti() {
        val t = WriteTracker()
        var last = 0
        repeat(WARN_REWRITE_AT + 1) { last = t.record("cli.py") }
        assertEquals(WARN_REWRITE_AT, last)
        repeat(STOP_REWRITE_AT - WARN_REWRITE_AT) { last = t.record("cli.py") }
        assertTrue(last >= STOP_REWRITE_AT)
    }

    @Test
    fun tesBerhasilMengosongkanHitungan() {
        val t = WriteTracker()
        repeat(4) { t.record("test_all.py") }
        t.noteVerified()
        assertEquals(0, t.record("test_all.py"))
    }

    @Test
    fun pathKosongDiabaikan() {
        assertEquals(0, WriteTracker().record("  "))
    }

    @Test
    fun unittestDanPytestDikenaliSebagaiTes() {
        assertEquals(VerificationKind.TESTS, verificationKind("run_command", "python -m unittest -v"))
        assertEquals(VerificationKind.TESTS, verificationKind("run_command", "pytest -q"))
        assertEquals(VerificationKind.TESTS, verificationKind("run_command", "python test_all.py"))
    }

    @Test
    fun peringatanMenyebutFileDanJumlah() {
        val w = rewriteWarning("service.py", 3)
        assertTrue(w.contains("service.py") && w.contains("3 kali"))
    }

    @Test
    fun perintahBacaYangMenyebutTesTidakMengosongkanHitungan() {
        assertTrue(!clearsRewriteCount(verificationKind("run_command", "cat test_all.py"), "cat test_all.py"))
        assertTrue(!clearsRewriteCount(verificationKind("run_command", "grep -r check src"), "grep -r check src"))
        assertTrue(clearsRewriteCount(verificationKind("run_command", "python test_all.py"), "python test_all.py"))
        assertTrue(clearsRewriteCount(verificationKind("run_command", "pytest -q"), "pytest -q"))
    }

    @Test
    fun responsKosongDianggapSementaraDanLayakPindahModel() {
        assertTrue(isFallbackWorthy(EmptyResponseException("kosong")))
    }
}
