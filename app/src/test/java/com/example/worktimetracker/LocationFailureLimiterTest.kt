package com.example.worktimetracker

import com.example.worktimetracker.location.service.LocationFailureLimiter
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationFailureLimiterTest {
    @Test fun repeatsAreSuppressedAndFifthFailureNotifies() {
        val limiter = LocationFailureLimiter()
        assertEquals(LocationFailureLimiter.Action.LOG, limiter.record("state", 0))
        assertEquals(LocationFailureLimiter.Action.SUPPRESS, limiter.record("state", 1))
        limiter.record("state", 2); limiter.record("state", 3)
        assertEquals(LocationFailureLimiter.Action.NOTIFY_AND_THROTTLE, limiter.record("state", 4))
        limiter.success()
        assertEquals(LocationFailureLimiter.Action.LOG, limiter.record("state", 20 * 60_000L))
    }
}
