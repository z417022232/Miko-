package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.domain.model.RecordStatus
import com.example.worktimetracker.domain.model.ShiftType
import com.example.worktimetracker.domain.model.WorkCalculationInput
import com.example.worktimetracker.domain.model.WorkSettings
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.max

data class V1FinalResult(
    val finalMinutes: Int,
    val effectiveStartMillis: Long?,
    val effectiveEndMillis: Long?,
    val ruleTrace: List<String>
)

class WorkHourCalculator(private val zoneId: ZoneId = ZoneId.systemDefault()) {
    fun calculateFinalMinutes(input: WorkCalculationInput): Int {
        input.manualFinalMinutes?.let { return max(0, it) }
        if (input.manualSegments.isNotEmpty()) {
            val total = input.manualSegments.sumOf { segment ->
                minutesBetween(segment.startMillis, segment.endMillis) - if (segment.deductRest) input.settings.restDeductionMinutes else 0
            }
            return max(0, total)
        }
        if (input.settings.hasDefaultHours && input.settings.defaultWorkMinutes != null) {
            return max(0, input.settings.defaultWorkMinutes)
        }
        val start = input.startMillis ?: input.fallbackStartMillis
        val end = input.endMillis ?: input.fallbackEndMillis
        if (start == null || end == null) return 0
        return max(0, minutesBetween(start, end) - input.settings.restDeductionMinutes)
    }

    fun actualMinutes(startMillis: Long?, endMillis: Long?): Int {
        if (startMillis == null || endMillis == null) return 0
        return max(0, minutesBetween(startMillis, endMillis))
    }

    /**
     * 按公司 v1 计薪规则（R1–R8）计算 finalMinutes。
     *
     * 规则要点：
     * - R1: 迟到 ≤3min 不计 → arrivalToleranceMinutes 已对齐
     * - R2: 21:00 整正常下班 → endTime == 21:00:00 算正常工时
     * - R3: 21:00–21:29 灰区 → finalMinutes = 0 + needsReview（实际已扣后还在灰区）
     * - R4: 21:30+ 不计加班 → finalMinutes = 0 + needsReview
     * - R5: 跨夜归日 → workDate 取【上班日期（开班日）】；跨夜班次的班次边界按 shiftType 取
     *     白班 09:00→21:00（同日）；夜班 21:00→次日 09:00。workDate 由 assignedDate 决定，
     *     本函数只负责在跨夜时用正确的班次边界算工时（否则 R2/R3/R4/R7 会套错日历）
     * - R6: 晚到/晚离/晚到早离 同白班算法 + 状态映射（已由 detectStatus 产出 status）
     * - R7: 早退 ≥1h 直接按公式（endTime 对齐 effective_end；早退 <1h 仍按公式但 needsReview）
     * - R8: 午休扣 1h → settings.restDeductionMinutes = 60
     *
     * **A8 默认工时短路（用户 2026-09-12 确认）**：
     * - 设了上下班时间 + 默认工时，且**正常出勤**（不迟到不早退）→ 直接计 `defaultWorkMinutes`
     *   （例：08:45 到岗 / 21:00 离岗，默认 12h ⇒ 720min）
     * - 但**迟到或早退**时短路失效，走 R1/R7 公式（迟到向上取整、早退按实际）
     * - 没设默认工时 → 一律按排班窗口算（08:45 到岗仍按 09:00 起算）
     *
     * **夜班与白班算法完全一致（用户 2026-09-12 确认）**：同一套 R1–R8，R8 同样扣 1h。
     * 唯一差别是班次窗口由 `shiftType` 决定（白班 09:00→21:00；夜班 21:00→次日 09:00）。
     */
    fun calculateV1FinalMinutes(
        status: RecordStatus,
        startMillis: Long?,
        endMillis: Long?,
        settings: WorkSettings,
        /** A5/R5: 班次类型，决定 expected 边界。白班 09:00→21:00；夜班 21:00→次日 09:00。 */
        shiftType: ShiftType = ShiftType.DAY_SHIFT,
        lateToleranceMinutes: Int = settings.arrivalToleranceMinutes
    ): V1FinalResult {
        if (status == RecordStatus.REST) {
            return V1FinalResult(0, null, null, listOf("R_REST"))
        }
        if (startMillis == null || endMillis == null) {
            return V1FinalResult(0, null, null, listOf("R_NULL_BOUNDS"))
        }

        val trace = mutableListOf<String>()
        // R5: workDate 归属日 = 上班日（开班日）
        val date = Instant.ofEpochMilli(startMillis).atZone(zoneId).toLocalDate()
        val endDate = Instant.ofEpochMilli(endMillis).atZone(zoneId).toLocalDate()
        val crossesMidnight = endDate != date
        if (crossesMidnight) trace.add("R5_CROSS_NIGHT")
        // A5: 班次边界按 shiftType 取，夜班 expected 跨到次日 09:00
        val window = expectedWindow(date, shiftType, settings)
        val expectedStart = window.first
        val expectedEnd = window.second

        val arrivalLateMillis = startMillis - expectedStart
        val arrivalLateMinutes = TimeUnit.MILLISECONDS.toMinutes(arrivalLateMillis).toInt()

        // A8（用户 2026-09-12 确认）：默认工时短路**仅在正常出勤时**生效。
        //   设了默认工时「正常打卡」→ 直接计默认工时（08:45 到岗 / 21:00 离岗 → 默认值）
        //   但「迟到或早退」时 → 短路失效，走下方 R1–R8 公式（迟到取整、早退按实际）
        //   晚退不在例外内：R2/R3/R4 本就不计加班，结果仍为默认工时（灰区/超限另加 needsReview）
        if (settings.hasDefaultHours && settings.defaultWorkMinutes != null) {
            val arrivalLate = arrivalLateMinutes > lateToleranceMinutes
            val earlyLeave = endMillis < expectedEnd - settings.earlyLeaveToleranceMinutes * 60_000L
            if (!arrivalLate && !earlyLeave) {
                return V1FinalResult(
                    finalMinutes = max(0, settings.defaultWorkMinutes),
                    effectiveStartMillis = startMillis,
                    effectiveEndMillis = endMillis,
                    // 保留 trace 里已记录的跨夜等标记，便于诊断
                    ruleTrace = trace + "R_DEFAULT_HOURS"
                )
            }
            // 迟到/早退 → 记下短路被绕过，继续走公式
            trace.add(if (arrivalLate) "R_DEFAULT_BYPASS_LATE" else "R_DEFAULT_BYPASS_EARLY")
        }

        val effectiveStart: Long = when (status) {
            RecordStatus.ARRIVAL_EXCEPTION, RecordStatus.WORK, RecordStatus.EARLY_LEAVE -> {
                // R1: 迟到 ≤3min（含早到，arrivalLateMinutes ≤ 0）不计 → 仍按 09:00 起算
                //     迟到 > 3min → 向上取整到下一整点（公司规则：⌈上班时间⌉）
                if (arrivalLateMinutes <= lateToleranceMinutes) {
                    trace.add("R1")
                    expectedStart
                } else {
                    trace.add("R1_ALIGN_UP")
                    alignUpToHour(startMillis)
                }
            }
            else -> startMillis
        }

        val effectiveEnd: Long = when (status) {
            RecordStatus.ARRIVAL_EXCEPTION, RecordStatus.WORK -> {
                val lateBy = endMillis - expectedEnd
                val lateMin = TimeUnit.MILLISECONDS.toMinutes(lateBy).toInt()
                when {
                    lateMin < 0 -> {
                        // 早于 21:00 离开：按真实 endTime 算（如早退）
                        trace.add("R7_EARLY")
                        endMillis
                    }
                    lateMin <= 3 -> {
                        // R2 ±3min 容差：21:00–21:03 内算正常下班，effective_end 截到 21:00
                        trace.add("R2")
                        expectedEnd
                    }
                    lateMin in 4..29 -> {
                        // R3 灰区：21:04–21:29 离岗，effective_end 截到 21:00（不计加班），needsReview
                        trace.add("R3_GREY")
                        expectedEnd
                    }
                    else -> {
                        // R4 不计加班：21:30+ 离岗，effective_end 截到 21:00
                        trace.add("R4_NO_OVERTIME")
                        expectedEnd
                    }
                }
            }
            RecordStatus.EARLY_LEAVE -> {
                // 早退：直接按公式，effective_end 保留原始 endTime（按 R7）
                trace.add("R7")
                endMillis
            }
            else -> endMillis
        }

        trace.add("R8")
        val grossMinutes = minutesBetween(effectiveStart, effectiveEnd)
        val finalMinutes = max(0, grossMinutes - settings.restDeductionMinutes)

        return V1FinalResult(finalMinutes, effectiveStart, effectiveEnd, trace.distinct())
    }

    private fun startOfDayMillis(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()

    /**
     * A5/R5: 按班次类型给出该归属日的 (expectedStart, expectedEnd)。
     *
     * - 白班（DAY_SHIFT）：`date 09:00` → `date 21:00`（同日）
     * - 夜班（NIGHT_SHIFT）：`date 21:00` → `date+1 09:00`（跨夜）
     *
     * 归属日 `date` = 上班日（开班日），与 ShiftDetector.expectedStart/expectedEnd 语义保持一致。
     */
    private fun expectedWindow(
        date: LocalDate,
        shiftType: ShiftType,
        settings: WorkSettings
    ): Pair<Long, Long> {
        val start = date.atTime(settings.workStartMinutes / 60, settings.workStartMinutes % 60)
        val end = date.atTime(settings.workEndMinutes / 60, settings.workEndMinutes % 60)
        return when (shiftType) {
            ShiftType.DAY_SHIFT -> start.toMillis() to end.toMillis()
            ShiftType.NIGHT_SHIFT -> end.toMillis() to start.plusDays(1).toMillis()
        }
    }

    private fun LocalDateTime.toMillis(): Long = atZone(zoneId).toInstant().toEpochMilli()

    private fun alignUpToHour(millis: Long): Long {
        val zdt = Instant.ofEpochMilli(millis).atZone(zoneId)
        val truncated = zdt.withMinute(0).withSecond(0).withNano(0)
        val aligned = if (zdt.toLocalTime().minute == 0 && zdt.toLocalTime().second == 0) truncated else truncated.plusHours(1)
        return aligned.toInstant().toEpochMilli()
    }

    private fun minutesBetween(startMillis: Long, endMillis: Long): Int =
        TimeUnit.MILLISECONDS.toMinutes(endMillis - startMillis).toInt()
}