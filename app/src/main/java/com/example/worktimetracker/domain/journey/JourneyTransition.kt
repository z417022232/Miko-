package com.example.worktimetracker.domain.journey

/**
 * `JourneyEngine.reduce` 的**唯一产物**（§5.2.3）。
 *
 * ⚠️ 它**不含采样档**：采样是 `SamplingDecision` 的产物（一轮 P0-2 ——
 * 若把 `samplingTier` 塞进来，引擎要么调采样策略、要么自己算档、要么由 Coordinator 改产物，
 * 三种都与三层分离冲突）。
 *
 * [confirmedEvents] 为**列表**（二轮 P0-3）：同一拍可确认多个事件，
 * 列表内的排序约束由 [JourneyEventOrder] 校验。
 * 空列表是**合法且常见**的（候选期只更新状态、还没到确认那一刻）。
 */
data class JourneyTransition(
    /** 下一拍快照（含更新后的候选累计）。 */
    val snapshot: JourneySnapshot,

    /** 本拍确认的事件；空 = 未确认任何事件。排序约束见 [JourneyEventOrder]。 */
    val confirmedEvents: List<JourneyEvent>,

    /** 机器可断言的原因码（测试与影子对比用）。 */
    val reasonCodes: Set<JourneyReason>,

    /** 人话原因（方案 §一 原则 6：每次判定必须能解释依据）。 */
    val explanation: String
)
