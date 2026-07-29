package com.example.worktimetracker

import com.example.worktimetracker.domain.engine.WorkSessionEngine
import com.example.worktimetracker.domain.model.RecordStatus
import com.example.worktimetracker.domain.model.ShiftType
import com.example.worktimetracker.domain.model.WorkSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class WorkSessionEdgeCasesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val engine = WorkSessionEngine(zone)
    private val settings = WorkSettings(workStartMinutes = 9 * 60, workEndMinutes = 21 * 60, hasDefaultHours = true, defaultWorkMinutes = 12 * 60)

    @Test fun abnormalLateArrivalIsMarkedForReview() {
        val session = engine.buildSession(ms(2026, 7, 22, 14, 0), ms(2026, 7, 22, 21, 10), settings)
        assertEquals(RecordStatus.ARRIVAL_EXCEPTION, session.status)
        assertTrue(session.needsReview)
    }

    @Test fun missingStartForNightShiftFallsBackToPreviousNightStart() {
        val session = engine.buildSession(null, ms(2026, 7, 23, 9, 10), settings)
        assertEquals("2026-07-22", session.assignedDate)
        assertEquals(ShiftType.NIGHT_SHIFT, session.shiftType)
        assertEquals(12 * 60, session.finalMinutes)
        assertTrue(session.needsReview)
    }

    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int): Long = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()
}
