package com.example.worktimetracker

import com.example.worktimetracker.domain.payroll.SlipMonthResolver
import com.example.worktimetracker.domain.payroll.SlipMonthResolver.Outcome
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「点录入该入哪个月」的判定护栏（v6 第一步，用户 2026-09-15 定的口径）。
 *
 * 守的是这条链路上最容易写歪的一点：`monthly_salaries.month` = 发薪月 = 计薪月 + 1，
 * 所以站在某个月的日历上点录入时，「本月」和「上月」都可能，**不能替用户猜**。
 */
class SlipMonthResolverTest {

    private fun months(vararg keys: String): Set<YearMonth> =
        keys.map { YearMonth.parse(it) }.toSet()

    private fun resolve(anchor: String, vararg recorded: String): Outcome =
        SlipMonthResolver.resolve(YearMonth.parse(anchor), months(*recorded))

    /** 锚点月已有条子 -> 打开它本身（编辑既有条子，不另建）。 */
    @Test
    fun anchorAlreadyHasSlip_opensAnchor() {
        assertEquals(Outcome.Direct(YearMonth.parse("2026-08")), resolve("2026-08", "2026-08"))
    }

    /** 用户场景①：9 月日历点录入，8 月已有条子 -> 直接入 9 月。 */
    @Test
    fun anchorMissingButPreviousRecorded_opensAnchor() {
        assertEquals(Outcome.Direct(YearMonth.parse("2026-09")), resolve("2026-09", "2026-08"))
    }

    /** 用户场景②：9 月空着、日历停在 10 月 -> 有歧义，问用户补 9 月还是录 10 月。 */
    @Test
    fun anchorAndPreviousBothMissing_asksUser() {
        assertEquals(
            Outcome.Ambiguous(previous = YearMonth.parse("2026-09"), anchor = YearMonth.parse("2026-10")),
            resolve("2026-10", "2026-08"),
        )
    }

    /** 全新安装（一条条子都没有）也走提问，不能默认吞掉。 */
    @Test
    fun noSlipsAtAll_asksUser() {
        assertEquals(
            Outcome.Ambiguous(previous = YearMonth.parse("2026-08"), anchor = YearMonth.parse("2026-09")),
            resolve("2026-09"),
        )
    }

    /** 跨年：1 月的上一个月是 12 月（`minusMonths` 会退到上一年）。 */
    @Test
    fun crossYearPreviousIsDecember() {
        assertEquals(
            Outcome.Ambiguous(previous = YearMonth.parse("2026-12"), anchor = YearMonth.parse("2027-01")),
            resolve("2027-01", "2026-11"),
        )
    }

    /** 锚点已有条子时，即便上一个月空着也不提问 —— 补录靠翻页，不打断正常编辑。 */
    @Test
    fun anchorHasSlip_doesNotAskEvenIfPreviousMissing() {
        val outcome = resolve("2026-09", "2026-09")
        assertTrue(outcome is Outcome.Direct)
    }
}
