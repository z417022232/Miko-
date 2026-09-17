package com.example.worktimetracker.domain.journey

import com.example.worktimetracker.domain.evidence.FusedDecision

/**
 * 证据新鲜度 —— [EvidenceHealth.freshness] 的类型（§5.3.2 冻结）。
 *
 * 为什么不让采样策略自己拿「距上次定位的秒数」去比阈值：
 * ① 阈值得有一个唯一定义处（连续性窗口 20 分钟已经有 `EvidenceContinuityPolicy` /
 * `JourneyConfig.staleAfterSeconds`，**策略里再写一个 20 分钟就是第二套魔数**）；
 * ② 多来源 TTL 的解释（任意定位 vs 可靠定位、哪条线管哪件事）是采集/编排层的事实，
 * 采样策略重新解释一遍 = 同一件事有两处解释，迟早不一致。
 *
 * 所以由调用方（Coordinator，第 6 步）按既有常量算好 [EvidenceFreshness] 填进来，
 * 策略只消费三档结论。派生函数见 [EvidenceHealth.freshnessOf]（阈值注入，domain 不藏常量）。
 */
enum class EvidenceFreshness {
    /** 任意定位与可靠定位都在各自窗口内 —— 不因此提高紧迫度。 */
    FRESH,

    /** 可靠定位超过老化线（建议 5 分钟）未更新 —— 证据在变旧。 */
    AGING,

    /** 任意定位超过连续性窗口（对齐 20 分钟口径）未更新 —— 证据断了。 */
    STALE
}

/**
 * 证据健康度 —— `AdaptiveSamplingPolicy.decide` 的输入之一（§5.1.1）。
 *
 * 它是**观察的横切摘要**：把「定位还活着吗 / 多久没拿到可靠点 / 置信如何 /
 * 提供器连续失败几次」这些与"加密采样值不值"有关的量聚在一起，
 * 使采样策略不必反查整个 [com.example.worktimetracker.domain.journey.JourneyObservation]。
 *
 * ⚠️ 它**不含地点判定**（那是 `JourneyObservation.place/placeDecision` 的活）：
 * 采样策略只决定"多花多少电去取证"，不判断"人在哪"。
 *
 * ## 字段的消费方式（§5.3.2 冻结，缺一条就是"保留字段却静默忽略"）
 *
 * | 字段 | 谁消费 | 怎么消费 |
 * |---|---|---|
 * | [freshness] | 采样策略 | 直接进 [SamplingContract.urgencyFloorOf] 的健康度下限表 |
 * | [secondsSinceFix] / [secondsSinceReliableFix] | [freshnessOf]（调用方构造 `freshness` 时） | 派生 [EvidenceFreshness]；策略**不**再解释秒数 |
 * | [confidence] / [placeDecision] / [providerFailureStreak] | 采样策略 | 进 [SamplingContract.urgencyFloorOf] 的健康度下限表 |
 * | [locationAvailable] | 采样策略 | **硬覆盖**：false 时不进 CRITICAL（兜底档也要压掉），见 [SamplingContract.withoutCritical] |
 *
 * `confidence` 为 `NaN` 时策略**整拍回落兜底**（不许静默当 0 ——
 * "算不出来"不是"很健康"也不是"很差"，是无效拍）。
 */
data class EvidenceHealth(
    /** 距最近一次**任意**有效定位的秒数（断流检测）。 */
    val secondsSinceFix: Long,

    /** 距最近一次**可靠**定位（融合层 CONFIRMED）的秒数。 */
    val secondsSinceReliableFix: Long,

    /** 当前融合置信（0..1）。 */
    val confidence: Double,

    /** 当前融合决策等级。 */
    val placeDecision: FusedDecision,

    /**
     * 定位可用性：权限已授予 **且** 系统定位开关已打开。
     * 为 false 时采样策略**不得**强制高频请求（§5.3 契约 4 —— 此时高频只是白耗电）。
     */
    val locationAvailable: Boolean,

    /** 提供器连续失败次数（0 = 最近一次成功）。用于指数退避。 */
    val providerFailureStreak: Int,

    /** 证据新鲜度（调用方按既有 TTL 常量派生，策略不再解释秒数）。 */
    val freshness: EvidenceFreshness
) {
    companion object {
        /**
         * 由原始秒数派生 [EvidenceFreshness]（纯函数，**阈值一律注入**）。
         *
         * 优先级：`STALE > AGING > FRESH`（任意定位断了比可靠定位变旧更严重）。
         * 负数秒数按 0（无效输入取保守侧：不凭空制造"断流"）。
         *
         * @param staleAfterSeconds 连续性窗口（秒）—— 调用方从既有常量注入
         *   （对齐 `JourneyConfig.staleAfterSeconds` / `EvidenceContinuityPolicy`，约 20 分钟），
         *   **策略与 domain 都不另写一套**。
         * @param reliableAgingSeconds 可靠定位老化线（秒）—— 调用方注入，建议 5 分钟
         *   （对齐常规采样间隔 `SamplingTuning.DEFAULT_SAMPLING_INTERVAL_MINUTES`）。
         */
        fun freshnessOf(
            secondsSinceFix: Long,
            secondsSinceReliableFix: Long,
            staleAfterSeconds: Long,
            reliableAgingSeconds: Long
        ): EvidenceFreshness {
            val sinceFix = secondsSinceFix.coerceAtLeast(0L)
            val sinceReliable = secondsSinceReliableFix.coerceAtLeast(0L)
            return when {
                sinceFix > staleAfterSeconds -> EvidenceFreshness.STALE
                sinceReliable > reliableAgingSeconds -> EvidenceFreshness.AGING
                else -> EvidenceFreshness.FRESH
            }
        }
    }
}
