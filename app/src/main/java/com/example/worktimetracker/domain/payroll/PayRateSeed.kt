package com.example.worktimetracker.domain.payroll

import com.example.worktimetracker.data.entity.PayRateSegmentEntity

/**
 * 计薪参数的出厂分段常量 —— 全部来自用户 2026-09-13 上传的工资条实测值。
 *
 * 迁移到 DB v12 时一次性写入。之后用户在「计薪规则」页新增/修改的分段覆盖在这里之上。
 *
 * 分段依据（工资条逐月实测）：
 * - 基本工资 / 岗位津贴：2026-07 调薪 3000→3100、1500→1900
 * - 工龄工资：2026-01 = 30，2026-02 起 = 50
 * - 社保个人 / 公积金个人：2026-07 变 783.30→948.11、358.00→451.00
 * - 其余（全勤 100、绩效基数 600、夜班单价 45、加班包干 36h、起征点 5000、税率 3%）7 个月恒定
 */
object PayRateSeed {

    /** 最早一段的生效月：早于此月的计薪月一律退化到这一段。 */
    const val EARLIEST = "2024-01"

    fun segments(): List<PayRateSegmentEntity> = buildList {
        // ---- 调薪过的 4 项：两段
        add(row(PayRateKey.BASIC_SALARY, EARLIEST, 300_000L, "工资条 2026-01～06 实测"))
        add(row(PayRateKey.BASIC_SALARY, "2026-07", 310_000L, "2026-07 调薪"))
        add(row(PayRateKey.POST_ALLOWANCE, EARLIEST, 150_000L, "工资条 2026-01～06 实测"))
        add(row(PayRateKey.POST_ALLOWANCE, "2026-07", 190_000L, "2026-07 调薪"))
        add(row(PayRateKey.SOCIAL_INSURANCE, EARLIEST, 78_330L, "工资条 2026-01～06 实测"))
        add(row(PayRateKey.SOCIAL_INSURANCE, "2026-07", 94_811L, "2026-07 缴费基数调整"))
        add(row(PayRateKey.HOUSING_FUND, EARLIEST, 35_800L, "工资条 2026-01～06 实测"))
        add(row(PayRateKey.HOUSING_FUND, "2026-07", 45_100L, "2026-07 缴费基数调整"))

        // ---- 工龄工资：2026-02 起从 30 涨到 50
        add(row(PayRateKey.SENIOR_ALLOWANCE, EARLIEST, 3_000L, "工资条 2026-01 实测"))
        add(row(PayRateKey.SENIOR_ALLOWANCE, "2026-02", 5_000L, "工资条 2026-02 起实测"))

        // ---- 7 个月恒定
        add(row(PayRateKey.FULL_ATTENDANCE, EARLIEST, 10_000L, "工资条实测恒定"))
        add(row(PayRateKey.PERF_BASE, EARLIEST, 60_000L, "工资条「绩效工资基数」"))
        add(row(PayRateKey.NIGHT_ALLOWANCE_UNIT, EARLIEST, 4_500L, "津贴值 ÷ 夜班天数 恒为 45"))
        add(row(PayRateKey.OT_PACKAGE_HOURS, EARLIEST, 3_600L, "加班工资 = 1.5×36h×基本工资÷174"))
        add(row(PayRateKey.TAX_THRESHOLD, EARLIEST, 500_000L, "个税起征点"))
        add(row(PayRateKey.TAX_RATE_BP, EARLIEST, 300L, "个税 3% 档"))
    }

    private fun row(key: PayRateKey, from: String, value: Long, note: String) =
        PayRateSegmentEntity(paramKey = key.storageKey, effectiveFrom = from, value = value, note = note)
}
