package com.example.worktimetracker.location.evidence

import com.example.worktimetracker.data.dao.EnvironmentEvidenceDao
import com.example.worktimetracker.data.entity.EnvironmentFingerprintEntity
import com.example.worktimetracker.data.entity.EvidenceObservationEntity
import com.example.worktimetracker.data.entity.LocationHealthEntity
import com.example.worktimetracker.domain.evidence.EvidenceFusionEngine
import com.example.worktimetracker.domain.evidence.EvidenceObservation
import com.example.worktimetracker.domain.evidence.EvidenceSource
import com.example.worktimetracker.domain.evidence.FingerprintLearningPolicy
import com.example.worktimetracker.domain.evidence.FingerprintLevel
import com.example.worktimetracker.domain.evidence.FingerprintState
import com.example.worktimetracker.domain.evidence.FusedEvidence
import com.example.worktimetracker.domain.evidence.LearningGate
import com.example.worktimetracker.domain.evidence.LearningSample
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import java.time.Clock
import java.time.LocalDate

/** GNSS 输入：经纬度分类结果、精度、核心区域标记、稳定开始时间与推算/回放/异常标记。 */
data class GnssInput(
    val eventTime: Long,
    val place: ResolvedPlace,
    val accuracyMeters: Float,
    val inCore: Boolean,
    val stableSince: Long,
    val inferred: Boolean = false,
    val manualReplay: Boolean = false,
    val anomalousShift: Boolean = false
)

/**
 * 证据协调器：采集、学习、持久化与融合的单一编排入口。
 * - 手动记录和人工修正优先，学习与推算不得覆盖。
 * - 环境原始标识只在内存短暂存在，数据库只保存加盐哈希。
 * - 观察明细只保留 30 天，每个来源最多 10,000 条；指纹 30 天衰减、90 天停用。
 */
class EvidenceCoordinator(
    private val store: EnvironmentEvidenceDao,
    private val wifiCollector: AmbientCollector,
    private val bluetoothCollector: AmbientCollector,
    private val cellCollector: AmbientCollector,
    private val learningPolicy: FingerprintLearningPolicy,
    private val fusionEngine: EvidenceFusionEngine,
    private val clock: Clock
) {
    private var lastResolvedPlace: ResolvedPlace = ResolvedPlace.UNKNOWN
    private var lastCleanupDay: String? = null

    /** 服务启动时调用一次：执行限量清理并初始化健康状态。 */
    suspend fun onServiceStart(now: Long = clock.millis()) {
        runCleanup(now)
    }

    suspend fun onGnss(input: GnssInput): FusedEvidence {
        maybeCleanup(input.eventTime)
        val quality = gnssQuality(input.accuracyMeters)
        insertOncePerMinute(
            EvidenceObservation(
                eventTime = input.eventTime,
                receivedAt = clock.millis(),
                source = EvidenceSource.GNSS,
                quality = quality,
                placeHint = input.place,
                identifierHash = null,
                signal = null
            )
        )
        val gate = LearningGate(
            accuracyMeters = input.accuracyMeters,
            stableMillis = input.eventTime - input.stableSince,
            inCore = input.inCore,
            inferred = input.inferred,
            manualReplay = input.manualReplay,
            anomalousShift = input.anomalousShift
        )
        if (learningPolicy.accepts(gate)) {
            learnFingerprints(input)
        }
        return fuse(input.eventTime)
    }

    suspend fun onMotion(at: Long) {
        maybeCleanup(at)
        insertOncePerMinute(
            EvidenceObservation(
                eventTime = at,
                receivedAt = clock.millis(),
                source = EvidenceSource.MOTION,
                quality = MOTION_QUALITY,
                placeHint = if (lastResolvedPlace == ResolvedPlace.UNKNOWN) ResolvedPlace.HOME else lastResolvedPlace,
                identifierHash = null,
                signal = null
            )
        )
    }

    /** 采集一次环境快照并融合；采集失败只更新健康状态，不改变已知地点。 */
    suspend fun collectAmbient(now: Long): FusedEvidence {
        maybeCleanup(now)
        val results = collectAll(now)
        val features = CollectorSnapshot.merge(results.map { it.second }).take(MAX_FEATURES_PER_ROUND)
        for ((name, result) in results) {
            if (isHealthRelevantFailure(result.failure)) {
                store.upsertHealth(
                    LocationHealthEntity(
                        name = name,
                        lastCallbackAt = now,
                        lastSuccessAt = 0L,
                        registered = true,
                        recoveryCount = 0,
                        lastFailure = result.failure?.name
                    )
                )
            }
        }
        for (observation in ambientObservations(features, now)) {
            insertOncePerMinute(observation)
        }
        return fuse(now)
    }

    private suspend fun collectAll(now: Long): List<Pair<String, CollectorResult>> = listOf(
        "wifi" to wifiCollector.snapshot(now),
        "bluetooth" to bluetoothCollector.snapshot(now),
        "cell" to cellCollector.snapshot(now)
    )

    private suspend fun learnFingerprints(input: GnssInput) {
        val results = collectAll(input.eventTime)
        val features = CollectorSnapshot.merge(results.map { it.second }).take(MAX_FEATURES_PER_ROUND)
        val day = dayOf(input.eventTime)
        for (feature in features) {
            val existing = store.fingerprints(input.place.name, feature.source.name)
                .firstOrNull { it.identifierHash == feature.identifierHash }
            val state = learningPolicy.update(existing?.toState(), LearningSample(day, input.eventTime, feature.signal))
            store.upsertFingerprint(state.toEntity(input.place.name, feature.source.name, feature.identifierHash))
        }
    }

    private suspend fun ambientObservations(
        features: List<CollectorFeature>,
        now: Long
    ): List<EvidenceObservation> {
        val observations = mutableListOf<EvidenceObservation>()
        for (source in listOf(EvidenceSource.WIFI, EvidenceSource.BLUETOOTH, EvidenceSource.CELL)) {
            val sourceFeatures = features.filter { it.source == source }
            if (sourceFeatures.isEmpty()) continue
            var bestPlace: ResolvedPlace? = null
            var bestQuality = 0.0
            for (place in listOf(ResolvedPlace.HOME, ResolvedPlace.COMPANY)) {
                val fingerprints = store.fingerprints(place.name, source.name)
                    .filter { it.level == FingerprintLevel.STABLE.name && it.discriminative }
                if (fingerprints.isEmpty()) continue
                val matched = sourceFeatures.filter { feature ->
                    fingerprints.any { it.identifierHash == feature.identifierHash }
                }
                if (matched.isEmpty()) continue
                val ratio = matched.size.toDouble() / sourceFeatures.size
                val inRange = matched.count { feature ->
                    val fingerprint = fingerprints.first { it.identifierHash == feature.identifierHash }
                    feature.signal in (fingerprint.minSignal - SIGNAL_RANGE_MARGIN)..(fingerprint.maxSignal + SIGNAL_RANGE_MARGIN)
                }.toDouble() / matched.size
                val quality = 0.6 * ratio + 0.4 * inRange
                if (quality > bestQuality) {
                    bestQuality = quality
                    bestPlace = place
                }
            }
            if (bestPlace != null && bestQuality > 0.0) {
                observations += EvidenceObservation(
                    eventTime = now,
                    receivedAt = clock.millis(),
                    source = source,
                    quality = bestQuality,
                    placeHint = bestPlace!!,
                    identifierHash = null,
                    signal = null
                )
            }
        }
        return observations
    }

    private suspend fun fuse(now: Long): FusedEvidence {
        val since = now - AMBIENT_WINDOW_MILLIS
        val stored = store.recentObservations(since)
        val observations = stored.map { it.toDomain() }
        val fused = fusionEngine.resolve(observations, now, lastResolvedPlace)
        if (fused.place != ResolvedPlace.UNKNOWN) {
            lastResolvedPlace = fused.place
            for (entity in stored) {
                if (entity.source in fused.sources.map { it.name } && entity.placeHint == fused.place.name) {
                    store.markUsedForEvent(entity.id, true)
                }
            }
        }
        return fused
    }

    private suspend fun insertOncePerMinute(observation: EvidenceObservation) {
        val minute = observation.eventTime / 60_000L
        val existing = store.recentObservations(observation.eventTime - 60_000L)
            .any { it.source == observation.source.name && it.eventTime / 60_000L == minute }
        if (existing) return
        store.insertObservation(observation.toEntity())
    }

    private suspend fun maybeCleanup(now: Long) {
        val day = dayOf(now)
        if (day != lastCleanupDay) {
            runCleanup(now)
        }
    }

    private suspend fun runCleanup(now: Long) {
        lastCleanupDay = dayOf(now)
        store.deleteObservationsBefore(now - OBSERVATION_RETENTION_MILLIS)
        for (source in listOf(EvidenceSource.GNSS, EvidenceSource.CELL, EvidenceSource.WIFI,
                EvidenceSource.BLUETOOTH, EvidenceSource.MOTION)) {
            store.trimObservations(source.name, MAX_OBSERVATIONS_PER_SOURCE)
        }
        for (fingerprint in store.allFingerprints()) {
            val decayed = learningPolicy.decay(fingerprint.toState(), now)
            if (decayed != fingerprint.toState()) {
                store.upsertFingerprint(
                    decayed.toEntity(fingerprint.place, fingerprint.source, fingerprint.identifierHash)
                )
            }
        }
    }

    private fun isHealthRelevantFailure(failure: CollectorFailure?): Boolean =
        failure != null && failure != CollectorFailure.EMPTY

    private fun dayOf(time: Long): String =
        LocalDate.ofInstant(java.time.Instant.ofEpochMilli(time), clock.zone).toString()

    private fun gnssQuality(accuracyMeters: Float): Double =
        (1.0 - accuracyMeters / 150.0).coerceIn(0.0, 1.0)

    private fun EnvironmentFingerprintEntity.toState() = FingerprintState(
        observationCount = observationCount,
        distinctDayCount = distinctDayCount,
        lastObservedDay = lastObservedDay,
        lastObservedAt = lastObservedAt,
        minSignal = minSignal,
        maxSignal = maxSignal,
        level = FingerprintLevel.valueOf(level),
        discriminative = discriminative
    )

    private fun FingerprintState.toEntity(place: String, source: String, hash: String) =
        EnvironmentFingerprintEntity(
            place = place,
            source = source,
            identifierHash = hash,
            observationCount = observationCount,
            distinctDayCount = distinctDayCount,
            lastObservedDay = lastObservedDay,
            lastObservedAt = lastObservedAt,
            minSignal = minSignal,
            maxSignal = maxSignal,
            level = level.name,
            discriminative = discriminative
        )

    private fun EvidenceObservationEntity.toDomain() = EvidenceObservation(
        eventTime = eventTime,
        receivedAt = receivedAt,
        source = EvidenceSource.valueOf(source),
        quality = quality,
        placeHint = ResolvedPlace.valueOf(placeHint),
        identifierHash = identifierHash,
        signal = signal
    )

    private fun EvidenceObservation.toEntity() = EvidenceObservationEntity(
        eventTime = eventTime,
        receivedAt = receivedAt,
        source = source.name,
        quality = quality,
        placeHint = placeHint.name,
        identifierHash = identifierHash,
        signal = signal,
        usedForEvent = false
    )

    companion object {
        const val MOTION_QUALITY = 0.90
        const val AMBIENT_WINDOW_MILLIS = 10 * 60_000L
        const val OBSERVATION_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val MAX_OBSERVATIONS_PER_SOURCE = 10_000
        const val MAX_FEATURES_PER_ROUND = 60
        const val SIGNAL_RANGE_MARGIN = 10
    }
}
