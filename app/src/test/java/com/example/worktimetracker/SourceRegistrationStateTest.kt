package com.example.worktimetracker

import com.example.worktimetracker.location.service.SourceRegistrationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRegistrationStateTest {
    @Test fun sameConfigurationDoesNotRegisterAgain() {
        val state = SourceRegistrationState()
        assertTrue(state.begin("gps", 300_000L))
        assertFalse(state.begin("gps", 300_000L))
        assertTrue(state.begin("gps", 600_000L))
    }

    @Test fun providerRecoveryFirstFixIsBaselineOnly() {
        val state = SourceRegistrationState()
        state.providerRecovered("gps")
        assertFalse(state.mayEmitEvidence("gps"))
        assertTrue(state.mayEmitEvidence("gps"))
    }

    @Test fun invalidateForcesNextBeginToRegister() {
        val state = SourceRegistrationState()
        assertTrue(state.begin("gps", 300_000L))
        assertFalse(state.begin("gps", 300_000L))
        state.invalidate("gps")
        assertTrue(state.begin("gps", 300_000L))
    }

    @Test fun sourcesAreTrackedIndependently() {
        val state = SourceRegistrationState()
        assertTrue(state.begin("gps", 300_000L))
        assertTrue(state.begin("network", 300_000L))
        assertFalse(state.begin("gps", 300_000L))
        assertFalse(state.begin("network", 300_000L))
    }

    @Test fun lastCallbackIsTrackedPerSource() {
        val state = SourceRegistrationState()
        assertNull(state.lastCallback("gps"))
        state.recordCallback("gps", 1_000L)
        assertEquals(1_000L, state.lastCallback("gps"))
        assertNull(state.lastCallback("network"))
        state.recordCallback("network", 2_000L)
        assertEquals(2_000L, state.lastCallback("network"))
    }

    @Test fun mayEmitEvidenceWithoutRecoveryIsAlwaysTrue() {
        val state = SourceRegistrationState()
        assertTrue(state.mayEmitEvidence("gps"))
        assertTrue(state.mayEmitEvidence("gps"))
    }
}
