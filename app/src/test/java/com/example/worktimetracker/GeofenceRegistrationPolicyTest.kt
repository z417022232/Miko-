package com.example.worktimetracker

import com.example.worktimetracker.location.recovery.GeofenceRegistrationPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class GeofenceRegistrationPolicyTest {
    @Test fun removedOrDisabledSitesAreReturnedForCleanup() {
        assertEquals(setOf(11L, 33L), GeofenceRegistrationPolicy.staleSiteIds(setOf(11L, 22L, 33L), setOf(22L)))
    }
}
