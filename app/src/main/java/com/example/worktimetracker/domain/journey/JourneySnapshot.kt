package com.example.worktimetracker.domain.journey

/**
 * 状态机的**内部记忆**（Reducer 的输入之一，§5.2.1）—— 可整体持久化/恢复（§5.6）。
 *
 * **只放状态机自己的东西**：当前状态、进行中的候选、上一个已确认状态、上次变迁时刻。
 *
 * ⚠️ 二轮 P0-1：`activeWorkSession` **已从这里移除**，改为
 * `JourneyObservation.hasActiveWorkSession`。理由：
 * - 影子期正式会话由**旧机**维护，放进快照后新机快照不会自动跟随旧机；
 *   Coordinator 每拍强行改快照又违反「不修改纯单元产物」；
 * - 切正式机后会形成**循环**：状态机靠它判下班，它又等状态机确认 `CompanyDeparture` 才结束。
 *
 * 分工：**Observation = 外部事实，Snapshot = 内部记忆**。
 */
data class JourneySnapshot(
    /** 当前状态。 */
    val phase: JourneyPhase,

    /** 进行中的候选；null = 无候选。 */
    val candidate: JourneyCandidate?,

    /**
     * 上一个**已确认**的状态（不是上一个状态）—— 断流进 STALE 恢复后接回这里。
     * ⚠️ 必须持久化（§5.6 `lastConfirmedPhase` 列）：不落库的话恢复规则无从执行（二轮 P0-2）。
     */
    val lastConfirmedPhase: JourneyPhase?,

    /** 上次状态变迁时刻（迟滞用），epoch millis。 */
    val lastTransitionAt: Long
) {
    companion object {
        /**
         * 初始快照：**尚无证据** —— `UNKNOWN`、无候选、无已确认状态。
         *
         * 起始态取 `UNKNOWN` 而不是 `STALE`：STALE 的语义是"曾经有过证据、现在断了"，
         * 刚启动尚未取证时不该被描述成"断流"。
         */
        fun initial(now: Long): JourneySnapshot = JourneySnapshot(
            phase = JourneyPhase.UNKNOWN,
            candidate = null,
            lastConfirmedPhase = null,
            lastTransitionAt = now
        )
    }
}
