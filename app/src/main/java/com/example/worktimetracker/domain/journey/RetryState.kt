package com.example.worktimetracker.domain.journey

/**
 * 采样重试状态 —— `AdaptiveSamplingPolicy.decide` 的输入之一，**也是它的输出之一**
 * （§5.1.1；输出侧经 [SamplingDecision.nextRetryState] 原样带回，见下）。
 *
 * 为什么它是**输入**而不是策略内部的可变状态：策略必须是纯函数（同输入同输出、可回放），
 * 所以"连续失败几次 / CRITICAL 何时开始 / 上次何时结束"这类跨拍记忆必须由调用方持有并传进来，
 * 策略只负责算结果。也正因为它必须由调用方持有，**策略必须把下一拍的重试状态一并返回** ——
 * 否则调用方得自己拼装，重试业务逻辑就漏进编排层了。
 *
 * ⚠️ **四个时刻各管一件事，绝不互相兼任**（§5.3 冻结）：
 *
 * | 字段 | 语义 | 谁写 |
 * |---|---|---|
 * | [attempt] | 连续失败次数（指数退避的指数） | CRITICAL 到期退出 +1；成功取得可靠证据清 0 |
 * | [lastAttemptAt] | 最近一次尝试（无论成败）的时刻 | **每次重试都更新** |
 * | [currentCriticalStartedAt] | **本轮 CRITICAL 的起点** | 进入 CRITICAL 置 now；CRITICAL 内部重试**不得刷新**；退出置 null |
 * | [lastCriticalEndedAt] | 上一轮 CRITICAL 的结束时刻（冷却期起点） | 退出 CRITICAL 时置 now（成功与超时都要写） |
 *
 * **为什么不能拿 [lastAttemptAt] 当 CRITICAL 起点**（第 3 步的原始阻塞）：
 * 它是"最近一次尝试"的时刻，CRITICAL 内部每次重试都会刷新它 ——
 * 每重试一次就等于重新获得 10 分钟，**永远到不了上限**，
 * "单次 CRITICAL 最长 10 分钟"这条契约会静默失效（既不报错，日志里也看不出来）。
 * 起点必须有自己的字段：它不是"我们试过没有"，而是"这一轮从什么时候开始算账"。
 *
 * ## CRITICAL 轮的**三种**退出（§5.3.2 冻结；实现曾漏写第三种，导致轮次消失却没有结束记录）
 *
 * | 退出原因 | 字段结果 | attempt |
 * |---|---|---|
 * | 取得可靠证据（`CONFIRMED`） | `currentCriticalStartedAt = null`、`lastCriticalEndedAt = now` | **清 0**（成功） |
 * | 达到 10 分钟上限 | `currentCriticalStartedAt = null`、`lastCriticalEndedAt = now` | **+1**（退避指数） |
 * | 定位不可用（权限被撤 / 开关关闭） | `currentCriticalStartedAt = null`、`lastCriticalEndedAt = now` | **不变**（前置条件不满足 ≠ 提供器尝试失败，不污染退避指数） |
 *
 * 三种退出都**必须写** `lastCriticalEndedAt`（冷却起点）——
 * "轮次消失了但没有结束记录"会让冷却无从起算。
 *
 * ⚠️ 本类**不含算法**：何时进入/退出轮次是策略的判定；
 * 下面这组纯数据变换只是把上表的字段结果固化成代码，策略不许绕开它们自己 `copy`。
 */
data class RetryState(
    /** 连续失败次数（0 = 无失败）。指数退避的指数。 */
    val attempt: Int = 0,

    /** 最近一次尝试（无论成败）的时刻，epoch millis。 */
    val lastAttemptAt: Long? = null,

    /**
     * 本轮 CRITICAL 的**起点**时刻，epoch millis；null = 当前不在 CRITICAL 轮次内。
     *
     * "同一轮"的唯一判据就是这个字段有没有被刷新过：CRITICAL 内部重试只写 [lastAttemptAt]，
     * 一旦这里也被刷新，10 分钟上限就再也算不出来。
     */
    val currentCriticalStartedAt: Long? = null,

    /**
     * 上一轮 CRITICAL **结束**的时刻（达到时长上限或取得可靠证据），epoch millis。
     * 它是冷却期的起点（§5.3 契约 3）。**成功退出也要写** ——
     * "成功了"不等于"不用冷却"，下一拍立刻再进 CRITICAL 同样白耗电。
     */
    val lastCriticalEndedAt: Long? = null
) {
    /** 当前是否处于 CRITICAL 轮次内（起点存在即在内）。 */
    val inCritical: Boolean get() = currentCriticalStartedAt != null

    /** 进入 CRITICAL 轮：起点与最近尝试都置 [now]；[attempt] 保留（退避指数只在退出时改）。 */
    fun enterCritical(now: Long): RetryState =
        copy(lastAttemptAt = now, currentCriticalStartedAt = now)

    /**
     * CRITICAL 内部重试：**只**更新 [lastAttemptAt]。
     * 绝不触碰 [currentCriticalStartedAt] —— 刷新起点 = 重新获得 10 分钟 = 上限静默失效。
     */
    fun noteAttempt(now: Long): RetryState = copy(lastAttemptAt = now)

    /** 退出方式一：取得可靠证据。清起点、写结束时刻、清失败数（成功）。 */
    fun exitCriticalOnSuccess(now: Long): RetryState = copy(
        attempt = 0,
        currentCriticalStartedAt = null,
        lastCriticalEndedAt = now
    )

    /** 退出方式二：达到时长上限。清起点、写结束时刻、失败数 +1（封顶防溢出）。 */
    fun exitCriticalOnTimeout(now: Long): RetryState = copy(
        attempt = if (attempt == Int.MAX_VALUE) attempt else attempt + 1,
        currentCriticalStartedAt = null,
        lastCriticalEndedAt = now
    )

    /**
     * 退出方式三：定位不可用（权限被撤 / 系统开关关闭）。
     * 清起点、写结束时刻；**失败数不变** —— 前置条件不满足不是提供器尝试失败，
     * 把它计入退避指数会让真正的失败被稀释。
     */
    fun exitCriticalOnUnavailable(now: Long): RetryState = copy(
        currentCriticalStartedAt = null,
        lastCriticalEndedAt = now
    )
}
