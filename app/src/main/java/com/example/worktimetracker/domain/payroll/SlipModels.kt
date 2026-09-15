package com.example.worktimetracker.domain.payroll

/**
 * 工资条分项的阶段 —— 决定它进哪一条核对式。
 *
 * 公司工资条是**两段式**的：
 * ```
 * 校验① Σ(INCOME) − Σ(DEDUCT_PRE_GROSS)  == 条上「应发工资」
 * 校验② 条上「应发工资」 − Σ(DEDUCT_POST_GROSS) == 条上「实发工资」
 * ```
 * 两条**分别**核对，绝不把「应发」与「实发」直接相减当误差。
 */
enum class SlipItemStage {
    /** 收入项：进应发 */
    INCOME,

    /** 收入侧扣减：事假 / 迟到 / 绩效扣款 —— 在应发**之前**扣，所以也影响应发 */
    DEDUCT_PRE_GROSS,

    /** 应发后扣减：社保 / 公积金 / 个税 / 其他扣款 —— 只影响实发 */
    DEDUCT_POST_GROSS,
}

/**
 * 分项性质 —— 决定是否参与「浮动项学习」、是否延续到下个月。
 *
 * | nature | 含义 | 学习 |
 * |---|---|---|
 * | FIXED | 由分段常量确定的固定项 | 不学，走 `pay_rate_segments` |
 * | FLOATING | 每月浮动的常规项 | **参与学习**（中位数 / 加权近期 / 按出勤折算） |
 * | ONE_TIME | 补发、一次性奖励、报销 | **不学、默认不延续** |
 */
enum class SlipItemNature { FIXED, FLOATING, ONE_TIME }

/** 工资条状态（存 `String`，即 `name`）。 */
enum class SlipStatus(val label: String) {
    /** 草稿：自己算出来的 / 还没核对 */
    DRAFT("草稿"),

    /** 待核对：有已知疑点或校验不平，等用户裁决 */
    PENDING_REVIEW("待核对"),

    /** 已确认：可作为学习样本（`confirmedAt` 必须有值） */
    CONFIRMED("已确认");

    companion object {
        fun parse(raw: String?): SlipStatus =
            entries.firstOrNull { it.name == raw } ?: DRAFT
    }
}

/**
 * 工资条分项目录 —— 与 `verification/计薪规则v2-工资条口径.md` §1.2 的清单一一对应。
 *
 * 顺序即界面展示顺序（与纸质工资条自上而下一致）。
 *
 * ⚠️「病假工资」按 01 月反推是**收入项不是扣款**（加回 331.03 才能让应发加总等于条上值），
 * 但含义本身有歧义，所以 [reviewByDefault] = true：默认标「待裁决」，由用户确认属性后固化。
 */
enum class SlipItemKey(
    val storageKey: String,
    val label: String,
    val stage: SlipItemStage,
    val nature: SlipItemNature,
    /** 默认是否需要用户裁决「这是收入还是扣款」 */
    val reviewByDefault: Boolean = false,
) {
    // ------------------------------------------------------------- 收入
    BASIC_SALARY("BASIC_SALARY", "基本工资", SlipItemStage.INCOME, SlipItemNature.FIXED),
    POST_ALLOWANCE("POST_ALLOWANCE", "岗位津贴", SlipItemStage.INCOME, SlipItemNature.FIXED),
    PERFORMANCE_PAY("PERFORMANCE_PAY", "绩效工资", SlipItemStage.INCOME, SlipItemNature.FLOATING),
    SENIOR_ALLOWANCE("SENIOR_ALLOWANCE", "工龄工资", SlipItemStage.INCOME, SlipItemNature.FIXED),
    FULL_ATTENDANCE("FULL_ATTENDANCE", "全勤奖", SlipItemStage.INCOME, SlipItemNature.FIXED),
    OVERTIME_PAY("OVERTIME_PAY", "加班工资", SlipItemStage.INCOME, SlipItemNature.FIXED),
    NIGHT_ALLOWANCE("NIGHT_ALLOWANCE", "夜班津贴", SlipItemStage.INCOME, SlipItemNature.FLOATING),
    BENEFIT_BONUS("BENEFIT_BONUS", "效益奖金", SlipItemStage.INCOME, SlipItemNature.FLOATING),
    HEAT_ALLOWANCE("HEAT_ALLOWANCE", "高温补贴", SlipItemStage.INCOME, SlipItemNature.FLOATING),
    SICK_PAY("SICK_PAY", "病假工资", SlipItemStage.INCOME, SlipItemNature.FLOATING, reviewByDefault = true),
    BACK_PAY("BACK_PAY", "补发", SlipItemStage.INCOME, SlipItemNature.ONE_TIME),
    OTHER_ADD("OTHER_ADD", "其他加项", SlipItemStage.INCOME, SlipItemNature.ONE_TIME),

    // ------------------------------------------- 收入侧扣减（应发之前扣）
    PERFORMANCE_DEDUCT("PERFORMANCE_DEDUCT", "绩效扣款", SlipItemStage.DEDUCT_PRE_GROSS, SlipItemNature.FLOATING),
    PERSONAL_LEAVE_DEDUCT("PERSONAL_LEAVE_DEDUCT", "事假扣款", SlipItemStage.DEDUCT_PRE_GROSS, SlipItemNature.FLOATING),
    LATE_DEDUCT("LATE_DEDUCT", "迟到扣款", SlipItemStage.DEDUCT_PRE_GROSS, SlipItemNature.FLOATING),

    // --------------------------------------- 应发后扣减（只影响到手）
    SOCIAL_INSURANCE("SOCIAL_INSURANCE", "社保个人", SlipItemStage.DEDUCT_POST_GROSS, SlipItemNature.FIXED),
    HOUSING_FUND("HOUSING_FUND", "住房公积金个人", SlipItemStage.DEDUCT_POST_GROSS, SlipItemNature.FIXED),
    INCOME_TAX("INCOME_TAX", "个人所得税", SlipItemStage.DEDUCT_POST_GROSS, SlipItemNature.FIXED),
    DORM_DEDUCT("DORM_DEDUCT", "宿舍代扣", SlipItemStage.DEDUCT_POST_GROSS, SlipItemNature.FLOATING),
    UNION_FEE("UNION_FEE", "工会费", SlipItemStage.DEDUCT_POST_GROSS, SlipItemNature.FLOATING),
    OTHER_DEDUCT("OTHER_DEDUCT", "其他扣款", SlipItemStage.DEDUCT_POST_GROSS, SlipItemNature.ONE_TIME);

    companion object {
        fun byStorageKey(raw: String?): SlipItemKey? =
            entries.firstOrNull { it.storageKey == raw }

        /** 界面展示顺序（与枚举声明顺序一致，分组连续）。 */
        val displayOrder: List<SlipItemKey> = entries.toList()
    }
}

/**
 * 一个分项的录入值。
 *
 * **`null` = 未填写；`0` = 明确为零** —— 这是整个模块最关键的一处语义区分：
 * 未填写的项**不参与合计**（否则会冒充"明确为零"把预测压低），
 * 而是进入「未填写清单」并解释校验差额。
 */
data class SlipItemEntry(
    val key: SlipItemKey,
    val amountCents: Long?,
    val needsReview: Boolean = false,
) {
    val isUnfilled: Boolean get() = amountCents == null
    val isExplicitZero: Boolean get() = amountCents != null && amountCents == 0L
}
