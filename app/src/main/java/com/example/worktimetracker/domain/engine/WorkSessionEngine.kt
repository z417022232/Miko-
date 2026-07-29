package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.domain.model.RecordStatus
import com.example.worktimetracker.domain.model.ShiftType
import com.example.worktimetracker.domain.model.WorkCalculationInput
import com.example.worktimetracker.domain.model.WorkSession
import com.example.worktimetracker.domain.model.WorkSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class WorkSessionEngine(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val shiftDetector: ShiftDetector = ShiftDetector(zoneId),
    private val calculator: WorkHourCalculator = WorkHourCalculator()
) {
    fun buildSession(startMillis: Long?, endMillis: Long?, settings: WorkSettings): WorkSession {
        val effectiveStart = startMillis ?: fallbackStart(endMillis, settings)
        val shift = effectiveStart?.let { shiftDetector.detectShift(it, settings) } ?: ShiftType.DAY_SHIFT
        val assigned = effectiveStart?.let { shiftDetector.assignedDate(it) } ?: LocalDate.now(zoneId).toString()
        val expectedStart = shiftDetector.expectedStart(LocalDate.parse(assigned), shift, settings).atZone(zoneId).toInstant().toEpochMilli()
        val expectedEnd = shiftDetector.expectedEnd(LocalDate.parse(assigned), shift, settings).atZone(zoneId).toInstant().toEpochMilli()
        val effectiveEnd = endMillis ?: expectedEnd
        val actual = calculator.actualMinutes(effectiveStart, effectiveEnd)
        val finalMinutes = calculator.calculateFinalMinutes(
            WorkCalculationInput(
                startMillis = effectiveStart,
                endMillis = effectiveEnd,
                settings = settings,
                fallbackStartMillis = expectedStart,
                fallbackEndMillis = expectedEnd
            )
        )
        val status = detectStatus(effectiveStart, effectiveEnd, expectedStart, expectedEnd, settings)
        val arrivalLate = effectiveStart != null && effectiveStart > expectedStart + settings.arrivalToleranceMinutes * 60_000L
        return WorkSession(
            startMillis = effectiveStart,
            endMillis = effectiveEnd,
            assignedDate = assigned,
            shiftType = shift,
            status = if (arrivalLate && status == RecordStatus.WORK) RecordStatus.ARRIVAL_EXCEPTION else status,
            actualMinutes = actual,
            finalMinutes = finalMinutes,
            needsReview = status == RecordStatus.EARLY_LEAVE || arrivalLate || startMillis == null || endMillis == null
        )
    }

    private fun detectStatus(startMillis: Long?, endMillis: Long?, expectedStart: Long, expectedEnd: Long, settings: WorkSettings): RecordStatus {
        if (startMillis == null && endMillis == null) return RecordStatus.REST
        if (endMillis != null && endMillis < expectedEnd - settings.earlyLeaveToleranceMinutes * 60_000L) return RecordStatus.EARLY_LEAVE
        return RecordStatus.WORK
    }

    private fun fallbackStart(endMillis: Long?, settings: WorkSettings): Long? {
        if (endMillis == null) return null
        val endDate = Instant.ofEpochMilli(endMillis).atZone(zoneId).toLocalDate()
        val likelyNight = Instant.ofEpochMilli(endMillis).atZone(zoneId).toLocalTime().toSecondOfDay() / 60 <= settings.workStartMinutes + 120
        val assignedDate = if (likelyNight) endDate.minusDays(1) else endDate
        val shift = if (likelyNight) ShiftType.NIGHT_SHIFT else ShiftType.DAY_SHIFT
        return shiftDetector.expectedStart(assignedDate, shift, settings).atZone(zoneId).toInstant().toEpochMilli()
    }
}
