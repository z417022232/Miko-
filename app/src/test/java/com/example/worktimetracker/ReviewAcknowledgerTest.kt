package com.example.worktimetracker

import com.example.worktimetracker.data.entity.ManualField
import com.example.worktimetracker.data.entity.ManualFieldMask
import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.engine.ReviewAcknowledger
import com.example.worktimetracker.domain.engine.ReviewRecordEditor
import com.example.worktimetracker.location.service.ConfirmedSession
import com.example.worktimetracker.location.service.MergeMode
import com.example.worktimetracker.location.service.ProtectedRecordMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A6: 灰区记录「认可复核」路径。
 *
 * 核心不变量：**认可 ≠ 改值**。认可只清 needsReview + 写 NEEDS_REVIEW_ACK，
 * 不得把 finalMinutes 标成人工保护位（否则自动算法永久锁死，属 A3 修过的同类缺陷）。
 */
class ReviewAcknowledgerTest {

    private fun greyRecord() = WorkRecordEntity(
        id = 42,
        workDate = "2026-09-12",
        status = "WORK",
        shift = "DAY_SHIFT",
        startTime = 1_000_000L,
        endTime = 2_000_000L,
        actualMinutes = 673,
        finalMinutes = 660,
        needsReview = true,
        reviewReason = "R3 21:00-21:29 灰区",
        // A2/A3 自动痕迹：自动算过 finalMinutes + 自动判过 needsReview
        manualFieldsMask = ManualField.AUTO_FINAL_MINUTES.bit or ManualField.AUTO_NEEDS_REVIEW.bit
    )

    @Test fun acknowledgeClearsReviewAndSetsAckBit() {
        val result = ReviewAcknowledger.acknowledge(greyRecord(), note = "", now = 9L).getOrThrow()
        assertFalse("认可后不应再需要确认", result.needsReview)
        assertTrue("必须写 NEEDS_REVIEW_ACK 位", ManualFieldMask.isNeedsReviewAcknowledged(result.manualFieldsMask))
    }

    @Test fun acknowledgeDoesNotLockFinalMinutesWithHumanBit() {
        val before = greyRecord()
        val after = ReviewAcknowledger.acknowledge(before, now = 9L).getOrThrow()
        assertFalse("认可不是手工记录", after.isManual)
        assertFalse(
            "不得标 FINAL_MINUTES 人工位——否则自动算法永久锁死",
            ManualFieldMask.contains(after.manualFieldsMask, ManualField.FINAL_MINUTES)
        )
        assertEquals(
            "认可只应在 HUMAN_BITS 里留下 NEEDS_REVIEW_ACK",
            ManualField.NEEDS_REVIEW_ACK.bit,
            ManualFieldMask.humanOnly(after.manualFieldsMask)
        )
    }

    @Test fun acknowledgeKeepsAutoTraceBits() {
        val after = ReviewAcknowledger.acknowledge(greyRecord(), now = 9L).getOrThrow()
        assertTrue("自动算过 finalMinutes 的痕迹应保留", ManualFieldMask.hasAutoFinalMinutes(after.manualFieldsMask))
        assertTrue("自动判过 needsReview 的痕迹应保留", ManualFieldMask.hasAutoNeedsReview(after.manualFieldsMask))
    }

    @Test fun acknowledgeKeepsAllValuesUntouched() {
        val before = greyRecord()
        val after = ReviewAcknowledger.acknowledge(before, now = 9L).getOrThrow()
        assertEquals(before.finalMinutes, after.finalMinutes)
        assertEquals(before.actualMinutes, after.actualMinutes)
        assertEquals(before.status, after.status)
        assertEquals(before.shift, after.shift)
        assertEquals(before.startTime, after.startTime)
        assertEquals(before.endTime, after.endTime)
        assertEquals(before.reviewReason, after.reviewReason)
        assertEquals(before.isManual, after.isManual)
        assertEquals(9L, after.updatedAt)
    }

    @Test fun acknowledgeRejectsRecordThatNeedsNoReview() {
        val clean = greyRecord().copy(needsReview = false)
        assertTrue("已确认过的记录再点认可应报错", ReviewAcknowledger.acknowledge(clean).isFailure)
    }

    @Test fun noteIsOnlyOverwrittenWhenNonBlank() {
        val withNote = greyRecord().copy(note = "原备注")
        assertEquals("原备注", ReviewAcknowledger.acknowledge(withNote, note = "  ", now = 9L).getOrThrow().note)
        assertEquals("用户备注", ReviewAcknowledger.acknowledge(withNote, note = "用户备注", now = 9L).getOrThrow().note)
    }

    // ---------- 与合并语义的协同 ----------

    @Test fun acknowledgedRecordSurvivesProtectedMerge() {
        val acknowledged = ReviewAcknowledger.acknowledge(greyRecord(), now = 9L).getOrThrow()
        val automatic = acknowledged.copy(
            finalMinutes = 660,
            needsReview = false,
            manualFieldsMask = acknowledged.manualFieldsMask or ManualField.AUTO_NEEDS_REVIEW.bit
        )
        val merged = ProtectedRecordMerge.merge(acknowledged, automatic, MergeMode.FINALIZE_SESSION)
        assertFalse("已认可记录不应被自动合并重新拉回待确认", merged.needsReview)
        assertTrue("ACK 人工位必须被保留", ManualFieldMask.isNeedsReviewAcknowledged(merged.manualFieldsMask))
    }

    @Test fun autoRefinalizeClearsAckSoNewSituationMustBeReconfirmed() {
        // 刻意行为：自动重算（v1RuleTrace 非空且非手工记录）会清掉 ACK，情况变了要重新确认
        val acknowledged = ReviewAcknowledger.acknowledge(greyRecord(), now = 9L).getOrThrow()
        val refinalized = ConfirmedSession.merge(
            existing = acknowledged,
            shift = "DAY_SHIFT",
            companyArrival = 1_000_000L,
            companyDeparture = 2_000_000L,
            homeDeparture = null,
            homeArrival = null,
            actualMinutes = 673,
            calculatedMinutes = 660,
            needsReview = false,
            status = "WORK",
            mode = MergeMode.FINALIZE_SESSION,
            v1RuleTrace = listOf("R3_GREY", "R8")
        )
        assertFalse(
            "自动重算后 ACK 应失效（下次需重新确认）",
            ManualFieldMask.isNeedsReviewAcknowledged(refinalized.manualFieldsMask)
        )
    }

    // ---------- 编辑确认也记 ACK ----------

    @Test fun editConfirmAlsoRecordsAckBit() {
        val edited = ReviewRecordEditor.confirm(
            existing = greyRecord(),
            shift = "DAY_SHIFT",
            startMillis = 1_000_000L,
            endMillis = 2_000_000L,
            finalMinutes = 660,
            note = "人工修正",
            now = 9L
        ).getOrThrow()
        assertTrue("改完确认也应记 ACK", ManualFieldMask.isNeedsReviewAcknowledged(edited.manualFieldsMask))
        assertFalse(edited.needsReview)
        assertTrue("编辑路径应保持人工锁", ManualFieldMask.contains(edited.manualFieldsMask, ManualField.FINAL_MINUTES))
    }
}
