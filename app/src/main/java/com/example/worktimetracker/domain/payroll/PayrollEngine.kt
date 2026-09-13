package com.example.worktimetracker.domain.payroll

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 公司计薪引擎 v2（工资条口径）。
 *
 * **纯函数，不做任何 IO，推算结果永不落库。** 已手动录入的实发工资（monthly_salaries）
 * 是唯一权威来源，引擎的输出只用于「还没录入的月份」的展示。
 *
 * 公式全部由用户 2026-09-13 上传的工资条反推并逐月对账得到，见
 * `verification/计薪规则v2-工资条口径.md`。
 *
 * ```
 * 加班工资 = 1.5 × 包干小时 × (基本工资 ÷ 21.75 ÷ 8)      ← 7/7 月命中到分
 * 夜班津贴 = 单价 × 当月夜班天数
 * 绩效工资 = (绩效基数 + Δ月) × 绩效系数 × 出勤折算系数
 * 出勤折算 = 当月计薪出勤天数 ÷ 21.75
 * 应发     = 基本 + 岗位 + 绩效 + 工龄 + 全勤 + 加班 + 夜班 + 效益 + 高温 + 病假 + 补发 + 其他
 * 个税     = max(0, 应发 − 社保 − 公积金 − 起征点) × 税率       ← 5/7 月命中到分
 * 实发     = 应发 − 社保 − 公积金 − 个税
 * ```
 */
object PayrollEngine {

    /** 法定月计薪天数 */
    private val MONTH_DAYS = BigDecimal("21.75")

    /** 日标准工时 */
    private val HOURS_PER_DAY = BigDecimal(8)

    /** 平时加班倍率 */
    private val OT_MULTIPLIER = BigDecimal("1.5")

    private val HUNDRED = BigDecimal(100)
    private val TEN_THOUSAND = BigDecimal(10_000)

    private const val SCALE = 10

    // ----------------------------------------------------------------- 单项

    /**
     * 加班工资 = `1.5 × 包干小时 × (基本工资 ÷ 21.75 ÷ 8)`。
     *
     * 公司不按真实加班时长结算，而是每月包干固定小时数（工资条实测 36h）。
     * 7 个月全部命中到分：3000 元 → 931.03，3100 元 → 962.07。
     */
    fun overtimePayCents(basicSalaryCents: Long, packageHoursX100: Long): Long {
        val hourly = BigDecimal(basicSalaryCents)
            .divide(MONTH_DAYS, SCALE, RoundingMode.HALF_UP)
            .divide(HOURS_PER_DAY, SCALE, RoundingMode.HALF_UP)
        val hours = BigDecimal(packageHoursX100).divide(HUNDRED, 4, RoundingMode.HALF_UP)
        return hourly.multiply(OT_MULTIPLIER).multiply(hours)
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
    }

    /** 夜班津贴 = 单价 × 夜班天数 */
    fun nightAllowanceCents(unitCents: Long, nightShiftDays: Int): Long =
        unitCents.coerceAtLeast(0L) * nightShiftDays.coerceAtLeast(0).toLong()

    /** 出勤折算系数 = 当月计薪出勤天数 ÷ 21.75（不封顶：用户月出勤 25–28 天） */
    fun attendanceFactor(attendDays: Int): BigDecimal =
        BigDecimal(attendDays.coerceAtLeast(0)).divide(MONTH_DAYS, 6, RoundingMode.HALF_UP)

    /**
     * 绩效工资 = `(绩效基数 + Δ月) × 绩效系数 × 出勤折算系数`。
     *
     * 工资条印的「绩效基数 600 × 绩效系数」对不上条上的绩效工资（详见口径文档 §1.3），
     * 所以允许调用方用 [PayrollInputs.perfAmountCents] 直接给金额覆盖。
     */
    fun performancePayCents(
        perfBaseCents: Long,
        deltaCents: Long,
        coefficient: BigDecimal,
        factor: BigDecimal,
    ): Long {
        val base = BigDecimal(perfBaseCents + deltaCents)
        return base.multiply(coefficient).multiply(factor)
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
            .coerceAtLeast(0L)
    }

    /**
     * 个税 = `max(0, 应发 − 社保 − 公积金 − 起征点) × 税率`。
     *
     * 公司用的是**按月**口径（不是年度累计预扣），工资条 5/7 月精确命中到分。
     * 02 / 06 月与条上差 0.01（对方系统的进位差），已在口径文档记录。
     */
    fun incomeTaxCents(
        grossCents: Long,
        socialCents: Long,
        fundCents: Long,
        thresholdCents: Long,
        rateBp: Long,
    ): Long {
        val taxable = grossCents - socialCents - fundCents - thresholdCents
        if (taxable <= 0L) return 0L
        return BigDecimal(taxable)
            .multiply(BigDecimal(rateBp.coerceAtLeast(0L)))
            .divide(TEN_THOUSAND, 0, RoundingMode.HALF_UP)
            .toLong()
    }

    /** 应纳税所得额（可能为负，UI 直接显示 0）。 */
    fun taxableBaseCents(grossCents: Long, socialCents: Long, fundCents: Long, thresholdCents: Long): Long =
        grossCents - socialCents - fundCents - thresholdCents

    // ----------------------------------------------------------------- 整月

    /**
     * 一个计薪月的推算总额。
     *
     * [PayrollBreakdown.grossCents] = 应发，[PayrollBreakdown.netCents] = 实发（预计到手）。
     */
    fun estimate(rates: PayRateSet, inputs: PayrollInputs): PayrollBreakdown {
        val social = inputs.socialOverrideCents ?: rates.socialInsuranceCents
        val fund = inputs.housingFundOverrideCents ?: rates.housingFundCents
        val nights = inputs.nightShiftsOverride ?: inputs.nightShiftDays

        val factor = attendanceFactor(inputs.attendDays)
        val overtime = overtimePayCents(rates.basicSalaryCents, rates.otPackageHoursX100)
        val night = nightAllowanceCents(rates.nightAllowanceUnitCents, nights)
        val perf = inputs.perfAmountCents ?: performancePayCents(
            perfBaseCents = rates.perfBaseCents,
            deltaCents = inputs.perfBaseDeltaCents,
            coefficient = inputs.perfCoefficient ?: BigDecimal.ONE,
            factor = factor,
        )

        val gross = rates.basicSalaryCents +
            rates.postAllowanceCents +
            perf +
            rates.seniorAllowanceCents +
            rates.fullAttendanceCents +
            overtime +
            night +
            inputs.benefitBonusCents +
            inputs.heatAllowanceCents +
            inputs.sickPayCents +
            inputs.backPayCents +
            inputs.otherAddCents

        val taxable = taxableBaseCents(gross, social, fund, rates.taxThresholdCents)
        val tax = incomeTaxCents(gross, social, fund, rates.taxThresholdCents, rates.taxRateBp)
        val net = gross - social - fund - tax

        return PayrollBreakdown(
            attendanceFactor = factor,
            attendDays = inputs.attendDays,
            performancePayCents = perf,
            overtimePayCents = overtime,
            nightAllowanceCents = night,
            nightShiftDays = nights,
            benefitBonusCents = inputs.benefitBonusCents,
            heatAllowanceCents = inputs.heatAllowanceCents,
            sickPayCents = inputs.sickPayCents,
            backPayCents = inputs.backPayCents,
            otherAddCents = inputs.otherAddCents,
            basicSalaryCents = rates.basicSalaryCents,
            postAllowanceCents = rates.postAllowanceCents,
            seniorAllowanceCents = rates.seniorAllowanceCents,
            fullAttendanceCents = rates.fullAttendanceCents,
            grossCents = gross,
            socialCents = social,
            fundCents = fund,
            taxableCents = taxable,
            incomeTaxCents = tax,
            netCents = net,
        )
    }

    // ----------------------------------------------------------------- 日工资

    /**
     * 当日工资（**基准月校准法**，用户 2026-09-13 选定）。
     *
     * ```
     * 基准到手单价 = 基准月实发 ÷ 基准月出勤分钟
     * 当日工资     = 当日计薪分钟 × 基准到手单价
     * ```
     *
     * 基准月 = 最近一个「已手动录入实发」且「出勤分钟 > 0」的月份。
     * 用已录入的真实到手数据校准，而不是拿推算值去猜。
     * 找不到基准月返回 null —— 界面显示「暂无基准」而不是猜一个数。
     */
    fun dailyEstimateCents(dayMinutes: Int, baselineNetCents: Long, baselineMinutes: Int): Long? {
        if (dayMinutes <= 0) return 0L
        if (baselineMinutes <= 0 || baselineNetCents <= 0L) return null
        return BigDecimal(dayMinutes)
            .multiply(BigDecimal(baselineNetCents))
            .divide(BigDecimal(baselineMinutes), 0, RoundingMode.HALF_UP)
            .toLong()
    }

    /** 基准到手单价（分/小时），供界面显示「按 ¥xx.xx/小时 算」。 */
    fun baselineHourlyCents(baselineNetCents: Long, baselineMinutes: Int): Long? {
        if (baselineMinutes <= 0 || baselineNetCents <= 0L) return null
        return BigDecimal(baselineNetCents)
            .multiply(BigDecimal(60))
            .divide(BigDecimal(baselineMinutes), 0, RoundingMode.HALF_UP)
            .toLong()
    }
}

/**
 * 一个计薪月的输入。前 2 项来自 App 自己的工时记录，其余来自 `monthly_pay_params`。
 */
data class PayrollInputs(
    /** 当月计薪出勤天数（finalMinutes > 0 的记录数），由 App 记录统计 */
    val attendDays: Int,
    /** 当月夜班天数，由 App 记录统计 */
    val nightShiftDays: Int,
    /** 绩效系数，如 1.0 / 0.8；null 视为 1.0 */
    val perfCoefficient: BigDecimal? = null,
    /** Δ月：绩效基数月度增量 */
    val perfBaseDeltaCents: Long = 0L,
    /** 绩效工资直接覆盖（非空则忽略上面的乘积） */
    val perfAmountCents: Long? = null,
    val benefitBonusCents: Long = 0L,
    val heatAllowanceCents: Long = 0L,
    val sickPayCents: Long = 0L,
    val backPayCents: Long = 0L,
    val otherAddCents: Long = 0L,
    /** 社保月度覆盖（空则用分段常量） */
    val socialOverrideCents: Long? = null,
    /** 公积金月度覆盖（空则用分段常量） */
    val housingFundOverrideCents: Long? = null,
    /** 夜班天数覆盖（空则用 App 记录统计的天数） */
    val nightShiftsOverride: Int? = null,
)

/** 一个计薪月的推算明细。 [netCents] 即「预计到手」。 */
data class PayrollBreakdown(
    val attendanceFactor: BigDecimal,
    val attendDays: Int,
    val performancePayCents: Long,
    val overtimePayCents: Long,
    val nightAllowanceCents: Long,
    val nightShiftDays: Int,
    val benefitBonusCents: Long,
    val heatAllowanceCents: Long,
    val sickPayCents: Long,
    val backPayCents: Long,
    val otherAddCents: Long,
    val basicSalaryCents: Long,
    val postAllowanceCents: Long,
    val seniorAllowanceCents: Long,
    val fullAttendanceCents: Long,
    val grossCents: Long,
    val socialCents: Long,
    val fundCents: Long,
    val taxableCents: Long,
    val incomeTaxCents: Long,
    val netCents: Long,
)
