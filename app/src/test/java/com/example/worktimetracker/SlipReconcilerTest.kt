package com.example.worktimetracker

import com.example.worktimetracker.domain.payroll.PayRateSet
import com.example.worktimetracker.domain.payroll.PayrollEngine
import com.example.worktimetracker.domain.payroll.SlipItemEntry
import com.example.worktimetracker.domain.payroll.SlipItemKey
import com.example.worktimetracker.domain.payroll.SlipItemNature
import com.example.worktimetracker.domain.payroll.SlipItemStage
import com.example.worktimetracker.domain.payroll.SlipDraftSeeder
import com.example.worktimetracker.domain.payroll.SlipReconciler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工资条核对器护栏（v6「计薪预测与发薪对账」第一步）。
 *
 * 重点盯住最容易写歪的几条语义：
 * 1. **`null`（未填写）不进合计、`0`（明确为零）进合计**；
 * 2. 两条校验式**各自成立**，绝不拿「应发 − 实发」当误差；
 * 3. 病假这类含义不明的项**必须报待裁决**，不能自动猜；
 * 4. 与 `monthly_salaries` 不一致**只提示、不改数据**。
 */
class SlipReconcilerTest {

    private fun entry(key: SlipItemKey, cents: Long?, review: Boolean = false) =
        SlipItemEntry(key = key, amountCents = cents, needsReview = review)

    // ------------------------------------------------------- null vs 0

    @Test
    fun `未填写项与明确为零项被分开标记`() {
        val check = SlipReconciler.check(
            items = listOf(
                entry(SlipItemKey.BASIC_SALARY, 300_000L),
                entry(SlipItemKey.BENEFIT_BONUS, null),   // 未填写
                entry(SlipItemKey.HEAT_ALLOWANCE, 0L),    // 明确为零
            ),
            declaredGrossCents = 300_000L,
            declaredNetCents = 300_000L,
        )
        assertEquals(listOf(SlipItemKey.BENEFIT_BONUS), check.unfilledKeys)
        assertEquals(listOf(SlipItemKey.HEAT_ALLOWANCE), check.zeroKeys)
        assertFalse(check.isComplete)
    }

    @Test
    fun `未填写项不参与合计 于是差额正好等于待补部分`() {
        val check = SlipReconciler.check(
            items = listOf(
                entry(SlipItemKey.BASIC_SALARY, 310_000L),
                entry(SlipItemKey.PERFORMANCE_PAY, null),
            ),
            declaredGrossCents = 400_000L,
            declaredNetCents = null,
        )
        // 合计只含已填的基本工资
        assertEquals(310_000L, check.incomeSumCents)
        assertEquals(310_000L, check.computedGrossCents)
        // 差额 = 条上应发 − 已填项推出的应发 = 还没补上的那部分
        assertEquals(90_000L, check.grossDiffCents)
        assertTrue(check.issues.any { it.kind == SlipReconciler.IssueKind.UNFILLED_ITEMS })
        assertTrue(check.issues.any { it.kind == SlipReconciler.IssueKind.GROSS_MISMATCH })
    }

    // --------------------------------------------------- 两条校验式独立

    @Test
    fun `应发与实发的差额是社保公积金个税 不是错账`() {
        val check = SlipReconciler.check(
            items = listOf(
                entry(SlipItemKey.BASIC_SALARY, 1_000_000L),
                entry(SlipItemKey.SOCIAL_INSURANCE, 100_000L),
                entry(SlipItemKey.INCOME_TAX, 30_000L),
            ),
            declaredGrossCents = 1_000_000L,
            declaredNetCents = 870_000L,
        )
        // 校验①平：已填项推出的应发 == 条上应发
        assertEquals(0L, check.grossDiffCents)
        // 校验②平：应发 − 1300.00 == 条上实发
        assertEquals(0L, check.netDiffCents)
        // 但「应发 − 实发」= 1300.00，**绝不能**把它当误差
        assertEquals(130_000L, check.declaredGrossCents!! - 870_000L)
        assertTrue(check.issues.isEmpty())
    }

    @Test
    fun `收入侧扣减在应发之前扣 归入校验一`() {
        val check = SlipReconciler.check(
            items = listOf(
                entry(SlipItemKey.BASIC_SALARY, 1_000_000L),
                entry(SlipItemKey.PERSONAL_LEAVE_DEDUCT, 50_000L),  // 事假：应发之前扣
            ),
            declaredGrossCents = 950_000L,
            declaredNetCents = 950_000L,
        )
        assertEquals(50_000L, check.preGrossSumCents)
        assertEquals(950_000L, check.computedGrossCents)   // 1_000_000 − 50_000
        assertEquals(0L, check.grossDiffCents)
        assertEquals(0L, check.netDiffCents)
    }

    @Test
    fun `校验二在条上没写应发时退回已填项推出的应发`() {
        val check = SlipReconciler.check(
            items = listOf(
                entry(SlipItemKey.BASIC_SALARY, 500_000L),
                entry(SlipItemKey.SOCIAL_INSURANCE, 100_000L),
            ),
            declaredGrossCents = null,
            declaredNetCents = 400_000L,
        )
        assertEquals(500_000L, check.grossForNetCents)
        assertEquals(400_000L, check.computedNetCents)
        assertEquals(0L, check.netDiffCents)
        assertNull(check.grossDiffCents)   // 条上没写应发 → 校验①无从谈起
    }

    // ------------------------------------------------------- 异常标记

    @Test
    fun `病假默认标待裁决 且裁决后可消除`() {
        val flagged = SlipReconciler.check(
            items = listOf(entry(SlipItemKey.SICK_PAY, 33_103L, review = true)),
            declaredGrossCents = 33_103L,
            declaredNetCents = 33_103L,
        )
        assertEquals(listOf(SlipItemKey.SICK_PAY), flagged.needsReviewKeys)
        assertTrue(flagged.issues.any { it.kind == SlipReconciler.IssueKind.AMBIGUOUS_STAGE })

        // 用户裁决完成后把标记撤掉 → 告警消失（枚举里的 reviewByDefault 不该永远钉住）
        val adjudicated = SlipReconciler.check(
            items = listOf(entry(SlipItemKey.SICK_PAY, 33_103L, review = false)),
            declaredGrossCents = 33_103L,
            declaredNetCents = 33_103L,
        )
        assertTrue(adjudicated.needsReviewKeys.isEmpty())
        assertTrue(adjudicated.issues.isEmpty())
        assertTrue(adjudicated.isBalanced)
    }

    @Test
    fun `负数金额被标为不合理`() {
        val check = SlipReconciler.check(
            items = listOf(entry(SlipItemKey.SOCIAL_INSURANCE, -1L)),
            declaredGrossCents = 0L,
            declaredNetCents = 0L,
        )
        assertTrue(check.issues.any { it.kind == SlipReconciler.IssueKind.IMPLAUSIBLE })
    }

    @Test
    fun `与已录入实发不一致只提示 不算改数据`() {
        val check = SlipReconciler.check(
            items = listOf(entry(SlipItemKey.BASIC_SALARY, 910_000L)),
            declaredGrossCents = 910_000L,
            declaredNetCents = 910_000L,
            recordedNetCents = 900_000L,
        )
        assertEquals(10_000L, check.recordedNetDiffCents)
        val issue = check.issues.first { it.kind == SlipReconciler.IssueKind.MONTHLY_SALARY_MISMATCH }
        assertTrue(issue.hint.contains("不会改动"))
    }

    // --------------------------------------------------------- 状态建议

    @Test
    fun `分项没填全时建议状态为草稿`() {
        val check = SlipReconciler.check(
            items = listOf(entry(SlipItemKey.BASIC_SALARY, 1L), entry(SlipItemKey.BACK_PAY, null)),
            declaredGrossCents = 1L,
            declaredNetCents = 1L,
        )
        assertEquals(com.example.worktimetracker.domain.payroll.SlipStatus.DRAFT, check.suggestedStatus)
    }

    @Test
    fun `填全且平账时也不会自动变已确认`() {
        val check = SlipReconciler.check(
            items = listOf(entry(SlipItemKey.BASIC_SALARY, 1L)),
            declaredGrossCents = 1L,
            declaredNetCents = 1L,
        )
        assertTrue(check.isComplete)
        assertTrue(check.isBalanced)
        // 仍建议「待核对」—— 确认必须由用户点，不能自动化
        assertEquals(
            com.example.worktimetracker.domain.payroll.SlipStatus.PENDING_REVIEW,
            check.suggestedStatus
        )
    }

    // --------------------------------------------- 草稿生成器（同文件护栏）

    /** 2026-07 调薪后的取值（与 PayRateKey 默认值一致）。 */
    private val julyRates = PayRateSet.DEFAULT

    @Test
    fun `草稿只填确定项 浮动项留未填写`() {
        val items = SlipDraftSeeder.draftItems(julyRates)
        val filled = items.filter { it.amountCents != null }.map { it.key }.toSet()
        assertEquals(
            setOf(
                SlipItemKey.BASIC_SALARY,
                SlipItemKey.POST_ALLOWANCE,
                SlipItemKey.SENIOR_ALLOWANCE,
                SlipItemKey.FULL_ATTENDANCE,
                SlipItemKey.OVERTIME_PAY,
                SlipItemKey.SOCIAL_INSURANCE,
                SlipItemKey.HOUSING_FUND,
            ),
            filled
        )
        // 绩效 / 效益奖金 / 高温 / 夜班 必须留空（夜班天数未知，绩效要学）
        listOf(
            SlipItemKey.PERFORMANCE_PAY,
            SlipItemKey.BENEFIT_BONUS,
            SlipItemKey.HEAT_ALLOWANCE,
            SlipItemKey.NIGHT_ALLOWANCE,
            SlipItemKey.INCOME_TAX,
        ).forEach { key ->
            assertNull(items.first { it.key == key }.amountCents)
        }
    }

    @Test
    fun `草稿的加班工资走包干公式`() {
        val items = SlipDraftSeeder.draftItems(julyRates)
        val ot = items.first { it.key == SlipItemKey.OVERTIME_PAY }.amountCents
        assertEquals(PayrollEngine.overtimePayCents(310_000L, 3_600L), ot)  // 962.07
        assertEquals(96_207L, ot)
    }

    @Test
    fun `草稿里病假被标记待裁决`() {
        val items = SlipDraftSeeder.draftItems(julyRates)
        val sick = items.first { it.key == SlipItemKey.SICK_PAY }
        assertTrue(sick.needsReview)
        assertNull(sick.amountCents)
    }

    @Test
    fun `草稿顺序与界面展示顺序一致`() {
        val items = SlipDraftSeeder.draftItems(julyRates)
        assertEquals(SlipItemKey.displayOrder, items.map { it.key })
    }

    @Test
    fun `草稿在 2026-07 之前的月份用旧分段`() {
        // EARLIEST 段（未调薪）：基本 3000、岗位 1500、工龄 30、社保 783.30、公积金 358
        val old = com.example.worktimetracker.domain.payroll.PayRateResolver.resolve(
            payrollMonth = "2026-01",
            segments = com.example.worktimetracker.domain.payroll.PayRateSeed.segments()
                .groupBy { com.example.worktimetracker.domain.payroll.PayRateKey.byStorageKey(it.paramKey)!! }
                .mapNotNull { (k, rows) ->
                    k to rows.map { r ->
                        com.example.worktimetracker.domain.payroll.PayRateResolver.Segment(
                            r.effectiveFrom, r.value
                        )
                    }
                }
                .toMap()
        )
        val items = SlipDraftSeeder.draftItems(old)
        fun amount(k: SlipItemKey) = items.first { it.key == k }.amountCents
        assertEquals(300_000L, amount(SlipItemKey.BASIC_SALARY))
        assertEquals(150_000L, amount(SlipItemKey.POST_ALLOWANCE))
        assertEquals(3_000L, amount(SlipItemKey.SENIOR_ALLOWANCE))
        assertEquals(78_330L, amount(SlipItemKey.SOCIAL_INSURANCE))
        assertEquals(35_800L, amount(SlipItemKey.HOUSING_FUND))
        assertEquals(93_103L, amount(SlipItemKey.OVERTIME_PAY))   // 3000 基本 → 931.03
    }

    @Test
    fun `分项目录的阶段与性质与口径文档一致`() {
        assertEquals(SlipItemStage.INCOME, SlipItemKey.SICK_PAY.stage)          // 病假是收入项
        assertEquals(SlipItemNature.ONE_TIME, SlipItemKey.BACK_PAY.nature)      // 补发不延续
        assertEquals(SlipItemNature.FLOATING, SlipItemKey.PERFORMANCE_PAY.nature)
        assertEquals(SlipItemNature.FLOATING, SlipItemKey.BENEFIT_BONUS.nature)
        assertEquals(SlipItemStage.DEDUCT_POST_GROSS, SlipItemKey.INCOME_TAX.stage)
        assertEquals(SlipItemStage.DEDUCT_PRE_GROSS, SlipItemKey.PERSONAL_LEAVE_DEDUCT.stage)
        // 一次性项都不参与学习
        assertEquals(
            SlipItemKey.entries.filter { it.nature == SlipItemNature.ONE_TIME }.toSet(),
            setOf(SlipItemKey.BACK_PAY, SlipItemKey.OTHER_ADD, SlipItemKey.OTHER_DEDUCT)
        )
    }
}
