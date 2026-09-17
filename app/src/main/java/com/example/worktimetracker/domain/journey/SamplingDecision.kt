package com.example.worktimetracker.domain.journey

/**
 * `AdaptiveSamplingPolicy.decide` 的**唯一产物**（§5.3）。
 *
 * 四个"防失控"字段是二轮/一轮复查补的硬契约，缺一个 CRITICAL 就可能变成
 * 「定位关闭时无限高频请求」—— 那时高频什么都换不来，只烧电：
 *
 * - [expiresAt]：档位失效时刻，到期回落一档（防"卡在高档"）；
 * - [cooldownUntil]：冷却截止时刻（CRITICAL 达到时长上限后进入，冷却期内不得再进 CRITICAL）；
 * - [retryAttempt]：连续失败次数（指数退避的指数）；
 * - [fallbackApplied]：是否兜底（状态机失败回落 `SamplingTuning` 时置 true）。
 *
 * ⚠️ CRITICAL 的**时长上限**不在此处另建常量 —— 复用
 * `SamplingTuning.HARD_BURST_CAP_MINUTES`（10 分钟），同源原则（§7 第 10 条）。
 */
data class SamplingDecision(
    /** 采样档位。 */
    val tier: SamplingTier,

    /** 紧迫度（0..1）—— 只可加采样，不可减到兜底下限。 */
    val urgency: Double,

    /** 采样原因码。 */
    val reasonCodes: Set<SamplingReason>,

    /** 该档位失效时刻（epoch millis）；null = 不自动失效。 */
    val expiresAt: Long?,

    /** 冷却截止时刻（epoch millis）；null = 未冷却。 */
    val cooldownUntil: Long?,

    /** 连续失败次数（0 = 无失败）。 */
    val retryAttempt: Int,

    /** 是否回落到 `SamplingTuning` 兜底。 */
    val fallbackApplied: Boolean
)
