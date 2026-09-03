package com.example.worktimetracker.domain.evidence

/**
 * 环境指纹自动学习策略（纯 Kotlin，无 Android 依赖）。
 *
 * 学习门槛：精度不超过 50 米、核心区域稳定 5 分钟、非推算/人工回放/异常班次。
 * 晋级：观察数至少 6 且跨越至少 3 个不同日期才进入 STABLE。
 * 衰减：30 天未观察进入 DECAYING，90 天未观察进入 DISABLED。
 */
class FingerprintLearningPolicy {

    fun accepts(gate: LearningGate): Boolean =
        gate.accuracyMeters <= MAX_LEARNING_ACCURACY_METERS &&
            gate.stableMillis >= MIN_STABLE_MILLIS &&
            gate.inCore &&
            !gate.inferred &&
            !gate.manualReplay &&
            !gate.anomalousShift

    fun update(current: FingerprintState?, sample: LearningSample): FingerprintState {
        val base = current ?: FingerprintState(
            observationCount = 0,
            distinctDayCount = 0,
            lastObservedDay = "",
            lastObservedAt = 0L,
            minSignal = sample.signal,
            maxSignal = sample.signal,
            level = FingerprintLevel.NEW,
            discriminative = true
        )
        val newDay = sample.localDay > base.lastObservedDay
        val observationCount = base.observationCount + 1
        val distinctDayCount = base.distinctDayCount + if (newDay) 1 else 0
        val level = when {
            base.level == FingerprintLevel.DISABLED -> FingerprintLevel.DISABLED
            observationCount >= STABLE_OBSERVATIONS && distinctDayCount >= STABLE_DAYS -> FingerprintLevel.STABLE
            else -> FingerprintLevel.CANDIDATE
        }
        return base.copy(
            observationCount = observationCount,
            distinctDayCount = distinctDayCount,
            lastObservedDay = if (newDay) sample.localDay else base.lastObservedDay,
            lastObservedAt = maxOf(base.lastObservedAt, sample.observedAt),
            minSignal = minOf(base.minSignal, sample.signal),
            maxSignal = maxOf(base.maxSignal, sample.signal),
            level = level,
            discriminative = base.level != FingerprintLevel.DISABLED && base.discriminative
        )
    }

    fun decay(current: FingerprintState, now: Long): FingerprintState {
        val age = now - current.lastObservedAt
        return when {
            age >= DISABLE_AFTER_MILLIS -> current.copy(level = FingerprintLevel.DISABLED)
            age >= DECAY_AFTER_MILLIS -> current.copy(level = FingerprintLevel.DECAYING)
            else -> current
        }
    }

    fun markCrossPlace(current: FingerprintState): FingerprintState =
        current.copy(discriminative = false)

    companion object {
        const val MAX_LEARNING_ACCURACY_METERS = 50f
        const val MIN_STABLE_MILLIS = 300_000L
        const val STABLE_OBSERVATIONS = 6
        const val STABLE_DAYS = 3
        const val DECAY_AFTER_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val DISABLE_AFTER_MILLIS = 90L * 24 * 60 * 60 * 1000
    }
}

enum class FingerprintLevel { NEW, CANDIDATE, STABLE, DECAYING, DISABLED }

data class LearningGate(
    val accuracyMeters: Float,
    val stableMillis: Long,
    val inCore: Boolean,
    val inferred: Boolean,
    val manualReplay: Boolean,
    val anomalousShift: Boolean
)

data class LearningSample(
    val localDay: String,
    val observedAt: Long,
    val signal: Int
)

data class FingerprintState(
    val observationCount: Int,
    val distinctDayCount: Int,
    val lastObservedDay: String,
    val lastObservedAt: Long,
    val minSignal: Int,
    val maxSignal: Int,
    val level: FingerprintLevel,
    val discriminative: Boolean
)
