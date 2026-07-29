package com.example.worktimetracker

import com.example.worktimetracker.domain.engine.LocationStatusAnalyzer
import com.example.worktimetracker.domain.engine.ShiftDetector
import com.example.worktimetracker.domain.engine.WorkHourCalculator
import com.example.worktimetracker.domain.engine.WorkSessionEngine
import com.example.worktimetracker.domain.model.LocationType
import com.example.worktimetracker.domain.model.RecordStatus
import com.example.worktimetracker.domain.model.ShiftType
import com.example.worktimetracker.domain.model.WorkCalculationInput
import com.example.worktimetracker.domain.model.WorkSegment
import com.example.worktimetracker.domain.model.WorkSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class WorkTimeEngineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val settings = WorkSettings(workStartMinutes = 9 * 60, workEndMinutes = 21 * 60, hasDefaultHours = false)
    private val fixed12 = settings.copy(hasDefaultHours = true, defaultWorkMinutes = 12 * 60)

    @Test fun detectsDayShift() {
        val detector = ShiftDetector(zone)
        assertEquals(ShiftType.DAY_SHIFT, detector.detectShift(ms(2026, 7, 22, 8, 50), settings))
    }

    @Test fun detectsNightShift() {
        val detector = ShiftDetector(zone)
        assertEquals(ShiftType.NIGHT_SHIFT, detector.detectShift(ms(2026, 7, 22, 20, 50), settings))
    }

    @Test fun nightShiftAssignedToStartDate() {
        val session = WorkSessionEngine(zone).buildSession(ms(2026, 7, 22, 20, 50), ms(2026, 7, 23, 9, 10), fixed12)
        assertEquals("2026-07-22", session.assignedDate)
        assertEquals(ShiftType.NIGHT_SHIFT, session.shiftType)
        assertEquals(12 * 60, session.finalMinutes)
    }

    @Test fun defaultHoursOverrideActualLocationHours() {
        val result = WorkHourCalculator().calculateFinalMinutes(
            WorkCalculationInput(ms(2026, 7, 22, 8, 40), ms(2026, 7, 22, 21, 30), settings = fixed12)
        )
        assertEquals(12 * 60, result)
    }

    @Test fun manualFinalHoursHaveHighestPriority() {
        val result = WorkHourCalculator().calculateFinalMinutes(
            WorkCalculationInput(
                startMillis = ms(2026, 7, 22, 8, 40),
                endMillis = ms(2026, 7, 22, 21, 30),
                manualFinalMinutes = 510,
                settings = fixed12
            )
        )
        assertEquals(510, result)
    }

    @Test fun manualSegmentsOverrideDefaultHours() {
        val result = WorkHourCalculator().calculateFinalMinutes(
            WorkCalculationInput(
                startMillis = null,
                endMillis = null,
                manualSegments = listOf(
                    WorkSegment(ms(2026, 7, 22, 8, 0), ms(2026, 7, 22, 12, 0)),
                    WorkSegment(ms(2026, 7, 22, 13, 0), ms(2026, 7, 22, 18, 0))
                ),
                settings = fixed12
            )
        )
        assertEquals(9 * 60, result)
    }

    @Test fun locationCalculatedHoursDeductRestWhenNoDefault() {
        val result = WorkHourCalculator().calculateFinalMinutes(
            WorkCalculationInput(ms(2026, 7, 22, 8, 45), ms(2026, 7, 22, 21, 15), settings = settings)
        )
        assertEquals(11 * 60 + 30, result)
    }

    @Test fun earlyLeaveMoreThanThreeMinutesNeedsReview() {
        val session = WorkSessionEngine(zone).buildSession(ms(2026, 7, 22, 8, 50), ms(2026, 7, 22, 20, 56), fixed12)
        assertEquals(RecordStatus.EARLY_LEAVE, session.status)
        assertEquals(true, session.needsReview)
    }

    @Test fun leavingWithinThreeMinutesIsNormal() {
        val session = WorkSessionEngine(zone).buildSession(ms(2026, 7, 22, 8, 50), ms(2026, 7, 22, 20, 57), fixed12)
        assertEquals(RecordStatus.WORK, session.status)
    }

    @Test fun missingEndUsesReferenceEndAndNeedsReview() {
        val session = WorkSessionEngine(zone).buildSession(ms(2026, 7, 22, 8, 50), null, fixed12)
        assertEquals(ms(2026, 7, 22, 21, 0), session.endMillis)
        assertEquals(true, session.needsReview)
    }

    @Test fun classifiesHomeCompanyAndOther() {
        val analyzer = LocationStatusAnalyzer()
        assertEquals(LocationType.COMPANY, analyzer.classify(30.0, 120.0, 30.0, 120.0, 150, 31.0, 121.0, 150))
        assertEquals(LocationType.HOME, analyzer.classify(31.0, 121.0, 30.0, 120.0, 150, 31.0, 121.0, 150))
        assertEquals(LocationType.OTHER, analyzer.classify(32.0, 122.0, 30.0, 120.0, 150, 31.0, 121.0, 150))
    }

    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()
}
