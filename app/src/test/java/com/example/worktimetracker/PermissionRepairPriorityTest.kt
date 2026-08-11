package com.example.worktimetracker

import com.example.worktimetracker.location.permission.PermissionItem
import com.example.worktimetracker.location.permission.PermissionRepairPriority
import com.example.worktimetracker.location.permission.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionRepairPriorityTest {
    @Test
    fun `repair next follows fixed permission order`() {
        val status = PermissionStatus(fineLocation = true, backgroundLocation = false, notifications = false, batteryUnrestricted = false)
        assertEquals(PermissionItem.BACKGROUND_LOCATION, PermissionRepairPriority.next(status))
    }

    @Test
    fun `all detectable permissions complete returns vivo autostart`() {
        val status = PermissionStatus(true, true, true, true)
        assertEquals(PermissionItem.VIVO_AUTOSTART, PermissionRepairPriority.next(status))
    }
}
