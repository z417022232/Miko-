package com.example.worktimetracker

import com.example.worktimetracker.location.recovery.SystemLocationNotifyGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemLocationNotifyGateTest {

    @Test fun `sleep mode active means the nightly location off is expected and silent`() {
        assertFalse(SystemLocationNotifyGate.shouldNotifyLocationOff(sleepModeActive = true))
    }

    @Test fun `any disable outside vivo sleep mode still notifies`() {
        assertTrue(SystemLocationNotifyGate.shouldNotifyLocationOff(sleepModeActive = false))
    }

    @Test fun `unreadable sleep flag falls back to notifying`() {
        // VivoSleepModeReader 读取失败按未激活处理：宁可多通知，不静默
        assertTrue(SystemLocationNotifyGate.shouldNotifyLocationOff(sleepModeActive = false))
    }
}
