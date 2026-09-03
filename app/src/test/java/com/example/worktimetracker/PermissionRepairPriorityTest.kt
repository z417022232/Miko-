package com.example.worktimetracker

import com.example.worktimetracker.location.permission.PermissionItem
import com.example.worktimetracker.location.permission.PermissionRepairPriority
import com.example.worktimetracker.location.permission.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRepairPriorityTest {
    private fun allGranted() = PermissionStatus(
        fineLocation = true, backgroundLocation = true,
        nearbyDevices = true, activityRecognition = true,
        notifications = true, batteryUnrestricted = true
    )

    @Test
    fun `repair next follows fixed permission order`() {
        val status = PermissionStatus(fineLocation = true, backgroundLocation = false,
            nearbyDevices = false, activityRecognition = false, notifications = false, batteryUnrestricted = false)
        assertEquals(PermissionItem.BACKGROUND_LOCATION, PermissionRepairPriority.next(status))
    }

    @Test
    fun `nearby devices comes after background location and before activity recognition`() {
        val status = PermissionStatus(fineLocation = true, backgroundLocation = true,
            nearbyDevices = false, activityRecognition = false, notifications = false, batteryUnrestricted = false)
        assertEquals(PermissionItem.NEARBY_DEVICES, PermissionRepairPriority.next(status))
    }

    @Test
    fun `activity recognition comes before notifications`() {
        val status = PermissionStatus(fineLocation = true, backgroundLocation = true,
            nearbyDevices = true, activityRecognition = false, notifications = false, batteryUnrestricted = false)
        assertEquals(PermissionItem.ACTIVITY_RECOGNITION, PermissionRepairPriority.next(status))
    }

    @Test
    fun `notifications comes before battery restriction`() {
        val status = PermissionStatus(fineLocation = true, backgroundLocation = true,
            nearbyDevices = true, activityRecognition = true, notifications = false, batteryUnrestricted = false)
        assertEquals(PermissionItem.NOTIFICATIONS, PermissionRepairPriority.next(status))
    }

    @Test
    fun `all detectable permissions complete returns vivo autostart`() {
        assertEquals(PermissionItem.VIVO_AUTOSTART, PermissionRepairPriority.next(allGranted()))
    }

    @Test
    fun `ready requires nearby devices and activity recognition`() {
        assertTrue(allGranted().ready)
        assertFalse(allGranted().copy(nearbyDevices = false).ready)
        assertFalse(allGranted().copy(activityRecognition = false).ready)
        assertFalse(allGranted().copy(backgroundLocation = false).ready)
    }
}
