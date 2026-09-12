package com.example.worktimetracker

import com.example.worktimetracker.domain.engine.WorkSessionEngine
import com.example.worktimetracker.domain.model.RecordStatus
import com.example.worktimetracker.domain.model.ShiftType
import com.example.worktimetracker.domain.model.WorkSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A5/R5 跨夜归日强校验。
 *
 * 归属约定（2026-09-12 用户确认）：跨夜班次 workDate 一律取【上班日期（开班日）】。
 * 例：夜班 8/1 21:00 → 8/2 09:00 记 8/1；8/1 20:44 → 8/2 09:12 记 8/1。
 *
 * 这些测试是**护栏**：任何把 workDate 改成基于 endMillis 的改动都会在此失败。
 */
class CrossMidnightAssignmentTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val engine = WorkSessionEngine(zone)
    // 不配 defaultHours，走真实 v1 公式（R1–R8），而不是 R_DEFAULT_HOURS 直传
    private val settings = WorkSettings(workStartMinutes = 9 * 60, workEndMinutes = 21 * 60)

    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    // ---------- 归属日 ----------

    @Test fun nightShiftIsAssignedToStartDateEvenWhenEndIsNextDay() {
        // 8/1 20:44 上班 → 8/2 09:12 下班：按开班日归 8/1
        val s = engine.buildSession(ms(2026, 8, 1, 20, 44), ms(2026, 8, 2, 9, 12), settings)
        assertEquals("跨夜班次必须归上班日 8/1", "2026-08-01", s.assignedDate)
        assertEquals(ShiftType.NIGHT_SHIFT, s.shiftType)
        assertTrue("应识别为跨夜", s.crossesMidnight)
    }

    @Test fun workDateIsNeverDerivedFromEndDate() {
        val s = engine.buildSession(ms(2026, 8, 1, 21, 0), ms(2026, 8, 2, 9, 0), settings)
        assertFalse("workDate 不得等于下班日 8/2", s.assignedDate == "2026-08-02")
        assertEquals("2026-08-01", s.assignedDate)
        assertTrue(s.v1RuleTrace.contains("R5_CROSS_NIGHT"))
    }

    @Test fun missingStartForNightShiftStillOwnsPreviousNight() {
        // 只有下班证据（8/2 09:10）→ 推断为 8/1 夜班
        val s = engine.buildSession(null, ms(2026, 8, 2, 9, 10), settings)
        assertEquals("2026-08-01", s.assignedDate)
        assertEquals(ShiftType.NIGHT_SHIFT, s.shiftType)
        assertTrue(s.crossesMidnight)
    }

    @Test fun dayShiftDoesNotCrossMidnightAndKeepsSameDate() {
        val s = engine.buildSession(ms(2026, 9, 12, 9, 0), ms(2026, 9, 12, 21, 0), settings)
        assertEquals("2026-09-12", s.assignedDate)
        assertEquals(ShiftType.DAY_SHIFT, s.shiftType)
        assertFalse(s.crossesMidnight)
        assertFalse("白班不应带跨夜标记", s.v1RuleTrace.contains("R5_CROSS_NIGHT"))
    }

    // ---------- 跨夜班次边界（R2/R4 必须套在次日 09:00 上，而非日历 21:00）----------

    @Test fun nightShiftNineToNineIsTwelveHoursMinusRest() {
        // 8/1 21:00 → 8/2 09:00 = 12h，扣 1h 午休 = 660
        val s = engine.buildSession(ms(2026, 8, 1, 21, 0), ms(2026, 8, 2, 9, 0), settings)
        assertEquals(12 * 60, s.actualMinutes)
        assertEquals("11h = 660min", 660, s.finalMinutes)
        assertFalse("正常夜班下班不应需要复核", s.needsReview)
    }

    @Test fun nightShiftLateEndWithinToleranceDoesNotAddMinutes() {
        // 09:03 离岗 → R2 容差，截到 09:00，仍是 660
        val s = engine.buildSession(ms(2026, 8, 1, 21, 0), ms(2026, 8, 2, 9, 3), settings)
        assertEquals(660, s.finalMinutes)
        assertTrue(s.v1RuleTrace.contains("R2"))
    }

    @Test fun nightShiftLateEndBeyondToleranceIsCappedAndFlagged() {
        // 10:00 离岗 → 超出 09:00 一小时，R4 不计加班，截到 09:00
        val s = engine.buildSession(ms(2026, 8, 1, 21, 0), ms(2026, 8, 2, 10, 0), settings)
        assertEquals(660, s.finalMinutes)
        assertTrue("应标记不计加班", s.v1RuleTrace.contains("R4_NO_OVERTIME"))
        assertTrue(s.needsReview)
    }

    @Test fun nightShiftLateArrivalStillAlignsUpToHour() {
        // 8/1 21:40 上班 → 容差外，向上取整到 22:00；8/2 09:00 下班 → 11h - 1h = 600
        val s = engine.buildSession(ms(2026, 8, 1, 21, 40), ms(2026, 8, 2, 9, 0), settings)
        assertEquals(600, s.finalMinutes)
        assertTrue(s.v1RuleTrace.contains("R1_ALIGN_UP"))
    }

    // ---------- 白班回归：加 shiftType 参数后白班公式不得变 ----------

    @Test fun dayShiftLateArrivalStillYieldsSixHundred() {
        val s = engine.buildSession(ms(2026, 9, 12, 9, 49), ms(2026, 9, 12, 21, 0), settings)
        assertEquals(RecordStatus.ARRIVAL_EXCEPTION, s.status)
        assertEquals("⌈09:49⌉=10:00 → 11h-1h=600", 600, s.finalMinutes)
    }

    // ---------- 强校验本体 ----------

    @Test fun validateAssignmentPassesForStartDate() {
        val violations = engine.validateAssignment(ms(2026, 8, 1, 21, 0), "2026-08-01")
        assertTrue("上班日归属应无违规", violations.isEmpty())
    }

    @Test fun validateAssignmentFlagsEndDateMismatch() {
        // 故意传下班日 → 必须报违规（护栏：防止有人把 workDate 改成 endMillis 日期）
        val violations = engine.validateAssignment(ms(2026, 8, 1, 21, 0), "2026-08-02")
        assertEquals(1, violations.size)
        assertTrue(violations.first().contains("R5"))
        assertTrue(violations.first().contains("2026-08-01"))
    }

    @Test fun validateAssignmentIsSilentWhenNoStartEvidence() {
        assertTrue(engine.validateAssignment(null, "2026-08-02").isEmpty())
    }
}
