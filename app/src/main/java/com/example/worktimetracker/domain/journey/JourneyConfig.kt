package com.example.worktimetracker.domain.journey

/**
 * 状态机阈值（Reducer 的输入之一，§5.2.1）—— **全部阈值集中在此，引擎内部零魔数**。
 *
 * ⚠️ 本类**刻意不提供默认值/常量**：数值的唯一定义处必须留在既有常量里
 * （§7 第 10 条「常量同源」），而那些常量位于 `location/service` 等编排层 ——
 * domain **不允许反向依赖**编排层。因此数值由 Coordinator 在构造时注入，
 * 下面注释给出每项的对齐来源，避免第 6 步实现时凭空造数：
 *
 * - [staleAfterSeconds]：对齐 `EvidenceContinuityPolicy.DEFAULT_MAX_GAP_MILLIS`（20 分钟）
 *   与 `EvidenceCoordinator.CONTINUITY_WINDOW_MILLIS`（20 分钟）；
 * - [candidateExpiryMillis]：对齐 `TrajectoryAnchorEngine.CANDIDATE_EXPIRE_MILLIS`（2 小时）；
 * - [arrivalRequiredMillis] / [departureRequiredMillis]：对齐旧机的拍数门槛换算成时长
 *   （旧机环境证据到岗需连续 2 次稳定读数 —— **新机改按累计时长计**，见 [JourneyCandidate] 注释）；
 * - [tempLeaveMaxMillis]：对齐旧 `TrajectoryAnchorEngine.Config.leaveConfirmMinutes`
 *   （弱证据下确认下班所需的等待时长）；
 * - [motionExpirySeconds]：新增 —— 运动判定保鲜期，超过即按 `MotionPhase.UNKNOWN` 处理。
 */
data class JourneyConfig(
    /** 断流门槛：距最近有效定位超过它进 `JourneyPhase.STALE`。 */
    val staleAfterSeconds: Long,

    /** 到岗候选确认所需的**累计稳定时长**（毫秒，不是拍数）。 */
    val arrivalRequiredMillis: Long,

    /** 离岗候选确认所需的**累计稳定时长**（毫秒，不是拍数）。 */
    val departureRequiredMillis: Long,

    /** 候选保鲜期：支持链中断超过它则候选作废。 */
    val candidateExpiryMillis: Long,

    /**
     * 临时离岗上限：离开公司后停留超过它仍未回司 → 按正式下班处理（§5.2.4）。
     *
     * ⚠️ 它是「确认正式下班」的三条判据之一（持续远离 / 到家 / 超时），
     * **不是**判定"是否临时离岗"的前置条件。
     */
    val tempLeaveMaxMillis: Long,

    /** 运动判定保鲜期：超过它则 `MotionPhase` 视为 UNKNOWN。 */
    val motionExpirySeconds: Long
)
