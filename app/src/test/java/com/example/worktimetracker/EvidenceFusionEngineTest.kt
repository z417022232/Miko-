package com.example.worktimetracker

import com.example.worktimetracker.domain.evidence.EvidenceFusionEngine
import com.example.worktimetracker.domain.evidence.EvidenceObservation
import com.example.worktimetracker.domain.evidence.EvidenceSource
import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceFusionEngineTest {
    private val engine = EvidenceFusionEngine()
    private fun e(source: EvidenceSource, place: ResolvedPlace, quality: Double, at: Long = 1_000_000L) =
        EvidenceObservation(at, at, source, quality, place, null, null)

    @Test fun strongGnssWinsOverAuxiliaryConflict() {
        val result = engine.resolve(listOf(
            e(EvidenceSource.GNSS, ResolvedPlace.COMPANY, 0.95),
            e(EvidenceSource.CELL, ResolvedPlace.HOME, 0.90),
            e(EvidenceSource.WIFI, ResolvedPlace.HOME, 0.90)
        ), 1_000_000L, ResolvedPlace.UNKNOWN)
        assertEquals(ResolvedPlace.COMPANY, result.place)
        assertEquals(1_000_000L, result.firstReliableAt)
        assertEquals(FusedDecision.CONFIRMED, result.decision)
    }

    @Test fun oneAuxiliarySourceCannotConfirmPlace() {
        val result = engine.resolve(
            listOf(e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.95)),
            1_000_000L, ResolvedPlace.HOME
        )
        assertEquals(ResolvedPlace.UNKNOWN, result.place)
        assertTrue(result.reason.startsWith("UNKNOWN_LOW_CONFIDENCE"))
    }

    @Test fun twoStableAuxiliarySourcesCanConfirmPlace() {
        val result = engine.resolve(listOf(
            e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.80),
            e(EvidenceSource.CELL, ResolvedPlace.COMPANY, 0.75)
        ), 1_000_000L, ResolvedPlace.UNKNOWN)
        assertEquals(ResolvedPlace.COMPANY, result.place)
        assertTrue(result.confidence >= 0.70)
        assertEquals(FusedDecision.CONFIRMED, result.decision)
    }

    @Test fun staleAmbientEvidenceMaintainsPreviousPlaceInsteadOfUnknown() {
        // 家 Wi-Fi 已陈旧；公司基站新鲜但单源不足：弱证据与上一地点一致 → 维持
        val result = engine.resolve(listOf(
            e(EvidenceSource.WIFI, ResolvedPlace.HOME, 0.90, 100_000L),
            e(EvidenceSource.CELL, ResolvedPlace.COMPANY, 0.90)
        ), 1_000_000L, ResolvedPlace.COMPANY)
        assertEquals(ResolvedPlace.COMPANY, result.place)
        assertEquals(FusedDecision.MAINTAINED, result.decision)
        assertEquals("MAINTAIN_WEAK_EVIDENCE", result.reason)
    }

    @Test fun poorQualityGnssIsNotDirectlyPreferred() {
        val result = engine.resolve(
            listOf(e(EvidenceSource.GNSS, ResolvedPlace.HOME, 0.60)),
            1_000_000L, ResolvedPlace.UNKNOWN
        )
        assertEquals(ResolvedPlace.UNKNOWN, result.place)
    }

    @Test fun motionIsNoLongerPlaceEvidence() {
        // 方案一：Motion 只负责唤醒重新取证，不再与单一环境来源凑确认
        val result = engine.resolve(listOf(
            e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.95),
            e(EvidenceSource.MOTION, ResolvedPlace.COMPANY, 0.90)
        ), 1_000_000L, ResolvedPlace.UNKNOWN)
        assertEquals(ResolvedPlace.UNKNOWN, result.place)
    }

    @Test fun closeHomeAndCompanyScoresResolveToConflictWithDetail() {
        val result = engine.resolve(listOf(
            e(EvidenceSource.WIFI, ResolvedPlace.HOME, 0.80),
            e(EvidenceSource.CELL, ResolvedPlace.HOME, 0.75),
            e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.80),
            e(EvidenceSource.CELL, ResolvedPlace.COMPANY, 0.78)
        ), 1_000_000L, ResolvedPlace.UNKNOWN)
        assertEquals(ResolvedPlace.UNKNOWN, result.place)
        assertTrue(result.reason.startsWith("UNKNOWN_CONFLICT"))
        assertTrue(result.reason.contains("home=1.55"))
        assertTrue(result.reason.contains("company=1.58"))
    }

    @Test fun higherScorePlaceWinsWhenBothSupported() {
        // 家 0.70+0.70=1.40，公司 0.95+0.95=1.90：分差足够大时必须选最高分的公司
        val result = engine.resolve(listOf(
            e(EvidenceSource.WIFI, ResolvedPlace.HOME, 0.70),
            e(EvidenceSource.CELL, ResolvedPlace.HOME, 0.70),
            e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.95),
            e(EvidenceSource.CELL, ResolvedPlace.COMPANY, 0.95)
        ), 1_000_000L, ResolvedPlace.UNKNOWN)
        assertEquals(ResolvedPlace.COMPANY, result.place)
        assertEquals(FusedDecision.CONFIRMED, result.decision)
    }

    @Test fun repeatedLowQualityScansDoNotAccumulateIntoConfirmation() {
        // 两轮 Wi-Fi+基站质量均 0.4：单轮 0.8 不足以确认，重复扫描累加后也不允许确认
        val result = engine.resolve(listOf(
            e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.40, 1_000_000L),
            e(EvidenceSource.CELL, ResolvedPlace.COMPANY, 0.40, 1_000_000L),
            e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.40, 900_000L),
            e(EvidenceSource.CELL, ResolvedPlace.COMPANY, 0.40, 900_000L)
        ), 1_000_000L, ResolvedPlace.UNKNOWN)
        assertEquals(ResolvedPlace.UNKNOWN, result.place)
    }

    @Test fun weakSingleSourceMaintainsPreviousPlace() {
        // 方案三：已确认公司后 GPS 消失，只剩公司 Wi-Fi → 维持 COMPANY，不转 UNKNOWN
        val result = engine.resolve(
            listOf(e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.70)),
            1_000_000L, ResolvedPlace.COMPANY
        )
        assertEquals(ResolvedPlace.COMPANY, result.place)
        assertEquals(FusedDecision.MAINTAINED, result.decision)
    }

    @Test fun staleBluetoothBeyondTtlCannotConfirm() {
        // 方案五：蓝牙 TTL 3 分钟——4 分钟前的设备已不能证明「现在还在那里」
        val result = engine.resolve(listOf(
            e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.75),
            e(EvidenceSource.BLUETOOTH, ResolvedPlace.COMPANY, 0.75, 1_000_000L - 4 * 60_000L)
        ), 1_000_000L, ResolvedPlace.UNKNOWN)
        assertEquals(ResolvedPlace.UNKNOWN, result.place)
    }

    @Test fun freshBluetoothWithinTtlCanConfirm() {
        val result = engine.resolve(listOf(
            e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.75),
            e(EvidenceSource.BLUETOOTH, ResolvedPlace.COMPANY, 0.75, 1_000_000L - 2 * 60_000L)
        ), 1_000_000L, ResolvedPlace.UNKNOWN)
        assertEquals(ResolvedPlace.COMPANY, result.place)
        assertEquals(FusedDecision.CONFIRMED, result.decision)
    }

    @Test fun networkLocationCanDirectlyConfirmWhenReliable() {
        // 方案六：Network Location 是独立来源，可靠时同样可以直接确认
        val result = engine.resolve(
            listOf(e(EvidenceSource.NETWORK_LOCATION, ResolvedPlace.HOME, 0.85)),
            1_000_000L, ResolvedPlace.UNKNOWN
        )
        assertEquals(ResolvedPlace.HOME, result.place)
        assertEquals(FusedDecision.CONFIRMED, result.decision)
        assertTrue(result.reason.contains("NETWORK_LOCATION"))
    }

    @Test fun noObservationsYieldNoDataReason() {
        val result = engine.resolve(emptyList(), 1_000_000L, ResolvedPlace.UNKNOWN)
        assertEquals(ResolvedPlace.UNKNOWN, result.place)
        assertTrue(result.reason.startsWith("UNKNOWN_NO_DATA"))
    }

    @Test fun futureEvidenceIsIgnored() {
        val result = engine.resolve(
            listOf(e(EvidenceSource.GNSS, ResolvedPlace.HOME, 0.95, 2_000_000L)),
            1_000_000L, ResolvedPlace.UNKNOWN
        )
        assertEquals(ResolvedPlace.UNKNOWN, result.place)
        assertTrue(result.reason.startsWith("UNKNOWN_STALE"))
    }
}
