package com.example.worktimetracker.domain.journey

/**
 * `AdaptiveSamplingPolicy.decide` 的**唯一产物**（§5.3）。
 *
 * 四个"防失控"字段是二轮/一轮复查补的硬契约，缺一个 CRITICAL 就可能变成
 * 「定位关闭时无限高频请求」—— 那时高频什么都换不来，只烧电：
 *
 * - [expiresAt]：档位失效时刻，到期回落一档（防"卡在高档"）；
 * - [cooldownUntil]：冷却截止时刻（CRITICAL 结束后的冷却，期内不得再进 CRITICAL）；
 * - [nextRetryState]：**下一拍的重试状态** —— 策略负责的重试记忆全在里面；
 * - [fallbackApplied]：是否兜底（状态机失败回落 `SamplingTuning` 时置 true）。
 *
 * **为什么带回完整的 [nextRetryState]，而不是只给一个失败次数（第 3 步的原始阻塞）**：
 * 进入/退出 CRITICAL、累加失败次数、起算冷却、成功清零，**全都是策略的职责**
 * （§5.3 四条契约）。只回一个 `retryAttempt` 的话，调用方还得自己拼出
 * `currentCriticalStartedAt` / `lastAttemptAt` / `lastCriticalEndedAt` ——
 * 那等于把"什么时候算一轮 CRITICAL、什么时候清成功状态"这层重试语义搬进编排层，
 * 而编排层**不许包含业务判定**（§5.1.1）。所以策略一次把下一拍的完整重试状态算完。
 *
 * ⚠️ 刻意**不保留**冗余的 `retryAttempt` 展示字段：失败次数只有一处真相
 * （`nextRetryState.attempt`）。留一个平行字段意味着两个值有一天会不一致，
 * 而"两个字段说同一件事"正是本项目反复踩过的坑（三层职责、展示与事实同源）。
 *
 * ⚠️ CRITICAL 的**时长上限**不在此处另建常量 —— 见 [SamplingContract.CRITICAL_MAX_MINUTES]：
 * 它与 `SamplingTuning.HARD_BURST_CAP_MINUTES`（10 分钟）同源，
 * 由护栏测试 `SamplingContractTest` 钉住相等（domain 不许反向依赖 `location/service`，
 * 所以同源只能靠测试守，不能靠 import）。
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

    /**
     * 下一拍要喂回 `decide` 的重试状态（完整，不含猜测）。
     *
     * 读失败次数的唯一入口是 `nextRetryState.attempt`。
     */
    val nextRetryState: RetryState,

    /** 是否回落到 `SamplingTuning` 兜底。 */
    val fallbackApplied: Boolean
)
