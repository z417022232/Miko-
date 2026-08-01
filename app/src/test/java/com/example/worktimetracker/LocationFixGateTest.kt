package com.example.worktimetracker

import com.example.worktimetracker.location.service.LocationFixGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFixGateTest {
    private val gate = LocationFixGate(maxAgeMillis = 10 * 60_000L)

    @Test fun sameProviderFixIsAcceptedOnlyOnce() {
        assertTrue(gate.shouldAccept("gps", 1_000L, 2_000L))
        assertFalse(gate.shouldAccept("gps", 1_000L, 3_000L))
    }

    @Test fun staleLastKnownFixIsRejected() {
        assertFalse(gate.shouldAccept("network", 1_000L, 11 * 60_000L))
    }

    @Test fun gpsAndNetworkKeepIndependentCursors() {
        assertTrue(gate.shouldAccept("gps", 1_000L, 2_000L))
        assertTrue(gate.shouldAccept("network", 1_000L, 2_000L))
    }
}
