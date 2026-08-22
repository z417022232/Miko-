package com.example.worktimetracker

import com.example.worktimetracker.location.recovery.HealthAction
import com.example.worktimetracker.location.recovery.ServiceHealthPolicy
import com.example.worktimetracker.location.recovery.ServiceHealthSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceHealthSnapshotTest {
    private val now = 10_000_000L
    @Test fun liveServiceWithoutRecentFixIsProviderStale() {
        assertEquals(HealthAction.REREGISTER_LOCATION, ServiceHealthPolicy.evaluate(
            ServiceHealthSnapshot(now-2*60_000L, now-40*60_000L, now-40*60_000L, true), now))
    }
    @Test fun deadServiceRequiresUserRecoveryWhenBackgroundStartBlocked() {
        assertEquals(HealthAction.NOTIFY_TAP_TO_RECOVER, ServiceHealthPolicy.evaluate(
            ServiceHealthSnapshot(now-30*60_000L, now-40*60_000L, now-40*60_000L, true), now))
    }
    @Test fun unavailableProviderIsReportedSeparately() {
        assertEquals(HealthAction.PROVIDER_UNAVAILABLE, ServiceHealthPolicy.evaluate(
            ServiceHealthSnapshot(now-2*60_000L, now-40*60_000L, now-40*60_000L, false), now))
    }
}
