package com.example.worktimetracker

import com.example.worktimetracker.domain.payroll.SlipItemKey
import com.example.worktimetracker.domain.payroll.SlipItemStage
import com.example.worktimetracker.domain.payroll.SlipOcrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * **真实工资条回归** —— 夹具逐行取自用户 2026-09-16 上传的两张「8 月工资条」截图
 * （钉钉工资条详情页，上下两半）。两半拼起来才是完整条子。
 *
 * 与 [SlipOcrParserTest] 的区别：那边是"理想版面"，这边是**真机真图**，
 * 专门盯住真实版面里会骗过解析器的三处陷阱：
 *
 * 1. `入职日期 2024/02/26` —— 条上第一个日期**不是发薪日期**，绝不能被填进 `paymentDate`；
 * 2. `绩效工资基数 600` —— 它是**计薪参数**（`PayRateKey.PERF_BASE`），
 *    不是工资条分项；绝不能让它的 600 抢走真正的 `绩效工资 810.00`；
 * 3. `假期加班工资 0` —— 它与 `加班工资 962.07` 是**两栏**，不能互相冒充。
 *    用户只截了下半张时，把 `加班工资` 记成 0 就是错账。
 *
 * 另外这两张图还压出一个**漏识别**：`全勤 100.00`（条上没写"全勤奖"三个字），
 * 漏掉它会让校验①差 100 元不平 —— 工资条明明在手上却对不上账。
 */
class SlipOcrRealSlipTest {

    /** 截图 1（上半）：抬头 + 部门/员工 + 收入项前半。 */
    private val shot1 = listOf(
        "工资条详细",
        "8月 工资条",
        "感谢您的努力工作，辛苦啦！",
        "9,364.52",
        "实发工资",
        "工资条属于敏感信息，请注意保密",
        "工资明细",
        "部门 金属车间",
        "员工 刘亚东",
        "(3702@weyer.com.cn)",
        "入职日期 2024/02/26",
        "基本工资 3,100.00",
        "绩效工资基数 600",
        "绩效系数 0.8",
        "绩效工资 810.00",
        "工龄工资 50.00",
        "独生费 0.00",
        "岗位津贴 1,900.00",
        "效益奖金 3046.55",
        "加班工资 962.07",
    )

    /** 截图 2（下半）：收入项后半 + 应发/扣款/实发。 */
    private val shot2 = listOf(
        "工资条详细",
        "假期加班工资 0",
        "全勤 100.00",
        "夜班补贴 630",
        "话费补贴 0.00",
        "高温补贴 300.00",
        "迟到 0",
        "事假 0.00",
        "病假 0.00",
        "绩效扣款 0",
        "补发/一次性补发 0",
        "应发工资 10898.62",
        "社保个人合计 948.11",
        "住房公积金_个人 451.00",
        "所得税 134.99",
        "实发工资 9,364.52",
        "宿舍代扣 0",
        "工会费代扣 0",
        "其他应扣款 0.00",
        "银行实发 9364.52",
    )

    /** 条上印的权威值（两张截图）。 */
    private val grossOnSlip = BigDecimal("10898.62")
    private val netOnSlip = BigDecimal("9364.52")

    private fun cents(text: String?): BigDecimal =
        text?.let { BigDecimal(it).setScale(2) } ?: BigDecimal.ZERO

    private fun SlipOcrParser.Parsed.sumOf(stage: SlipItemStage): BigDecimal =
        items.entries
            .filter { it.key.stage == stage }
            .fold(BigDecimal.ZERO) { acc, e -> acc + cents(e.value) }

    // ------------------------------------------------------------------ 三条陷阱

    @Test
    fun hireDateIsNotPaymentDate() {
        val p = SlipOcrParser.parse(shot1 + shot2)
        // 条上根本没有发薪日期 -> 必须留空，绝不能拿入职日期顶上
        assertNull(p.paymentDate)
    }

    @Test
    fun aDateWithoutPayrollWordingIsNotGuessed() {
        // 真机踩坑复现：ML Kit 的行序让「入职」没落进上下文，
        // 2024-02-26 就被当成发薪日期写进了草稿 —— 日期字段宁缺勿错。
        assertNull(SlipOcrParser.parse(listOf("2024/02/26")).paymentDate)
        assertNull(SlipOcrParser.parse(listOf("入职日期", "2024/02/26")).paymentDate)
        assertNull(SlipOcrParser.parse(listOf("员工 刘亚东", "2024/02/26")).paymentDate)
        // 但带正词的必须认出来（含被拆成两行的情况）
        assertEquals("2026-08-15", SlipOcrParser.parse(listOf("发薪日期 2026-08-15")).paymentDate)
        assertEquals("2026-08-15", SlipOcrParser.parse(listOf("发薪日期", "2026-08-15")).paymentDate)
    }

    @Test
    fun performanceBaseDoesNotStealPerformancePay() {
        val p = SlipOcrParser.parse(shot1 + shot2)
        // 真值 810.00；「绩效工资基数 600」是参数，不许抢
        assertEquals("810.00", p.items[SlipItemKey.PERFORMANCE_PAY])
    }

    @Test
    fun holidayOvertimeIsNotPlainOvertime() {
        // 只截了下半张时，加班工资（真值 962.07）绝不能被「假期加班工资 0」冒充
        val lower = SlipOcrParser.parse(shot2)
        assertNull(lower.items[SlipItemKey.OVERTIME_PAY])
        assertEquals("0", lower.items[SlipItemKey.HOLIDAY_OVERTIME_PAY])

        // 两张都给时，各归各位
        val both = SlipOcrParser.parse(shot1 + shot2)
        assertEquals("962.07", both.items[SlipItemKey.OVERTIME_PAY])
        assertEquals("0", both.items[SlipItemKey.HOLIDAY_OVERTIME_PAY])
    }

    @Test
    fun fullAttendanceIsRecognisedWithoutTheAwardSuffix() {
        // 条上印的是「全勤」两个字，不是「全勤奖」
        val p = SlipOcrParser.parse(shot2)
        assertEquals("100.00", p.items[SlipItemKey.FULL_ATTENDANCE])
    }

    // ------------------------------------------------------------------ 整体口径

    @Test
    fun headlineAmountsMatchTheSlip() {
        val p = SlipOcrParser.parse(shot1 + shot2)
        assertEquals("10898.62", p.grossText)
        assertEquals("9364.52", p.netText)
    }

    @Test
    fun bothReconciliationIdentitiesHoldForTheRealNumbers() {
        val p = SlipOcrParser.parse(shot1 + shot2)

        // 校验① Σ(收入) − Σ(收入侧扣减) == 条上应发
        val income = p.sumOf(SlipItemStage.INCOME)
        val preGross = p.sumOf(SlipItemStage.DEDUCT_PRE_GROSS)
        assertEquals(
            "校验①不平：收入 $income − 收入侧扣减 $preGross",
            0,
            (income - preGross - grossOnSlip).compareTo(BigDecimal.ZERO)
        )

        // 校验② 条上应发 − Σ(应发后扣减) == 条上实发
        val postGross = p.sumOf(SlipItemStage.DEDUCT_POST_GROSS)
        assertEquals(
            "校验②不平：应发 $grossOnSlip − 应发后扣减 $postGross",
            0,
            (grossOnSlip - postGross - netOnSlip).compareTo(BigDecimal.ZERO)
        )
    }

    @Test
    fun parsesEveryLineItemPrintedOnTheSlip() {
        val p = SlipOcrParser.parse(shot1 + shot2)
        val expect = mapOf(
            SlipItemKey.BASIC_SALARY to "3100.00",
            SlipItemKey.POST_ALLOWANCE to "1900.00",
            SlipItemKey.PERFORMANCE_PAY to "810.00",
            SlipItemKey.SENIOR_ALLOWANCE to "50.00",
            SlipItemKey.FULL_ATTENDANCE to "100.00",
            SlipItemKey.OVERTIME_PAY to "962.07",
            SlipItemKey.HOLIDAY_OVERTIME_PAY to "0",
            SlipItemKey.NIGHT_ALLOWANCE to "630",
            SlipItemKey.BENEFIT_BONUS to "3046.55",
            SlipItemKey.HEAT_ALLOWANCE to "300.00",
            SlipItemKey.SICK_PAY to "0.00",
            SlipItemKey.BACK_PAY to "0",
            SlipItemKey.PERFORMANCE_DEDUCT to "0",
            SlipItemKey.PERSONAL_LEAVE_DEDUCT to "0.00",
            SlipItemKey.LATE_DEDUCT to "0",
            SlipItemKey.SOCIAL_INSURANCE to "948.11",
            SlipItemKey.HOUSING_FUND to "451.00",
            SlipItemKey.INCOME_TAX to "134.99",
            SlipItemKey.DORM_DEDUCT to "0",
            SlipItemKey.UNION_FEE to "0",
            SlipItemKey.OTHER_DEDUCT to "0.00",
        )
        expect.forEach { (k, v) -> assertEquals("分项 ${k.label}", v, p.items[k]) }
    }

    @Test
    fun underlinedHousingFundStillMatches() {
        // 条上印的是「住房公积金_个人」（带下划线）
        val p = SlipOcrParser.parse(listOf("住房公积金_个人 451.00"))
        assertEquals("451.00", p.items[SlipItemKey.HOUSING_FUND])
    }
}
