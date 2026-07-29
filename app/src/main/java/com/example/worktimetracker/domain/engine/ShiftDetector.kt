package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.domain.model.ShiftType
import com.example.worktimetracker.domain.model.WorkSettings
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

class ShiftDetector(private val zoneId: ZoneId = ZoneId.systemDefault()) {
    fun detectShift(arrivalMillis: Long, settings: WorkSettings): ShiftType {
        val arrival = Instant.ofEpochMilli(arrivalMillis).atZone(zoneId).toLocalDateTime()
        val dayStart = arrival.toLocalDate().atTime(minutesToTime(settings.workStartMinutes))
        val nightStart = arrival.toLocalDate().atTime(minutesToTime(settings.workEndMinutes))
        val candidates = listOf(dayStart, nightStart, dayStart.minusDays(1), nightStart.minusDays(1), dayStart.plusDays(1), nightStart.plusDays(1))
        val nearest = candidates.minBy { abs(java.time.Duration.between(it, arrival).toMinutes()) }
        return if (nearest.toLocalTime() == minutesToTime(settings.workStartMinutes)) ShiftType.DAY_SHIFT else ShiftType.NIGHT_SHIFT
    }

    fun expectedStart(date: LocalDate, shift: ShiftType, settings: WorkSettings): LocalDateTime = when (shift) {
        ShiftType.DAY_SHIFT -> date.atTime(minutesToTime(settings.workStartMinutes))
        ShiftType.NIGHT_SHIFT -> date.atTime(minutesToTime(settings.workEndMinutes))
    }

    fun expectedEnd(date: LocalDate, shift: ShiftType, settings: WorkSettings): LocalDateTime = when (shift) {
        ShiftType.DAY_SHIFT -> date.atTime(minutesToTime(settings.workEndMinutes))
        ShiftType.NIGHT_SHIFT -> date.plusDays(1).atTime(minutesToTime(settings.workStartMinutes))
    }

    fun assignedDate(startMillis: Long): String = Instant.ofEpochMilli(startMillis).atZone(zoneId).toLocalDate().toString()

    private fun minutesToTime(minutes: Int): LocalTime = LocalTime.of((minutes / 60) % 24, minutes % 60)
}
