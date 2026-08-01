package com.example.worktimetracker

import com.example.worktimetracker.location.recovery.ServiceRecoveryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRecoveryPolicyTest {
    @Test fun recoveryCoversBootUnlockAndPackageUpgrade() {
        assertTrue(ServiceRecoveryPolicy.actions.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(ServiceRecoveryPolicy.actions.contains("android.intent.action.USER_UNLOCKED"))
        assertTrue(ServiceRecoveryPolicy.actions.contains("android.intent.action.MY_PACKAGE_REPLACED"))
    }

    @Test fun healthCheckUsesAndroidMinimumPeriodicInterval() {
        assertEquals(15L, ServiceRecoveryPolicy.healthCheckMinutes)
    }

    @Test fun locationServiceStartsOnlyWhenLocationPermissionExists() {
        assertTrue(ServiceRecoveryPolicy.canStartLocationService(true, false))
        assertTrue(ServiceRecoveryPolicy.canStartLocationService(false, true))
        assertTrue(!ServiceRecoveryPolicy.canStartLocationService(false, false))
    }
}
