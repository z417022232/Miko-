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

    // ---------- v8.1 P2-7：时间衰减（有效质量） ----------

    @Test fun effectiveQualityDecaysLinearlyThenHitsFloor() {
        val now = 1_000_000L
        // Wi-Fi TTL = 5 分钟。age 0 → 1.0；age 60s → 0.8；age 150s（半程）→ 0.5（下限）
        assertEquals(1.0, engine.effectiveQuality(1.0, now, now, EvidenceSource.WIFI), 1e-9)
        assertEquals(0.8, engine.effectiveQuality(1.0, now - 60_000L, now, EvidenceSource.WIFI), 1e-9)
        assertEquals(0.5, engine.effectiveQuality(1.0, now - 150_000L, now, EvidenceSource.WIFI), 1e-9)
        // 越过半程后由 DECAY_FLOOR 兜底，不再继续下探到 0
        assertEquals(0.5, engine.effectiveQuality(1.0, now - 299_000L, now, EvidenceSource.WIFI), 1e-9)
    }

    @Test fun effectiveQualityIsZeroBeyondTtlInFutureAndForNonPlaceSources() {
        val now = 1_000_000L
        // 恰好等于 TTL 仍算有效，超过即 0（与 isFresh 口径一致）
        assertEquals(0.5, engine.effectiveQuality(1.0, now - 300_000L, now, EvidenceSource.WIFI), 1e-9)
        assertEquals(0.0, engine.effectiveQuality(1.0, now - 300_001L, now, EvidenceSource.WIFI), 1e-9)
        // 未来时间戳：负年龄不得被当成「最新鲜」
        assertEquals(0.0, engine.effectiveQuality(1.0, now + 1_000L, now, EvidenceSource.WIFI), 1e-9)
        // Motion / ShiftWindow 不作为地点证据
        assertEquals(0.0, engine.effectiveQuality(1.0, now, now, EvidenceSource.MOTION), 1e-9)
    }

    @Test fun decayNeverMovesTheConfirmGate() {
        // 复查口径：衰减只改排序与置信度，**不改确认门槛**。
        // 两条来源原始质量 0.75+0.75=1.50 ≥ 1.40，即便都已老化到接近 TTL，
        // 仍必须判为 CONFIRMED_COMPANY（门槛按原始质量算），只是置信度被衰减压低。
        val result = engine.resolve(
            listOf(
                e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.75, 1_000_000L - 200_000L),
                e(EvidenceSource.CELL, ResolvedPlace.COMPANY, 0.75, 1_000_000L - 200_000L)
            ),
            1_000_000L, ResolvedPlace.UNKNOWN
        )
        assertEquals(ResolvedPlace.COMPANY, result.place)
        assertEquals(FusedDecision.CONFIRMED, result.decision)
        assertEquals("CONFIRMED_AMBIENT", result.reason)
        // Wi-Fi 老化 200/300 秒 → 系数被 DECAY_FLOOR 兜到 0.5 → 0.375
        // 基站老化 200/600 秒 → 系数 2/3 → 0.500；合计 0.875，摊到两类 = 0.4375
        // 未衰减时本应是 0.75（0.75+0.75 摊两类），衰减确实把置信度压低了
        assertEquals(0.4375, result.confidence, 1e-6)
    }

    @Test fun agedSourcesCannotOutrankFreshConflictingOnes() {
        // 公司原始质量和 1.80 > 家 1.60，按原始分本该选公司；
        // 但公司两条都已临期（有效质量各 0.45），衰减后 0.90 < 家 1.60 → 必须选家。
        val result = engine.resolve(
            listOf(
                e(EvidenceSource.WIFI, ResolvedPlace.HOME, 0.80),
                e(EvidenceSource.CELL, ResolvedPlace.HOME, 0.80),
                e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.90, 1_000_000L - 280_000L),
                e(EvidenceSource.CELL, ResolvedPlace.COMPANY, 0.90, 1_000_000L - 550_000L)
            ),
            1_000_000L, ResolvedPlace.UNKNOWN
        )
        assertEquals(ResolvedPlace.HOME, result.place)
        assertEquals(FusedDecision.CONFIRMED, result.decision)
    }

    @Test fun weakMaintenanceConfidenceAlsoDecaysWithAge() {
        // 已确认公司后 GPS 消失，只剩一条 150 秒前的公司 Wi-Fi：
        // 维持 COMPANY，但置信度必须按年龄衰减（0.90 × 0.5 = 0.45），不能还是 0.90
        val result = engine.resolve(
            listOf(e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.90, 1_000_000L - 150_000L)),
            1_000_000L, ResolvedPlace.COMPANY
        )
        assertEquals(ResolvedPlace.COMPANY, result.place)
        assertEquals(FusedDecision.MAINTAINED, result.decision)
        assertEquals("MAINTAIN_WEAK_EVIDENCE", result.reason)
        assertEquals(0.45, result.confidence, 1e-9)
    }

    // ---------- v8.1 P2-7：切换候选（切换余量） ----------

    @Test fun newPlaceOnlySlightlyHigherDoesNotFlipPreviousPlace() {
        // 家 1.50（本轮仍成立），公司 1.70：分差 0.20 已越过冲突线，但不到 1.50×1.25=1.875，
        // 说明公司迹象还不足以推翻"在家" → 保持家、降级为 MAINTAINED
        val result = engine.resolve(
            listOf(
                e(EvidenceSource.WIFI, ResolvedPlace.HOME, 0.75),
                e(EvidenceSource.CELL, ResolvedPlace.HOME, 0.75),
                e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.85),
                e(EvidenceSource.CELL, ResolvedPlace.COMPANY, 0.85)
            ),
            1_000_000L, ResolvedPlace.HOME
        )
        assertEquals(ResolvedPlace.HOME, result.place)
        assertEquals(FusedDecision.MAINTAINED, result.decision)
        assertEquals("MAINTAIN_HELD_PREVIOUS", result.reason)
    }

    @Test fun clearlyHigherNewPlaceStillFlips() {
        // 公司 0.95+0.95=1.90 ≥ 1.50×1.25=1.875：确实换地方了，必须切
        val result = engine.resolve(
            listOf(
                e(EvidenceSource.WIFI, ResolvedPlace.HOME, 0.75),
                e(EvidenceSource.CELL, ResolvedPlace.HOME, 0.75),
                e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.95),
                e(EvidenceSource.CELL, ResolvedPlace.COMPANY, 0.95)
            ),
            1_000_000L, ResolvedPlace.HOME
        )
        assertEquals(ResolvedPlace.COMPANY, result.place)
        assertEquals(FusedDecision.CONFIRMED, result.decision)
    }

    @Test fun previousPlaceWithoutSupportThisRoundDoesNotBlockSwitch() {
        // 上一地点本轮毫无证据（家一条都没有）：切换余量不得成为路障
        val result = engine.resolve(
            listOf(
                e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.80),
                e(EvidenceSource.CELL, ResolvedPlace.COMPANY, 0.80)
            ),
            1_000_000L, ResolvedPlace.HOME
        )
        assertEquals(ResolvedPlace.COMPANY, result.place)
        assertEquals(FusedDecision.CONFIRMED, result.decision)
    }

    @Test fun reliableAbsoluteFixIgnoresSwitchMargin() {
        // 铁律：辅助证据不得推翻新鲜高精度 GNSS。上一地点是家也不能拦住可靠定位
        val result = engine.resolve(
            listOf(e(EvidenceSource.GNSS, ResolvedPlace.COMPANY, 0.95)),
            1_000_000L, ResolvedPlace.HOME
        )
        assertEquals(ResolvedPlace.COMPANY, result.place)
        assertEquals(FusedDecision.CONFIRMED, result.decision)
        assertTrue(result.reason.startsWith("CONFIRMED_GNSS"))
    }
}
