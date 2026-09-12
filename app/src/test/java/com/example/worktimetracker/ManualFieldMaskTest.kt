package com.example.worktimetracker

import com.example.worktimetracker.data.entity.ManualField
import com.example.worktimetracker.data.entity.ManualFieldMask
import com.example.worktimetracker.data.entity.WorkRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualFieldMaskTest {
    @Test fun onlySelectedFieldsAreProtected() {
        val mask = ManualFieldMask.add(0, ManualField.FINAL_MINUTES)
        assertTrue(ManualFieldMask.contains(mask, ManualField.FINAL_MINUTES))
        assertFalse(ManualFieldMask.contains(mask, ManualField.HOME_ARRIVAL))
    }

    @Test fun legacyManualMaskProtectsPresentFieldsButNotMissingFields() {
        val record = WorkRecordEntity(
            workDate = "2026-08-19", status = "MANUAL", shift = "NIGHT_SHIFT",
            startTime = 100L, endTime = null, finalMinutes = 660, isManual = true
        )
        val mask = ManualFieldMask.fromLegacy(record)
        assertTrue(ManualFieldMask.contains(mask, ManualField.SHIFT))
        assertTrue(ManualFieldMask.contains(mask, ManualField.COMPANY_ARRIVAL))
        assertTrue(ManualFieldMask.contains(mask, ManualField.FINAL_MINUTES))
        assertFalse(ManualFieldMask.contains(mask, ManualField.COMPANY_DEPARTURE))
        assertFalse(ManualFieldMask.contains(mask, ManualField.HOME_ARRIVAL))
    }

    // ---------- A3: 人工位 vs 自动痕迹位 ----------

    @Test fun autoBitsAreNotHumanProtection() {
        // 只有 AUTO_* 位时，不应被当成"有人工意图需要保护"
        val mask = ManualFieldMask.add(0, ManualField.AUTO_FINAL_MINUTES)
        val mask2 = ManualFieldMask.add(mask, ManualField.AUTO_NEEDS_REVIEW)
        assertFalse("纯自动痕迹不算人工保护", ManualFieldMask.hasHumanProtection(mask2))
        assertEquals(0, ManualFieldMask.humanOnly(mask2))
    }

    @Test fun humanBitStillCountsAsProtection() {
        // 加了人工位（用户手改 finalMinutes）→ 有人工保护
        var mask = ManualFieldMask.add(0, ManualField.AUTO_FINAL_MINUTES)
        mask = ManualFieldMask.add(mask, ManualField.FINAL_MINUTES)
        assertTrue(ManualFieldMask.hasHumanProtection(mask))
        assertEquals(ManualField.FINAL_MINUTES.bit, ManualFieldMask.humanOnly(mask))
    }

    @Test fun humanOnlyStripsAutoBitsButKeepsHumanBits() {
        // 人工位 + 自动位混合 → humanOnly 只留人工位
        var mask = ManualFieldMask.add(0, ManualField.SHIFT)
        mask = ManualFieldMask.add(mask, ManualField.AUTO_FINAL_MINUTES)
        mask = ManualFieldMask.add(mask, ManualField.AUTO_NEEDS_REVIEW)
        mask = ManualFieldMask.add(mask, ManualField.NEEDS_REVIEW_ACK)
        assertEquals(
            ManualField.SHIFT.bit or ManualField.NEEDS_REVIEW_ACK.bit,
            ManualFieldMask.humanOnly(mask)
        )
    }

    @Test fun autoFinalMinutesDetected() {
        val mask = ManualFieldMask.add(0, ManualField.AUTO_FINAL_MINUTES)
        assertTrue(ManualFieldMask.hasAutoFinalMinutes(mask))
        assertFalse(ManualFieldMask.hasAutoFinalMinutes(ManualField.FINAL_MINUTES.bit))
    }

    @Test fun needsReviewAckDetected() {
        val mask = ManualFieldMask.add(0, ManualField.NEEDS_REVIEW_ACK)
        assertTrue(ManualFieldMask.isNeedsReviewAcknowledged(mask))
        assertFalse(ManualFieldMask.isNeedsReviewAcknowledged(0))
    }

    @Test fun removeClearsBit() {
        var mask = ManualFieldMask.add(0, ManualField.NEEDS_REVIEW_ACK)
        mask = ManualFieldMask.remove(mask, ManualField.NEEDS_REVIEW_ACK)
        assertEquals(0, mask)
    }

    @Test fun bitLayoutIsBackwardCompatible() {
        // 关键：bit 0–6 必须是 2026-09-06 起沿用的历史位，不得改动
        assertEquals(1 shl 0, ManualField.SHIFT.bit)
        assertEquals(1 shl 1, ManualField.COMPANY_ARRIVAL.bit)
        assertEquals(1 shl 2, ManualField.COMPANY_DEPARTURE.bit)
        assertEquals(1 shl 3, ManualField.HOME_DEPARTURE.bit)
        assertEquals(1 shl 4, ManualField.HOME_ARRIVAL.bit)
        assertEquals(1 shl 5, ManualField.FINAL_MINUTES.bit)
        assertEquals(1 shl 6, ManualField.NOTE.bit)
        // A3 新增
        assertEquals(1 shl 7, ManualField.AUTO_FINAL_MINUTES.bit)
        assertEquals(1 shl 8, ManualField.AUTO_NEEDS_REVIEW.bit)
        assertEquals(1 shl 9, ManualField.NEEDS_REVIEW_ACK.bit)
    }
}