package com.example.worktimetracker

import com.example.worktimetracker.ui.app.AppForegroundReset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppForegroundResetTest {
    @Test
    fun firstStartRequestsToday() {
        assertTrue(AppForegroundReset().onStart())
    }

    @Test
    fun repeatedStartDoesNotRequestTodayAgain() {
        val reset = AppForegroundReset()
        reset.onStart()

        assertFalse(reset.onStart())
    }

    @Test
    fun startAfterStopRequestsToday() {
        val reset = AppForegroundReset()
        reset.onStart()
        reset.onStop()

        assertTrue(reset.onStart())
    }
}
