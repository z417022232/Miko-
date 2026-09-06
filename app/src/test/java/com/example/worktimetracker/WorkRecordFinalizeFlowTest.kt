package com.example.worktimetracker

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.engine.WorkSessionEngine
import com.example.worktimetracker.domain.model.WorkSettings
import com.example.worktimetracker.location.service.ConfirmedSession
import com.example.worktimetracker.location.service.MergeMode
import com.example.worktimetracker.location.service.ProtectedRecordMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 正常下班 finalize 全链路测试：WorkSessionEngine 计算 → ConfirmedSession.merge → ProtectedRecordMerge。
 * 复现并守护 2026-09-06 的"粘性 needsReview"修复：草稿 endTime 为空时 finalize 补齐不产生人工审核。
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
            mode = MergeMode.FINALIZE_SESSION
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
        // 复现 9/6：08:39:21 到岗、21:06:25 离岗（09:00 上班 / 21:00 下班 / 容差 3 分钟）→ 不迟到不早退
        val final = finalize(at("08:39:21"), at("21:06:25"), draft(at("08:39:21")))
        assertFalse(final.needsReview)
        assertEquals("WORK", final.status)
        assertEquals(at("21:06:25"), final.endTime)
    }

    @Test
    fun lateArrivalKeepsReviewAndArrivalExceptionStatus() {
        // 09:10 到岗 > 09:03 阈值 → ARRIVAL_EXCEPTION 且 needsReview=true
        val final = finalize(at("09:10:00"), at("21:06:25"), draft(at("09:10:00")))
        assertTrue(final.needsReview)
        assertEquals("ARRIVAL_EXCEPTION", final.status)
    }

    @Test
    fun earlyLeaveKeepsReviewAndEarlyLeaveStatus() {
        // 20:30 离岗 < 20:57 阈值 → EARLY_LEAVE 且 needsReview=true
        val final = finalize(at("08:50:00"), at("20:30:00"), draft(at("08:50:00")))
        assertTrue(final.needsReview)
        assertEquals("EARLY_LEAVE", final.status)
    }

    @Test
    fun freshFinalizeWithoutDraftAlsoStaysClean() {
        // 无草稿直接落库（离岗计时器 CONFIRM 抢先于草稿的场景）
        val final = finalize(at("08:39:21"), at("21:06:25"), null)
        assertFalse(final.needsReview)
        assertEquals("WORK", final.status)
    }
}
