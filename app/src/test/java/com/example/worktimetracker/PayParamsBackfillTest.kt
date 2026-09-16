package com.example.worktimetracker

import com.example.worktimetracker.data.entity.MonthlyPayParamsEntity
import com.example.worktimetracker.domain.payroll.PayParamsBackfill
import com.example.worktimetracker.domain.payroll.SlipItemEntry
import com.example.worktimetracker.domain.payroll.SlipItemKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工资条 → 月度计薪参数（`monthly_pay_params`）回填的语义护栏。
 *
 * 背景：设置页把 10 项浮动参数从 UI 移除（只留绩效系数），
 * 于是**工资条导入成了这些引擎入参的唯一来源** —— 这条回填一旦写歪，
 * 预估会静默失真，界面上又没有任何输入框能暴露它。
 *
 * 守三件事：
 *  1. 工资条**没给的项**绝不抹掉库里原值（空白 = 未填写）；
 *  2. 工资条**印出来的 0** 是「明确为零」，要如实覆盖；
 *  3. `perfCoefficient` 等不由工资条派生的字段一律原样保留。
 */
class PayParamsBackfillTest {

    private val month = "2026-07"

    /** 工资条上「什么都没印」：全部分项未填写。 */
    private fun blankItems(): List<SlipItemEntry> =
        SlipItemKey.displayOrder.map { SlipItemEntry(it, null) }

    /** 在「全未填写」的基础上，只点亮关心的几项（`null` 值 = 未填写）。 */
    private fun items(vararg pairs: Pair<SlipItemKey, Long?>): List<SlipItemEntry> =
        blankItems().map { entry ->
            val hit = pairs.firstOrNull { it.first == entry.key }
            if (hit == null) entry else entry.copy(amountCents = hit.second)
        }

    private fun merge(
        items: List<SlipItemEntry>,
        current: MonthlyPayParamsEntity? = null,
        nights: Int? = null,
    ): MonthlyPayParamsEntity? =
        PayParamsBackfill.mergeOrNull(
            payrollMonth = month,
            current = current,
            items = items,
            nightShifts = nights,
            updatedAt = 1_700_000_000_000L,
        )

    @Test
    fun `工资条上什么都没有时返回 null 表示不要碰数据库`() {
        assertNull(merge(items = blankItems()))
    }

    @Test
    fun `工资条金额逐项回填到引擎入参`() {
        val merged = merge(
            items = items(
                SlipItemKey.PERFORMANCE_PAY to 220_000L,
                SlipItemKey.BENEFIT_BONUS to 150_000L,
                SlipItemKey.HEAT_ALLOWANCE to 30_000L,
                SlipItemKey.SICK_PAY to 33_103L,
                SlipItemKey.BACK_PAY to 12_345L,
                SlipItemKey.OTHER_ADD to 6_789L,
                SlipItemKey.SOCIAL_INSURANCE to 94_811L,
                SlipItemKey.HOUSING_FUND to 21_600L,
            ),
            nights = 10,
        )!!

        assertEquals(month, merged.payrollMonth)
        assertEquals(220_000L, merged.perfAmountCents)
        assertEquals(150_000L, merged.benefitBonusCents)
        assertEquals(30_000L, merged.heatAllowanceCents)
        assertEquals(33_103L, merged.sickPayCents)
        assertEquals(12_345L, merged.backPayCents)
        assertEquals(6_789L, merged.otherAddCents)
        assertEquals(94_811L, merged.socialOverrideCents)
        assertEquals(21_600L, merged.housingFundOverrideCents)
        assertEquals(10, merged.nightShiftsOverride)
    }

    @Test
    fun `工资条留空的项保留库里原值`() {
        val cur = MonthlyPayParamsEntity(
            payrollMonth = month,
            socialOverrideCents = 78_330L, // 工资条 1–6 月口径
            otherAddCents = 5_000L,
        )
        val merged = merge(
            current = cur,
            items = items(
                // SOCIAL_INSURANCE 未印出来 → 保留
                // OTHER_ADD 未印出来 → 保留
                SlipItemKey.BENEFIT_BONUS to 1_000L,
            ),
        )!!

        assertEquals(78_330L, merged.socialOverrideCents)
        assertEquals(5_000L, merged.otherAddCents)
        assertEquals(1_000L, merged.benefitBonusCents)
    }

    @Test
    fun `条上印出来的零是明确为零 要覆盖库里原值`() {
        val cur = MonthlyPayParamsEntity(payrollMonth = month, socialOverrideCents = 78_330L)
        val merged = merge(
            current = cur,
            items = items(SlipItemKey.SOCIAL_INSURANCE to 0L),
        )!!

        assertEquals(0L, merged.socialOverrideCents)
    }

    @Test
    fun `不由工资条派生的字段原样保留`() {
        val cur = MonthlyPayParamsEntity(
            payrollMonth = month,
            perfCoefficient = "0.8",
            perfBaseDeltaCents = 20_000L,
        )
        val merged = merge(current = cur, items = items(SlipItemKey.BENEFIT_BONUS to 1L))!!

        assertEquals("0.8", merged.perfCoefficient)
        assertEquals(20_000L, merged.perfBaseDeltaCents)
    }

    @Test
    fun `库里没有该月行时按计薪月新建`() {
        val merged = merge(items = items(SlipItemKey.BENEFIT_BONUS to 1_000L))!!

        assertEquals(month, merged.payrollMonth)
        assertEquals(0L, merged.perfBaseDeltaCents)
        assertNull(merged.perfCoefficient)
    }

    @Test
    fun `只写了夜班天数也能回填`() {
        val merged = merge(items = blankItems(), nights = 12)!!

        assertEquals(12, merged.nightShiftsOverride)
        assertEquals(month, merged.payrollMonth)
    }

    @Test
    fun `全为零的行被判为空 交由调用方删除`() {
        val merged = merge(items = items(SlipItemKey.BENEFIT_BONUS to 0L))!!

        assertTrue(merged.isEmpty)
    }
}
