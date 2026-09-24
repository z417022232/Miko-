package com.example.worktimetracker

import com.example.worktimetracker.location.recovery.LocationSwitchRecoveryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSwitchRecoveryPolicyTest {

    @Test
    fun `only providers changed and mode changed are switch actions`() {
        assertTrue(LocationSwitchRecoveryPolicy.isSwitchAction("android.location.PROVIDERS_CHANGED"))
        assertTrue(LocationSwitchRecoveryPolicy.isSwitchAction("android.location.MODE_CHANGED"))
        assertFalse(LocationSwitchRecoveryPolicy.isSwitchAction("android.intent.action.BOOT_COMPLETED"))
        assertFalse(LocationSwitchRecoveryPolicy.isSwitchAction(""))
    }

    @Test
    fun `re-enabled switch with stale heartbeat kicks the watchdog`() {
        assertEquals(
            LocationSwitchRecoveryPolicy.Decision.KICK_ALARM_NOW,
            LocationSwitchRecoveryPolicy.evaluate(enabled = true, heartbeatAge = 9 * 60_000L, deadAfterMillis = 8 * 60_000L)
        )
    }

    @Test
    fun `fresh heartbeat means service is alive and only the state is recorded`() {
        assertEquals(
            LocationSwitchRecoveryPolicy.Decision.RECORD_ONLY,
            LocationSwitchRecoveryPolicy.evaluate(enabled = true, heartbeatAge = 5 * 60_000L, deadAfterMillis = 8 * 60_000L)
        )
    }

    @Test
    fun `switch off never kicks the service`() {
        assertEquals(
            LocationSwitchRecoveryPolicy.Decision.RECORD_ONLY,
            LocationSwitchRecoveryPolicy.evaluate(enabled = false, heartbeatAge = 60 * 60_000L, deadAfterMillis = 8 * 60_000L)
        )
    }
}
