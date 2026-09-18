package com.example.worktimetracker

import com.example.worktimetracker.domain.location.HistoricalAnchorBootstrap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalAnchorBootstrapTest {
    @Test fun `seven historical days can bootstrap without waiting another seven days`() {
        assertTrue(HistoricalAnchorBootstrap.canApply(distinctDays = 7, offsetMeters = 12.0))
    }

    @Test fun `insufficient history or large offset stays conservative`() {
        assertFalse(HistoricalAnchorBootstrap.canApply(distinctDays = 6, offsetMeters = 12.0))
        assertFalse(HistoricalAnchorBootstrap.canApply(distinctDays = 30, offsetMeters = 31.0))
    }
}
