package com.build.mythos

import org.junit.Assert.*
import org.junit.Test

class WorkspacePathsTest {
    private val home = "/data/data/com.termux/files/home"

    @Test fun defaultRelativePathResolvesUnderTermuxHome() {
        assertEquals("$home/MYTHOS", WorkspacePaths.resolve("MYTHOS", home))
        assertEquals("$home/projects/demo", WorkspacePaths.resolve("projects/demo", home))
    }

    @Test fun homeAndAbsolutePathsAreHandledExplicitly() {
        assertEquals(home, WorkspacePaths.resolve("~", home))
        assertEquals(home, WorkspacePaths.resolve("", home))
        assertEquals("/sdcard/Projects/demo", WorkspacePaths.resolve("/sdcard/Projects/demo/", home))
        assertEquals("$home/Projects/demo", WorkspacePaths.resolve("~/Projects/./demo", home))
    }

    @Test fun traversalAndAmbiguousTildeAreRejected() {
        assertNull(WorkspacePaths.normalizeSetting("../repo"))
        assertNull(WorkspacePaths.normalizeSetting("projects/../../repo"))
        assertNull(WorkspacePaths.normalizeSetting("~other/repo"))
        assertNull(WorkspacePaths.normalizeSetting("bad\u0000path"))
    }

    @Test fun rootAndProtectedReferenceCannotBeWorkspace() {
        assertNotNull(WorkspacePaths.validationError("/", home))
        assertNotNull(WorkspacePaths.validationError("MYTHOS/reference", home))
        assertNotNull(WorkspacePaths.validationError("~/MYTHOS/reference/source", home))
        assertNull(WorkspacePaths.validationError("MYTHOS", home))
        assertNull(WorkspacePaths.validationError("/sdcard/Projects", home))
        assertNotNull(WorkspacePaths.validationError("/data/data/com.termux/files/usr", home))
    }

    @Test fun gitRootProbeChecksBothDirectoryAndWorktreeMarker() {
        val script = WorkspacePaths.gitRootProbeCommand("$home/work repo") { "'" + it + "'" }
        assertTrue(script.contains("realpath -m -- '$home/work repo'"))
        assertTrue(script.contains("probe=\$target"))
        assertTrue(script.contains("[ -d \"\$probe/.git\" ]"))
        assertTrue(script.contains("[ -f \"\$probe/.git\" ]"))
        assertTrue(script.contains("MYTHOS_WORKDIR_IN_GIT_REPO"))
        assertTrue(script.contains("MYTHOS_WORKDIR_PROTECTED_SOURCE"))
        assertTrue(script.contains("MYTHOS_WORKDIR_NOT_IN_GIT_REPO"))
        assertTrue(script.contains("github\\.com[:/]JepriNicholee/automatic-couscous"))
    }

    @Test fun referenceGuardUsesCanonicalPathsAndFailsClosed() {
        val script = WorkspacePaths.referenceGuardCommand(
            listOf("$home/work/projects/aktif", "$home/work/attachments"),
            "$home/MYTHOS/reference"
        ) { "'" + it.replace("'", "'\\''") + "'" }
        assertTrue(script.contains("command -v realpath"))
        assertTrue(script.contains("reference_real=$(realpath -m -- '$home/MYTHOS/reference')"))
        assertTrue(script.contains("target0=$(realpath -m -- '$home/work/projects/aktif')"))
        assertTrue(script.contains("MYTHOS_REFERENCE_BLOCKED"))
        assertTrue(script.contains("MYTHOS_REFERENCE_PATH_OK"))
    }

    @Test fun containmentUsesPathBoundaries() {
        assertTrue(WorkspacePaths.isWithin("/h/MYTHOS/reference/a.kt", "/h/MYTHOS/reference"))
        assertTrue(WorkspacePaths.isWithin("/h/MYTHOS/reference", "/h/MYTHOS/reference"))
        assertFalse(WorkspacePaths.isWithin("/h/MYTHOS/reference-old/a.kt", "/h/MYTHOS/reference"))
    }
}
