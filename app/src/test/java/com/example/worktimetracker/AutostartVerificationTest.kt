package com.example.worktimetracker

import com.example.worktimetracker.location.permission.AutostartState
import com.example.worktimetracker.location.permission.AutostartVerificationPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class AutostartVerificationTest {
    @Test
    fun userConfirmationUpgradesUnknownToConfirmed() {
        assertEquals(
            AutostartState.USER_CONFIRMED,
            AutostartVerificationPolicy.userConfirmed(AutostartState.UNKNOWN, true)
        )
    }

    @Test
    fun cancelKeepsCurrentState() {
        assertEquals(
            AutostartState.UNKNOWN,
            AutostartVerificationPolicy.userConfirmed(AutostartState.UNKNOWN, false)
        )
    }

    @Test
    fun successfulBootRecoveryUpgradesConfirmedToVerified() {
        assertEquals(
            AutostartState.BOOT_VERIFIED,
            AutostartVerificationPolicy.bootRecovery(AutostartState.USER_CONFIRMED, true)
        )
    }

    @Test
    fun failedBootRecoveryDoesNotClaimVerified() {
        assertEquals(
            AutostartState.USER_CONFIRMED,
            AutostartVerificationPolicy.bootRecovery(AutostartState.USER_CONFIRMED, false)
        )
    }
}
