package com.example.worktimetracker

import com.example.worktimetracker.location.recovery.SystemLocationNotifyGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemLocationNotifyGateTest {

    @Test fun `no sleep mode means always disturb regardless of session`() {
        assertTrue(SystemLocationNotifyGate.shouldDisturbUser(sleepModeActive = false, hasActiveWorkSession = true))
        assertTrue(SystemLocationNotifyGate.shouldDisturbUser(sleepModeActive = false, hasActiveWorkSession = false))
    }

    @Test fun `sleep mode with active work session must disturb`() {
        // 夜班：人醒着在工作，PEM 误判睡眠关定位——静默等于丢工时证据
        assertTrue(SystemLocationNotifyGate.shouldDisturbUser(sleepModeActive = true, hasActiveWorkSession = true))
    }

    @Test fun `sleep mode without work session stays silent`() {
        assertFalse(SystemLocationNotifyGate.shouldDisturbUser(sleepModeActive = true, hasActiveWorkSession = false))
    }
}
