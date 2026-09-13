package com.example.worktimetracker

import com.example.worktimetracker.domain.evidence.EvidenceSource
import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.evidence.FusedStatusSnapshot
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import com.example.worktimetracker.ui.TodayStatusPresenter
import com.example.worktimetracker.ui.UiDayRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 「今日」页纯推导护栏。
 *
 * 这一页最容易悄悄错的地方是**进行中工时的估算**：它不该把自动判定算出来的
 * 落库值改掉，也不该在固定工时模式下显示一个会跳动的数字。下面把这些口径钉死。
 */
class TodayStatusPresenterTest {

    private val date = LocalDate.of(2026, 9, 13)

    private fun record(
        status: String = "白班",
        start: Long? = null,
        end: Long? = null,
        finalMinutes: Int = 0,
        needsReview: Boolean = false
    ) = UiDayRecord(
        date = date,
        status = status,
        startMillis = start,
        endMillis = end,
        finalMinutes = finalMinutes,
        needsReview = needsReview
    )

    // ------------------------------------------------------------- 工时展示

    @Test
    fun `已离岗时用落库值且不再计时`() {
        val result = TodayStatusPresenter.displayMinutes(
            finalMinutes = 660,
            startMillis = 1_000L,
            endMillis = 2_000L,
            nowMillis = 99_999_999L,
            restDeductionMinutes = 60
        )
        assertEquals(660, result.minutes)
        assertFalse(result.running)
        assertFalse(result.fixed)
    }

    @Test
    fun `固定工时模式直接显示固定值而不是跳动估算`() {
        val result = TodayStatusPresenter.displayMinutes(
            finalMinutes = 0,
            startMillis = 0L,
            endMillis = null,
            nowMillis = 10L * 60 * 60 * 1000,
            restDeductionMinutes = 60,
            fixedMinutes = 720
        )
        assertEquals(720, result.minutes)
        assertFalse(result.running)
        assertTrue(result.fixed)
    }

    @Test
    fun `进行中按已持续时长减休息扣除`() {
        val start = 8L * 60 * 60 * 1000 // 08:00
        val now = 14L * 60 * 60 * 1000  // 14:00 → 已持续 360 分钟
        val result = TodayStatusPresenter.displayMinutes(
            finalMinutes = 0,
            startMillis = start,
            endMillis = null,
            nowMillis = now,
            restDeductionMinutes = 60
        )
        assertEquals(300, result.minutes)
        assertTrue(result.running)
    }

    @Test
    fun `进行中不足休息扣除时下限为零`() {
        val result = TodayStatusPresenter.displayMinutes(
            finalMinutes = 0,
            startMillis = 0L,
            endMillis = null,
            nowMillis = 20L * 60 * 1000,
            restDeductionMinutes = 60
        )
        assertEquals(0, result.minutes)
        assertTrue(result.running)
    }

    @Test
    fun `没有上班时间时保留落库值`() {
        val result = TodayStatusPresenter.displayMinutes(
            finalMinutes = 0,
            startMillis = null,
            endMillis = null,
            nowMillis = 123L,
            restDeductionMinutes = 60
        )
        assertEquals(0, result.minutes)
        assertFalse(result.running)
    }

    // --------------------------------------------------------------- 金额

    @Test
    fun `时薪未设置时不给金额`() {
        assertNull(TodayStatusPresenter.earningsCents(600, 0L))
        assertNull(TodayStatusPresenter.earningsCents(600, -1L))
    }

    @Test
    fun `金额按工时乘基本时薪四舍五入到分`() {
        // 8h12m = 492 分钟，时薪 ¥24.00 → 196.80
        assertEquals(19_680L, TodayStatusPresenter.earningsCents(492, 2_400L))
    }

    @Test
    fun `金额四舍五入的边界`() {
        assertEquals(2L, TodayStatusPresenter.earningsCents(1, 100L))   // 1.67 → 2
        assertEquals(3L, TodayStatusPresenter.earningsCents(2, 100L))   // 3.33 → 3
        assertEquals(5L, TodayStatusPresenter.earningsCents(3, 100L))   // 5.00 → 5
        assertEquals(0L, TodayStatusPresenter.earningsCents(0, 100L))
    }

    // --------------------------------------------------------------- 状态

    @Test
    fun `无记录时状态是还没有记录`() {
        val headline = TodayStatusPresenter.headline(null)
        assertEquals(TodayStatusPresenter.TodayTone.IDLE, headline.tone)
        assertTrue(headline.text.contains("还没有记录"))
    }

    @Test
    fun `只有上班时间时是在岗中`() {
        val headline = TodayStatusPresenter.headline(record(start = 1L))
        assertEquals("在岗中", headline.text)
        assertEquals(TodayStatusPresenter.TodayTone.WORKING, headline.tone)
    }

    @Test
    fun `有上下班时间是已下班`() {
        val headline = TodayStatusPresenter.headline(record(start = 1L, end = 2L))
        assertEquals("已下班", headline.text)
        assertEquals(TodayStatusPresenter.TodayTone.DONE, headline.tone)
    }

    @Test
    fun `早退与到岗异常都是警示态`() {
        assertEquals(
            TodayStatusPresenter.TodayTone.WARN,
            TodayStatusPresenter.headline(record(status = "下早班", start = 1L, end = 2L)).tone
        )
        assertEquals(
            TodayStatusPresenter.TodayTone.WARN,
            TodayStatusPresenter.headline(record(status = "到岗异常", start = 1L, end = 2L)).tone
        )
    }

    @Test
    fun `请假与休息是离岗态`() {
        assertEquals(
            TodayStatusPresenter.TodayTone.OFF,
            TodayStatusPresenter.headline(record(status = "请假")).tone
        )
        assertEquals(
            TodayStatusPresenter.TodayTone.OFF,
            TodayStatusPresenter.headline(record(status = "休息")).tone
        )
    }

    @Test
    fun `外出中压过在岗`() {
        val headline = TodayStatusPresenter.headline(record(status = "外出", start = 1L))
        assertEquals("外出中", headline.text)
        assertEquals(TodayStatusPresenter.TodayTone.WARN, headline.tone)
    }

    @Test
    fun `没打卡但有待确认时提示待确认`() {
        val headline = TodayStatusPresenter.headline(record(status = "休息", needsReview = true))
        assertEquals(TodayStatusPresenter.TodayTone.WARN, headline.tone)
    }

    // --------------------------------------------------------------- 文案

    @Test
    fun `周次用 ISO 口径`() {
        assertEquals("第 37 周", TodayStatusPresenter.weekLabel(LocalDate.of(2026, 9, 13)))
    }

    @Test
    fun `日期文案含月日与星期`() {
        val label = TodayStatusPresenter.dateLabel(LocalDate.of(2026, 9, 13))
        assertTrue(label, label.contains("9 月 13 日"))
        assertTrue(label, label.contains("周日"))
    }

    // ------------------------------------------------------------- 证据来源

    @Test
    fun `证据芯片顺序固定且无快照时全部未点亮`() {
        val chips = TodayStatusPresenter.evidenceChips(null)
        assertEquals(listOf("GPS", "Wi-Fi", "蓝牙", "基站", "Motion"), chips.map { it.label })
        assertTrue(chips.none { it.active })
    }

    @Test
    fun `证据芯片按来源点亮并追加额外来源`() {
        val snapshot = FusedStatusSnapshot(
            place = ResolvedPlace.COMPANY,
            decision = FusedDecision.CONFIRMED,
            reason = "CONFIRMED_AMBIENT",
            confidence = 0.92,
            sources = setOf(EvidenceSource.WIFI, EvidenceSource.BLUETOOTH, EvidenceSource.NETWORK_LOCATION)
        )
        val chips = TodayStatusPresenter.evidenceChips(snapshot)
        assertEquals(listOf("GPS", "Wi-Fi", "蓝牙", "基站", "Motion", "网络定位"), chips.map { it.label })
        assertTrue(chips.first { it.label == "Wi-Fi" }.active)
        assertTrue(chips.first { it.label == "网络定位" }.active)
        assertFalse(chips.first { it.label == "GPS" }.active)
    }

    @Test
    fun `冲突只在协调器明确给出时才提示`() {
        val conflict = FusedStatusSnapshot(
            place = ResolvedPlace.UNKNOWN,
            decision = FusedDecision.UNKNOWN,
            reason = "UNKNOWN_CONFLICT",
            confidence = 0.3,
            sources = setOf(EvidenceSource.CELL, EvidenceSource.WIFI)
        )
        assertEquals("1 项冲突", TodayStatusPresenter.conflictText(conflict))
        assertEquals("共 2 项证据 · 1 项冲突", TodayStatusPresenter.evidenceLine(conflict))

        val maintained = conflict.copy(reason = "MAINTAIN_WEAK_EVIDENCE")
        assertNull(TodayStatusPresenter.conflictText(maintained))
        assertEquals("共 2 项证据", TodayStatusPresenter.evidenceLine(maintained))
    }

    @Test
    fun `没有证据时不输出证据行`() {
        assertNull(TodayStatusPresenter.evidenceCountText(null))
        assertNull(TodayStatusPresenter.evidenceLine(null))
    }
}
