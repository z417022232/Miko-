package com.example.worktimetracker.location.evidence

import com.example.worktimetracker.domain.evidence.EvidenceSource

data class CollectorFeature(
    val source: EvidenceSource,
    val identifierHash: String,
    val signal: Int
)

enum class CollectorFailure { PERMISSION, DISABLED, THROTTLED, EMPTY, SECURITY, SYSTEM }

data class CollectorResult(
    val features: List<CollectorFeature>,
    val collectedAt: Long,
    val failure: CollectorFailure? = null
) {
    companion object {
        fun failed(failure: CollectorFailure, at: Long) =
            CollectorResult(emptyList(), at, failure)
    }
}

/**
 * 采集结果的统一合并：按 source + identifierHash 去重并保留最强信号，
 * 每类来源最多保留 [limit] 个特征。所有标识均为加盐哈希，不含原始值。
 */
object CollectorSnapshot {

    fun merge(results: List<CollectorResult>, limit: Int = DEFAULT_FEATURE_LIMIT): List<CollectorFeature> =
        results.flatMap { it.features }
            .groupBy { it.source to it.identifierHash }
            .map { (_, features) -> features.maxBy { it.signal } }
            .groupBy { it.source }
            .flatMap { (_, features) -> features.sortedByDescending { it.signal }.take(limit) }

    const val DEFAULT_FEATURE_LIMIT = 20
}

/** 环境采集器的统一接口：失败必须返回结构化原因，不得向服务主循环抛异常。 */
interface AmbientCollector {
    suspend fun snapshot(now: Long): CollectorResult
    fun stop()
}
