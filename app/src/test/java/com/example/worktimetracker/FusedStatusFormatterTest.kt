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
        assertEquals("当前判断：公司", FusedStatusFormatter.headline(s))
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
}
