package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.domain.model.RecordStatus
import com.example.worktimetracker.domain.model.WorkCalculationInput
import com.example.worktimetracker.domain.model.WorkSettings
import java.time.Instant
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
     * - R5: 跨夜归日 → session.finalize 时按 endTime 本地日期；workDate 由 assignedDate 决定
     * - R6: 晚到/晚离/晚到早离 同白班算法 + 状态映射（已由 detectStatus 产出 status）
     * - R7: 早退 ≥1h 直接按公式（endTime 对齐 effective_end；早退 <1h 仍按公式但 needsReview）
     * - R8: 午休扣 1h → settings.restDeductionMinutes = 60
     */
    fun calculateV1FinalMinutes(
        status: RecordStatus,
        startMillis: Long?,
        endMillis: Long?,
        settings: WorkSettings,
        lateToleranceMinutes: Int = settings.arrivalToleranceMinutes
    ): V1FinalResult {
        if (status == RecordStatus.REST) {
            return V1FinalResult(0, null, null, listOf("R_REST"))
        }
        if (startMillis == null || endMillis == null) {
            return V1FinalResult(0, null, null, listOf("R_NULL_BOUNDS"))
        }

        // 用户在 settings 里显式配了 defaultHours（如固定 12h/天），v1 直接沿用，不做规则计算
        if (settings.hasDefaultHours && settings.defaultWorkMinutes != null) {
            return V1FinalResult(
                finalMinutes = max(0, settings.defaultWorkMinutes),
                effectiveStartMillis = startMillis,
                effectiveEndMillis = endMillis,
                ruleTrace = listOf("R_DEFAULT_HOURS")
            )
        }

        val trace = mutableListOf<String>()
        val date = Instant.ofEpochMilli(startMillis).atZone(zoneId).toLocalDate()
        val expectedStart = date.atTime(settings.workStartMinutes / 60, settings.workStartMinutes % 60)
            .atZone(zoneId).toInstant().toEpochMilli()
        val endDate = Instant.ofEpochMilli(endMillis).atZone(zoneId).toLocalDate()
        val expectedEnd = endDate.atTime(settings.workEndMinutes / 60, settings.workEndMinutes % 60)
            .atZone(zoneId).toInstant().toEpochMilli()

        val arrivalLateMillis = startMillis - expectedStart
        val arrivalLateMinutes = TimeUnit.MILLISECONDS.toMinutes(arrivalLateMillis).toInt()

        val effectiveStart: Long = when (status) {
            RecordStatus.ARRIVAL_EXCEPTION -> {
                // 迟到向上取整到下一个整点
                val aligned = alignUpToHour(startMillis)
                val lateMinutes = TimeUnit.MILLISECONDS.toMinutes(aligned - startMillis).toInt()
                if (lateMinutes <= lateToleranceMinutes) {
                    trace.add("R1")
                    expectedStart
                } else {
                    trace.add("R1_ALIGN_UP")
                    aligned
                }
            }
            RecordStatus.WORK, RecordStatus.EARLY_LEAVE -> {
                // 早到/准时：从 expectedStart (09:00) 起算；迟到但被 detectStatus 标为 WORK 时也向上取整
                if (arrivalLateMinutes in 1..lateToleranceMinutes) {
                    trace.add("R1")
                    expectedStart
                } else if (arrivalLateMinutes <= 0) {
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

    private fun alignUpToHour(millis: Long): Long {
        val zdt = Instant.ofEpochMilli(millis).atZone(zoneId)
        val truncated = zdt.withMinute(0).withSecond(0).withNano(0)
        val aligned = if (zdt.toLocalTime().minute == 0 && zdt.toLocalTime().second == 0) truncated else truncated.plusHours(1)
        return aligned.toInstant().toEpochMilli()
    }

    private fun minutesBetween(startMillis: Long, endMillis: Long): Int =
        TimeUnit.MILLISECONDS.toMinutes(endMillis - startMillis).toInt()
}