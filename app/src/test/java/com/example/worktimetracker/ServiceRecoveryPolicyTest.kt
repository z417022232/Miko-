package com.example.worktimetracker

import com.example.worktimetracker.location.recovery.ServiceRecoveryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRecoveryPolicyTest {
    @Test fun backgroundHealthCheckMustNotStartLocationForegroundService() {
        assertFalse(ServiceRecoveryPolicy.canStartLocationService(ServiceRecoveryPolicy.RecoveryTrigger.BACKGROUND_HEALTH_CHECK, true, true))
    }

    @Test fun userVisibleStartMayStartLocationForegroundServiceWithLocationPermission() {
        assertTrue(ServiceRecoveryPolicy.canStartLocationService(ServiceRecoveryPolicy.RecoveryTrigger.USER_VISIBLE, true, false))
    }

    @Test fun bootStartRequiresBackgroundLocationPermission() {
        assertFalse(ServiceRecoveryPolicy.canStartLocationService(ServiceRecoveryPolicy.RecoveryTrigger.BOOT, true, false, false))
        assertTrue(ServiceRecoveryPolicy.canStartLocationService(ServiceRecoveryPolicy.RecoveryTrigger.BOOT, true, false, true))
    }

    @Test fun geofenceTransitionRequiresBackgroundLocationPermission() {
        assertFalse(ServiceRecoveryPolicy.canStartLocationService(ServiceRecoveryPolicy.RecoveryTrigger.GEOFENCE, true, false, false))
        assertTrue(ServiceRecoveryPolicy.canStartLocationService(ServiceRecoveryPolicy.RecoveryTrigger.GEOFENCE, true, false, true))
    }

    @Test fun bootVerificationRequiresEveryRecoveryPart() {
        assertTrue(ServiceRecoveryPolicy.bootVerified(true, true, true))
        assertFalse(ServiceRecoveryPolicy.bootVerified(true, false, true))
        assertFalse(ServiceRecoveryPolicy.bootVerified(false, true, true))
        assertFalse(ServiceRecoveryPolicy.bootVerified(true, true, false))
    }
}
