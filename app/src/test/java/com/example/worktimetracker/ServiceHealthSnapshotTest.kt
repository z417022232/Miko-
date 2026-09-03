package com.example.worktimetracker

import com.example.worktimetracker.location.recovery.HealthAction
import com.example.worktimetracker.location.recovery.HealthNotificationGate
import com.example.worktimetracker.location.recovery.ServiceHealthPolicy
import com.example.worktimetracker.location.recovery.ServiceHealthSnapshot
import com.example.worktimetracker.location.recovery.SourceHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceHealthSnapshotTest {
    private val now = 10_000_000L

    private fun source(name: String, lastCallback: Long, lastSuccess: Long = lastCallback,
        registered: Boolean = true, recoveryCount: Int = 0, failure: String? = null) =
        name to SourceHealth(lastCallback, lastSuccess, registered, recoveryCount, failure)

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

    @Test fun freshHeartbeatWithStaleGnssReturnsReregisterGnss() {
        val snapshot = ServiceHealthSnapshot(now-2*60_000L, now-2*60_000L, now-2*60_000L, true,
            sourceHealth = mapOf(source("gnss", now-40*60_000L)))
        assertEquals(HealthAction.REREGISTER_GNSS, ServiceHealthPolicy.evaluate(snapshot, now))
    }

    @Test fun freshGnssWithBluetoothPermissionFailureIsAuxiliaryDegradedNotServiceFailure() {
        val snapshot = ServiceHealthSnapshot(now-2*60_000L, now-2*60_000L, now-2*60_000L, true,
            sourceHealth = mapOf(
                source("gnss", now-2*60_000L),
                source("bluetooth", 0L, 0L, true, 0, "PERMISSION")))
        assertEquals(HealthAction.AUXILIARY_DEGRADED, ServiceHealthPolicy.evaluate(snapshot, now))
    }

    @Test fun motionSourceNotRegisteredRequiresReregisterMotion() {
        val snapshot = ServiceHealthSnapshot(now-2*60_000L, now-2*60_000L, now-2*60_000L, true,
            sourceHealth = mapOf(
                source("gnss", now-2*60_000L),
                "motion" to SourceHealth(0L, 0L, false, 0)))
        assertEquals(HealthAction.REREGISTER_MOTION, ServiceHealthPolicy.evaluate(snapshot, now))
    }

    @Test fun auxiliaryFailureDoesNotTriggerProviderUnavailable() {
        val snapshot = ServiceHealthSnapshot(now-2*60_000L, now-2*60_000L, now-2*60_000L, true,
            sourceHealth = mapOf(
                source("gnss", now-2*60_000L),
                source("wifi", 0L, 0L, true, 0, "SECURITY")))
        assertEquals(HealthAction.AUXILIARY_DEGRADED, ServiceHealthPolicy.evaluate(snapshot, now))
    }

    @Test fun sameFailureNotifiesOnlyOncePerSixtyMinutes() {
        val gate = HealthNotificationGate()
        assertTrue(gate.shouldNotify("gnss-stale", now))
        assertFalse(gate.shouldNotify("gnss-stale", now + 30 * 60_000L))
        assertTrue(gate.shouldNotify("gnss-stale", now + 61 * 60_000L))
        assertTrue(gate.shouldNotify("bluetooth-permission", now + 30 * 60_000L))
    }
}
