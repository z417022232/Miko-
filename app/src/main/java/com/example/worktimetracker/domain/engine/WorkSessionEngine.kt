package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.domain.model.RecordStatus
import com.example.worktimetracker.domain.model.ShiftType
import com.example.worktimetracker.domain.model.WorkSession
import com.example.worktimetracker.domain.model.WorkSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class WorkSessionEngine(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val shiftDetector: ShiftDetector = ShiftDetector(zoneId),
    private val calculator: WorkHourCalculator = WorkHourCalculator(zoneId)
) {
    fun buildSession(startMillis: Long?, endMillis: Long?, settings: WorkSettings): WorkSession {
        val effectiveStart = startMillis ?: fallbackStart(endMillis, settings)
        val shift = effectiveStart?.let { shiftDetector.detectShift(it, settings) } ?: ShiftType.DAY_SHIFT
        // R5：归属日 = 吸附后班次的**开班日**（凌晨到岗 → 前一晚那个夜班），
        // 与 detectShift 同源于 ShiftDetector.anchorFor，不会分叉。
        val assigned = effectiveStart?.let { shiftDetector.assignedDate(it, settings) }
            ?: LocalDate.now(zoneId).toString()
        val expectedStart = shiftDetector.expectedStart(LocalDate.parse(assigned), shift, settings).atZone(zoneId).toInstant().toEpochMilli()
        val expectedEnd = shiftDetector.expectedEnd(LocalDate.parse(assigned), shift, settings).atZone(zoneId).toInstant().toEpochMilli()
        val effectiveEnd = endMillis ?: expectedEnd
        val actual = calculator.actualMinutes(effectiveStart, effectiveEnd)
        val status = detectStatus(effectiveStart, effectiveEnd, expectedStart, expectedEnd, settings)
        // R1：迟到判定与计薪同口径（分钟截断），且恰好等于容差不算迟到
        val arrivalLate = effectiveStart != null &&
            calculator.isArrivalLate(effectiveStart, expectedStart, settings.arrivalToleranceMinutes)
        val finalStatus = if (arrivalLate && status == RecordStatus.WORK) RecordStatus.ARRIVAL_EXCEPTION else status
        // A5/R5: 跨夜判定 + 归属不变式强校验（workDate 必须 == 开班日的本地日期）
        val crossesMidnight = effectiveStart != null && shiftDetector.crossesMidnight(effectiveStart, endMillis)
        val assignmentViolations = validateAssignment(effectiveStart, assigned, settings)

        // A1: finalize 自动按 v1 规则算 finalMinutes
        val v1Result = calculator.calculateV1FinalMinutes(
            status = finalStatus,
            startMillis = effectiveStart,
            endMillis = effectiveEnd,
            settings = settings,
            shiftType = shift,
            // R5 关键：把**开班日**传下去，公式才能取到正确的班次窗口。
            // 不传的话凌晨到岗会被套成"次日 21:00 开班"，迟到变早到、算出 0 分钟。
            assignedDate = LocalDate.parse(assigned)
        )

        // A2: needsReview 结构化原因（对照 verification/工时计薪规则.md §3 触发矩阵）
        val reviewReasons = mutableListOf<String>()
        if (finalStatus == RecordStatus.EARLY_LEAVE && endMillis != null) {
            val earlyMin = TimeUnit.MILLISECONDS.toMinutes(expectedEnd - effectiveEnd).toInt()
            reviewReasons.add("R7 早退 ${earlyMin}min")
        }
        if (arrivalLate) {
            val lateMin = calculator.arrivalLateMinutes(effectiveStart!!, expectedStart)
            reviewReasons.add("R1 迟到 ${lateMin}min")
        }
        if (startMillis == null) reviewReasons.add("缺上班时间")
        if (endMillis == null) reviewReasons.add("缺下班时间")
        if (v1Result.ruleTrace.contains("R3_GREY")) reviewReasons.add("R3 21:00-21:29 灰区")
        if (v1Result.ruleTrace.contains("R4_NO_OVERTIME")) reviewReasons.add("R4 21:30+ 不计加班")
        // A5: 归属日与上班日不一致属于严重数据异常，必须人工复核
        reviewReasons.addAll(assignmentViolations)

        return WorkSession(
            startMillis = effectiveStart,
            endMillis = effectiveEnd,
            assignedDate = assigned,
            shiftType = shift,
            status = finalStatus,
            actualMinutes = actual,
            finalMinutes = v1Result.finalMinutes,
            needsReview = reviewReasons.isNotEmpty(),
            v1EffectiveStartMillis = v1Result.effectiveStartMillis,
            v1EffectiveEndMillis = v1Result.effectiveEndMillis,
            v1RuleTrace = v1Result.ruleTrace,
            reviewReason = reviewReasons.takeIf { it.isNotEmpty() }?.joinToString("；"),
            crossesMidnight = crossesMidnight
        )
    }

    /**
     * A5/R5 强校验：workDate 必须等于【开班日】的本地日期。
     *
     * 这是不可协商的不变式：跨夜班次（夜班 21:00→次日 09:00）记开班日，
     * 否则会与次日白班落在同一 workDate 而互相覆盖。
     * 返回违规说明列表（空 = 通过）。正常构造下永远为空，作为上下游改动的护栏。
     */
    internal fun validateAssignment(
        effectiveStart: Long?,
        assignedDate: String,
        settings: WorkSettings
    ): List<String> {
        if (effectiveStart == null) return emptyList()
        val startDate = shiftDetector.assignedDate(effectiveStart, settings)
        return if (startDate != assignedDate) {
            listOf("R5 归属日异常：workDate=$assignedDate 应为开班日 $startDate")
        } else emptyList()
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