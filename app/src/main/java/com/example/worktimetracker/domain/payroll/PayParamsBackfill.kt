package com.example.worktimetracker.domain.payroll

import com.example.worktimetracker.data.entity.MonthlyPayParamsEntity

/**
 * 工资条 → 月度计薪参数（`monthly_pay_params`）的**回填**（纯函数，零 IO）。
 *
 * ## 为什么需要它
 *
 * 设置页已把 10 项浮动参数从界面移除（本计薪月只留「绩效系数」一个输入框），
 * 但**引擎入参必须保留** —— 用户从相册导入 / 手录工资条后，
 * 这些**实际发生的金额**要落进 `monthly_pay_params`，
 * 下个月的预估才能按真实数据校准（否则引擎永远只有分段常量 + 绩效系数）。
 *
 * ## 合并规则（与 `WorkTimeViewModel.savePerfCoefficient` 同口径）
 *
 * | 工资条状态 | 语义 | 结果 |
 * |---|---|---|
 * | 有金额 | 如实记录 | **覆盖**库里同项 |
 * | 空白（未印/没认出来） | 未填写 | **保留**库里原值，绝不抹掉 |
 * | 印出来的 `0` | 明确为零 | 如实写 `0`（与「未填写」区分） |
 *
 * `perfCoefficient`（用户在界面填的）与 `perfBaseDeltaCents` **不由工资条派生**，一律原样保留。
 *
 * 返回 `null` = 工资条这一项也没给、那一项也没给 ⇒ **调用方不要碰数据库**；
 * 返回非 null 时，若 [MonthlyPayParamsEntity.isEmpty] 为真则调用方应删除该行（不留空壳）。
 */
object PayParamsBackfill {

    /**
     * @param payrollMonth 计薪月（`YYYY-MM`），新建行时的主键
     * @param current 库里已有的行；`null` = 该计薪月还没有行
     * @param items 工资条分项（`amountCents == null` 即「未填写」，会被跳过）
     * @param nightShifts 工资条「计薪夜班数」；`null` = 条上没写
     * @param updatedAt 落库时间戳
     * @return 待 upsert 的整行；`null` = 本次没有任何可回填的内容
     */
    fun mergeOrNull(
        payrollMonth: String,
        current: MonthlyPayParamsEntity?,
        items: List<SlipItemEntry>,
        nightShifts: Int?,
        updatedAt: Long,
    ): MonthlyPayParamsEntity? {
        val byKey = items.mapNotNull { entry -> entry.amountCents?.let { entry.key to it } }.toMap()
        if (byKey.isEmpty() && nightShifts == null) return null

        val cur = current ?: MonthlyPayParamsEntity(payrollMonth = payrollMonth)
        return cur.copy(
            // 绩效工资：有直接金额就覆盖（引擎见 perfAmountCents 非空即不再算 (基数+Δ)×系数）
            perfAmountCents = byKey[SlipItemKey.PERFORMANCE_PAY] ?: cur.perfAmountCents,
            benefitBonusCents = byKey[SlipItemKey.BENEFIT_BONUS] ?: cur.benefitBonusCents,
            heatAllowanceCents = byKey[SlipItemKey.HEAT_ALLOWANCE] ?: cur.heatAllowanceCents,
            sickPayCents = byKey[SlipItemKey.SICK_PAY] ?: cur.sickPayCents,
            backPayCents = byKey[SlipItemKey.BACK_PAY] ?: cur.backPayCents,
            otherAddCents = byKey[SlipItemKey.OTHER_ADD] ?: cur.otherAddCents,
            socialOverrideCents = byKey[SlipItemKey.SOCIAL_INSURANCE] ?: cur.socialOverrideCents,
            housingFundOverrideCents = byKey[SlipItemKey.HOUSING_FUND] ?: cur.housingFundOverrideCents,
            nightShiftsOverride = nightShifts ?: cur.nightShiftsOverride,
            updatedAt = updatedAt,
        )
    }
}
