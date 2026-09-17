package com.example.worktimetracker.domain.journey

/**
 * 状态机原因码（`JourneyTransition.reasonCodes`）—— 机器可断言，测试与影子对比用。
 *
 * 与 `JourneyTransition.explanation` 的分工：本枚举给**程序看**（对比、统计、断言），
 * explanation 给**人看**（诊断页一句话）。两者必须同时产出，
 * 光有人话文案的判定无法在影子对照里被自动分类。
 */
enum class JourneyReason {
    /** 这一拍没有任何变化（状态、候选都没动）。 */
    NO_CHANGE,

    /** 断流：距最近有效定位超过 `JourneyConfig.staleAfterSeconds`，进 STALE。 */
    STALE_NO_FIX,

    /** 证据不足/矛盾，无法判定（UNKNOWN）。 */
    INSUFFICIENT_EVIDENCE,

    /** 地点为 CONFIRMED，允许推进状态/候选。 */
    PLACE_CONFIRMED,

    /** 地点仅为 MAINTAINED —— 只维持，**不推进**（§5.2.1 推进规则）。 */
    PLACE_MAINTAINED_ONLY,

    /** 地点 UNKNOWN —— 不推进，且连续超时后进 STALE。 */
    PLACE_UNKNOWN,

    /** 开了新候选。 */
    CANDIDATE_STARTED,

    /** 候选得到新的支持证据（累计推进，但未达门槛）。 */
    CANDIDATE_SUPPORTED,

    /** 候选过期（超过 `JourneyConfig.candidateExpiryMillis`）作废。 */
    CANDIDATE_EXPIRED,

    /** 候选支持链命中门槛，确认事件。 */
    CANDIDATE_CONFIRMED,

    /** 候选被反向证据取消（回到上一个已确认状态，不产生事件）。 */
    CANDIDATE_REVERSED,

    /**
     * 观测时刻早于状态机已知的最后时刻（设备时间回拨 / 换机恢复备份）。
     * 落点固定为：丢弃候选、保留 `lastConfirmedPhase`、状态置 UNKNOWN（§5.6 规则 2）。
     */
    CLOCK_ROLLED_BACK,

    /**
     * 观测值越界或不可解释（负秒数 / NaN 或越界置信 / 负距离）。
     * 一律按**保守侧**清洗（负秒数按 0、坏置信按 0），绝不猜一个"看起来合理"的值。
     */
    INVALID_INPUT,

    /** 迟滞生效：阈值附近本可翻转，但被迟滞按住。 */
    HYSTERESIS_HELD,

    /** 确认到岗（CompanyArrival）。 */
    ARRIVAL_CONFIRMED,

    /** 确认离岗（CompanyDeparture）—— 正式下班，工作会话结束。 */
    DEPARTURE_CONFIRMED,

    /** 临时离岗开始（TempLeaveStart）。 */
    TEMP_LEAVE_STARTED,

    /** 临时离岗结束（TempLeaveEnd）—— 回司，工作会话继续。 */
    TEMP_LEAVE_ENDED,

    /** 临时离岗超时仍未归 → 按正式下班处理（§5.2.4）。 */
    TEMP_LEAVE_TIMEOUT,

    /** 运动判定过期（超过 `JourneyConfig.motionExpirySeconds`）。 */
    MOTION_EXPIRED,

    /** 新状态机执行异常；影子Coordinator保留上一快照并回落旧采样策略。 */
    ENGINE_FAILED
}
