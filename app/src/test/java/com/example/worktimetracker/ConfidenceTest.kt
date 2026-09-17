package com.example.worktimetracker

import com.example.worktimetracker.domain.learning.Confidence
import com.example.worktimetracker.domain.learning.ConfidenceLevel
import com.example.worktimetracker.domain.location.PlaceModelResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一可信等级（方案 §十 `Confidence.kt`）。
 *
 * 两个设计意图必须被守住：
 *  1. 档位线**引用已有门槛**，不另立魔数（否则 UI 上会出现第二套「高中低」）；
 *  2. 组合取**交**不取并 —— 「分数很高但只观察过 2 天」不许算高可信。
 */
class ConfidenceTest {

    @Test fun scoreBandsFollowTheDocumentedCutoffs() {
        assertEquals(ConfidenceLevel.NONE, Confidence.fromScore(0.0))
        assertEquals(ConfidenceLevel.NONE, Confidence.fromScore(-1.0))
        assertEquals(ConfidenceLevel.LOW, Confidence.fromScore(0.01))
        assertEquals(ConfidenceLevel.LOW, Confidence.fromScore(0.69))
        assertEquals(ConfidenceLevel.MEDIUM, Confidence.fromScore(0.70))
        assertEquals(ConfidenceLevel.MEDIUM, Confidence.fromScore(0.84))
        assertEquals(ConfidenceLevel.HIGH, Confidence.fromScore(0.85))
        assertEquals(ConfidenceLevel.HIGH, Confidence.fromScore(1.0))
    }

    @Test fun sampleDayBandsFollowTheAnchorGates() {
        assertEquals(ConfidenceLevel.NONE, Confidence.fromSampleDays(0))
        assertEquals(ConfidenceLevel.LOW, Confidence.fromSampleDays(1))
        assertEquals(ConfidenceLevel.LOW, Confidence.fromSampleDays(4))
        assertEquals(ConfidenceLevel.MEDIUM, Confidence.fromSampleDays(5))
        assertEquals(ConfidenceLevel.MEDIUM, Confidence.fromSampleDays(9))
        assertEquals(ConfidenceLevel.HIGH, Confidence.fromSampleDays(10))
    }

    @Test fun conservativeAlwaysPicksTheLowerBandRegardlessOfOrder() {
        assertEquals(ConfidenceLevel.LOW, Confidence.conservative(ConfidenceLevel.HIGH, ConfidenceLevel.LOW))
        assertEquals(ConfidenceLevel.LOW, Confidence.conservative(ConfidenceLevel.LOW, ConfidenceLevel.HIGH))
        assertEquals(ConfidenceLevel.NONE, Confidence.conservative(ConfidenceLevel.NONE, ConfidenceLevel.HIGH))
        assertEquals(ConfidenceLevel.MEDIUM, Confidence.conservative(ConfidenceLevel.MEDIUM, ConfidenceLevel.HIGH))
    }

    @Test fun combineNeverLetsAGreatScoreOutvoteThinSamples() {
        // 0.95 分（HIGH）× 只观察过 3 天（LOW）→ 必须落回 LOW
        assertEquals(ConfidenceLevel.LOW, Confidence.combine(score = 0.95, distinctDays = 3))
        // 分数低但观察期很长 → 也取低档，交集而非并集
        assertEquals(ConfidenceLevel.LOW, Confidence.combine(score = 0.30, distinctDays = 30))
        assertEquals(ConfidenceLevel.MEDIUM, Confidence.combine(score = 0.80, distinctDays = 6))
        assertEquals(ConfidenceLevel.HIGH, Confidence.combine(score = 0.90, distinctDays = 10))
    }

    @Test fun onlyHighAndMediumMayAutoApply() {
        assertTrue(ConfidenceLevel.HIGH.allowsAutoApply)
        assertTrue(ConfidenceLevel.MEDIUM.allowsAutoApply)
        assertFalse(ConfidenceLevel.LOW.allowsAutoApply)
        assertFalse(ConfidenceLevel.NONE.allowsAutoApply)
    }

    @Test fun autoApplyFloorIsTheSameLineAsThePlaceModelResolver() {
        // 两处门槛必须同源：改了一处忘了另一处，就会出现「模型说能生效、取用说不行」
        assertEquals(PlaceModelResolver.AUTO_APPLY_CONFIDENCE, Confidence.AUTO_APPLY_FLOOR, 1e-9)
        assertEquals(ConfidenceLevel.MEDIUM, Confidence.fromScore(Confidence.AUTO_APPLY_FLOOR))
    }

    @Test fun everyLevelHasAUserFacingChineseLabel() {
        assertEquals("高", ConfidenceLevel.HIGH.label)
        assertEquals("中", ConfidenceLevel.MEDIUM.label)
        assertEquals("低", ConfidenceLevel.LOW.label)
        assertEquals("—", ConfidenceLevel.NONE.label)
    }
}
