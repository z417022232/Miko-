package com.example.worktimetracker

import com.example.worktimetracker.data.dao.EnvironmentEvidenceDao
import com.example.worktimetracker.data.entity.EnvironmentFingerprintEntity
import com.example.worktimetracker.data.entity.EvidenceObservationEntity
import com.example.worktimetracker.data.entity.LocationHealthEntity
import com.example.worktimetracker.domain.evidence.EvidenceFusionEngine
import com.example.worktimetracker.domain.evidence.EvidenceSource
import com.example.worktimetracker.domain.evidence.FingerprintLearningPolicy
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import com.example.worktimetracker.location.evidence.AmbientCollector
import com.example.worktimetracker.location.evidence.CollectorResult
import com.example.worktimetracker.location.evidence.EvidenceCoordinator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 多源证据数据量边界：观察明细只保留 30 天、每来源最多 10,000 条、
 * 单轮最多 60 个环境特征、同一分钟证据合并、稳定已知地点不持续扫描。
 */
class EvidenceRetentionPolicyTest {

    private class RecordingDao : EnvironmentEvidenceDao {
        val observations = mutableListOf<EvidenceObservationEntity>()
        var deletedBeforeCutoff: Long? = null
        val trimmed = mutableListOf<Pair<String, Int>>()

        override suspend fun upsertFingerprint(fingerprint: EnvironmentFingerprintEntity) = Unit
        override suspend fun fingerprints(place: String, source: String): List<EnvironmentFingerprintEntity> = emptyList()
        override suspend fun allFingerprints(): List<EnvironmentFingerprintEntity> = emptyList()

        override suspend fun insertObservation(observation: EvidenceObservationEntity): Long {
            observations += observation
            return observations.size.toLong()
        }

        override suspend fun recentObservations(since: Long): List<EvidenceObservationEntity> =
            observations.filter { it.eventTime >= since }

        override suspend fun markUsedForEvent(id: Long, used: Boolean) = Unit
        override suspend fun usedForEventCount(): Int = 0
        override suspend fun deleteObservationsBefore(cutoff: Long) {
            deletedBeforeCutoff = cutoff
            observations.removeAll { it.eventTime < cutoff }
        }

        override suspend fun trimObservations(source: String, keep: Int) {
            trimmed += source to keep
        }

        override suspend fun upsertHealth(health: LocationHealthEntity) = Unit
        override suspend fun allHealth(): List<LocationHealthEntity> = emptyList()
        override suspend fun health(name: String): LocationHealthEntity? = null
    }

    private class EmptyCollector : AmbientCollector {
        override suspend fun snapshot(now: Long): CollectorResult =
            CollectorResult(emptyList(), now)
        override fun stop() = Unit
    }

    private fun coordinator(dao: RecordingDao) = EvidenceCoordinator(
        dao, EmptyCollector(), EmptyCollector(), EmptyCollector(),
        FingerprintLearningPolicy(), EvidenceFusionEngine(),
        Clock.fixed(Instant.ofEpochMilli(0L), ZoneOffset.UTC)
    )

    @Test fun observationRetentionIsThirtyDays() {
        assertEquals(30L * 24 * 60 * 60 * 1000, EvidenceCoordinator.OBSERVATION_RETENTION_MILLIS)
    }

    @Test fun maxObservationsPerSourceIsTenThousand() {
        assertEquals(10_000, EvidenceCoordinator.MAX_OBSERVATIONS_PER_SOURCE)
    }

    @Test fun maxFeaturesPerRoundIsSixty() {
        assertEquals(60, EvidenceCoordinator.MAX_FEATURES_PER_ROUND)
    }

    @Test fun cleanupDeletesBeforeCutoffAndTrimsEverySource() = runTest {
        val dao = RecordingDao()
        dao.insertObservation(EvidenceObservationEntity(
            eventTime = 1_000L, receivedAt = 1_000L, source = EvidenceSource.WIFI.name,
            quality = 0.9, placeHint = ResolvedPlace.HOME.name, identifierHash = null,
            signal = -60, usedForEvent = false))
        val now = 1_000L + 40L * 24 * 60 * 60 * 1000
        coordinator(dao).onServiceStart(now)
        assertEquals(now - EvidenceCoordinator.OBSERVATION_RETENTION_MILLIS, dao.deletedBeforeCutoff)
        assertTrue(dao.observations.isEmpty())
        val trimmedSources = dao.trimmed.map { it.first }.toSet()
        assertEquals(
            setOf("GNSS", "CELL", "WIFI", "BLUETOOTH", "MOTION"),
            trimmedSources
        )
        assertTrue(dao.trimmed.all { it.second == EvidenceCoordinator.MAX_OBSERVATIONS_PER_SOURCE })
    }

    @Test fun sameMinuteObservationsAreMerged() = runTest {
        val dao = RecordingDao()
        val coordinator = coordinator(dao)
        coordinator.onGnss(com.example.worktimetracker.location.evidence.GnssInput(
            1_000_000L, ResolvedPlace.HOME, 20f, true, 1_000_000L))
        coordinator.onGnss(com.example.worktimetracker.location.evidence.GnssInput(
            1_000_050L, ResolvedPlace.HOME, 20f, true, 1_000_000L))
        assertEquals(1, dao.observations.size)
    }

    @Test fun stableKnownPlaceWithoutMotionDoesNotScan() {
        val decision = com.example.worktimetracker.location.evidence.AmbientScanPolicy().evaluate(
            com.example.worktimetracker.location.evidence.ScanPolicyInput(
                now = 10_000_000L,
                lastScanAt = 10_000_000L - 10 * 60_000L,
                significantMotion = false,
                gnssStale = false,
                nearShiftWindow = false,
                stableKnownPlace = true
            )
        )
        assertEquals(com.example.worktimetracker.location.evidence.ScanDecision.NONE, decision)
    }
}
