package com.example.worktimetracker

import com.example.worktimetracker.domain.engine.WorkSessionEngine
import com.example.worktimetracker.domain.model.ShiftType
import com.example.worktimetracker.domain.model.WorkSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A8: 默认工时短路规则（用户 2026-09-12 确认）。
 *
 * 三种情形：
 * 1. 设了上下班时间、**没设**默认工时 → 按排班窗口算（08:45 到岗仍按 09:00 起算）
 * 2. 设了上下班时间、**也设了**默认工时 → 正常出勤直接计默认工时
 * 3. 设了默认工时但**迟到或早退** → 短路失效，回到 R1/R7 公式
 *
 * 另确认：**夜班与白班算法完全一致**（同一套 R1–R8，含 R8 扣 1h），仅班次窗口不同。
 */
class DefaultWorkHoursRuleTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val engine = WorkSessionEngine(zone)

    /** 09:00–21:00，无默认工时 */
    private val scheduleOnly = WorkSettings(workStartMinutes = 9 * 60, workEndMinutes = 21 * 60)

    /** 09:00–21:00，默认工时 12h */
    private val withDefault = scheduleOnly.copy(hasDefaultHours = true, defaultWorkMinutes = 12 * 60)

    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    // ---------- 情形 1：无默认工时 → 按排班窗口 ----------

    @Test fun noDefaultHours_earlyArrivalUsesScheduledStart() {
        // 08:45 到岗 / 21:00 离岗，无默认工时 → 按 09:00–21:00 算 = 11h
        val s = engine.buildSession(ms(2026, 7, 22, 8, 45), ms(2026, 7, 22, 21, 0), scheduleOnly)
        assertEquals("早到不额外算，仍按 09:00 起算", 11 * 60, s.finalMinutes)
    }

    // ---------- 情形 2：有默认工时 + 正常出勤 → 默认工时 ----------

    @Test fun withDefaultHours_normalAttendanceUsesDefaultHours() {
        // 08:45 到岗 / 21:00 离岗，默认 12h → 720（而非按窗口算出的 660）
        val s = engine.buildSession(ms(2026, 7, 22, 8, 45), ms(2026, 7, 22, 21, 0), withDefault)
        assertEquals(12 * 60, s.finalMinutes)
        assertEquals("应走默认工时短路", listOf("R_DEFAULT_HOURS"), s.v1RuleTrace)
        assertFalse(s.needsReview)
    }

    @Test fun withDefaultHours_earlyArrivalAloneIsNotLate() {
        // 08:00 到岗（早到 1h）不算迟到 → 仍计默认工时
        val s = engine.buildSession(ms(2026, 7, 22, 8, 0), ms(2026, 7, 22, 21, 0), withDefault)
        assertEquals(12 * 60, s.finalMinutes)
    }

    @Test fun withDefaultHours_departureWithinToleranceIsNormal() {
        // 20:58 离岗（早退 2min，在 3min 容差内）→ 不算早退 → 默认工时
        val s = engine.buildSession(ms(2026, 7, 22, 8, 45), ms(2026, 7, 22, 20, 58), withDefault)
        assertEquals(12 * 60, s.finalMinutes)
    }

    @Test fun withDefaultHours_lateDepartureStillUsesDefaultHours() {
        // 21:10 离岗（晚退 10min）→ 既非迟到也非早退 → 仍计默认工时；不计加班由默认工时保证
        val s = engine.buildSession(ms(2026, 7, 22, 8, 45), ms(2026, 7, 22, 21, 10), withDefault)
        assertEquals(12 * 60, s.finalMinutes)
    }

    // ---------- 情形 3：有默认工时 + 迟到/早退 → 回到公式 ----------

    @Test fun withDefaultHours_lateArrivalFallsBackToFormula() {
        // 09:49 到岗（迟到 49min）→ 短路失效 → ⌈09:49⌉=10:00，11h−1h = 600
        val s = engine.buildSession(ms(2026, 7, 22, 9, 49), ms(2026, 7, 22, 21, 0), withDefault)
        assertEquals("迟到时必须按公式，不得直接给默认工时", 600, s.finalMinutes)
        assertTrue("应记录短路被绕过：${s.v1RuleTrace}", s.v1RuleTrace.contains("R_DEFAULT_BYPASS_LATE"))
        assertTrue(s.v1RuleTrace.contains("R1_ALIGN_UP"))
    }

    @Test fun withDefaultHours_earlyLeaveFallsBackToFormula() {
        // 09:00 到岗 / 18:00 离岗（早退 3h）→ 短路失效 → 9h−1h = 480
        val s = engine.buildSession(ms(2026, 7, 22, 9, 0), ms(2026, 7, 22, 18, 0), withDefault)
        assertEquals("早退时必须按公式", 480, s.finalMinutes)
        assertTrue("应记录短路被绕过：${s.v1RuleTrace}", s.v1RuleTrace.contains("R_DEFAULT_BYPASS_EARLY"))
        assertTrue(s.v1RuleTrace.contains("R7"))
    }

    @Test fun withDefaultHours_lateAndEarlyFallsBackToFormula() {
        // 迟到 + 早退同时发生
        val s = engine.buildSession(ms(2026, 7, 22, 9, 49), ms(2026, 7, 22, 18, 0), withDefault)
        assertEquals("⌈09:49⌉=10:00 → 8h−1h = 420", 420, s.finalMinutes)
        assertTrue(s.needsReview)
    }

    // ---------- 夜班与白班算法一致 ----------

    @Test fun nightShiftWithDefaultHoursNormalAttendanceUsesDefaultHours() {
        // 20:50 上班 / 次日 09:05 下班 → 正常 → 默认工时
        val s = engine.buildSession(ms(2026, 7, 22, 20, 50), ms(2026, 7, 23, 9, 5), withDefault)
        assertEquals(ShiftType.NIGHT_SHIFT, s.shiftType)
        assertEquals(12 * 60, s.finalMinutes)
    }

    @Test fun nightShiftLateArrivalFallsBackToFormula() {
        // 22:30 上班（迟到 90min）→ ⌈22:30⌉=23:00 → 23:00→次日09:00 = 10h−1h = 540
        val s = engine.buildSession(ms(2026, 7, 22, 22, 30), ms(2026, 7, 23, 9, 0), withDefault)
        assertEquals(540, s.finalMinutes)
        assertTrue(s.v1RuleTrace.contains("R_DEFAULT_BYPASS_LATE"))
    }

    @Test fun nightShiftEarlyLeaveFallsBackToFormula() {
        // 21:00 上班 / 次日 07:00 下班（早退 2h）→ 10h−1h = 540
        val s = engine.buildSession(ms(2026, 7, 22, 21, 0), ms(2026, 7, 23, 7, 0), withDefault)
        assertEquals(540, s.finalMinutes)
        assertTrue(s.v1RuleTrace.contains("R_DEFAULT_BYPASS_EARLY"))
    }

    @Test fun nightShiftAndDayShiftShareTheSameAlgorithm() {
        // 核心断言：夜班算法与白班完全一致 —— 同样 12h 在岗、同样扣 1h → 都是 660
        val day = engine.buildSession(ms(2026, 7, 22, 9, 0), ms(2026, 7, 22, 21, 0), scheduleOnly)
        val night = engine.buildSession(ms(2026, 7, 22, 21, 0), ms(2026, 7, 23, 9, 0), scheduleOnly)
        assertEquals(11 * 60, day.finalMinutes)
        assertEquals("夜班应与白班算出同一结果", day.finalMinutes, night.finalMinutes)
        assertEquals(ShiftType.DAY_SHIFT, day.shiftType)
        assertEquals(ShiftType.NIGHT_SHIFT, night.shiftType)
    }
}
