package com.example.worktimetracker

import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.location.service.LocationAccuracyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationAccuracyPolicyTest {
    @Test fun `high uses gps and network with ten metre movement`() {
        val plan = LocationAccuracyPolicy.plan(UserSettingsEntity.LOCATION_ACCURACY_HIGH)
        assertEquals(listOf("gps", "network"), plan.activeProviders)
        assertEquals(10f, plan.minDistanceMeters)
    }

    @Test fun `balanced uses gps and network with fifty metre movement`() {
        val plan = LocationAccuracyPolicy.plan(UserSettingsEntity.LOCATION_ACCURACY_BALANCED)
        assertEquals(listOf("gps", "network"), plan.activeProviders)
        assertEquals(50f, plan.minDistanceMeters)
    }

    @Test fun `power saving avoids gps and accepts wider movement`() {
        val plan = LocationAccuracyPolicy.plan(UserSettingsEntity.LOCATION_ACCURACY_POWER_SAVING)
        assertEquals(listOf("network"), plan.activeProviders)
        assertEquals(100f, plan.minDistanceMeters)
    }

    @Test fun `unknown mode safely falls back to balanced`() {
        assertEquals(LocationAccuracyPolicy.plan(UserSettingsEntity.LOCATION_ACCURACY_BALANCED), LocationAccuracyPolicy.plan("broken"))
    }

    @Test fun `mode change requires listener reconfiguration`() {
        assertTrue(LocationAccuracyPolicy.requiresReconfigure("BALANCED", "HIGH"))
        assertFalse(LocationAccuracyPolicy.requiresReconfigure("HIGH", "HIGH"))
    }
}
