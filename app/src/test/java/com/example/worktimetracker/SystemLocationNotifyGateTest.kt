package com.example.worktimetracker

import com.example.worktimetracker.location.recovery.SystemLocationNotifyGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemLocationNotifyGateTest {

    @Test fun `no sleep mode means always notify regardless of session`() {
        assertTrue(SystemLocationNotifyGate.shouldNotifyLocationOff(sleepModeActive = false, hasActiveWorkSession = true))
        assertTrue(SystemLocationNotifyGate.shouldNotifyLocationOff(sleepModeActive = false, hasActiveWorkSession = false))
    }

    @Test fun `sleep mode with active work session must notify`() {
        // 夜班：人醒着在工作，PEM 误判睡眠关定位——静默等于丢工时证据
        assertTrue(SystemLocationNotifyGate.shouldNotifyLocationOff(sleepModeActive = true, hasActiveWorkSession = true))
    }

    @Test fun `sleep mode without work session stays silent`() {
        assertFalse(SystemLocationNotifyGate.shouldNotifyLocationOff(sleepModeActive = true, hasActiveWorkSession = false))
    }
}
