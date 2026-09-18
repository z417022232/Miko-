package com.example.worktimetracker

import com.example.worktimetracker.location.recovery.SystemLocationStatusPresenter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemLocationStatusPresenterTest {
    @Test fun disabledLocationShowsRepairBanner() {
        assertTrue(SystemLocationStatusPresenter.showRepairBanner(false))
    }

    @Test fun enabledLocationHidesRepairBanner() {
        assertFalse(SystemLocationStatusPresenter.showRepairBanner(true))
    }
}
