package com.build.mythos

import org.junit.Assert.*
import org.junit.Test

class ReferencePolicyTest {
    private val home = "/data/data/com.termux/files/home"
    private val reference = "$home/MYTHOS/reference"
    private val workspace = "$home/MYTHOS"

    @Test fun detectsAbsoluteAndWorkspaceRelativeReferencePaths() {
        assertTrue(ReferencePolicy.mentionsProtectedReference("rm -rf '$reference/source'", reference, workspace))
        assertTrue(ReferencePolicy.mentionsProtectedReference("cp reference/a.kt projects/aktif/a.kt", reference, workspace))
        assertFalse(ReferencePolicy.mentionsProtectedReference("echo reference > notes.txt", reference, "/sdcard/work"))
    }

    @Test fun copyOutRequiresDestinationOutsideReference() {
        assertTrue(ReferencePolicy.isSafeCopyOut("cp -r -- reference/source projects/aktif/source", reference, workspace))
        assertEquals("$workspace/projects/aktif/source", ReferencePolicy.safeCopyOutDestination("cp -r -- reference/source projects/aktif/source", reference, workspace))
        assertFalse(ReferencePolicy.isSafeCopyOut("cp -r reference/source reference/backup", reference, workspace))
        assertFalse(ReferencePolicy.isSafeCopyOut("cp -r reference/source ../MYTHOS/reference/backup", reference, workspace))
        assertFalse(ReferencePolicy.isSafeCopyOut("cp reference/* projects/aktif", reference, workspace))
        assertFalse(ReferencePolicy.isSafeCopyOut("cp reference/a projects/aktif/a && rm -rf x", reference, workspace))
    }

    @Test fun officialScriptMustResolveInsideExactAllowlistDirectory() {
        assertTrue(ReferencePolicy.isAllowedAlatCall("bash reference/alat/kita-self.sh audit projects/aktif/demo", reference, workspace))
        assertFalse(ReferencePolicy.isAllowedAlatCall("bash /tmp/MYTHOS/reference/alat/kita-self.sh audit projects/aktif/demo", reference, workspace))
        assertFalse(ReferencePolicy.isAllowedAlatCall("bash reference/alat/unknown.sh audit projects/aktif/demo", reference, workspace))
        assertFalse(ReferencePolicy.isAllowedAlatCall("bash reference/alat/kita-self.sh audit projects/aktif/demo; touch x", reference, workspace))
    }
}
