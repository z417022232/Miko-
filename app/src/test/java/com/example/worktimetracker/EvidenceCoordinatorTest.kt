package com.example.worktimetracker

import com.example.worktimetracker.data.dao.EnvironmentEvidenceDao
import com.example.worktimetracker.data.entity.EnvironmentFingerprintEntity
import com.example.worktimetracker.data.entity.EvidenceObservationEntity
import com.example.worktimetracker.data.entity.LocationHealthEntity
import com.example.worktimetracker.domain.evidence.EvidenceFusionEngine
import com.example.worktimetracker.domain.evidence.EvidenceSource
import com.example.worktimetracker.domain.evidence.FingerprintLearningPolicy
import com.example.worktimetracker.domain.evidence.FingerprintLevel
import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import com.example.worktimetracker.location.evidence.AmbientCollector
import com.example.worktimetracker.location.evidence.CollectorFeature
import com.example.worktimetracker.location.evidence.CollectorFailure
import com.example.worktimetracker.location.evidence.CollectorResult
import com.example.worktimetracker.location.evidence.EvidenceCoordinator
import com.example.worktimetracker.location.evidence.GnssInput
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class EvidenceCoordinatorTest {

    private class FakeDao : EnvironmentEvidenceDao {
        val fingerprints = mutableListOf<EnvironmentFingerprintEntity>()
        val observations = mutableListOf<EvidenceObservationEntity>()
        val health = mutableListOf<LocationHealthEntity>()
        var deletedBeforeCutoff: Long? = null
        val trimmed = mutableListOf<Pair<String, Int>>()
        var upsertCount = 0

        val usedForEventCount: Int get() = observations.count { it.usedForEvent }
        val lastHealth: LocationHealthEntity? get() = health.lastOrNull()

        override suspend fun upsertFingerprint(fingerprint: EnvironmentFingerprintEntity) {
            upsertCount++
            fingerprints.removeAll {
                it.place == fingerprint.place && it.source == fingerprint.source &&
                    it.identifierHash == fingerprint.identifierHash
            }
            fingerprints += fingerprint
        }

        override suspend fun fingerprints(place: String, source: String): List<EnvironmentFingerprintEntity> =
            fingerprints.filter { it.place == place && it.source == source }

        override suspend fun allFingerprints(): List<EnvironmentFingerprintEntity> = fingerprints.toList()

        override suspend fun insertObservation(observation: EvidenceObservationEntity): Long {
            val entity = observation.copy(id = observations.size + 1L)
            observations += entity
            return entity.id
        }

        override suspend fun recentObservations(since: Long): List<EvidenceObservationEntity> =
            observations.filter { it.eventTime >= since }

        override suspend fun markUsedForEvent(id: Long, used: Boolean) {
            val index = observations.indexOfFirst { it.id == id }
            if (index >= 0) observations[index] = observations[index].copy(usedForEvent = used)
        }

        override suspend fun usedForEventCount(): Int = observations.count { it.usedForEvent }

        override suspend fun deleteObservationsBefore(cutoff: Long) {
            deletedBeforeCutoff = cutoff
            observations.removeAll { it.eventTime < cutoff }
        }

        override suspend fun trimObservations(source: String, keep: Int) {
            trimmed += source to keep
        }

        override suspend fun upsertHealth(h: LocationHealthEntity) {
            health.removeAll { it.name == h.name }
            health += h
        }

        override suspend fun allHealth(): List<LocationHealthEntity> = health.toList()

        override suspend fun health(name: String): LocationHealthEntity? = health.firstOrNull { it.name == name }
    }

    private class FakeCollector(private val result: CollectorResult) : AmbientCollector {
        override suspend fun snapshot(now: Long): CollectorResult = result
        override fun stop() = Unit
    }

    private val dao = FakeDao()
    private val emptyResult = CollectorResult(emptyList(), 0L, CollectorFailure.EMPTY)
    private fun features(source: EvidenceSource, count: Int) =
        (1..count).map { CollectorFeature(source, "hash-$source-$it", -70 - it) }

    private fun coordinator(
        wifi: AmbientCollector = FakeCollector(emptyResult),
        bluetooth: AmbientCollector = FakeCollector(emptyResult),
        cell: AmbientCollector = FakeCollector(emptyResult)
    ) = EvidenceCoordinator(
        dao, wifi, bluetooth, cell,
        FingerprintLearningPolicy(), EvidenceFusionEngine(),
        Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC)
    )

    @Test fun reliableGnssWritesFingerprintOnlyAfterFiveMinutesStable() = runTest {
        val wifi = FakeCollector(CollectorResult(features(EvidenceSource.WIFI, 2), 1_000_000L))
        val coordinator = coordinator(wifi = wifi)
        val early = coordinator.onGnss(
            GnssInput(1_000_000L, ResolvedPlace.HOME, 20f, inCore = true, stableSince = 1_000_000L - 4 * 60_000L)
        )
        assertEquals(0, dao.fingerprints.size)
        assertEquals(ResolvedPlace.HOME, early.place)
        coordinator.onGnss(
            GnssInput(1_000_000L, ResolvedPlace.HOME, 20f, inCore = true, stableSince = 1_000_000L - 5 * 60_000L)
        )
        assertEquals(2, dao.fingerprints.size)
        assertTrue(dao.fingerprints.all { it.place == ResolvedPlace.HOME.name })
    }

    @Test fun duplicateSameMinuteObservationSavedOnce() = runTest {
        val coordinator = coordinator()
        coordinator.onGnss(GnssInput(1_000_000L, ResolvedPlace.HOME, 20f, true, 1_000_000L))
        coordinator.onGnss(GnssInput(1_000_030L, ResolvedPlace.HOME, 20f, true, 1_000_000L))
        assertEquals(1, dao.observations.count { it.source == EvidenceSource.GNSS.name })
    }

    @Test fun thirtyDayOldObservationsAreCleaned() = runTest {
        val coordinator = coordinator()
        dao.insertObservation(
            EvidenceObservationEntity(eventTime = 1_000L, receivedAt = 1_000L,
                source = EvidenceSource.WIFI.name, quality = 0.9, placeHint = ResolvedPlace.HOME.name,
                identifierHash = null, signal = -60, usedForEvent = false)
        )
        val now = 1_000L + 31L * 24 * 60 * 60 * 1000
        coordinator.onGnss(GnssInput(now, ResolvedPlace.HOME, 20f, true, now - 60_000L))
        assertNotNull(dao.deletedBeforeCutoff)
        assertTrue(dao.deletedBeforeCutoff!! <= now - 30L * 24 * 60 * 60 * 1000)
        assertTrue(dao.observations.none { it.eventTime == 1_000L })
        assertTrue(dao.trimmed.isNotEmpty())
    }

    @Test fun staleFingerprintDecaysDuringCleanup() = runTest {
        val coordinator = coordinator()
        dao.fingerprints += EnvironmentFingerprintEntity(
            place = ResolvedPlace.HOME.name, source = EvidenceSource.WIFI.name,
            identifierHash = "old-fp", observationCount = 6, distinctDayCount = 3,
            lastObservedDay = "2026-08-01", lastObservedAt = 1_000L,
            minSignal = -90, maxSignal = -50, level = FingerprintLevel.STABLE.name, discriminative = true
        )
        val now = 1_000L + 31L * 24 * 60 * 60 * 1000
        coordinator.onServiceStart(now)
        assertEquals(FingerprintLevel.DECAYING.name, dao.fingerprints.single().level)
    }

    @Test fun ambientRoundKeepsAtMostSixtyFeatures() = runTest {
        val wifi = FakeCollector(CollectorResult(features(EvidenceSource.WIFI, 30), 1_000_000L))
        val bluetooth = FakeCollector(CollectorResult(features(EvidenceSource.BLUETOOTH, 30), 1_000_000L))
        val cell = FakeCollector(CollectorResult(features(EvidenceSource.CELL, 30), 1_000_000L))
        val coordinator = coordinator(wifi, bluetooth, cell)
        coordinator.onGnss(
            GnssInput(1_000_000L, ResolvedPlace.COMPANY, 20f, true, 1_000_000L - 6 * 60_000L)
        )
        assertTrue(dao.fingerprints.size <= 60)
        assertTrue(dao.upsertCount <= 60)
    }

    @Test fun failedAmbientCollectionDoesNotEmitDeparture() = runTest {
        val wifi = FakeCollector(CollectorResult.failed(CollectorFailure.PERMISSION, 2_000_000L))
        val coordinator = coordinator(wifi = wifi)
        val result = coordinator.collectAmbient(2_000_000L)
        assertEquals(ResolvedPlace.UNKNOWN, result.place)
        assertEquals(0, dao.usedForEventCount)
        assertEquals("wifi", dao.lastHealth!!.name)
        assertEquals(CollectorFailure.PERMISSION.name, dao.lastHealth!!.lastFailure)
    }

    @Test fun twoAmbientSourcesConfirmKnownPlace() = runTest {
        // 先学习两家指纹
        val wifiFeatures = listOf(CollectorFeature(EvidenceSource.WIFI, "wf-home-1", -60))
        val cellFeatures = listOf(CollectorFeature(EvidenceSource.CELL, "cell-home-1", -100))
        val wifi = FakeCollector(CollectorResult(wifiFeatures, 1_000_000L))
        val cell = FakeCollector(CollectorResult(cellFeatures, 1_000_000L))
        val coordinator = coordinator(wifi = wifi, cell = cell)
        coordinator.onGnss(
            GnssInput(1_000_000L, ResolvedPlace.HOME, 20f, true, 1_000_000L - 6 * 60_000L)
        )
        // 学习到的指纹还处于 CANDIDATE，不参与确认；GNSS 已过期后环境不应误确认
        assertEquals(FingerprintLevel.CANDIDATE.name, dao.fingerprints.first().level)

        // 将指纹手动晋级为 STABLE 后，GNSS 过期时环境证据应能确认地点
        dao.fingerprints.replaceAll { it.copy(level = FingerprintLevel.STABLE.name) }
        val fused = coordinator.collectAmbient(1_000_000L + 5 * 60_000L)
        assertEquals(ResolvedPlace.HOME, fused.place)
        assertTrue(dao.usedForEventCount >= 2)
    }

    @Test fun staleEvidenceMaintainsPlaceWithinContinuityWindow() = runTest {
        // 方案八：强证据确认 COMPANY 后证据全部陈旧，20 分钟连续性窗口内维持 COMPANY
        dao.insertObservation(EvidenceObservationEntity(
            eventTime = 1_000_000L, receivedAt = 1_000_000L,
            source = EvidenceSource.WIFI.name, quality = 0.90, placeHint = ResolvedPlace.COMPANY.name,
            identifierHash = null, signal = null, usedForEvent = false
        ))
        dao.insertObservation(EvidenceObservationEntity(
            eventTime = 1_000_000L, receivedAt = 1_000_000L,
            source = EvidenceSource.CELL.name, quality = 0.90, placeHint = ResolvedPlace.COMPANY.name,
            identifierHash = null, signal = null, usedForEvent = false
        ))
        val coordinator = coordinator()
        val confirmed = coordinator.collectAmbient(1_000_000L)
        assertEquals(FusedDecision.CONFIRMED, confirmed.decision)
        assertEquals(ResolvedPlace.COMPANY, confirmed.place)
        // 12 分钟后所有来源均超过各自 TTL：连续性窗口内维持，而非打断为 UNKNOWN
        val maintained = coordinator.collectAmbient(1_000_000L + 12 * 60_000L)
        assertEquals(ResolvedPlace.COMPANY, maintained.place)
        assertEquals(FusedDecision.MAINTAINED, maintained.decision)
        assertEquals("MAINTAIN_CONTINUITY", maintained.reason)
        // 25 分钟后连续性中断：回到 UNKNOWN
        val interrupted = coordinator.collectAmbient(1_000_000L + 25 * 60_000L)
        assertEquals(ResolvedPlace.UNKNOWN, interrupted.place)
        assertEquals(FusedDecision.UNKNOWN, interrupted.decision)
    }

    @Test fun networkLocationObservationsDoNotLearnFingerprints() = runTest {
        // 方案六：指纹学习只信任真正 GPS；Network Location 只参与融合
        val wifi = FakeCollector(CollectorResult(features(EvidenceSource.WIFI, 2), 1_000_000L))
        val coordinator = coordinator(wifi = wifi)
        coordinator.onGnss(
            GnssInput(1_000_000L, ResolvedPlace.COMPANY, 20f, true, 1_000_000L - 6 * 60_000L, provider = "network")
        )
        assertEquals(0, dao.fingerprints.size)
        val obs = dao.observations.single { it.source == EvidenceSource.NETWORK_LOCATION.name }
        assertEquals("network", obs.provider)
        assertEquals(20f, obs.accuracyMeters)
    }

    @Test fun sharedFingerprintAcrossPlacesLosesDiscriminative() = runTest {
        // 公司已学到 hash=shared-wifi 的 STABLE 指纹；在家再次观察到同一 hash
        //（如随身热点）：两边都应被 markCrossPlace 取消判别资格
        dao.fingerprints += EnvironmentFingerprintEntity(
            place = ResolvedPlace.COMPANY.name, source = EvidenceSource.WIFI.name,
            identifierHash = "shared-wifi", observationCount = 6, distinctDayCount = 3,
            lastObservedDay = "2026-09-01", lastObservedAt = 900_000L,
            minSignal = -70, maxSignal = -50, level = FingerprintLevel.STABLE.name, discriminative = true
        )
        val wifi = FakeCollector(
            CollectorResult(listOf(CollectorFeature(EvidenceSource.WIFI, "shared-wifi", -60)), 1_000_000L)
        )
        val coordinator = coordinator(wifi = wifi)
        coordinator.onGnss(
            GnssInput(1_000_000L, ResolvedPlace.HOME, 20f, true, 1_000_000L - 6 * 60_000L)
        )
        val company = dao.fingerprints.first { it.place == ResolvedPlace.COMPANY.name }
        assertTrue(!company.discriminative)
        // 该 hash 不应为家建立新的可用指纹（或已有也必须取消资格）
        assertTrue(
            dao.fingerprints.none {
                it.place == ResolvedPlace.HOME.name && it.identifierHash == "shared-wifi" && it.discriminative
            }
        )
    }

    @Test fun poorAccuracyGnssDoesNotConfirmPlace() = runTest {
        val coordinator = coordinator()
        val result = coordinator.onGnss(GnssInput(1_000_000L, ResolvedPlace.HOME, 120f, true, 1_000_000L))
        assertEquals(ResolvedPlace.UNKNOWN, result.place)
        assertNull(result.firstReliableAt)
    }
}
