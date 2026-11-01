package com.build.mythos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDeclarationsTest {
    @Test fun webToolsAreBuiltinAndDocumented() {
        for (n in listOf("cari_web", "baca_web")) {
            assertTrue("$n harus bawaan (tidak bisa ditimpa alat buatan)", n in CustomTools.BUILTIN)
            assertTrue("$n harus ada di ToolDocs.NAMES", n in ToolDocs.NAMES)
            assertTrue("$n harus ada di ToolDocs.SIGNATURES", ToolDocs.SIGNATURES.any { it.startsWith("$n(") })
        }
    }

    @Test fun tavilyToolIsGone() {
        assertFalse("web_search" in ToolDocs.NAMES)
        assertFalse("web_search" in CustomTools.BUILTIN)
    }

    @Test fun webToolsAreNotSentToLocalModels() {
        assertFalse("cari_web" in LOCAL_TOOL_NAMES)
        assertFalse("baca_web" in LOCAL_TOOL_NAMES)
    }
}
