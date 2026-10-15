package com.kita.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalPolicyTest {
    @Test
    fun instalasiBaruTidakMelewatiConfirm() {
        // Default prefs autonomous=false dan allowAll=false harus masuk ke confirm().
        assertFalse(bypassesApproval(allowAll = false, autonomous = false))
    }

    @Test
    fun modeOtonomHanyaMelewatiConfirmSetelahOptIn() {
        assertTrue(bypassesApproval(allowAll = false, autonomous = true))
        assertTrue(bypassesApproval(allowAll = true, autonomous = false))
    }
}
