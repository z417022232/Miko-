package com.example.worktimetracker.domain.journey

/**
 * Coordinator **组合**两个纯单元产物后的运行时快照（§5.1.1）。
 *
 * ⚠️ 组合 = **原样并排**，Coordinator **不得修改**两个产物的任何字段
 * （一轮 P0-2：改了就分不清"状态机的结论"和"编排层加工过的结论"）。
 *
 * 它是**运行时**的（不持久化）：落库的是 [JourneyTransition.snapshot] 的影子状态（§5.6），
 * 采样决策只在当拍有效。
 */
data class JourneyRuntimeDecision(
    /** 状态机产物。 */
    val transition: JourneyTransition,

    /** 采样策略产物。 */
    val sampling: SamplingDecision
)
