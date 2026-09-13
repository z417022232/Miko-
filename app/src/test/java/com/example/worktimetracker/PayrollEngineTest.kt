package com.example.worktimetracker

import com.example.worktimetracker.domain.payroll.PayRateKey
import com.example.worktimetracker.domain.payroll.PayRateResolver
import com.example.worktimetracker.domain.payroll.PayRateSeed
import com.example.worktimetracker.domain.payroll.PayrollEngine
import com.example.worktimetracker.domain.payroll.PayrollInputs
import com.example.worktimetracker.ui.PayrollPresenter
import com.example.worktimetracker.ui.UiDayRecord
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计薪规则 v2 的护栏测试。
 *
 * 最强的一条是 [工资条逐月对账]：把用户 2026-09-13 手打的工资条当成固定夹具，
 * 用**出厂分段常量**（[PayRateSeed]）推应发与实发，必须复现条上的金额。
 *
 * 口径依据见 `verification/计薪规则v2-工资条口径.md`。
 */
class PayrollEngineTest {

    // ---------------------------------------------------------------- 单项公式

    @Test
    fun `加班工资是包干36小时乘法定基数`() {
        // 工资条 1–6 月基本工资 3000 → 931.03；7 月调到 3100 → 962.07
        assertEquals(93_103L, PayrollEngine.overtimePayCents(300_000L, 3_600L))
        assertEquals(96_207L, PayrollEngine.overtimePayCents(310_000L, 3_600L))
    }

    @Test
    fun `加班工资按包干小时数线性变化`() {
        // 36h → 931.03 ⇒ 18h 正好一半
        assertEquals(46_552L, PayrollEngine.overtimePayCents(300_000L, 1_800L))
        assertEquals(0L, PayrollEngine.overtimePayCents(300_000L, 0L))
    }

    @Test
    fun `夜班津贴等于单价乘夜班天数`() {
        assertEquals(49_500L, PayrollEngine.nightAllowanceCents(4_500L, 11))
        assertEquals(67_500L, PayrollEngine.nightAllowanceCents(4_500L, 15))
        assertEquals(0L, PayrollEngine.nightAllowanceCents(4_500L, 0))
        // 负天数不得变成负收入
        assertEquals(0L, PayrollEngine.nightAllowanceCents(4_500L, -3))
    }

    @Test
    fun `出勤折算系数是出勤天数除以法定月计薪天数`() {
        assertEquals(BigDecimal("1.195402"), PayrollEngine.attendanceFactor(26))
        // 没出勤就是 0（不能被 "max(...,1)" 抬成 1），负数同样不得抬高
        assertEquals(BigDecimal("0.000000"), PayrollEngine.attendanceFactor(0))
        assertEquals(BigDecimal("0.000000"), PayrollEngine.attendanceFactor(-5))
        assertEquals(BigDecimal("1.379310"), PayrollEngine.attendanceFactor(30))
    }

    @Test
    fun `绩效工资等于基数加月度增量乘系数乘出勤折算`() {
        // (600 + 0) × 1.0 × (26 / 21.75) = 717.24
        assertEquals(
            71_724L,
            PayrollEngine.performancePayCents(
                perfBaseCents = 60_000L,
                deltaCents = 0L,
                coefficient = BigDecimal.ONE,
                factor = PayrollEngine.attendanceFactor(26)
            )
        )
        // 系数 0.8 与 Δ月 100 元都要生效： (600+100) × 0.8 × 1.195402 = 669.43
        assertEquals(
            66_943L,
            PayrollEngine.performancePayCents(
                perfBaseCents = 60_000L,
                deltaCents = 10_000L,
                coefficient = BigDecimal("0.8"),
                factor = PayrollEngine.attendanceFactor(26)
            )
        )
    }

    @Test
    fun `个税按月口径且负数取零`() {
        // 2 月：应发 7587.03 − 783.30 − 358.00 − 5000 = 1445.73 → 3% = 43.37
        assertEquals(4_337L, PayrollEngine.incomeTaxCents(758_703L, 78_330L, 35_800L, 500_000L, 300L))
        // 3 月：4700.42 × 3% = 141.01（精确命中）
        assertEquals(14_101L, PayrollEngine.incomeTaxCents(1_084_172L, 78_330L, 35_800L, 500_000L, 300L))
        // 7 月：2312.72 × 3% = 69.38（精确命中）
        assertEquals(6_938L, PayrollEngine.incomeTaxCents(871_183L, 94_811L, 45_100L, 500_000L, 300L))
        // 达不到起征点 → 0，绝不能出负数
        assertEquals(0L, PayrollEngine.incomeTaxCents(500_000L, 0L, 0L, 500_000L, 300L))
        assertEquals(0L, PayrollEngine.incomeTaxCents(100_000L, 0L, 0L, 500_000L, 300L))
    }

    // ------------------------------------------------------- 分段常量解析

    @Test
    fun `分段常量按生效月取最近一段`() {
        val segments = mapOf(
            PayRateKey.BASIC_SALARY to listOf(
                PayRateResolver.Segment("2024-01", 300_000L),
                PayRateResolver.Segment("2026-07", 310_000L)
            )
        )
        assertEquals(300_000L, PayRateResolver.resolve("2024-06", segments).basicSalaryCents)
        assertEquals(300_000L, PayRateResolver.resolve("2026-01", segments).basicSalaryCents)
        assertEquals(300_000L, PayRateResolver.resolve("2026-06", segments).basicSalaryCents)
        assertEquals(310_000L, PayRateResolver.resolve("2026-07", segments).basicSalaryCents)
        assertEquals(310_000L, PayRateResolver.resolve("2026-12", segments).basicSalaryCents)
    }

    @Test
    fun `早于所有分段的月份退化到最早一段而不是默认值`() {
        val segments = mapOf(
            PayRateKey.POST_ALLOWANCE to listOf(PayRateResolver.Segment("2026-07", 190_000L))
        )
        // 2024 年的月份落在唯一一段之前 —— 必须给 1900 而不是枚举默认值
        assertEquals(190_000L, PayRateResolver.resolve("2024-03", segments).postAllowanceCents)
    }

    @Test
    fun `缺失的参数回退到枚举默认值`() {
        assertEquals(
            PayRateKey.NIGHT_ALLOWANCE_UNIT.defaultValue,
            PayRateResolver.resolve("2026-07", emptyMap()).nightAllowanceUnitCents
        )
    }

    @Test
    fun `出厂分段常量覆盖了工资条的全部调薪点`() {
        val segments = PayRateSeed.segments()
            .groupBy { PayRateKey.byStorageKey(it.paramKey) ?: return@groupBy null }
            .filterKeys { it != null }
            .mapNotNull { (k, v) -> k?.let { it to v.map { r -> PayRateResolver.Segment(r.effectiveFrom, r.value) } } }
            .toMap()

        val jan = PayRateResolver.resolve("2026-01", segments)
        assertEquals(300_000L, jan.basicSalaryCents)
        assertEquals(150_000L, jan.postAllowanceCents)
        assertEquals(78_330L, jan.socialInsuranceCents)
        assertEquals(35_800L, jan.housingFundCents)
        assertEquals(3_000L, jan.seniorAllowanceCents)

        val jul = PayRateResolver.resolve("2026-07", segments)
        assertEquals(310_000L, jul.basicSalaryCents)
        assertEquals(190_000L, jul.postAllowanceCents)
        assertEquals(94_811L, jul.socialInsuranceCents)
        assertEquals(45_100L, jul.housingFundCents)
        assertEquals(5_000L, jul.seniorAllowanceCents)

        // 7 个月恒定的那几项
        assertEquals(10_000L, jul.fullAttendanceCents)
        assertEquals(60_000L, jul.perfBaseCents)
        assertEquals(4_500L, jul.nightAllowanceUnitCents)
        assertEquals(3_600L, jul.otPackageHoursX100)
        assertEquals(500_000L, jul.taxThresholdCents)
        assertEquals(300L, jul.taxRateBp)
    }

    // ---------------------------------------------------------- 端到端对账

    /** 工资条一行：月度浮动项 + 条上金额（单位：分）。 */
    private data class PayslipRow(
        val month: String,
        val perfCents: Long,
        val nights: Int,
        val bonusCents: Long,
        val heatCents: Long,
        val backCents: Long,
        val sickCents: Long,
        val attendDays: Int,
        val grossCents: Long,
        val netCents: Long,
    )

    private val payslip = listOf(
        PayslipRow("2026-02", 74_600, 3, 112_500, 0, 0, 0, 16, 758_703, 640_235),
        PayslipRow("2026-03", 96_500, 15, 212_069, 0, 150_000, 0, 27, 1_084_172, 955_941),
        PayslipRow("2026-04", 91_500, 13, 232_759, 0, 0, 0, 28, 940_862, 816_930),
        PayslipRow("2026-05", 72_300, 12, 196_552, 0, 0, 0, 26, 880_955, 758_820),
        PayslipRow("2026-07", 56_700, 10, 128_276, 30_000, 0, 0, 26, 871_183, 724_334),
    )

    private fun seedSegments() = PayRateSeed.segments()
        .groupBy { PayRateKey.byStorageKey(it.paramKey) }
        .mapNotNull { (key, rows) ->
            key?.let { it to rows.map { row -> PayRateResolver.Segment(row.effectiveFrom, row.value) } }
        }
        .toMap()

    private fun estimateFor(row: PayslipRow) = PayrollEngine.estimate(
        PayRateResolver.resolve(row.month, seedSegments()),
        PayrollInputs(
            attendDays = row.attendDays,
            nightShiftDays = row.nights,
            // 绩效工资条印的「基数 600 × 系数」对不上条上金额（口径文档 §1.3），
            // 所以这里用「直接填金额」这条路复现，与 App 的默认交互一致。
            perfAmountCents = row.perfCents,
            benefitBonusCents = row.bonusCents,
            heatAllowanceCents = row.heatCents,
            backPayCents = row.backCents,
            sickPayCents = row.sickCents
        )
    )

    @Test
    fun `工资条逐月对账_应发必须精确复现`() {
        payslip.forEach { row ->
            val result = estimateFor(row)
            assertEquals("${row.month} 应发", row.grossCents, result.grossCents)
        }
    }

    @Test
    fun `工资条逐月对账_实发允许一分钱进位差`() {
        payslip.forEach { row ->
            val diff = estimateFor(row).netCents - row.netCents
            assertTrue("${row.month} 实发差 $diff 分（应 ≤1）", abs(diff) <= 1L)
        }
    }

    @Test
    fun `工资条逐月对账_加班工资与夜班津贴由公式算出而非硬编码`() {
        payslip.forEach { row ->
            val result = estimateFor(row)
            // 1–6 月基本工资 3000 → 1.5×36×3000/21.75/8 = 931.03
            // 7 月起调到 3100 → 962.07（分段常量生效，加班工资必须跟着变）
            val expectedOt = if (row.month >= "2026-07") 96_207L else 93_103L
            assertEquals("${row.month} 加班工资", expectedOt, result.overtimePayCents)
            assertEquals("${row.month} 夜班津贴", 4_500L * row.nights, result.nightAllowanceCents)
            assertEquals("${row.month} 夜班天数", row.nights, result.nightShiftDays)
        }
        // 最后一个对账月就是 7 月，确认调档确实被区分对待
        assertEquals(96_207L, estimateFor(payslip.last()).overtimePayCents)
    }

    @Test
    fun `已知工资条笔误不影响公式_06月少打360元`() {
        val june = PayslipRow("2026-06", 94_100, 3, 209_483, 30_000, 0, 0, 25, 941_186, 817_245)
        val result = estimateFor(june)
        // 条上应发 9411.86，各部件加总只有 9051.86 —— 差 360，是工资条漏打一项
        assertEquals(905_186L, result.grossCents)
        assertEquals(36_000L, june.grossCents - result.grossCents)
    }

    @Test
    fun `已知工资条笔误不影响公式_01月病假是工资项`() {
        // 01 月只有把「病假 331.03」当**收入项**加进去，加总才等于条上应发 8400.51
        val jan = PayslipRow("2026-01", 78_500, 11, 122_845, 0, 0, 33_103, 22, 840_051, 723_644)
        assertEquals(840_051L, estimateFor(jan).grossCents)
        // 01 月的个税是唯一真异常（条上 22.77，公式 67.78，差额恰为 1500 × 3% 的专项附加扣除）
        assertEquals(6_778L, estimateFor(jan).incomeTaxCents)
    }

    // ------------------------------------------------------------- 日工资

    @Test
    fun `日工资按基准月到手单价折算`() {
        // 基准月 2026-07：实发 7243.34 / 15600 分钟（260h）
        assertEquals(30_645L, PayrollEngine.dailyEstimateCents(660, 724_334L, 15_600))
        // 没上班就是 0，而不是 null
        assertEquals(0L, PayrollEngine.dailyEstimateCents(0, 724_334L, 15_600))
    }

    @Test
    fun `没有基准月时不给日工资`() {
        assertNull(PayrollEngine.dailyEstimateCents(660, 724_334L, 0))
        assertNull(PayrollEngine.dailyEstimateCents(660, 0L, 15_600))
    }

    @Test
    fun `基准到手单价按小时换算`() {
        // 7243.34 / 260h = 27.86 元/h
        assertEquals(2_786L, PayrollEngine.baselineHourlyCents(724_334L, 15_600))
        assertNull(PayrollEngine.baselineHourlyCents(0L, 15_600))
    }

    // --------------------------------------------------- Presenter 单位换算

    @Test
    fun `三种单位都能正确解析回分值`() {
        assertEquals(310_000L, PayrollPresenter.parseValue(PayRateKey.BASIC_SALARY, "3100"))
        assertEquals(310_050L, PayrollPresenter.parseValue(PayRateKey.BASIC_SALARY, "3100.5"))
        assertEquals(310_000L, PayrollPresenter.parseValue(PayRateKey.BASIC_SALARY, "3,100"))
        assertEquals(3_600L, PayrollPresenter.parseValue(PayRateKey.OT_PACKAGE_HOURS, "36"))
        assertEquals(3_650L, PayrollPresenter.parseValue(PayRateKey.OT_PACKAGE_HOURS, "36.5"))
        assertEquals(300L, PayrollPresenter.parseValue(PayRateKey.TAX_RATE_BP, "3"))
        assertEquals(300L, PayrollPresenter.parseValue(PayRateKey.TAX_RATE_BP, "3%"))
    }

    @Test
    fun `非法输入一律拒绝`() {
        listOf("", "  ", "abc", "-1", "1.2.3", "三千").forEach { bad ->
            assertNull("『$bad』不该被接受", PayrollPresenter.parseValue(PayRateKey.BASIC_SALARY, bad))
        }
        assertNull(PayrollPresenter.parseCoefficient("-1"))
        assertNull(PayrollPresenter.parseCoefficient("abc"))
        assertNull(PayrollPresenter.parseMoneyOrNull("abc"))
    }

    @Test
    fun `金额空串表示不覆盖`() {
        assertNull(PayrollPresenter.parseMoneyOrNull(""))
        assertNull(PayrollPresenter.parseMoneyOrNull("   "))
        assertEquals(94_811L, PayrollPresenter.parseMoneyOrNull("948.11"))
    }

    @Test
    fun `参数值展示串带单位且能往返`() {
        assertEquals("¥3,100.00", PayrollPresenter.displayValue(PayRateKey.BASIC_SALARY, 310_000L))
        assertEquals("36.00 小时", PayrollPresenter.displayValue(PayRateKey.OT_PACKAGE_HOURS, 3_600L))
        assertEquals("3.00%", PayrollPresenter.displayValue(PayRateKey.TAX_RATE_BP, 300L))

        PayRateKey.displayOrder.forEach { key ->
            val value = key.defaultValue
            val text = PayrollPresenter.valueText(key, value)
            assertEquals("$key 往返失败（文本=$text）", value, PayrollPresenter.parseValue(key, text))
        }
    }

    @Test
    fun `出勤统计按记录算天与夜班`() {
        val records = listOf(
            UiDayRecord(LocalDate.of(2026, 7, 1), "白班", shift = "白班", finalMinutes = 660),
            UiDayRecord(LocalDate.of(2026, 7, 2), "夜班", shift = "夜班", finalMinutes = 660),
            UiDayRecord(LocalDate.of(2026, 7, 3), "夜班", shift = "夜班", finalMinutes = 600),
            // 没出工的不算出勤，也不算夜班
            UiDayRecord(LocalDate.of(2026, 7, 4), "休息", shift = "夜班", finalMinutes = 0),
            // 请假不计工时
            UiDayRecord(LocalDate.of(2026, 7, 5), "请假", shift = null, finalMinutes = 0)
        )
        val stats = PayrollPresenter.attendanceStats(records)
        assertEquals(3, stats.attendDays)
        assertEquals(2, stats.nightShiftDays)
        assertEquals(1_920, stats.totalMinutes)
    }

    @Test
    fun `没有基准月时日工资标签为空`() {
        assertNull(PayrollPresenter.dailyPayLabel(9, 14, null))
        assertEquals("9/14 工资 ≈ ¥306.45", PayrollPresenter.dailyPayLabel(9, 14, 30_645L))
    }
}
