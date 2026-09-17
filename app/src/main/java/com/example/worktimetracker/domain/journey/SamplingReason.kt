package com.example.worktimetracker.domain.journey

/**
 * 采样原因码（`SamplingDecision.reasonCodes`）—— 解释"这一分钟为什么加密/降档"。
 *
 * 与 [JourneyReason] 分开：[SamplingDecision] 只讲采样，[com.example.worktimetracker.domain.journey.JourneyTransition]
 * 只讲状态；两个产物都不改对方内容，Coordinator 只组合（§5.1.1）。
 */
enum class SamplingReason {
    /** 常规基准档。 */
    BASELINE,

    /** 长时间稳定（在宅/在岗且置信高）—— 可以省。 */
    STABLE_PLACE,

    /** 候选期（LEAVING_* / ARRIVING_*）—— 需要加密以尽快确认。 */
    CANDIDATE_PENDING,

    /** 通勤中 —— 最密，避免丢掉到/离岗时刻。 */
    COMMUTING,

    /** 断流窗口内 —— 需要尽快拿回定位。 */
    STALE_WINDOW,

    /** 置信骤降 —— 需要更多证据补强。 */
    LOW_CONFIDENCE,

    /** 首次到岗 —— 事件正式时刻敏感，值得加密。 */
    FIRST_ARRIVAL,

    /** 冷却期内：本可进 CRITICAL，被冷却挡下（§5.3 契约 3）。 */
    COOLDOWN,

    /** CRITICAL 达到时长上限（复用 `SamplingTuning.HARD_BURST_CAP_MINUTES`），强制退出。 */
    CRITICAL_TIMEOUT,

    /** 连续失败后按指数退避推迟重试。 */
    RETRY_BACKOFF,

    /** 定位权限被撤或系统定位开关关闭 —— **不重复强制请求**（§5.3 契约 4）。 */
    LOCATION_UNAVAILABLE,

    /** 状态机未能给出结论，回落到 `SamplingTuning` 兜底。 */
    FALLBACK_ENGINE_FAILED,

    /** 可靠定位超过老化线（约 5 分钟）未更新 —— 证据在变旧（§5.3.2 健康度下限表）。 */
    AGING_EVIDENCE,

    /** 证据只够维持上一地点（MAINTAINED）—— 不许推进状态，但值得加密观察。 */
    WEAK_EVIDENCE,

    /** 地点判不出来（UNKNOWN）—— 与"没有证据"分开：证据在、但互相矛盾/不够。 */
    UNRESOLVED_PLACE,

    /** 提供器连续失败（1 次起 WATCH，3 次起 CRITICAL，§5.3.2 健康度下限表）。 */
    PROVIDER_FAILURES
}
