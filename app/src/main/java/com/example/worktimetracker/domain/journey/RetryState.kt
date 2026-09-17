package com.example.worktimetracker.domain.journey

/**
 * 采样重试状态 —— `AdaptiveSamplingPolicy.decide` 的输入之一（§5.1.1）。
 *
 * 为什么它是**输入**而不是策略内部的可变状态：策略必须是纯函数
 * （同输入同输出、可回放），所以"连续失败几次 / 上次 CRITICAL 何时结束"
 * 这类跨拍记忆要由调用方持有并传进来，策略只负责算结果。
 *
 * ⚠️ 本类**不含算法**：如何按 [attempt] 退避、何时进冷却，是
 * `AdaptiveSamplingPolicy` 的职责（阶段 3 第 4 步），此处只是数据。
 */
data class RetryState(
    /** 连续失败次数（0 = 无失败）。指数退避的指数。 */
    val attempt: Int = 0,

    /** 最近一次尝试（无论成败）的时刻，epoch millis。 */
    val lastAttemptAt: Long? = null,

    /**
     * 上一次 CRITICAL **结束**的时刻（达到时长上限或取得可靠证据），epoch millis。
     * 它是冷却期的起点（§5.3 契约 3）。
     */
    val lastCriticalEndedAt: Long? = null
)
