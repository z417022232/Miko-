package com.example.worktimetracker.domain.journey

import com.example.worktimetracker.domain.evidence.EvidenceSource
import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.evidence.ResolvedPlace

/**
 * 一拍的**纯观测**（Reducer 的输入之一，§5.2.1）。
 *
 * **只描述外部事实** —— 时间、地点、决策等级、运动、断流、工作会话、距离。
 * **不含任何状态、不含任何阈值**：状态在 [JourneySnapshot]，
 * 阈值在 [JourneyConfig]，两者都不是"观测到的事实"。
 *
 * 三个关键设计点：
 * 1. [placeDecision] 必须与 [place] 同传 —— 只有地点+置信度分不清
 *    `CONFIRMED HOME` 与 `MAINTAINED HOME`，而前者可推进、后者只可维持（一轮 P0-4）；
 * 2. [hasActiveWorkSession] 是**外部事实**而非状态机记忆（二轮 P0-1）——
 *    它由 Coordinator 每拍从权威工作会话读取后填入，绝不写进 [JourneySnapshot]
 *    （放在快照里会导致影子期不跟随旧机、切正式机后与 `CompanyDeparture` 循环依赖）；
 * 3. [motion] / [motionObservedAt] 受时钟域约束：业务时间一律 **epoch millis**，
 *    `SystemClock.elapsedRealtime()`（采集层现用单调钟）只用于进程内超时，
 *    **两钟不许互转互比**（二轮 P0-4）。编排层负责在构造本对象前完成换算。
 */
data class JourneyObservation(
    /** 本拍时刻，epoch millis。 */
    val now: Long,

    /** 融合层判定地点（HOME / COMPANY / OTHER / MOVING / UNKNOWN）。 */
    val place: ResolvedPlace,

    /**
     * 融合层决策等级。
     * - CONFIRMED：可推进状态转换（含候选累计）；
     * - MAINTAINED：**只可维持**，禁止推进；
     * - UNKNOWN：不推进，连续超时后进 `JourneyPhase.STALE`。
     */
    val placeDecision: FusedDecision,

    /** 融合层原始置信（0..1）—— 用分值不用 UI 档位，档位线改了不该影响状态机。 */
    val confidence: Double,

    /**
     * 本拍判定所依据的证据来源（GNSS / WIFI / CELL / BLUETOOTH …）。
     *
     * 它是 [JourneyCandidate.evidenceSources] 的**唯一数据来源** ——
     * 引擎不做任何推断：融合层说这一拍靠哪些源定的，状态机就记哪些源。
     * 没有它，候选的来源字段永远为空（"解释"就是假的），
     * 且 §5.6 要求持久化的三字段里有一个无从填写。
     */
    val evidenceSources: Set<EvidenceSource>,

    /** 运动形态（第一版只有 STATIONARY / MOVING / UNKNOWN）。 */
    val motion: MotionPhase,

    /** 最近一次运动判定的时刻，epoch millis；null = 从未有过运动判定。 */
    val motionObservedAt: Long?,

    /** 距最近一次有效定位的秒数（断流检测）。 */
    val secondsSinceFix: Long,

    /**
     * 是否存在活动工作会话（当天已确认到岗且未确认下班）。
     *
     * ⚠️ 用途边界：**只**用于区分 `AWAY`（休息日外出）与 `OTHER_STOP`（工作期间外出），
     * **不单独决定** TEMP_LEAVE vs 正式下班 —— 正式下班由路径、时长、到家证据共同确认
     * （§5.2.4；否则会出现"会话结束依赖下班事件、下班事件又依赖会话结束"的循环）。
     */
    val hasActiveWorkSession: Boolean,

    /** 到家距离（米），与旧 `Fix.homeDistanceMeters` 同源；未知为 null。 */
    val distanceToHomeMeters: Double?,

    /** 到公司距离（米），与旧 `Fix.companyDistanceMeters` 同源；未知为 null。 */
    val distanceToWorkMeters: Double?
)
