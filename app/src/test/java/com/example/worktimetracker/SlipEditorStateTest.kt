package com.example.worktimetracker

import com.example.worktimetracker.domain.payroll.SlipItemKey
import com.example.worktimetracker.ui.app.forecast.SlipEditorState
import com.example.worktimetracker.ui.app.forecast.SlipItemDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工资条录入页状态（v6 第一步）的语义护栏。
 *
 * 守的是整条新链路最容易写歪的两点：
 *  1. **留空 = 未填写（null）≠ 填 0（明确为零）** —— 未填写不进合计。
 *  2. **两条校验分开**：绝不把「应发」与「实发」直接相减当误差（那是社保/公积金/个税）。
 */
class SlipEditorStateTest {

    /** 全部分项先填 `0`，再由各用例覆盖关心的项，避免默认未填写干扰计数。 */
    private fun allZero(): MutableMap<SlipItemKey, SlipItemDraft> =
        SlipItemKey.displayOrder.associateWith { SlipItemDraft("0") }.toMutableMap()

    private fun state(
        items: Map<SlipItemKey, SlipItemDraft>,
        gross: String = "",
        net: String = "",
    ) = SlipEditorState(payrollMonth = "2026-07", items = items, grossText = gross, netText = net)

    @Test
    fun `留空的分项解析为未填写且不参与合计`() {
        val items = allZero().apply {
            this[SlipItemKey.BASIC_SALARY] = SlipItemDraft("3100.00")
            this[SlipItemKey.PERFORMANCE_PAY] = SlipItemDraft("")   // 留空
        }
        val check = state(items, gross = "3100.00").check

        assertEquals(310_000L, check.incomeSumCents)
        assertEquals(1, check.unfilledKeys.size)
        assertEquals(SlipItemKey.PERFORMANCE_PAY, check.unfilledKeys.first())
        assertEquals(0L, check.grossDiffCents)
    }

    @Test
    fun `填 0 是明确为零，不算未填写`() {
        val items = allZero().apply {
            this[SlipItemKey.BASIC_SALARY] = SlipItemDraft("3100")
            this[SlipItemKey.PERFORMANCE_PAY] = SlipItemDraft("0")
        }
        val check = state(items, gross = "3100").check

        assertTrue(check.unfilledKeys.isEmpty())
        assertTrue(check.zeroKeys.contains(SlipItemKey.PERFORMANCE_PAY))
        assertEquals(310_000L, check.incomeSumCents)
    }

    @Test
    fun `两条校验分开，应发与实发的正常差额不被当错账`() {
        val items = allZero().apply {
            this[SlipItemKey.BASIC_SALARY] = SlipItemDraft("5000")
            this[SlipItemKey.SOCIAL_INSURANCE] = SlipItemDraft("500")
        }
        val st = state(items, gross = "5000", net = "4500")
        val check = st.check

        // ① 收入 5000 − 收入侧扣减 0 = 应发 5000 ✓
        assertEquals(0L, check.grossDiffCents)
        // ② 应发 5000 − 应发后扣减 500 = 实发 4500 ✓
        assertEquals(0L, check.netDiffCents)
        assertEquals(500_000L, st.declaredGrossCents)
        assertEquals(450_000L, st.declaredNetCents)
    }

    @Test
    fun `条上应发未填写时校验二退回已填项推算的应发`() {
        val items = allZero().apply {
            this[SlipItemKey.BASIC_SALARY] = SlipItemDraft("1000")
            this[SlipItemKey.HOUSING_FUND] = SlipItemDraft("100")
        }
        val check = state(items, net = "900").check

        assertNull(check.grossDiffCents)          // 条上应发未填 → 不报校验①
        assertEquals(0L, check.netDiffCents)      // 1000 − 100 = 900
        assertEquals(90_000L, check.computedNetCents)
    }

    @Test
    fun `负数输入不参与合计（解析为 null 等价未填写）`() {
        val items = allZero().apply { this[SlipItemKey.BASIC_SALARY] = SlipItemDraft("-5") }
        val entry = state(items).entries.first { it.key == SlipItemKey.BASIC_SALARY }

        assertNull(entry.amountCents)
    }

    @Test
    fun `未填写项数量随留空项增长`() {
        val items = allZero().apply {
            this[SlipItemKey.PERFORMANCE_PAY] = SlipItemDraft("")
            this[SlipItemKey.BENEFIT_BONUS] = SlipItemDraft("")
            this[SlipItemKey.NIGHT_ALLOWANCE] = SlipItemDraft("")
        }
        val check = state(items).check
        assertEquals(3, check.unfilledKeys.size)
    }
}
