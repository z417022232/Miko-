package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.domain.model.ShiftType
import com.example.worktimetracker.domain.model.WorkSettings
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

class ShiftWindowFallback(private val zoneId: ZoneId = ZoneId.systemDefault()) {
    data class Window(
        val assignedDate: LocalDate,
        val shift: ShiftType,
        val startLocal: LocalDateTime,
        val endLocal: LocalDateTime,
        val startMillis: Long,
        val endMillis: Long
    )

    data class Completion(
        val assignedDate: String,
        val shift: ShiftType,
        val startMillis: Long?,
        val endMillis: Long?,
        val usedFallbackStart: Boolean,
        val usedFallbackEnd: Boolean,
        val needsReview: Boolean
    )

    fun windowFor(date: LocalDate, shift: ShiftType, settings: WorkSettings): Window {
        val dayStart = minutesToTime(settings.workStartMinutes)
        val dayEnd = minutesToTime(settings.workEndMinutes)
        val start = if (shift == ShiftType.DAY_SHIFT) date.atTime(dayStart) else date.atTime(dayEnd)
        val end = if (shift == ShiftType.DAY_SHIFT) date.atTime(dayEnd) else date.plusDays(1).atTime(dayStart)
        return Window(
            assignedDate = date,
            shift = shift,
            startLocal = start,
            endLocal = end,
            startMillis = start.atZone(zoneId).toInstant().toEpochMilli(),
            endMillis = end.atZone(zoneId).toInstant().toEpochMilli()
        )
    }

    fun nearestWindow(detectedAt: Long, settings: WorkSettings): Window {
        val localDate = Instant.ofEpochMilli(detectedAt).atZone(zoneId).toLocalDate()
        val candidates = (-1L..1L).flatMap { offset ->
            val date = localDate.plusDays(offset)
            listOf(windowFor(date, ShiftType.DAY_SHIFT, settings), windowFor(date, ShiftType.NIGHT_SHIFT, settings))
        }
        return candidates.minWith(compareBy<Window> { distanceToWindow(detectedAt, it) }
            .thenBy { if (it.startMillis <= detectedAt) 0 else 1 }
            .thenByDescending { it.startMillis })
    }

    fun complete(
        detectedAt: Long,
        reliableStart: Long?,
        reliableEnd: Long?,
        settings: WorkSettings
    ): Completion {
        val window = nearestWindow(reliableStart ?: reliableEnd ?: detectedAt, settings)
        val useStart = reliableStart == null
        val canComplete = detectedAt >= window.endMillis
        val useEnd = reliableEnd == null && canComplete
        return Completion(
            assignedDate = window.assignedDate.toString(),
            shift = window.shift,
            startMillis = reliableStart ?: window.startMillis,
            endMillis = reliableEnd ?: if (canComplete) window.endMillis else null,
            usedFallbackStart = useStart,
            usedFallbackEnd = useEnd,
            needsReview = useStart || useEnd
        )
    }

    private fun distanceToWindow(at: Long, window: Window): Long = when {
        at < window.startMillis -> window.startMillis - at
        at > window.endMillis -> at - window.endMillis
        else -> 0L
    }

    private fun minutesToTime(minutes: Int): LocalTime = LocalTime.of((minutes / 60) % 24, minutes % 60)
}
