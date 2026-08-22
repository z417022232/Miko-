package com.example.worktimetracker

import com.example.worktimetracker.location.service.LocationRegistrationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationRegistrationPolicyTest {
    @Test fun intervalChangeOverwritesWithoutRemovingListener() {
        val decision = LocationRegistrationPolicy.intervalChange(30 * 60_000L, 5 * 60_000L)
        assertTrue(decision.reconfigure)
        assertFalse(decision.removeExisting)
        assertEquals(0L, decision.delayMillis)
    }

    @Test fun relaxingIntervalIsDebounced() {
        val decision = LocationRegistrationPolicy.intervalChange(60_000L, 30 * 60_000L)
        assertTrue(decision.reconfigure)
        assertFalse(decision.removeExisting)
        assertEquals(30_000L, decision.delayMillis)
    }

    @Test fun sameIntervalDoesNothing() {
        assertFalse(LocationRegistrationPolicy.intervalChange(5 * 60_000L, 5 * 60_000L).reconfigure)
    }

    @Test fun openingUiDoesNotReconfigureRunningService() {
        assertEquals(LocationRegistrationPolicy.UserVisibleAction.NONE,
            LocationRegistrationPolicy.onUserVisible(serviceAlreadyCreated = true))
        assertEquals(LocationRegistrationPolicy.UserVisibleAction.START_SERVICE,
            LocationRegistrationPolicy.onUserVisible(serviceAlreadyCreated = false))
    }
}
