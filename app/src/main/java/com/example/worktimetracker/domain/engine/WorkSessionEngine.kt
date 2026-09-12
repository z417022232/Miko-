package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.domain.model.RecordStatus
import com.example.worktimetracker.domain.model.ShiftType
import com.example.worktimetracker.domain.model.WorkSession
import com.example.worktimetracker.domain.model.WorkSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class WorkSessionEngine(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val shiftDetector: ShiftDetector = ShiftDetector(zoneId),
    private val calculator: WorkHourCalculator = WorkHourCalculator(zoneId)
) {
    fun buildSession(startMillis: Long?, endMillis: Long?, settings: WorkSettings): WorkSession {
        val effectiveStart = startMillis ?: fallbackStart(endMillis, settings)
        val shift = effectiveStart?.let { shiftDetector.detectShift(it, settings) } ?: ShiftType.DAY_SHIFT
        val assigned = effectiveStart?.let { shiftDetector.assignedDate(it) } ?: LocalDate.now(zoneId).toString()
        val expectedStart = shiftDetector.expectedStart(LocalDate.parse(assigned), shift, settings).atZone(zoneId).toInstant().toEpochMilli()
        val expectedEnd = shiftDetector.expectedEnd(LocalDate.parse(assigned), shift, settings).atZone(zoneId).toInstant().toEpochMilli()
        val effectiveEnd = endMillis ?: expectedEnd
        val actual = calculator.actualMinutes(effectiveStart, effectiveEnd)
        val status = detectStatus(effectiveStart, effectiveEnd, expectedStart, expectedEnd, settings)
        val arrivalLate = effectiveStart != null && effectiveStart > expectedStart + settings.arrivalToleranceMinutes * 60_000L
        val finalStatus = if (arrivalLate && status == RecordStatus.WORK) RecordStatus.ARRIVAL_EXCEPTION else status

        // A1: finalize 自动按 v1 规则算 finalMinutes
        val v1Result = calculator.calculateV1FinalMinutes(
            status = finalStatus,
            startMillis = effectiveStart,
            endMillis = effectiveEnd,
            settings = settings
        )

        val needsReview = finalStatus == RecordStatus.EARLY_LEAVE ||
            arrivalLate ||
            startMillis == null ||
            endMillis == null ||
            v1Result.ruleTrace.contains("R3_GREY") ||
            v1Result.ruleTrace.contains("R4_NO_OVERTIME")

        return WorkSession(
            startMillis = effectiveStart,
            endMillis = effectiveEnd,
            assignedDate = assigned,
            shiftType = shift,
            status = finalStatus,
            actualMinutes = actual,
            finalMinutes = v1Result.finalMinutes,
            needsReview = needsReview,
            v1EffectiveStartMillis = v1Result.effectiveStartMillis,
            v1EffectiveEndMillis = v1Result.effectiveEndMillis,
            v1RuleTrace = v1Result.ruleTrace
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