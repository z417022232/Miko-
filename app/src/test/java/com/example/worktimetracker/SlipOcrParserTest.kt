package com.example.worktimetracker

import com.example.worktimetracker.domain.payroll.SlipItemKey
import com.example.worktimetracker.domain.payroll.SlipOcrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 夹具取自用户 2026-09-13 上传的真实工资条口径
 * （`verification/计薪规则v2-工资条口径.md`，2026-07 调薪后的数值）。
 */
class SlipOcrParserTest {

    /** 标准单列版面：一行一个键值对。 */
    private val fullSlip = listOf(
        "计薪月 2026-07",
        "基本工资 3100.00",
        "岗位津贴 1900.00",
        "工龄工资 50.00",
        "全勤奖 100.00",
        "加班工资 962.07",
        "绩效工资 785.00",
        "夜班津贴 225.00",
        "高温补贴 200.00",
        "应发工资 7322.07",
        "社保个人 948.11",
        "住房公积金个人 451.00",
        "个人所得税 3.00",
        "实发工资 5919.96",
        "计薪出勤天数 25",
        "计薪夜班数 5",
        "发薪日期 2026-08-15",
    )

    @Test fun parsesStandardSingleColumnSlip() {
        val p = SlipOcrParser.parse(fullSlip)
        assertEquals("2026-08-15", p.paymentDate)
        assertEquals("7322.07", p.grossText)
        assertEquals("5919.96", p.netText)
        assertEquals("25", p.attendDays)
        assertEquals("5", p.nightShifts)
        assertEquals("3100.00", p.items[SlipItemKey.BASIC_SALARY])
        assertEquals("1900.00", p.items[SlipItemKey.POST_ALLOWANCE])
        assertEquals("962.07", p.items[SlipItemKey.OVERTIME_PAY])
        assertEquals("225.00", p.items[SlipItemKey.NIGHT_ALLOWANCE])
        assertEquals("948.11", p.items[SlipItemKey.SOCIAL_INSURANCE])
        assertEquals("451.00", p.items[SlipItemKey.HOUSING_FUND])
        assertEquals("3.00", p.items[SlipItemKey.INCOME_TAX])
        assertFalse(p.isEmpty)
    }

    @Test fun parsesTwoKeyValuePairsOnOneLine() {
        val p = SlipOcrParser.parse(listOf("基本工资 3100.00  岗位津贴 1900.00"))
        assertEquals("3100.00", p.items[SlipItemKey.BASIC_SALARY])
        assertEquals("1900.00", p.items[SlipItemKey.POST_ALLOWANCE])
    }

    @Test fun stripsThousandsSeparator() {
        val p = SlipOcrParser.parse(listOf("基本工资 3,100.00", "岗位津贴 1,900.00"))
        assertEquals("3100.00", p.items[SlipItemKey.BASIC_SALARY])
        assertEquals("1900.00", p.items[SlipItemKey.POST_ALLOWANCE])
    }

    @Test fun performancePayAndDeductionDoNotCrossContaminate() {
        val p = SlipOcrParser.parse(listOf("绩效工资 785.00  绩效扣款 0.00"))
        assertEquals("785.00", p.items[SlipItemKey.PERFORMANCE_PAY])
        assertEquals("0.00", p.items[SlipItemKey.PERFORMANCE_DEDUCT])
    }

    @Test fun longestAliasWinsForNestedNames() {
        // 「住房公积金个人」里嵌着「住房公积金」「公积金个人」「公积金」三个短别名，
        // 必须只认最长那个，且不能被重复记三次
        val p = SlipOcrParser.parse(listOf("住房公积金个人 451.00"))
        assertEquals("451.00", p.items[SlipItemKey.HOUSING_FUND])
        assertEquals(1, p.items.size)
    }

    @Test fun normalizesFullWidthDigitsAndPunctuation() {
        val p = SlipOcrParser.parse(listOf("基本工资 ３１００．００", "计薪出勤天数：２５"))
        assertEquals("3100.00", p.items[SlipItemKey.BASIC_SALARY])
        assertEquals("25", p.attendDays)
    }

    @Test fun sickPayIsRecognisedAsIncomeItem() {
        val p = SlipOcrParser.parse(listOf("病假工资 331.03"))
        assertEquals("331.03", p.items[SlipItemKey.SICK_PAY])
    }

    @Test fun grossAndNetAreNotSwallowedByItemAliases() {
        // 「应发工资」「实发工资」不是分项，不能被当成 items 里的某一项
        val p = SlipOcrParser.parse(listOf("应发工资 7322.07"))
        assertEquals("7322.07", p.grossText)
        assertTrue(p.items.isEmpty())
        assertNull(p.netText)
        assertFalse(p.isEmpty)
    }

    @Test fun blownOutPhotoYieldsNothingInsteadOfGuessing() {
        val p = SlipOcrParser.parse(listOf("工资条", "制表人：张三", "2026 年 7 月"))
        assertTrue(p.isEmpty)
        assertEquals("没读到可用字段", p.summary)
    }

    @Test fun trailingUnitCharacterDoesNotBreakAmount() {
        val p = SlipOcrParser.parse(listOf("基本工资 3100.00元", "夜班津贴 225元"))
        assertEquals("3100.00", p.items[SlipItemKey.BASIC_SALARY])
        assertEquals("225", p.items[SlipItemKey.NIGHT_ALLOWANCE])
    }

    @Test fun nightAllowanceDoesNotFeedNightShiftCount() {
        // 「夜班津贴 225.00」绝不能被当成「计薪夜班数」
        val p = SlipOcrParser.parse(listOf("夜班津贴 225.00"))
        assertNull(p.nightShifts)
        assertEquals("225.00", p.items[SlipItemKey.NIGHT_ALLOWANCE])
    }

    @Test fun summaryListsWhatWasFound() {
        val p = SlipOcrParser.parse(listOf("应发工资 7322.07", "实发工资 5919.96", "基本工资 3100.00"))
        assertEquals("识别到 应发、实发、1 个分项", p.summary)
    }

    // ------------------------------------------ 2026-09-16 复查 P2：三项校验加固

    @Test fun impossibleCalendarDateIsRejected() {
        // OCR 把 2/30 认成 2/31 很常见；发薪日期是唯一会直接落库的字段 → 宁缺勿错
        assertNull(SlipOcrParser.parse(listOf("发薪日期 2026-02-31")).paymentDate)
        assertNull(SlipOcrParser.parse(listOf("发薪日期 2026/04/31")).paymentDate)
        assertNull(SlipOcrParser.parse(listOf("发薪日期 2026-13-01")).paymentDate)
        assertNull(SlipOcrParser.parse(listOf("发薪日期 2026-00-10")).paymentDate)
        // 非闰年的 2/29 同样不存在
        assertNull(SlipOcrParser.parse(listOf("发薪日期 2026-02-29")).paymentDate)
        // 合法日期照常识别，闰年 2/29 要放行
        assertEquals("2026-08-15", SlipOcrParser.parse(listOf("发薪日期 2026-08-15")).paymentDate)
        assertEquals("2024-02-29", SlipOcrParser.parse(listOf("发薪日期 2024-02-29")).paymentDate)
    }

    @Test fun negativeAmountIsNotRecordedAsPositive() {
        // 负号代表冲减，分项一律记正数 → 碰到负号放弃这一处，绝不把 -150 记成 150
        val p = SlipOcrParser.parse(listOf("其他扣款 -150.00", "补发工资 -300.00"))
        assertNull(p.items[SlipItemKey.OTHER_DEDUCT])
        assertNull(p.items[SlipItemKey.BACK_PAY])
        // 正数照常识别（不能因为加了负号判断就把正数也挡掉）
        val ok = SlipOcrParser.parse(listOf("其他扣款 150.00"))
        assertEquals("150.00", ok.items[SlipItemKey.OTHER_DEDUCT])
    }

    @Test fun parameterValueIsNotTakenAsGrossWhenAliasIsOnlyAPrefix() {
        // 「应发工资基数 8000」是计薪参数：既不能被「应发工资」取数，
        // 也不能被它的短前缀「应发」把 8000 吃成应发工资（参数被当成分项记账）
        assertNull(SlipOcrParser.parse(listOf("应发工资基数 8000.00")).grossText)

        // 参数与真值同屏时，必须取到真值
        val mixed = SlipOcrParser.parse(listOf("应发工资基数 8000.00", "应发工资 7322.07"))
        assertEquals("7322.07", mixed.grossText)
    }

    @Test fun parameterValueIsNotTakenAsNetWhenAliasIsOnlyAPrefix() {
        assertNull(SlipOcrParser.parse(listOf("实发工资基数 5000.00")).netText)
        val mixed = SlipOcrParser.parse(listOf("实发工资基数 5000.00", "实发工资 5919.96"))
        assertEquals("5919.96", mixed.netText)
    }
}
