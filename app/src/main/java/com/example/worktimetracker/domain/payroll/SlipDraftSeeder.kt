package com.example.worktimetracker.domain.payroll

/**
 * 历史工资条草稿生成器（**纯函数**）。
 *
 * 用户 2026-09-15 确认：「全量种子 + 未填写留空」—— 能由分段常量确定的项照填，
 * 确定不了的浮动项**保持未填写**（`null`），交给 [SlipReconciler] 报出差额，
 * 差额即「还没补上的那部分」。
 *
 * 只填这 7 项（全部可由 `pay_rate_segments` 直接推出）：
 * 基本工资、岗位津贴、工龄工资、全勤奖、加班工资、社保个人、住房公积金个人。
 *
 * 其余全部留 `null`：
 * - 绩效工资 / 效益奖金 / 高温补贴 —— 浮动，**要学的就是它们**
 * - 夜班津贴 —— 公式已知（45 × 夜班天数），但**天数未知**，需按工资条核对
 * - 补发 / 其他加项 —— 一次性，每月不同
 * - 个人所得税 —— 由应发派生，应发还不完整时没法算
 * - 事假 / 迟到 / 绩效扣款 / 宿舍代扣 / 工会费 —— 历史上恒为 0，但「没印就是未填写」
 *
 * 逻辑刻意留在 Kotlin（而不是写死在 SQL 迁移里），这样 `PayRateSeed` 改了草稿跟着改，
 * 只有一处真相。
 */
object SlipDraftSeeder {

    /** 可由分段常量直接确定的项。 */
    private val determinable = listOf(
        SlipItemKey.BASIC_SALARY,
        SlipItemKey.POST_ALLOWANCE,
        SlipItemKey.SENIOR_ALLOWANCE,
        SlipItemKey.FULL_ATTENDANCE,
        SlipItemKey.OVERTIME_PAY,
        SlipItemKey.SOCIAL_INSURANCE,
        SlipItemKey.HOUSING_FUND,
    )

    /**
     * 给定某计薪月适用的 [rates]，产出该月的草稿分项（**按 [SlipItemKey.displayOrder] 顺序**）。
     */
    fun draftItems(rates: PayRateSet): List<SlipItemEntry> {
        val values: Map<SlipItemKey, Long> = mapOf(
            SlipItemKey.BASIC_SALARY to rates.basicSalaryCents,
            SlipItemKey.POST_ALLOWANCE to rates.postAllowanceCents,
            SlipItemKey.SENIOR_ALLOWANCE to rates.seniorAllowanceCents,
            SlipItemKey.FULL_ATTENDANCE to rates.fullAttendanceCents,
            // 加班工资公司不按真实加班结算，每月包干固定小时数
            SlipItemKey.OVERTIME_PAY to PayrollEngine.overtimePayCents(
                rates.basicSalaryCents, rates.otPackageHoursX100
            ),
            SlipItemKey.SOCIAL_INSURANCE to rates.socialInsuranceCents,
            SlipItemKey.HOUSING_FUND to rates.housingFundCents,
        )

        return SlipItemKey.displayOrder.map { key ->
            SlipItemEntry(
                key = key,
                amountCents = values[key],
                // 病假工资含义有歧义 → 草稿阶段就标出来，等用户裁决
                needsReview = key.reviewByDefault,
            )
        }
    }

    /** 是否属于「能自动填」的那 7 项（界面用来标注「已按计薪参数填好」）。 */
    fun isDeterminable(key: SlipItemKey): Boolean = key in determinable
}
