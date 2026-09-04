package com.example.worktimetracker

import com.example.worktimetracker.domain.evidence.EvidenceFusionEngine
import com.example.worktimetracker.domain.evidence.EvidenceObservation
import com.example.worktimetracker.domain.evidence.EvidenceSource
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
    }

    @Test fun oneAuxiliarySourceCannotConfirmPlace() {
        val result = engine.resolve(
            listOf(e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.95)),
            1_000_000L, ResolvedPlace.HOME
        )
        assertEquals(ResolvedPlace.UNKNOWN, result.place)
    }

    @Test fun twoStableAuxiliarySourcesCanConfirmPlace() {
        val result = engine.resolve(listOf(
            e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.80),
            e(EvidenceSource.CELL, ResolvedPlace.COMPANY, 0.75)
        ), 1_000_000L, ResolvedPlace.UNKNOWN)
        assertEquals(ResolvedPlace.COMPANY, result.place)
        assertTrue(result.confidence >= 0.70)
    }

    @Test fun staleEvidenceIsIgnoredAndConflictIsUnknown() {
        val result = engine.resolve(listOf(
            e(EvidenceSource.WIFI, ResolvedPlace.HOME, 0.90, 100_000L),
            e(EvidenceSource.CELL, ResolvedPlace.COMPANY, 0.90)
        ), 1_000_000L, ResolvedPlace.COMPANY)
        assertEquals(ResolvedPlace.UNKNOWN, result.place)
    }

    @Test fun poorQualityGnssIsNotDirectlyPreferred() {
        val result = engine.resolve(
            listOf(e(EvidenceSource.GNSS, ResolvedPlace.HOME, 0.60)),
            1_000_000L, ResolvedPlace.UNKNOWN
        )
        assertEquals(ResolvedPlace.UNKNOWN, result.place)
    }

    @Test fun singleAmbientSourceWithMotionCanConfirmPlace() {
        val result = engine.resolve(listOf(
            e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.95),
            e(EvidenceSource.MOTION, ResolvedPlace.COMPANY, 0.90)
        ), 1_000_000L, ResolvedPlace.UNKNOWN)
        assertEquals(ResolvedPlace.COMPANY, result.place)
    }

    @Test fun closeHomeAndCompanyScoresResolveToUnknown() {
        val result = engine.resolve(listOf(
            e(EvidenceSource.WIFI, ResolvedPlace.HOME, 0.80),
            e(EvidenceSource.CELL, ResolvedPlace.HOME, 0.75),
            e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.80),
            e(EvidenceSource.CELL, ResolvedPlace.COMPANY, 0.78)
        ), 1_000_000L, ResolvedPlace.UNKNOWN)
        assertEquals(ResolvedPlace.UNKNOWN, result.place)
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

    @Test fun futureEvidenceIsIgnored() {
        val result = engine.resolve(
            listOf(e(EvidenceSource.GNSS, ResolvedPlace.HOME, 0.95, 2_000_000L)),
            1_000_000L, ResolvedPlace.UNKNOWN
        )
        assertEquals(ResolvedPlace.UNKNOWN, result.place)
    }
}
