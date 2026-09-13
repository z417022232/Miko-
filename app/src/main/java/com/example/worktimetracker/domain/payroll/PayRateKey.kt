package com.example.worktimetracker.domain.payroll

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 计薪参数的单位。分段常量表里所有值都存 `Long`，单位由 key 决定。
 */
enum class PayRateUnit {
    /** 分（1 元 = 100） */
    MONEY_CENTS,

    /** 小时 × 100（36 小时存 3600，允许 0.25 小时粒度） */
    HOURS_X100,

    /** 基点（300 = 3%） */
    RATE_BP,
}

/**
 * 计薪参数清单（v2 工资条口径）。
 *
 * 这些都是**分段常量**：每个参数可以有多条带「生效月份」的记录，
 * 取 `effectiveFrom <= 计薪月` 的最近一条。这样调薪只加一条新段，
 * 回看历史月份仍是旧数值，不会把历史算歪。
 *
 * 默认值 = 工资条 2026-07 实测值（最近一个月）。
 */
enum class PayRateKey(
    val storageKey: String,
    val label: String,
    val unit: PayRateUnit,
    val defaultValue: Long,
    val hint: String,
) {
    BASIC_SALARY(
        "BASIC_SALARY", "基本工资", PayRateUnit.MONEY_CENTS, 310_000L,
        "加班工资的计算基数，调薪后新增一段并选生效月"
    ),
    POST_ALLOWANCE(
        "POST_ALLOWANCE", "岗位津贴", PayRateUnit.MONEY_CENTS, 190_000L,
        "固定发放，与工时无关"
    ),
    SENIOR_ALLOWANCE(
        "SENIOR_ALLOWANCE", "工龄工资", PayRateUnit.MONEY_CENTS, 5_000L,
        "随工龄增长，涨了新增一段"
    ),
    FULL_ATTENDANCE(
        "FULL_ATTENDANCE", "全勤奖", PayRateUnit.MONEY_CENTS, 10_000L,
        "全勤时的固定奖励"
    ),
    PERF_BASE(
        "PERF_BASE", "绩效工资基数", PayRateUnit.MONEY_CENTS, 60_000L,
        "绩效工资 =（基数 + 月度Δ）× 系数 × 出勤折算系数"
    ),
    SOCIAL_INSURANCE(
        "SOCIAL_INSURANCE", "社保个人合计", PayRateUnit.MONEY_CENTS, 94_811L,
        "工资条「社保个人合计」，比例不固定，按实测填"
    ),
    HOUSING_FUND(
        "HOUSING_FUND", "住房公积金个人", PayRateUnit.MONEY_CENTS, 45_100L,
        "工资条「住房公积金_个人」"
    ),
    NIGHT_ALLOWANCE_UNIT(
        "NIGHT_ALLOWANCE_UNIT", "夜班津贴单价", PayRateUnit.MONEY_CENTS, 4_500L,
        "元/夜，工资条实测 45 元"
    ),
    OT_PACKAGE_HOURS(
        "OT_PACKAGE_HOURS", "加班包干小时", PayRateUnit.HOURS_X100, 3_600L,
        "加班工资 = 1.5 × 此小时数 ×（基本工资 ÷ 21.75 ÷ 8），公司不按真实加班结算"
    ),
    TAX_THRESHOLD(
        "TAX_THRESHOLD", "个税起征点", PayRateUnit.MONEY_CENTS, 500_000L,
        "元/月"
    ),
    TAX_RATE_BP(
        "TAX_RATE_BP", "个税税率", PayRateUnit.RATE_BP, 300L,
        "基点，300 = 3%"
    );

    companion object {
        fun byStorageKey(raw: String): PayRateKey? = entries.firstOrNull { it.storageKey == raw }

        /** 按界面展示顺序（与工资条自上而下一致）。 */
        val displayOrder: List<PayRateKey> = listOf(
            BASIC_SALARY, POST_ALLOWANCE, SENIOR_ALLOWANCE, FULL_ATTENDANCE, PERF_BASE,
            NIGHT_ALLOWANCE_UNIT, OT_PACKAGE_HOURS, SOCIAL_INSURANCE, HOUSING_FUND,
            TAX_THRESHOLD, TAX_RATE_BP,
        )
    }
}

/** 一个计薪月适用的全套参数取值。 */
data class PayRateSet(private val values: Map<PayRateKey, Long>) {
    operator fun get(key: PayRateKey): Long = values[key] ?: key.defaultValue

    val basicSalaryCents: Long get() = get(PayRateKey.BASIC_SALARY)
    val postAllowanceCents: Long get() = get(PayRateKey.POST_ALLOWANCE)
    val seniorAllowanceCents: Long get() = get(PayRateKey.SENIOR_ALLOWANCE)
    val fullAttendanceCents: Long get() = get(PayRateKey.FULL_ATTENDANCE)
    val perfBaseCents: Long get() = get(PayRateKey.PERF_BASE)
    val socialInsuranceCents: Long get() = get(PayRateKey.SOCIAL_INSURANCE)
    val housingFundCents: Long get() = get(PayRateKey.HOUSING_FUND)
    val nightAllowanceUnitCents: Long get() = get(PayRateKey.NIGHT_ALLOWANCE_UNIT)
    val otPackageHoursX100: Long get() = get(PayRateKey.OT_PACKAGE_HOURS)
    val taxThresholdCents: Long get() = get(PayRateKey.TAX_THRESHOLD)
    val taxRateBp: Long get() = get(PayRateKey.TAX_RATE_BP)

    companion object {
        val DEFAULT = PayRateSet(emptyMap())
    }
}

/**
 * 分段常量解析：把「key → 按生效月排序的 (effectiveFrom, value)」解析成某月适用的取值。
 *
 * `effectiveFrom` 用 `YYYY-MM` 字符串，字典序即时间序，因此直接用字符串比较。
 * 该月早于所有分段时退化为最早一段（不是默认值）——这样 2024 年的月份也能算。
 */
object PayRateResolver {

    fun resolve(
        payrollMonth: String,
        segments: Map<PayRateKey, List<Segment>>,
    ): PayRateSet {
        val out = HashMap<PayRateKey, Long>()
        for (key in PayRateKey.entries) {
            val list = segments[key]?.sortedBy { it.effectiveFrom }.orEmpty()
            val picked = list.lastOrNull { it.effectiveFrom <= payrollMonth } ?: list.firstOrNull()
            out[key] = picked?.value ?: key.defaultValue
        }
        return PayRateSet(out)
    }

    /** 取该 key 在该月的取值，以及它生效于哪个月（UI 要显示"自 2026-07 起"）。 */
    fun pick(payrollMonth: String, list: List<Segment>): Segment? {
        val sorted = list.sortedBy { it.effectiveFrom }
        return sorted.lastOrNull { it.effectiveFrom <= payrollMonth } ?: sorted.firstOrNull()
    }

    data class Segment(val effectiveFrom: String, val value: Long)
}
