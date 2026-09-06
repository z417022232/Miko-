package com.example.worktimetracker

import com.example.worktimetracker.data.entity.ManualField
import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.location.service.MergeMode
import com.example.worktimetracker.location.service.ProtectedRecordMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedRecordMergeTest {
    @Test fun fillsUnlockedNullsWithoutChangingManualHours() {
        val existing = record(end=null, home=null, minutes=660, mask=ManualField.SHIFT.bit or ManualField.COMPANY_ARRIVAL.bit or ManualField.FINAL_MINUTES.bit)
        val merged = ProtectedRecordMerge.merge(existing, record(end=900, home=1000, minutes=720))
        assertEquals(660, merged.finalMinutes)
        assertEquals(900L, merged.endTime)
        assertEquals(1000L, merged.homeArrivalTime)
        assertTrue(merged.needsReview)
    }

    @Test fun neverChangesProtectedDeparture() {
        val existing = record(end=900, home=null, minutes=660, mask=ManualField.COMPANY_DEPARTURE.bit)
        assertEquals(900L, ProtectedRecordMerge.merge(existing, record(end=1000, home=1100, minutes=720)).endTime)
    }

    @Test fun finalizeModeFillsEndTimeWithoutMarkingReview() {
        // 正常下班：草稿缺 endTime，finalize 自动补齐是会话正常收尾，不再强制人工审核
        val existing = WorkRecordEntity(
            workDate="2026-09-06", status="WORK", shift="DAY_SHIFT", startTime=100,
            endTime=null, homeArrivalTime=null, finalMinutes=660, isManual=false
        )
        val merged = ProtectedRecordMerge.merge(existing, record(end=900, home=1000, minutes=720),
            MergeMode.FINALIZE_SESSION)
        assertEquals(900L, merged.endTime)
        assertFalse(merged.needsReview)
    }

    @Test fun repairFillModeStillMarksReviewWhenFilling() {
        // 服务恢复/历史修复：自动补缺失 endTime 代表证据中断，必须人工核对
        val existing = WorkRecordEntity(
            workDate="2026-09-06", status="WORK", shift="DAY_SHIFT", startTime=100,
            endTime=null, homeArrivalTime=null, finalMinutes=660, isManual=false
        )
        val merged = ProtectedRecordMerge.merge(existing, record(end=900, home=1000, minutes=720),
            MergeMode.REPAIR_FILL)
        assertTrue(merged.needsReview)
    }

    @Test fun protectedFieldsPreserveReviewInFinalizeMode() {
        // 人工保护字段：finalize 不覆盖字段值，原审核标记保留
        val existing = record(end=900, home=null, minutes=660, mask=ManualField.COMPANY_DEPARTURE.bit, review=true)
        val merged = ProtectedRecordMerge.merge(existing, record(end=1000, home=1100, minutes=720),
            MergeMode.FINALIZE_SESSION)
        assertEquals(900L, merged.endTime)
        assertTrue(merged.needsReview)
    }

    private fun record(end: Long?, home: Long?, minutes: Int, mask: Int = 0, review: Boolean = false) = WorkRecordEntity(
        workDate="2026-08-19", status="MANUAL", shift="NIGHT_SHIFT", startTime=100,
        endTime=end, homeArrivalTime=home, finalMinutes=minutes, isManual=mask!=0,
        manualFieldsMask=mask, needsReview=review
    )
}
