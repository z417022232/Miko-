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
}
