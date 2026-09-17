package com.example.worktimetracker.domain.model

/**
 * `work_records.finalMinutes` 的来源（方案 §十一 阶段1）。
 *
 * 为什么必须落库：`WorkHourCalculator` 早就把来源写进了 `ruleTrace`，但那是**瞬时字符串**，
 * 落库后只剩一个 `finalMinutes` 数字。等到阶段4/5 要学「真实出勤时长」与
 * 「出勤折算系数」时，就分不清某天的 660 分钟是**真干了 11 小时**，
 * 还是被 `hasDefaultHours` 直接填的固定工时 —— 前者能进训练集，后者不能。
 *
 * ⚠️ 老记录该字段为 null（迁移不回填）：**不许猜**。来源未知的样本一律不进训练集，
 * 这与「OCR 未确认数据不参与工资学习」是同一条原则。
 */
enum class FinalMinutesSource {
    /** 由走班时间（到岗/离岗 + 计薪规则 R1–R8）实算出来的 */
    ACTUAL,

    /** 命中「固定工时」短路（R9 / `R_DEFAULT_HOURS`），**不是**真实出勤时长 */
    DEFAULT,

    /** 用户手工改过（`manualFieldsMask` 的 FINAL_MINUTES 位） */
    MANUAL,

    /** 由班次窗口推算补全（方案 §六），标记为待确认 */
    INFERRED;

    companion object {
        /** 宽松解析：库里可能混入历史/异常写法，认不出来返回 null 而不是抛异常。 */
        fun parse(raw: String?): FinalMinutesSource? =
            raw?.let { value -> entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }

        /** 是否可进学习训练集：只有实算与人工确认过的算「事实」。 */
        fun isLearnable(source: FinalMinutesSource?): Boolean =
            source == ACTUAL || source == MANUAL
    }
}
