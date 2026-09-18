package com.example.worktimetracker

import com.example.worktimetracker.ui.app.HolidayResultTone
import com.example.worktimetracker.ui.app.HolidayStatusPresenter
import com.example.worktimetracker.ui.app.HolidayStatusUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 节假日状态文案测试。
 *
 * 重点保证**告警不误导**：没联网不代表节假日功能失效（法定节日由算法推算），
 * 文案只能说"放假/调休安排待更新"。
 */
class HolidayStatusPresenterTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val atMillis = LocalDateTime.of(2026, 9, 13, 22, 40)
        .atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `sourceLabelReflectsWhereDataCameFrom`() {
        assertEquals("尚未获取", HolidayStatusPresenter.sourceLabel(HolidayStatusUi(), 2026))
        assertEquals(
            "内置公告",
            HolidayStatusPresenter.sourceLabel(HolidayStatusUi(cachedYears = setOf(2026)), 2026)
        )
        assertEquals(
            "联网获取",
            HolidayStatusPresenter.sourceLabel(
                HolidayStatusUi(lastSuccessAt = atMillis, coveredYears = setOf(2026)), 2026
            )
        )
        assertEquals(
            "更新中",
            HolidayStatusPresenter.sourceLabel(HolidayStatusUi(updating = true), 2026).take(3)
        )
    }

    @Test
    fun `updatedAtTextIsReadableAndHandlesMissingValue`() {
        assertEquals("尚未更新", HolidayStatusPresenter.updatedAtText(HolidayStatusUi(), zone))
        assertEquals(
            "最近更新：2026-09-13 22:40",
            HolidayStatusPresenter.updatedAtText(HolidayStatusUi(lastSuccessAt = atMillis), zone)
        )
    }

    @Test
    fun `warningAsksForUpdateWhenCurrentYearMissing`() {
        val warning = HolidayStatusPresenter.warning(
            HolidayStatusUi(), currentYear = 2026, currentMonth = 3
        )
        assertNotNull(warning)
        assertTrue("必须点出缺失年份", warning!!.contains("2026"))
        assertTrue("必须引导到立即更新", warning.contains("立即更新"))
        assertTrue(
            "必须说明法定节日仍可用，避免用户以为功能全废",
            warning.contains("法定节日")
        )
    }

    @Test
    fun `warningSuggestsNextYearOnlyAfterPublishWindow`() {
        val status = HolidayStatusUi(lastSuccessAt = atMillis, coveredYears = setOf(2026))
        // 3 月：不该催次年数据
        assertNull(HolidayStatusPresenter.warning(status, 2026, currentMonth = 3))
        // 11 月：国务院已发布次年安排，提示可提前获取
        val nov = HolidayStatusPresenter.warning(status, 2026, currentMonth = 11)
        assertNotNull(nov)
        assertTrue("应点到 2027", nov!!.contains("2027"))
    }

    @Test
    fun `warningIsSilentWhenEverythingIsReady`() {
        val status = HolidayStatusUi(
            lastSuccessAt = atMillis,
            coveredYears = setOf(2026, 2027)
        )
        assertNull(HolidayStatusPresenter.warning(status, 2026, currentMonth = 11))
    }

    @Test
    fun `resultTextDistinguishesFullAndPartialSuccess`() {
        val full = HolidayStatusPresenter.resultText(
            HolidayStatusUi(), setOf(2025, 2026, 2027), emptySet(), "cdn.jsdelivr.net"
        )
        assertTrue(full.contains("2025、2026、2027"))
        assertTrue(full.contains("cdn.jsdelivr.net"))

        val partial = HolidayStatusPresenter.resultText(
            HolidayStatusUi(), setOf(2025), setOf(2026), "cdn.jsdelivr.net", currentYear = 2026
        )
        assertTrue("部分失败必须说明已沿用本地数据", partial.contains("沿用本地数据"))
        assertTrue(partial.contains("2026"))

        val failed = HolidayStatusPresenter.resultText(HolidayStatusUi(), emptySet(), setOf(2026, 2027), null)
        assertTrue(failed.contains("沿用本地数据"))
    }

    @Test
    fun `currentYearSuccessIgnoresUnavailableFutureYear`() {
        val status = HolidayStatusUi(coveredYears = setOf(2026), error = "2027 数据尚未发布")
        val text = HolidayStatusPresenter.resultText(
            status, setOf(2026), setOf(2027), "cdn.jsdelivr.net", currentYear = 2026
        )
        assertEquals("已更新 2026 年（cdn.jsdelivr.net）", text)
        assertEquals(HolidayResultTone.SUCCESS, HolidayStatusPresenter.resultTone(true, text))
        assertNull(HolidayStatusPresenter.displayError(status, currentYear = 2026))
    }

    /**
     * 回归测试：`HolidaySyncOutcome.ok` 的语义是 succeededYears.isNotEmpty()，
     * 部分年份成功时它**也是 true**；若直接拿它当色调依据，橙色告警会被染成绿色。
     */
    @Test
    fun `resultToneKeepsGreenOnlyForFullSuccess`() {
        assertEquals(
            HolidayResultTone.SUCCESS,
            HolidayStatusPresenter.resultTone(true, "已更新 2025、2026 年（cdn.jsdelivr.net）")
        )
        assertEquals(
            "部分成功必须是橙色，不能染绿",
            HolidayResultTone.PARTIAL,
            HolidayStatusPresenter.resultTone(
                false, "已更新 2025、2026 年；2027 年失败，已沿用本地数据"
            )
        )
        assertEquals(
            HolidayResultTone.FAILURE,
            HolidayStatusPresenter.resultTone(false, "更新失败，已沿用本地数据")
        )
        assertEquals(HolidayResultTone.FAILURE, HolidayStatusPresenter.resultTone(null, ""))
    }

    @Test
    fun `resultToneNeverTurnsGreenWithoutExplicitFullSuccess`() {
        // resultOk 为 null 代表"尚无结论"，此时一律不能显示成功色
        assertEquals(
            HolidayResultTone.PARTIAL,
            HolidayStatusPresenter.resultTone(null, "已更新 2026 年")
        )
    }
}
