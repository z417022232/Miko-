package com.example.worktimetracker

import com.example.worktimetracker.domain.payroll.PositionedLine
import com.example.worktimetracker.domain.payroll.SlipItemKey
import com.example.worktimetracker.domain.payroll.SlipItemStage
import com.example.worktimetracker.domain.payroll.SlipOcrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * **真机原始输出回归** —— 夹具是 vivo V2425A 上 ML Kit 对用户「8 月工资条」
 * 两张截图（10:10:55 上半 / 10:11:02 下半）的**逐字输出**，含真实 `top` 坐标。
 *
 * 与 [SlipOcrRealSlipTest] 的区别：那边是我按版面**手写**的理想行，
 * 这边是**设备吐出来的原文**——包括它认错的字（`岗位律贴`/`全勒`/`事價`/`绩效扣歉`）、
 * 简繁混排（`话費补贴`/`高溫补贴`/`所得稅`）、以及表格边框被读进去的竖线。
 *
 * 这份数据是"改解析器之前必须先跑它"的那一类护栏：
 * 真实失败不是理想数据能模拟的（当初就是被手写夹具骗过、真机一条都识别不出来）。
 *
 * 行高统一取 45（真机表格行高量级）；`left` 取 100（左列标签）/ 900（右列金额），
 * 与截图版面一致 —— 关键是**同一个 y 的标签与金额必须配成一行**。
 */
class SlipOcrDeviceRealTest {

    /** 上半张截图（状态栏 10:10）。 */
    private val topLabels = listOf(
        197 to "くX", 77 to "10:10", 512 to "感谢您的努カ工作,辛苦啦!", 740 to "实发工资",
        1025 to "工资明细", 1144 to "|部门", 884 to "工资条属于敏感信息,靖注意保密",
        1268 to "员工", 1446 to "入职日期", 1566 to "基本工资", 1677 to "绩效工资基数",
        1801 to "绩效系数", 1923 to "绩效工资", 2046 to "工龄工资", 2161 to "独生费",
        210 to "工资条详细", 2280 to "|岗位律贴", 387 to "8月工资条", 2406 to "效益奖金",
        2516 to "|加班工资", 76 to "ll 5G 75", 1144 to "金属车间", 1265 to "刘亚东",
        1329 to "(3702@weyer.com.cn)", 885 to "X", 206 to "くX",
    )
    private val topValues = listOf(
        631 to "9,364.52", 1446 to "2024/02/26", 1565 to "3,100.00", 1691 to "600",
        1811 to "0.8", 1931 to "810.00", 2047 to "50.00", 2171 to "0.00",
        2291 to "1,900.00", 2411 to "3046.55", 2529 to "962.07",
    )

    /** 下半张截图（状态栏 10:11）。 */
    private val bottomLabels = listOf(
        463 to "全勒", 343 to "假期加班工资", 77 to "10:11", 575 to "夜班补贴", 210 to "X",
        701 to "话費补贴", 943 to "迟到", 816 to "高溫补贴", 1063 to "事價", 1183 to "病假",
        1300 to "绩效扣歉", 1423 to "补发/一次性补发", 1533 to "|应发工资",
        1661 to "|社保个人合计", 1781 to "住房公积金_个人", 1901 to "所得稅", 2018 to "实发工资",
        2140 to "宿舍代扣", 2263 to "工会费代扣", 2379 to "其他应扣款", 2492 to "|银行实发",
        207 to "工资条详细", 76 to "ul 5G 75",
    )
    private val bottomValues = listOf(
        468 to "100.00", 588 to "630", 705 to "0.00", 828 to "300.00", 1068 to "0.00",
        1185 to "0.00", 1544 to "10898.62", 1668 to "948.11", 1788 to "451.00",
        1907 to "134.99", 2028 to "9,364.52", 2385 to "0.00", 2508 to "9364.52",
    )

    private fun image(
        labels: List<Pair<Int, String>>,
        values: List<Pair<Int, String>>,
    ): List<PositionedLine> =
        labels.map { (y, t) -> PositionedLine(t, y, y + 45, 100) } +
            values.map { (y, t) -> PositionedLine(t, y, y + 45, 900) }

    private val parsed = SlipOcrParser.parseRecognized(
        listOf(image(topLabels, topValues), image(bottomLabels, bottomValues))
    )

    private fun cents(text: String?): BigDecimal =
        text?.let { BigDecimal(it).setScale(2) } ?: BigDecimal.ZERO

    private fun SlipOcrParser.Parsed.sumOf(stage: SlipItemStage): BigDecimal =
        items.entries
            .filter { it.key.stage == stage }
            .fold(BigDecimal.ZERO) { acc, e -> acc + cents(e.value) }

    @Test
    fun headlineAmountsAreReadFromTheRealScreenshot() {
        assertEquals("10898.62", parsed.grossText)
        assertEquals("9364.52", parsed.netText)
    }

    @Test
    fun hireDateIsStillNotPaymentDate() {
        // 上半张只有「入职日期 2024/02/26」，绝不能变成发薪日期
        assertNull(parsed.paymentDate)
    }

    @Test
    fun misreadLabelsAreRecoveredThroughConfusableVariants() {
        // 这四条是设备真实认错出来的字，靠近形字变体才救回来
        assertEquals("1900.00", parsed.items[SlipItemKey.POST_ALLOWANCE])   // 岗位律贴
        assertEquals("100.00", parsed.items[SlipItemKey.FULL_ATTENDANCE])   // 全勒
        assertEquals("0.00", parsed.items[SlipItemKey.PERSONAL_LEAVE_DEDUCT]) // 事價
    }

    @Test
    fun traditionalVariantsAreRecovered() {
        assertEquals("300.00", parsed.items[SlipItemKey.HEAT_ALLOWANCE])   // 高溫补贴
        assertEquals("0.00", parsed.items[SlipItemKey.OTHER_ADD])          // 话費补贴 / 独生费
        assertEquals("134.99", parsed.items[SlipItemKey.INCOME_TAX])       // 所得稅
    }

    @Test
    fun separatedColumnsArePairBynBoundingBoxNotByOrder() {
        val expect = mapOf(
            SlipItemKey.BASIC_SALARY to "3100.00",
            SlipItemKey.POST_ALLOWANCE to "1900.00",
            SlipItemKey.PERFORMANCE_PAY to "810.00",   // 不许被「绩效工资基数 600」抢走
            SlipItemKey.SENIOR_ALLOWANCE to "50.00",
            SlipItemKey.FULL_ATTENDANCE to "100.00",
            SlipItemKey.OVERTIME_PAY to "962.07",      // 不许被「假期加班工资」抢走
            SlipItemKey.NIGHT_ALLOWANCE to "630",
            SlipItemKey.BENEFIT_BONUS to "3046.55",
            SlipItemKey.HEAT_ALLOWANCE to "300.00",
            SlipItemKey.SICK_PAY to "0.00",
            SlipItemKey.PERSONAL_LEAVE_DEDUCT to "0.00",
            SlipItemKey.SOCIAL_INSURANCE to "948.11",
            SlipItemKey.HOUSING_FUND to "451.00",
            SlipItemKey.INCOME_TAX to "134.99",
            SlipItemKey.OTHER_DEDUCT to "0.00",
        )
        expect.forEach { (k, v) -> assertEquals("分项 ${k.label}", v, parsed.items[k]) }
    }

    /**
     * 最关键的一条：**两条核对式都必须平**。
     * 设备把 6 个值为 `0` 的项整行丢了（单字 "0" 识别不出），但它们都是 0，不影响合计 ——
     * 这条测试同时把"丢了哪些"钉住：丢了也只能丢 0 值项。
     */
    @Test
    fun bothReconciliationIdentitiesBalanceOnRealDeviceOutput() {
        val gross = BigDecimal("10898.62")
        val net = BigDecimal("9364.52")

        val income = parsed.sumOf(SlipItemStage.INCOME)
        val preGross = parsed.sumOf(SlipItemStage.DEDUCT_PRE_GROSS)
        assertEquals(
            "校验①不平：收入 $income − 收入侧扣减 $preGross",
            0,
            (income - preGross - gross).compareTo(BigDecimal.ZERO)
        )

        val postGross = parsed.sumOf(SlipItemStage.DEDUCT_POST_GROSS)
        assertEquals(
            "校验②不平：应发 $gross − 应发后扣减 $postGross",
            0,
            (gross - postGross - net).compareTo(BigDecimal.ZERO)
        )
    }

    /** 值为 0 的项被 OCR 丢掉是已知且可接受的：绝不能因此把它的金额编出来。 */
    @Test
    fun zeroValuedItemsThatTheDeviceDroppedStayUnfilledRatherThanGuessed() {
        assertNull(parsed.items[SlipItemKey.HOLIDAY_OVERTIME_PAY])
        assertNull(parsed.items[SlipItemKey.LATE_DEDUCT])
        assertNull(parsed.items[SlipItemKey.PERFORMANCE_DEDUCT])
        assertNull(parsed.items[SlipItemKey.BACK_PAY])
        assertNull(parsed.items[SlipItemKey.DORM_DEDUCT])
        assertNull(parsed.items[SlipItemKey.UNION_FEE])
    }
}
