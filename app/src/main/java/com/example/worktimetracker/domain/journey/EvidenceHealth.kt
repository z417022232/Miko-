package com.example.worktimetracker.domain.journey

import com.example.worktimetracker.domain.evidence.FusedDecision

/**
 * 证据健康度 —— `AdaptiveSamplingPolicy.decide` 的输入之一（§5.1.1）。
 *
 * 它是**观察的横切摘要**：把「定位还活着吗 / 多久没拿到可靠点 / 置信如何 /
 * 提供器连续失败几次」这些与"加密采样值不值"有关的量聚在一起，
 * 使采样策略不必反查整个 [com.example.worktimetracker.domain.journey.JourneyObservation]。
 *
 * ⚠️ 它**不含地点判定**（那是 `JourneyObservation.place/placeDecision` 的活）：
 * 采样策略只决定"多花多少电去取证"，不判断"人在哪"。
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
    val providerFailureStreak: Int
)
