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

    /**
     * R5 跨夜归日：workDate 一律取【上班日期（shift 开班日）】。
     *
     * 约定（2026-09-12 用户确认，A5 强校验）：
     * - 白班 09:00→21:00：起止同日，无歧义
     * - 夜班 21:00→次日 09:00：记【上班日】，不记下班日
     *   例：8/1 20:44 上班 → 8/2 09:12 下班 ⇒ workDate = "2026-08-01"
     * - 这样夜班与次日白班不会落在同一 workDate，月度统计按开班日编号（行业惯例）
     *
     * 注意：**禁止**改成基于 endMillis 的日期（会与次日白班撞车）。守护测试见 CrossMidnightAssignmentTest。
     */
    fun assignedDate(startMillis: Long): String = Instant.ofEpochMilli(startMillis).atZone(zoneId).toLocalDate().toString()

    /** 该班次是否跨夜（start 与 end 不在同一本地日期）。endMillis 为 null 时按不跨夜处理。 */
    fun crossesMidnight(startMillis: Long, endMillis: Long?): Boolean {
        if (endMillis == null) return false
        val s = Instant.ofEpochMilli(startMillis).atZone(zoneId).toLocalDate()
        val e = Instant.ofEpochMilli(endMillis).atZone(zoneId).toLocalDate()
        return s != e
    }

    private fun minutesToTime(minutes: Int): LocalTime = LocalTime.of((minutes / 60) % 24, minutes % 60)
}
