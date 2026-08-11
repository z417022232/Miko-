package com.example.worktimetracker

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.engine.ReviewRecordEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewRecordEditorTest {
    @Test
    fun `confirmation preserves home events and becomes manual`() {
        val old = record(homeDepartureTime = 10L, homeArrivalTime = 40L)
        val result = ReviewRecordEditor.confirm(old, "NIGHT_SHIFT", 20L, 30L, 600, "已核对", 99L).getOrThrow()
        assertFalse(result.needsReview)
        assertTrue(result.isManual)
        assertEquals("MANUAL", result.status)
        assertEquals(10L, result.homeDepartureTime)
        assertEquals(40L, result.homeArrivalTime)
    }

    @Test
    fun `editing confirmed record preserves home events and remains confirmed`() {
        val old = record(homeDepartureTime = 10L, homeArrivalTime = 40L).copy(needsReview = false)

        val result = ReviewRecordEditor.confirm(old, "DAY_SHIFT", 20L, 30L, 540, "人工修改", 99L).getOrThrow()

        assertTrue(result.isManual)
        assertFalse(result.needsReview)
        assertEquals(10L, result.homeDepartureTime)
        assertEquals(40L, result.homeArrivalTime)
    }

    @Test
    fun `confirmation rejects departure before arrival`() {
        assertTrue(ReviewRecordEditor.confirm(record(), "DAY_SHIFT", 30L, 20L, 600, "", 99L).isFailure)
    }

    @Test
    fun `confirmation rejects unknown shift`() {
        assertTrue(ReviewRecordEditor.confirm(record(), "UNKNOWN", 20L, 30L, 600, "", 99L).isFailure)
    }

    private fun record(homeDepartureTime: Long? = null, homeArrivalTime: Long? = null) = WorkRecordEntity(
        id = 9,
        workDate = "2026-08-01",
        status = "WORK",
        shift = "DAY_SHIFT",
        startTime = 1L,
        endTime = 2L,
        homeDepartureTime = homeDepartureTime,
        homeArrivalTime = homeArrivalTime,
        actualMinutes = 700,
        finalMinutes = 660,
        needsReview = true,
        createdAt = 5L
    )
}
