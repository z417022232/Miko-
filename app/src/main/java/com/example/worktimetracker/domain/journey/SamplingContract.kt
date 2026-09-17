package com.example.worktimetracker.domain.journey

/**
 * 采样契约的**冻结数值表**（阶段 3 §5.3）。
 *
 * 为什么单独抽一个对象而不是把数字写进 `AdaptiveSamplingPolicy`：
 * 规格要求「每个档位的上下边界各一条测试」，而**测试要能钉住数字，数字就必须是可见的契约**。
 * 散在算法里的字面量既没法逐档断言，也一定会在实现时被现场"凑一个看起来合理"的值 ——
 * 那正是"制造虚假的统计精度"。
 *
 * 本对象只有**查表与清洗**，没有策略：
 * 由 `状态 + 健康 + 重试 → 档位` 的判定逻辑（含下面两条硬覆盖）仍属
 * `AdaptiveSamplingPolicy`（阶段 3 第 4 步）。
 *
 * ## 一、紧迫度 → 档位（等距五档，§5.3）
 *
 * | urgency | 档位 |
 * |---|---|
 * | `0.0 ≤ u < 0.2` | [SamplingTier.STABLE] |
 * | `0.2 ≤ u < 0.4` | [SamplingTier.NORMAL] |
 * | `0.4 ≤ u < 0.6` | [SamplingTier.WATCH] |
 * | `0.6 ≤ u < 0.8` | [SamplingTier.TRANSITION] |
 * | `0.8 ≤ u ≤ 1.0` | [SamplingTier.CRITICAL] |
 *
 * 非法输入**一律按保守侧**处理（[cleanUrgency] / [tierFor]）：
 * `NaN → null`（信号"回落到兜底档"，**不能**当成 0 或 1 —— 见下）、
 * 负数 → 0、大于 1 → 1。
 *
 * ⚠️ `NaN` 必须显式拦：`NaN` 与任何数比较都是 false，
 * 直接套分段写法会让它掉进最后一个 `else`（= CRITICAL），
 * 于是"算不出来"被静默翻译成"最高频采样"，正是最该避免的方向。
 *
 * ## 二、状态基础紧迫度（§5.3）
 *
 * | 状态 | 基础 urgency |
 * |---|---|
 * | `AT_HOME` / `AT_WORK` | 0.1 |
 * | `AWAY` | 0.3 |
 * | `LEAVING_*` / `ARRIVING_*` | 0.5 |
 * | `TEMP_LEAVE` / `OTHER_STOP` | 0.5 |
 * | `COMMUTING_*` | 0.7 |
 * | `UNKNOWN` / `STALE` | 0.9 |
 *
 * **方向约束**：`EvidenceHealth` 只能**提高**紧迫度，**不得压低**状态基础值
 * （省电绝不允许以丢掉状态变迁证据为代价，§5.3 方向约束）。
 *
 * ## 三、CRITICAL 时长上限与冷却（§5.3 契约 1 / 3）
 *
 * - 单次 CRITICAL 最长 [CRITICAL_MAX_MINUTES] 分钟；
 * - 达到上限仍未取得可靠证据 → 进冷却，连续失败按
 *   `1 / 2 / 4 / 8 / 16` 分钟指数退避（[cooldownMinutes]），**最大 [COOLDOWN_MAX_MINUTES] 分钟封顶**。
 *
 * ## 四、策略必须实现的两条硬覆盖（不在本表内，§5.3）
 *
 * 1. `health.locationAvailable == false` → **不进入 CRITICAL**，回落 `fallbackTier`，
 *    并记 [SamplingReason.LOCATION_UNAVAILABLE]（此时高频请求什么都换不来）；
 * 2. `health.placeDecision == CONFIRMED`（取得新可靠点）→ **立即退出 CRITICAL**，
 *    回到"当前状态基础档"（不沿用 CRITICAL 档），并记 `lastCriticalEndedAt`。
 */
object SamplingContract {

    /** 五档的等距步长（仅作说明：边界是精确的 0.2 间隔，不是经验值）。 */
    const val URGENCY_TIER_STEP: Double = 0.2

    /**
     * 单次 CRITICAL 的时长上限（分钟）。
     *
     * ⚠️ 与 `SamplingTuning.HARD_BURST_CAP_MINUTES`（10 分钟）**同源**
     * （§7 第 10 条：Burst 已硬封 10 分钟，CRITICAL 与它同上限）。
     * domain **不许**反向 import `location/service`，所以同源由护栏测试
     * `SamplingContractTest.criticalCapStaysInSyncWithSamplingTuning` 守 ——
     * 改一处而不改另一处会立刻红。
     */
    const val CRITICAL_MAX_MINUTES: Int = 10

    /** 单次 CRITICAL 的时长上限（毫秒）。 */
    const val CRITICAL_MAX_MILLIS: Long = CRITICAL_MAX_MINUTES * 60_000L

    /** 冷却期上限（分钟）：退避到 16 分钟后不再增长。 */
    const val COOLDOWN_MAX_MINUTES: Int = 16

    /**
     * 退避的最大位移位次。
     *
     * `1 shl 4 = 16` 正好等于 [COOLDOWN_MAX_MINUTES]。
     * **绝不许对任意 `attempt` 直接 `1 shl attempt`**：
     * `attempt` 只增不减，`1 shl 31` 会变成负数（有符号 Int 溢出），
     * 冷却期于是算成"过去"——不但不冷却，还会立刻再进 CRITICAL。
     */
    const val COOLDOWN_MAX_BACKOFF_SHIFT: Int = 4

    /**
     * 紧迫度 → 档位。
     *
     * @return `null` 仅当 [raw] 是 `NaN`（调用方据此回落兜底档）；
     *   负数按 0、大于 1 按 1。
     */
    fun tierFor(raw: Double): SamplingTier? {
        if (raw.isNaN()) return null
        val urgency = raw.coerceIn(0.0, 1.0)
        return when {
            urgency < 0.2 -> SamplingTier.STABLE
            urgency < 0.4 -> SamplingTier.NORMAL
            urgency < 0.6 -> SamplingTier.WATCH
            urgency < 0.8 -> SamplingTier.TRANSITION
            else -> SamplingTier.CRITICAL
        }
    }

    /**
     * 清洗紧迫度：`NaN → null`（= 算不出来，交给兜底），其余夹到 `0.0..1.0`。
     *
     * 与 [tierFor] 的分工：[tierFor] 是"档位查表"，本函数是"先把输入弄干净"，
     * 需要把清洗后的值写进 [SamplingDecision.urgency] 时用它。
     */
    fun cleanUrgency(raw: Double): Double? = if (raw.isNaN()) null else raw.coerceIn(0.0, 1.0)

    /** 状态基础紧迫度（§5.3 表二）。未知状态属编译期穷尽，不会走到 `else`。 */
    fun baseUrgencyOf(phase: JourneyPhase): Double = when (phase) {
        JourneyPhase.AT_HOME, JourneyPhase.AT_WORK -> 0.1
        JourneyPhase.AWAY -> 0.3
        JourneyPhase.LEAVING_HOME, JourneyPhase.ARRIVING_HOME,
        JourneyPhase.LEAVING_WORK, JourneyPhase.ARRIVING_WORK,
        JourneyPhase.TEMP_LEAVE, JourneyPhase.OTHER_STOP -> 0.5
        JourneyPhase.COMMUTING_TO_WORK, JourneyPhase.COMMUTING_HOME -> 0.7
        JourneyPhase.UNKNOWN, JourneyPhase.STALE -> 0.9
    }

    /**
     * 连续失败 [attempt] 次后的冷却时长（分钟）：`1 << clamp(attempt, 0, 4)`。
     *
     * 负数按 0（1 分钟），超过 4 次一律 [COOLDOWN_MAX_MINUTES] 分钟。
     */
    fun cooldownMinutes(attempt: Int): Int =
        1 shl attempt.coerceIn(0, COOLDOWN_MAX_BACKOFF_SHIFT)

    /** 冷却时长（毫秒）。 */
    fun cooldownMillis(attempt: Int): Long = cooldownMinutes(attempt) * 60_000L
}
