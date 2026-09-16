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

class ShiftDetector(private val zoneId: ZoneId = ZoneId.systemDefault()) {

    /**
     * 班次**锚点**：把一个时刻吸附到最近的开班点后得到的结果。
     *
     * `date` = 开班日（归属日），`shift` = 该开班点对应的班次。
     */
    data class Anchor(val date: LocalDate, val shift: ShiftType)

    /**
     * 把时刻吸附到最近的开班锚点。
     *
     * 候选 = {当天 09:00, 当天 21:00, ±1 天的 09:00 / 21:00}，取距离最近者；
     * 列表以「当天 dayStart」开头，故完全并列时（如 15:00:00）判为**白班**。
     *
     * **为什么必须由它同时产出「班次」和「归属日」**：
     * 两者若各算各的就会自相矛盾 —— 凌晨 00:30 到岗，最近锚点是**前一晚 21:00**（夜班开班），
     * 但按「到岗时刻的日历日」取归属日会落成当天，于是这条记录既被识别成夜班、
     * 又被塞进次日，与次日白班撞在同一天（2026-09-16 复查 P0）。
     * 现在 detectShift / assignedDate 都从这一个锚点派生，不可能再分叉。
     */
    fun anchorFor(arrivalMillis: Long, settings: WorkSettings): Anchor {
        val arrival = Instant.ofEpochMilli(arrivalMillis).atZone(zoneId).toLocalDateTime()
        val today = arrival.toLocalDate()
        val dayStart = today.atTime(minutesToTime(settings.workStartMinutes))
        val nightStart = today.atTime(minutesToTime(settings.workEndMinutes))
        // 候选顺序必须保持「先白班后夜班、先今天后昨天」，并列时的取首项行为才是冻结的
        val candidates = listOf(
            Anchor(today, ShiftType.DAY_SHIFT) to dayStart,
            Anchor(today, ShiftType.NIGHT_SHIFT) to nightStart,
            Anchor(today.minusDays(1), ShiftType.DAY_SHIFT) to dayStart.minusDays(1),
            Anchor(today.minusDays(1), ShiftType.NIGHT_SHIFT) to nightStart.minusDays(1),
            Anchor(today.plusDays(1), ShiftType.DAY_SHIFT) to dayStart.plusDays(1),
            Anchor(today.plusDays(1), ShiftType.NIGHT_SHIFT) to nightStart.plusDays(1)
        )
        return candidates.minBy { abs(Duration.between(it.second, arrival).toMinutes()) }.first
    }

    /**
     * 到岗时刻 → 班次类型。
     *
     * 算法与历史实现完全一致（见 ShiftDetectorTest 的冻结边界用例），
     * 只是改为委托 [anchorFor]，以便与归属日共用同一判据。
     */
    fun detectShift(arrivalMillis: Long, settings: WorkSettings): ShiftType =
        anchorFor(arrivalMillis, settings).shift

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
     * ⚠️ **开班日取的是吸附后的锚点日，不是到岗时刻的日历日**（2026-09-16 复查 P0）：
     * 凌晨 00:30 到岗属于**前一晚 21:00 开班**的那个夜班，归属日必须是前一天；
     * 早班 08:50 到岗则吸附到当天 09:00，归属日仍是当天。
     * 否则「识别成夜班 / 归到次日」自相矛盾，还会和次日白班撞在同一个 workDate。
     *
     * 注意：**禁止**改成基于 endMillis 的日期（会与次日白班撞车）。守护测试见 CrossMidnightAssignmentTest。
     */
    fun assignedDate(arrivalMillis: Long, settings: WorkSettings): String =
        anchorFor(arrivalMillis, settings).date.toString()

    /** 该班次是否跨夜（start 与 end 不在同一本地日期）。endMillis 为 null 时按不跨夜处理。 */
    fun crossesMidnight(startMillis: Long, endMillis: Long?): Boolean {
        if (endMillis == null) return false
        val s = Instant.ofEpochMilli(startMillis).atZone(zoneId).toLocalDate()
        val e = Instant.ofEpochMilli(endMillis).atZone(zoneId).toLocalDate()
        return s != e
    }

    private fun minutesToTime(minutes: Int): LocalTime = LocalTime.of((minutes / 60) % 24, minutes % 60)
}
