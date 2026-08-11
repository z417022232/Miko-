package com.example.worktimetracker

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.engine.ManualRecordEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRecordEditorTest {
    @Test
    fun `batch edit changes shift and hours but preserves event times`() {
        val old = WorkRecordEntity(
            id = 28,
            workDate = "2026-07-28",
            status = "WORK",
            shift = "DAY_SHIFT",
            startTime = 100,
            endTime = 200,
            homeDepartureTime = 80,
            homeArrivalTime = 240,
            actualMinutes = 720,
            finalMinutes = 660,
            needsReview = true
        )

        val edited = ManualRecordEditor.apply(old, "2026-07-28", "NIGHT_SHIFT", 630, "批量修正", 999)

        assertEquals("NIGHT_SHIFT", edited.shift)
        assertEquals(630, edited.finalMinutes)
        assertEquals(100L, edited.startTime)
        assertEquals(200L, edited.endTime)
        assertEquals(80L, edited.homeDepartureTime)
        assertEquals(240L, edited.homeArrivalTime)
        assertEquals(720, edited.actualMinutes)
        assertEquals("MANUAL", edited.status)
        assertTrue(edited.isManual)
        assertFalse(edited.needsReview)
    }

    @Test
    fun `batch edit can create manual records for dates without a record`() {
        val edited = ManualRecordEditor.apply(null, "2026-07-30", "DAY_SHIFT", 480, "", 999)

        assertEquals("2026-07-30", edited.workDate)
        assertEquals("DAY_SHIFT", edited.shift)
        assertEquals(480, edited.finalMinutes)
        assertTrue(edited.isManual)
    }
}
