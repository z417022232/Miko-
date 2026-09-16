package com.example.worktimetracker

import com.example.worktimetracker.domain.evidence.EvidenceSource
import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.evidence.FusedStatusFormatter
import com.example.worktimetracker.domain.evidence.FusedStatusSnapshot
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FusedStatusFormatterTest {

    private fun snapshot(
        place: ResolvedPlace, decision: FusedDecision, reason: String,
        confidence: Double = 0.0,
        sources: Set<EvidenceSource> = emptySet(),
        breakdown: String? = null
    ) = FusedStatusSnapshot(place, decision, reason, confidence, sources, breakdown, 1_000_000L)

    @Test fun nullSnapshotMeansNoJudgment() {
        assertEquals("暂无位置判断", FusedStatusFormatter.headline(null))
    }

    @Test fun confirmedShowsPlaceWithConfidence() {
        val s = snapshot(ResolvedPlace.COMPANY, FusedDecision.CONFIRMED, "CONFIRMED_GNSS", 0.92)
        assertEquals("当前判断：公司", FusedStatusFormatter.headline(s))
        assertEquals("已确认", FusedStatusFormatter.decisionLabel(s.decision))
        assertEquals("92%", FusedStatusFormatter.confidenceLabel(s))
        assertEquals("GPS 定位确认", FusedStatusFormatter.reasonLabel(s.reason))
    }

    @Test fun maintainedShowsPlaceWithoutOverstating() {
        val s = snapshot(ResolvedPlace.COMPANY, FusedDecision.MAINTAINED,
            "MAINTAIN_WEAK_EVIDENCE", 0.70, setOf(EvidenceSource.WIFI))
        // MAINTAINED 是单源弱证据维持上一判断 → 语气必须比 CONFIRMED 弱一档
        assertEquals("暂时判断：公司", FusedStatusFormatter.headline(s))
        assertEquals("暂时维持", FusedStatusFormatter.decisionLabel(s.decision))
        assertEquals("当前只有单一环境来源，等待更多证据", FusedStatusFormatter.reasonLabel(s.reason))
        assertEquals("Wi-Fi", FusedStatusFormatter.sourcesLabel(s))
    }

    @Test fun continuityReasonExplainsMaintained() {
        assertEquals("上一判断的证据仍在有效期内，维持当前判断",
            FusedStatusFormatter.reasonLabel("MAINTAIN_CONTINUITY"))
    }

    @Test fun unknownConflictExplainsBothSides() {
        val s = snapshot(ResolvedPlace.UNKNOWN, FusedDecision.UNKNOWN,
            "UNKNOWN_CONFLICT:home=1.55 company=1.58 gap<0.15")
        assertEquals("当前位置暂不确定", FusedStatusFormatter.headline(s))
        assertEquals("位置不确定", FusedStatusFormatter.decisionLabel(s.decision))
        assertTrue(FusedStatusFormatter.reasonLabel(s.reason).contains("冲突"))
        assertNull(FusedStatusFormatter.confidenceLabel(s))
    }

    @Test fun staleAndNoDataExplanations() {
        assertTrue(FusedStatusFormatter.reasonLabel("UNKNOWN_STALE").contains("过期"))
        assertTrue(FusedStatusFormatter.reasonLabel("UNKNOWN_STALE").contains("不会因此修改工时"))
        assertEquals("当前没有可用的位置证据", FusedStatusFormatter.reasonLabel("UNKNOWN_NO_DATA"))
        assertEquals("证据不足，无法确认位置", FusedStatusFormatter.reasonLabel("UNKNOWN_LOW_CONFIDENCE"))
    }

    @Test fun unknownReasonFallsBackToRawText() {
        assertEquals("SOME_NEW_REASON", FusedStatusFormatter.reasonLabel("SOME_NEW_REASON"))
    }

    @Test fun sourcesLabelUsesChineseAndDistinct() {
        val s = snapshot(ResolvedPlace.HOME, FusedDecision.CONFIRMED, "CONFIRMED_AMBIENT",
            0.8, setOf(EvidenceSource.WIFI, EvidenceSource.BLUETOOTH, EvidenceSource.NETWORK_LOCATION))
        assertEquals("Wi-Fi · 蓝牙 · 网络定位", FusedStatusFormatter.sourcesLabel(s))
    }

    @Test fun emptySourcesAndConfidenceAreHidden() {
        val s = snapshot(ResolvedPlace.UNKNOWN, FusedDecision.UNKNOWN, "UNKNOWN_NO_DATA")
        assertNull(FusedStatusFormatter.sourcesLabel(s))
        assertNull(FusedStatusFormatter.confidenceLabel(s))
        assertFalse(FusedStatusFormatter.headline(s).contains("家"))
    }

    // -------------------------------------------- 位置主句（一眼看懂「现在在哪」）

    @Test fun placeSentenceSaysWhatUserWantsToKnow() {
        assertEquals("现在在家", FusedStatusFormatter.placeSentence(
            snapshot(ResolvedPlace.HOME, FusedDecision.CONFIRMED, "CONFIRMED_AMBIENT", 0.8)))
        assertEquals("现在在公司", FusedStatusFormatter.placeSentence(
            snapshot(ResolvedPlace.COMPANY, FusedDecision.CONFIRMED, "CONFIRMED_GNSS", 0.9)))
        assertEquals("正在路上", FusedStatusFormatter.placeSentence(
            snapshot(ResolvedPlace.MOVING, FusedDecision.CONFIRMED, "CONFIRMED_GNSS", 0.9)))
        assertEquals("在别的地方", FusedStatusFormatter.placeSentence(
            snapshot(ResolvedPlace.OTHER, FusedDecision.CONFIRMED, "CONFIRMED_GNSS", 0.9)))
        assertEquals("位置暂时判断不出来", FusedStatusFormatter.placeSentence(
            snapshot(ResolvedPlace.UNKNOWN, FusedDecision.UNKNOWN, "UNKNOWN_NO_DATA")))
        assertEquals("还没有位置判断", FusedStatusFormatter.placeSentence(null))
    }

    @Test fun unknownDecisionNeverNamesAPlace() {
        // 哪怕 place 字段残留了 HOME，只要决策是 UNKNOWN 就不能说「在家」
        val s = snapshot(ResolvedPlace.HOME, FusedDecision.UNKNOWN, "UNKNOWN_CONFLICT")
        assertEquals("位置暂时判断不出来", FusedStatusFormatter.placeSentence(s))
    }

    @Test fun maintainedPlaceSentenceIsTentativeNotConfirmed() {
        // 2026-09-16 复查 P1：MAINTAINED 不得写成确认式「现在在家」
        fun maintained(place: ResolvedPlace) = FusedStatusFormatter.placeSentence(
            snapshot(place, FusedDecision.MAINTAINED, "MAINTAIN_WEAK_EVIDENCE", 0.70))
        assertEquals("暂时判断仍在家", maintained(ResolvedPlace.HOME))
        assertEquals("暂时判断仍在公司", maintained(ResolvedPlace.COMPANY))
        assertEquals("可能还在路上", maintained(ResolvedPlace.MOVING))
        assertEquals("暂时判断还在别处", maintained(ResolvedPlace.OTHER))
        // 且确认式措辞一个字都不许出现
        listOf(ResolvedPlace.HOME, ResolvedPlace.COMPANY, ResolvedPlace.MOVING, ResolvedPlace.OTHER)
            .forEach { assertFalse(maintained(it).startsWith("现在")) }
    }

    // -------------------------------------------- 可信度档位（贴引擎真实门槛）

    @Test fun confirmedConfidenceTiers() {
        fun level(c: Double) = FusedStatusFormatter.confidenceLevel(
            snapshot(ResolvedPlace.HOME, FusedDecision.CONFIRMED, "CONFIRMED_AMBIENT", c))
        // 高档线就是引擎自己的可靠线 0.80：达到即可改变工时状态，UI 不该比它更保守
        assertEquals(FusedStatusFormatter.ConfidenceLevel.HIGH, level(0.92))
        assertEquals(FusedStatusFormatter.ConfidenceLevel.HIGH, level(0.85))
        assertEquals(FusedStatusFormatter.ConfidenceLevel.HIGH, level(0.80))
        assertEquals(FusedStatusFormatter.ConfidenceLevel.MEDIUM, level(0.79))
        assertEquals(FusedStatusFormatter.ConfidenceLevel.MEDIUM, level(0.75))
        assertEquals(FusedStatusFormatter.ConfidenceLevel.MEDIUM, level(0.70))
        assertEquals(FusedStatusFormatter.ConfidenceLevel.LOW, level(0.69))
        assertEquals("高", FusedStatusFormatter.ConfidenceLevel.HIGH.label)
        assertEquals("中", FusedStatusFormatter.ConfidenceLevel.MEDIUM.label)
        assertEquals("低", FusedStatusFormatter.ConfidenceLevel.LOW.label)
        assertEquals("—", FusedStatusFormatter.ConfidenceLevel.NONE.label)
    }

    @Test fun weakEvidenceNeverShowsHigh() {
        // MAINTAINED 是单源弱证据，只能维持上一判断 —— 不允许显示成「高」
        val s = snapshot(ResolvedPlace.COMPANY, FusedDecision.MAINTAINED,
            "MAINTAIN_WEAK_EVIDENCE", 0.95)
        assertEquals(FusedStatusFormatter.ConfidenceLevel.MEDIUM,
            FusedStatusFormatter.confidenceLevel(s))
        // 但它本来就没到高门槛时也不该被抬上来
        val low = snapshot(ResolvedPlace.COMPANY, FusedDecision.MAINTAINED,
            "MAINTAIN_WEAK_EVIDENCE", 0.60)
        assertEquals(FusedStatusFormatter.ConfidenceLevel.LOW,
            FusedStatusFormatter.confidenceLevel(low))
    }

    @Test fun unknownOrZeroConfidenceIsNone() {
        assertEquals(FusedStatusFormatter.ConfidenceLevel.NONE,
            FusedStatusFormatter.confidenceLevel(
                snapshot(ResolvedPlace.UNKNOWN, FusedDecision.UNKNOWN, "UNKNOWN_NO_DATA")))
        assertEquals(FusedStatusFormatter.ConfidenceLevel.NONE,
            FusedStatusFormatter.confidenceLevel(
                snapshot(ResolvedPlace.HOME, FusedDecision.CONFIRMED, "CONFIRMED_AMBIENT", 0.0)))
        assertEquals(FusedStatusFormatter.ConfidenceLevel.NONE,
            FusedStatusFormatter.confidenceLevel(null))
    }

    @Test fun basisLabelIsTheSameHumanTextAsReason() {
        assertEquals("GPS 定位确认", FusedStatusFormatter.basisLabel(
            snapshot(ResolvedPlace.COMPANY, FusedDecision.CONFIRMED, "CONFIRMED_GNSS", 0.92)))
        assertEquals("当前没有可用的位置证据", FusedStatusFormatter.basisLabel(
            snapshot(ResolvedPlace.UNKNOWN, FusedDecision.UNKNOWN, "UNKNOWN_NO_DATA")))
        assertNull(FusedStatusFormatter.basisLabel(null))
    }
}
