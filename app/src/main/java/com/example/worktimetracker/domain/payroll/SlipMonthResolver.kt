package com.example.worktimetracker.domain.payroll

import java.time.YearMonth

/**
 * 「点『录入』时这笔工资该记到哪个**计薪月**」的判定（口径由用户 2026-09-15 确定）。
 *
 * 为什么必须判定：`monthly_salaries.month` 是**发薪月** = `payrollMonth` + 1，
 * 而工资条录的是**计薪月**（干活那个月）。于是站在 10 月的日历上点录入时，
 * 「9 月工资」和「10 月工资」都讲得通 —— 前者是上月发薪日刚到手的欠账，后者是提前录当月。
 *
 * 判定规则（顺序即优先级）：
 * 1. [anchor] 已经有工资条 → 直接打开它（编辑既有条子，不另建一条）。
 * 2. [anchor] 没有，但**上一个月有** → 直接入 [anchor]（顺着往下录）。
 * 3. [anchor] 没有，**上一个月也没有** → 有歧义，[Outcome.Ambiguous]，交给用户裁决。
 *
 * 纯函数、零 IO，护栏 `SlipMonthResolverTest`。
 */
object SlipMonthResolver {

    /** 判定结果。 */
    sealed interface Outcome {
        /** 无需提问，直接打开这个计薪月。 */
        data class Direct(val payrollMonth: YearMonth) : Outcome

        /** 有歧义：让用户选这笔是 [previous] 还是 [anchor] 的工资。 */
        data class Ambiguous(val previous: YearMonth, val anchor: YearMonth) : Outcome
    }

    /**
     * @param anchor 用户点录入时**所在的月份**（日历当前月 / 今天所在月）
     * @param monthsWithSlip 已经有工资条的计薪月集合
     */
    fun resolve(anchor: YearMonth, monthsWithSlip: Set<YearMonth>): Outcome {
        if (anchor in monthsWithSlip) return Outcome.Direct(anchor)
        val previous = anchor.minusMonths(1)
        return if (previous in monthsWithSlip) Outcome.Direct(anchor)
        else Outcome.Ambiguous(previous, anchor)
    }
}

/**
 * 待用户裁决的月份对：[previous] = 上一个月（补录欠账），[anchor] = 当前所在月。
 *
 * 放在 domain 层而不是界面层：它是「该入哪个月」这个业务判断的产物，
 * 界面只负责把它渲染成一个提问框。
 */
data class MonthChoice(val previous: YearMonth, val anchor: YearMonth)
