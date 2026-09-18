package com.example.worktimetracker

import com.example.worktimetracker.location.recovery.SystemLocationStatePolicy
import com.example.worktimetracker.location.recovery.SystemLocationTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemLocationStateCheckerTest {
    @Test
    fun `android p and newer use the system wide location switch`() {
        assertFalse(SystemLocationStatePolicy.isEnabled(28, false, true, true))
        assertTrue(SystemLocationStatePolicy.isEnabled(28, true, false, false))
    }

    @Test
    fun `older android falls back to gps or network provider`() {
        assertTrue(SystemLocationStatePolicy.isEnabled(27, null, true, false))
        assertTrue(SystemLocationStatePolicy.isEnabled(27, null, false, true))
        assertFalse(SystemLocationStatePolicy.isEnabled(27, null, false, false))
    }

    @Test
    fun `disabled state is only recorded on transition`() {
        assertEquals(SystemLocationTransition.DISABLED, SystemLocationStatePolicy.transition(0, 0, false))
        assertEquals(SystemLocationTransition.NONE, SystemLocationStatePolicy.transition(100, 0, false))
    }

    @Test
    fun `recovery is only recorded after a disabled state`() {
        assertEquals(SystemLocationTransition.RECOVERED, SystemLocationStatePolicy.transition(100, 0, true))
        assertEquals(SystemLocationTransition.NONE, SystemLocationStatePolicy.transition(0, 0, true))
        assertEquals(SystemLocationTransition.NONE, SystemLocationStatePolicy.transition(100, 200, true))
    }
}
