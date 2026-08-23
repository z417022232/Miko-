package com.example.worktimetracker

import com.example.worktimetracker.location.service.ProviderAlertAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAlertAggregatorTest {
    @Test fun threeProviderCallbacksBecomeOneSummary() {
        val aggregator = ProviderAlertAggregator()
        aggregator.disabled("gps", 0)
        aggregator.disabled("network", 1)
        aggregator.disabled("passive", 2)
        assertEquals(setOf("gps", "network", "passive"), aggregator.disabledProviders())
        assertFalse(aggregator.shouldNotifyGlobal(locationEnabled = false, now = 59_999L))
        assertTrue(aggregator.shouldNotifyGlobal(locationEnabled = false, now = 60_000L))
        assertFalse(aggregator.shouldNotifyGlobal(locationEnabled = false, now = 120_000L))
    }

    @Test fun oneProviderDisabledWhileLocationEnabledDoesNotNotify() {
        val aggregator = ProviderAlertAggregator()
        aggregator.disabled("gps", 0)
        assertFalse(aggregator.shouldNotifyGlobal(locationEnabled = true, now = 120_000L))
    }

    @Test fun recoveryClearsStateSilently() {
        val aggregator = ProviderAlertAggregator()
        aggregator.disabled("gps", 0)
        aggregator.disabled("network", 1)
        assertTrue(aggregator.recovered(locationEnabled = true, now = 70_000L))
        assertTrue(aggregator.disabledProviders().isEmpty())
    }
}
