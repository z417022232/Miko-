package com.example.worktimetracker

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.location.service.ConfirmedSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmedSessionTest {
    @Test fun confirmedTimesAreOrderedAndManualValuesArePreserved() {
        val existing = WorkRecordEntity(
            id = 7,
            workDate = "2026-07-31",
            status = "MANUAL",
            finalMinutes = 660,
            isManual = true
        )
        val merged = ConfirmedSession.merge(
            existing = existing,
            shift = "NIGHT_SHIFT",
            companyArrival = 1_000L,
            companyDeparture = 2_000L,
            homeDeparture = 500L,
            homeArrival = 3_000L,
            actualMinutes = 16,
            calculatedMinutes = 16,
            needsReview = false
        )
        assertEquals(660, merged.finalMinutes)
        assertTrue(merged.isManual)
        assertEquals(1_000L, merged.startTime)
        assertEquals(2_000L, merged.endTime)
        assertEquals(3_000L, merged.homeArrivalTime)
    }

    @Test fun staleHomeArrivalIsDroppedAndMarkedForReviewInsteadOfCrashing() {
        val merged = ConfirmedSession.merge(null, "NIGHT_SHIFT", 1_000L, 3_000L, 500L, 2_000L, 10, 10, false)
        assertEquals(null, merged.homeArrivalTime)
        assertTrue(merged.needsReview)
    }
}
