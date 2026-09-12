package com.example.worktimetracker

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.engine.WorkSessionEngine
import com.example.worktimetracker.domain.model.WorkSettings
import com.example.worktimetracker.location.service.ConfirmedSession
import com.example.worktimetracker.location.service.MergeMode
import com.example.worktimetracker.location.service.ProtectedRecordMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 正常下班 finalize 全链路测试：WorkSessionEngine 计算 → ConfirmedSession.merge → ProtectedRecordMerge。
 * 复现并守护 2026-09-06 的"粘性 needsReview"修复：草稿 endTime 为空时 finalize 补齐不产生人工审核。
 *
 * A1 阶段扩展（2026-09-12）：v1 规则 R1–R8 落地到 finalize 链路，自动算 finalMinutes + 标 needsReview。
 */
class WorkRecordFinalizeFlowTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val engine = WorkSessionEngine(zone)
    // 与手机 user_settings 一致：09:00-21:00，迟到/早退容差 3 分钟
    private val settings = WorkSettings(
        workStartMinutes = 9 * 60, workEndMinutes = 21 * 60,
        earlyLeaveToleranceMinutes = 3, arrivalToleranceMinutes = 3
    )

    private fun at(time: String): Long =
        LocalDateTime.parse("2026-09-06T$time").atZone(zone).toInstant().toEpochMilli()

    private fun finalize(start: Long, end: Long, existing: WorkRecordEntity?): WorkRecordEntity {
        val session = engine.buildSession(start, end, settings)
        val recordToSave = ConfirmedSession.merge(
            existing = existing ?: WorkRecordEntity(workDate = session.assignedDate, status = session.status.name),
            shift = session.shiftType.name,
            companyArrival = start,
            companyDeparture = end,
            homeDeparture = at("08:26:54").takeIf { it <= start },
            homeArrival = at("21:17:28").takeIf { it >= end },
            actualMinutes = session.actualMinutes,
            calculatedMinutes = session.finalMinutes,
            needsReview = session.needsReview,
            status = session.status.name,
            mode = MergeMode.FINALIZE_SESSION,
            v1RuleTrace = session.v1RuleTrace,
            v1EffectiveStartMillis = session.v1EffectiveStartMillis,
            v1EffectiveEndMillis = session.v1EffectiveEndMillis
        )
        return if (existing != null) ProtectedRecordMerge.merge(existing, recordToSave, MergeMode.FINALIZE_SESSION)
        else recordToSave
    }

    private fun draft(start: Long) = WorkRecordEntity(
        workDate = "2026-09-06", status = "WORK", shift = "DAY_SHIFT",
        startTime = start, endTime = null, needsReview = false
    )

    @Test
    fun fullAttendanceDayFinalizesWithoutReview() {
        // R2 容差：21:00±3min 内离开 = 正常下班；按 v1 规则严格 effective_end=21:00 → final=11h
        val final = finalize(at("08:39:21"), at("21:03:00"), draft(at("08:39:21")))
        assertFalse(final.needsReview)
        assertEquals("WORK", final.status)
        assertEquals(at("21:03:00"), final.endTime)
        assertEquals(11 * 60, final.finalMinutes)
    }

    @Test
    fun lateArrivalKeepsReviewAndArrivalExceptionStatus() {
        // 09:10 到岗（迟到 10min > 3min 容差）→ ARRIVAL_EXCEPTION + needsReview=true
        // 21:00 整下班（R2）→ final=10h（10:00 向上取整 + 21:00 - 60min）
        val final = finalize(at("09:10:00"), at("21:00:00"), draft(at("09:10:00")))
        assertTrue(final.needsReview)
        assertEquals("ARRIVAL_EXCEPTION", final.status)
        assertEquals(10 * 60, final.finalMinutes)
    }

    @Test
    fun lateArrival2129DepartureFlagsGreyZoneReview() {
        // 9/12 实战场景：09:49 上班（迟到 49min 向上取整到 10:00）、21:06 离岗（21:00–21:29 灰区）
        // → ARRIVAL_EXCEPTION + needsReview=true（R3 灰区标记）+ final=10h
        val final = finalize(at("09:49:00"), at("21:06:00"), draft(at("09:49:00")))
        assertTrue(final.needsReview)
        assertEquals("ARRIVAL_EXCEPTION", final.status)
        assertEquals(10 * 60, final.finalMinutes)
        assertNotNull(final.note)
        assertTrue("note 应说明 R3 灰区", final.note!!.contains("R3") || final.note!!.contains("灰区"))
    }

    @Test
    fun lateArrival2130DepartureFlagsNoOvertime() {
        // 09:10 上班（向上取整到 10:00）、21:30 离岗（21:30+ 不计加班 R4）
        // → needsReview=true + final=10h（effective_end 截到 21:00）
        val final = finalize(at("09:10:00"), at("21:30:00"), draft(at("09:10:00")))
        assertTrue(final.needsReview)
        assertEquals("ARRIVAL_EXCEPTION", final.status)
        assertEquals(10 * 60, final.finalMinutes)
        assertNotNull(final.note)
        assertTrue("note 应说明 R4 不计加班", final.note!!.contains("R4") || final.note!!.contains("不计加班"))
    }

    @Test
    fun earlyLeaveAt1800FinalizesAs8h() {
        // R7：18:00 早退 = 8h（9:00-18:00-60min）+ needsReview=true（早退必复核）
        val final = finalize(at("08:50:00"), at("18:00:00"), draft(at("08:50:00")))
        assertTrue(final.needsReview)
        assertEquals("EARLY_LEAVE", final.status)
        assertEquals(8 * 60, final.finalMinutes)
    }

    @Test
    fun earlyLeaveAt2030KeepsReviewAndEarlyLeaveStatus() {
        // 20:30 离岗 < 20:57 阈值 → EARLY_LEAVE + needsReview=true
        val final = finalize(at("08:50:00"), at("20:30:00"), draft(at("08:50:00")))
        assertTrue(final.needsReview)
        assertEquals("EARLY_LEAVE", final.status)
    }

    @Test
    fun freshFinalizeWithoutDraftAlsoStaysClean() {
        // 无草稿直接落库（离岗计时器 CONFIRM 抢先于草稿的场景）；21:03 R2 容差内 = 正常下班
        val final = finalize(at("08:39:21"), at("21:03:00"), null)
        assertFalse(final.needsReview)
        assertEquals("WORK", final.status)
        assertEquals(11 * 60, final.finalMinutes)
    }

    @Test
    fun v1NoteIsAutoWrittenForGreyZone() {
        // 验证 R3 灰区触发自动写 note：迟到 + 灰区离岗 → note 非空且包含规则 ID
        val final = finalize(at("09:49:00"), at("21:06:00"), draft(at("09:49:00")))
        assertNotNull(final.note)
        assertTrue("note 应含 v1 规则 trace", final.note!!.startsWith("v1["))
        // manualFieldsMask 应标 FINAL_MINUTES 位（v1 自动算的）
        val finalBit = 1 shl 5 // ManualField.FINAL_MINUTES.bit
        assertTrue("manualFieldsMask 应标 FINAL_MINUTES 位", (final.manualFieldsMask and finalBit) != 0)
    }
}