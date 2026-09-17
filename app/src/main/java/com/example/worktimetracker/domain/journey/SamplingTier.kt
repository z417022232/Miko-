package com.example.worktimetracker.domain.journey

/**
 * 采样档位（§5.3）。由 [SamplingDecision] 承载，**不由 `JourneyTransition` 携带**
 * （一轮 P0-2：产物必须拆开，否则引擎要么调采样策略、要么自己算档，都违反三层分离）。
 *
 * 枚举顺序 = 由省到密，`ordinal` 递增即"更密"（`urgency` 单调不减的测试依赖这一点）。
 * **档位边界不在这里**：[SamplingContract.tierFor] 持有等距五档的冻结边界。
 */
enum class SamplingTier {
    /** 长时间在宅/在岗、置信高 —— 拉到最省。 */
    STABLE,

    /** 常规在岗/在宅 —— 基准。 */
    NORMAL,

    /** 候选期（LEAVING_* / ARRIVING_*）—— 加密。 */
    WATCH,

    /** 通勤中 —— 最密。 */
    TRANSITION,

    /** 断流中 / 首次到岗 / 置信骤降 —— 最高频且强制获取（**受 §5.3 四条契约约束**）。 */
    CRITICAL;

    companion object {
        /**
         * 从持久化字符串解析；**未知值返回 null（失败），绝不猜测**。
         */
        fun parseOrNull(raw: String?): SamplingTier? = entries.firstOrNull { it.name == raw }
    }
}
