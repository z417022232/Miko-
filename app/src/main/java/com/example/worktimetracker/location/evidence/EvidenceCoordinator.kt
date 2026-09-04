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
import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.evidence.FusedEvidence
import com.example.worktimetracker.domain.evidence.LearningGate
import com.example.worktimetracker.domain.evidence.LearningSample
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import com.example.worktimetracker.location.service.EvidenceContinuityPolicy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** GNSS/Network 输入：经纬度分类结果、精度、核心区域标记、稳定开始时间与推算/回放/异常标记。 */
data class GnssInput(
    val eventTime: Long,
    val place: ResolvedPlace,
    val accuracyMeters: Float,
    val inCore: Boolean,
    val stableSince: Long,
    val inferred: Boolean = false,
    val manualReplay: Boolean = false,
    val anomalousShift: Boolean = false,
    /** 定位提供者：gps → GNSS，network/passive → NETWORK_LOCATION，用于区分两类绝对定位 */
    val provider: String = "gps"
)

/**
 * 证据协调器：采集、学习、持久化与融合的单一编排入口。
 * - 手动记录和人工修正优先，学习与推算不得覆盖。
 * - Motion 不再产生地点证据：它只负责唤醒服务层重新取证（Movement Burst）。
 * - 连续性维持：20 分钟窗口内上一地点的最新证据未中断时，无/陈旧证据保持上一地点
 *   （MAINTAINED），避免 COMPANY → UNKNOWN → COMPANY 抖动。
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
    private val clock: Clock,
    /** 诊断日志回调（方案十）：每次融合结果/原因变化时输出证据明细 */
    private val diagnosticLogger: ((type: String, content: String) -> Unit)? = null
) {
    private var lastResolvedPlace: ResolvedPlace = ResolvedPlace.UNKNOWN
    private var lastCleanupDay: String? = null
    private var lastLoggedFusionKey: String? = null
    private val continuity = EvidenceContinuityPolicy()

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
                source = absoluteSourceOf(input.provider),
                quality = quality,
                placeHint = input.place,
                identifierHash = null,
                signal = null,
                provider = input.provider,
                accuracyMeters = input.accuracyMeters
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
        if (input.provider == "gps" && learningPolicy.accepts(gate)) {
            // 指纹学习只信任真正 GPS：Network Location 精度波动大，不适合做环境指纹
            learnFingerprints(input)
        }
        return fuse(input.eventTime)
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

    private fun absoluteSourceOf(provider: String): EvidenceSource =
        if (provider.equals("gps", ignoreCase = true)) EvidenceSource.GNSS else EvidenceSource.NETWORK_LOCATION

    private suspend fun learnFingerprints(input: GnssInput) {
        val results = collectAll(input.eventTime)
        val features = CollectorSnapshot.merge(results.map { it.second }).take(MAX_FEATURES_PER_ROUND)
        val day = dayOf(input.eventTime)
        val otherPlaces = listOf(ResolvedPlace.HOME, ResolvedPlace.COMPANY).filter { it != input.place }
        for (feature in features) {
            val existing = store.fingerprints(input.place.name, feature.source.name)
                .firstOrNull { it.identifierHash == feature.identifierHash }
            // 跨地点共享指纹检测：同一标识同时属于家和公司（随身热点、蓝牙设备等）
            // 时，两边都失去地点判别资格，防止同一设备成为两地的可靠特征
            val crossMatch = otherPlaces.firstNotNullOfOrNull { other ->
                store.fingerprints(other.name, feature.source.name)
                    .firstOrNull { it.identifierHash == feature.identifierHash }
                    ?.let { other to it }
            }
            if (crossMatch != null) {
                val (otherPlace, otherEntity) = crossMatch
                store.upsertFingerprint(
                    learningPolicy.markCrossPlace(otherEntity.toState())
                        .toEntity(otherPlace.name, otherEntity.source, otherEntity.identifierHash)
                )
                if (existing != null) {
                    store.upsertFingerprint(
                        learningPolicy.markCrossPlace(existing.toState())
                            .toEntity(input.place.name, feature.source.name, feature.identifierHash)
                    )
                }
                continue
            }
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
                    signal = null,
                    provider = source.name.lowercase()
                )
            }
        }
        return observations
    }

    private suspend fun fuse(now: Long): FusedEvidence {
        // 查询窗口放宽到 20 分钟连续性窗口；各来源的真实新鲜度由融合引擎按 TTL 过滤
        val since = now - CONTINUITY_WINDOW_MILLIS
        val stored = store.recentObservations(since)
        val observations = stored.map { it.toDomain() }
        val fused = fusionEngine.resolve(observations, now, lastResolvedPlace)
        val resolved = maintainContinuity(fused, stored, now)
        if (resolved.place != ResolvedPlace.UNKNOWN) {
            lastResolvedPlace = resolved.place
            for (entity in stored) {
                if (entity.source in resolved.sources.map { it.name } && entity.placeHint == resolved.place.name) {
                    store.markUsedForEvent(entity.id, true)
                }
            }
        }
        logDiagnostics(resolved, stored, now)
        return resolved
    }

    /**
     * 连续性维持（方案八）：引擎因无证据/证据陈旧返回 UNKNOWN 时，若上一地点的
     * 最新证据仍在 20 分钟连续性窗口内，则维持上一地点而不是打断状态连续性。
     */
    private fun maintainContinuity(
        fused: FusedEvidence,
        stored: List<EvidenceObservationEntity>,
        now: Long
    ): FusedEvidence {
        if (fused.decision != FusedDecision.UNKNOWN) return fused
        if (lastResolvedPlace == ResolvedPlace.UNKNOWN) return fused
        val isNoEvidence = fused.reason.startsWith("UNKNOWN_NO_DATA") || fused.reason.startsWith("UNKNOWN_STALE")
        if (!isNoEvidence) return fused
        val newestForPrevious = stored
            .filter { it.placeHint == lastResolvedPlace.name }
            .maxByOrNull { it.eventTime } ?: return fused
        if (!continuity.isContinuous(newestForPrevious.eventTime, now)) return fused
        return fused.copy(
            place = lastResolvedPlace,
            decision = FusedDecision.MAINTAINED,
            reason = "MAINTAIN_CONTINUITY"
        )
    }

    /** 最近一次融合的各来源证据明细：供 UI 实时展示（每次融合都刷新，与日志无关） */
    @Volatile var lastSourceBreakdown: String? = null
        private set

    /** 诊断日志（方案十）：结果或原因变化时输出一次各来源最新证据明细。 */
    private fun logDiagnostics(
        resolved: FusedEvidence,
        stored: List<EvidenceObservationEntity>,
        now: Long
    ) {
        val logger = diagnosticLogger ?: return
        val breakdown = stored.groupBy { it.source }.mapNotNull { (source, list) ->
            val latest = list.maxByOrNull { it.eventTime } ?: return@mapNotNull null
            val age = ((now - latest.eventTime).coerceAtLeast(0)) / 1000
            "$source=${latest.placeHint} q${"%.2f".format(latest.quality)} ${age}s前" +
                (latest.provider?.let { "($it ${"%.0f".format(latest.accuracyMeters ?: 0f)}m)" } ?: "")
        }.joinToString(" | ")
        lastSourceBreakdown = breakdown.ifEmpty { null }
        val key = "${resolved.place.name}/${resolved.decision.name}/${resolved.reason}"
        if (key == lastLoggedFusionKey) return
        lastLoggedFusionKey = key
        val content = "融合结果=${resolved.place.name}(${resolved.decision.name}/${resolved.reason}) " +
            "conf=${"%.2f".format(resolved.confidence)}" + if (breakdown.isEmpty()) "" else " 证据: $breakdown"
        runCatching { logger("FUSION", content) }
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
        // 每来源限量：Network Location 与 GNSS 同为绝对定位来源，一并限量；
        // Motion 已不产生新观察，但旧版本遗留行在 30 天内仍需按来源限量清理
        for (source in listOf(EvidenceSource.GNSS, EvidenceSource.NETWORK_LOCATION, EvidenceSource.CELL,
                EvidenceSource.WIFI, EvidenceSource.BLUETOOTH, EvidenceSource.MOTION)) {
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
        LocalDate.ofInstant(Instant.ofEpochMilli(time), clock.zone).toString()

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
        source = runCatching { EvidenceSource.valueOf(source) }.getOrDefault(EvidenceSource.NETWORK_LOCATION),
        quality = quality,
        placeHint = ResolvedPlace.valueOf(placeHint),
        identifierHash = identifierHash,
        signal = signal,
        provider = provider,
        accuracyMeters = accuracyMeters
    )

    private fun EvidenceObservation.toEntity() = EvidenceObservationEntity(
        eventTime = eventTime,
        receivedAt = receivedAt,
        source = source.name,
        quality = quality,
        placeHint = placeHint.name,
        identifierHash = identifierHash,
        signal = signal,
        usedForEvent = false,
        provider = provider,
        accuracyMeters = accuracyMeters
    )

    companion object {
        const val AMBIENT_WINDOW_MILLIS = 10 * 60_000L

        /** 连续性窗口：与 EvidenceContinuityPolicy 默认 20 分钟一致 */
        const val CONTINUITY_WINDOW_MILLIS = 20 * 60_000L
        const val OBSERVATION_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val MAX_OBSERVATIONS_PER_SOURCE = 10_000
        const val MAX_FEATURES_PER_ROUND = 60
        const val SIGNAL_RANGE_MARGIN = 10
    }
}
