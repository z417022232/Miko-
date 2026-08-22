package com.example.worktimetracker

import com.example.worktimetracker.location.service.ProviderRecoveryGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRecoveryGateTest {
    @Test fun firstFixAfterProviderRecoveryIsBaselineOnly() {
        val gate = ProviderRecoveryGate()
        gate.providerEnabled("gps")
        assertFalse(gate.shouldProcess("gps"))
        assertTrue(gate.shouldProcess("gps"))
    }

    @Test fun ordinaryFixProcessesImmediately() {
        assertTrue(ProviderRecoveryGate().shouldProcess("network"))
    }
}
