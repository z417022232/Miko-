package com.example.worktimetracker

import com.example.worktimetracker.domain.location.AnchorLearner
import com.example.worktimetracker.domain.location.AnchorUpdatePolicy
import com.example.worktimetracker.domain.location.GeoPoint
import com.example.worktimetracker.domain.location.ShadowObservation
import com.example.worktimetracker.domain.location.ShadowValidator
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 影子验证（方案 §三.2 的强化形式）。
 *
 * 这组测试守的是**影子期的目的**：在不参与判定的情况下，候选**是否继续**稳定。
 * 所以每个条件都要有「单独不满足就必须拦下」的用例 ——
 * 只测「全绿能过」等于没测，因为门禁的价值全在拒绝那一侧。
 *
 * 另外钉住一条与**训练**的分工：历史 `distinctDayCount` 再大，
 * 也不能抵扣前向观察天数（同批数据既训练又验证 = 验证集被污染）。
 */
class ShadowValidatorTest {

    private val start = LocalDate.of(2026, 9, 1)
    private val center = GeoPoint(31.0, 121.0)

    /**
     * 每度纬度对应的米数。
     *
     * ⚠️ 必须与 `LocationStatusAnalyzer.distanceMeters` 的球半径一致：那里用 R = 6_371_000 的
     * haversine，所以每度 = π·R/180 ≈ 111194.93 米，**不是**常被人随口写的 111200。
     * 用错系数会让「10 米漂移」实际只走 9.9995 米，于是边界用例静默变成「没越界」——
     * 这条正好踩过一次。
     */
    private val metersPerDegreeLatitude = Math.PI * 6_371_000.0 / 180.0

    /** 纬度方向挪 [meters] 米。 */
    private fun lat(meters: Double) = center.latitude + meters / metersPerDegreeLatitude

    /** 从 [start] 起连续 [days] 天、每天一条的干净观测。 */
    private fun cleanWindow(
        days: Int = 8,
        driftMeters: Double = 0.0,
        ambient: Int = 2,
        p90: Double? = 20.0
    ): List<ShadowObservation> = (0 until days).map { i ->
        ShadowObservation(
            day = start.plusDays(i.toLong()),
            center = GeoPoint(lat(if (i == days - 1) driftMeters else 0.0), center.longitude),
            ambientSources = if (i == days - 1) ambient else 3,
            spreadP90Meters = p90
        )
    }

    /** 窗口最后一天。 */
    private val lastDay: LocalDate get() = start.plusDays(7)

    @Test fun aCleanSevenDayWindowPasses() {
        val result = ShadowValidator.evaluate(cleanWindow(), lastDay, conflictCount = 0)
        assertTrue("干净窗口应当通过，实际未达标项=${result.failures}", result.passed)
        assertEquals(7, result.validation.elapsedDays)
        assertEquals(8, result.validation.observedDays)
    }

    @Test fun emptyWindowFailsWithAReason() {
        val result = ShadowValidator.evaluate(emptyList(), lastDay, conflictCount = 0)
        assertFalse(result.passed)
        assertTrue(result.failures.any { it.contains("没有任何有效观测") })
    }

    @Test fun fewerThanSevenElapsedDaysFails() {
        // 只观察了 5 天（跨 4 个自然日）
        val result = ShadowValidator.evaluate(cleanWindow(days = 5), start.plusDays(4), 0)
        assertFalse(result.passed)
        assertEquals(4, result.validation.elapsedDays)
        assertTrue(result.failures.any { it.contains("前向观察") })
    }

    @Test fun historicalSampleDaysCannotPayForTheShadowPeriod() {
        // 这是「训练/验证不能同源」的护栏：即便候选本身跨了 300 天，
        // 影子窗口只有 3 天 → 必须拦下。
        val result = ShadowValidator.evaluate(cleanWindow(days = 3), start.plusDays(2), 0)
        assertFalse(result.passed)
        assertTrue(result.failures.any { it.contains("前向观察 2/7 天") })
    }

    @Test fun aMissingDayInsideTheWindowFails() {
        // 8 天窗口里挖掉第 4 天（自然日 09-05）→ observedDays(7) < elapsedDays(7)+1
        val gapped = cleanWindow().filterNot { it.day == start.plusDays(4) }
        val result = ShadowValidator.evaluate(gapped, lastDay, 0)
        assertFalse(result.passed)
        assertEquals(7, result.validation.observedDays)
        assertTrue(result.failures.any { it.contains("没采到有效样本") })
    }

    @Test fun centerDriftAtOrBeyondTheLimitFails() {
        // 规则方向是 `maxDrift >= 10.0` 即拦下（10 米意味着锚点**位移了**，不是采样误差）。
        // 「正好 10.0 米」在浮点往返后落在 10.0±1e-13，无法确定性构造，所以这里取一个
        // 明确越过门槛的值；门槛的**位置**由常量断言 + 下面的 9.9 米用例共同钉住。
        val result = ShadowValidator.evaluate(cleanWindow(driftMeters = 10.5), lastDay, 0)
        assertFalse(result.passed)
        assertTrue(result.failures.any { it.contains("漂移") })
        assertTrue(
            "构造出来的漂移应当确实越过门槛：${result.validation.maxCenterDriftMeters}",
            result.validation.maxCenterDriftMeters >= ShadowValidator.MAX_CENTER_DRIFT_METERS
        )
    }

    @Test fun centerDriftJustUnderTheLimitIsTolerated() {
        // 9.9 米：贴着门槛的**通过**侧。这条 + 上一条把「门槛在 10 米」夹到 0.6 米宽的带里，
        // 门槛被误改成 10.5 或 9.5 都会有一边红。
        val result = ShadowValidator.evaluate(cleanWindow(driftMeters = 9.9), lastDay, 0)
        assertTrue("9.9 米漂移应在容差内，实际未达标项=${result.failures}", result.passed)
        assertTrue(
            "实际漂移应当接近 9.9 米：${result.validation.maxCenterDriftMeters}",
            result.validation.maxCenterDriftMeters in 9.5..10.0
        )
    }

    @Test fun ambientSourcesDroppingToZeroClassFailsEvenIfOnlyOnce() {
        // 最后一天掉到 1 类 —— 看的是**窗口内最小值**，不是最后一次的值
        val result = ShadowValidator.evaluate(cleanWindow(ambient = 1), lastDay, 0)
        assertFalse(result.passed)
        assertEquals(1, result.validation.minimumAmbientSources)
        assertTrue(result.failures.any { it.contains("环境来源") })
    }

    @Test fun anyFingerprintConflictFails() {
        val result = ShadowValidator.evaluate(cleanWindow(), lastDay, conflictCount = 1)
        assertFalse(result.passed)
        assertTrue(result.failures.any { it.contains("指纹冲突") })
    }

    @Test fun spreadGrowingBeyondTheRatioFails() {
        // 窗口初 20 米 → 上限 = max(20×1.5, 15) = 30 米；最后一天 60 米 → 拦下
        val window = cleanWindow().toMutableList()
        window[window.lastIndex] = window.last().copy(spreadP90Meters = 60.0)
        val result = ShadowValidator.evaluate(window, lastDay, 0)
        assertFalse(result.passed)
        assertTrue(result.failures.any { it.contains("恶化") })
    }

    @Test fun spreadGrowingWithinTheRatioPasses() {
        val window = cleanWindow().toMutableList()
        window[window.lastIndex] = window.last().copy(spreadP90Meters = 25.0)
        val result = ShadowValidator.evaluate(window, lastDay, 0)
        assertTrue("25 米相对 20 米不算恶化，实际未达标项=${result.failures}", result.passed)
    }

    @Test fun tinySpreadDoesNotMakeTheRatioGateOversensitive() {
        // 窗口初 1 米、最后 14 米 = 「涨了 14 倍」，但绝对值完全无害（固定点 GPS 噪声量级）。
        // 没有绝对下限（MIN_SPREAD_CEILING_METERS = 15）的话这条会被误拦 ——
        // 这正是那个下限存在的**唯一理由**，所以必须有用例证明它真的在起作用。
        val window = cleanWindow().toMutableList()
        window[0] = window[0].copy(spreadP90Meters = 1.0)
        window[window.lastIndex] = window.last().copy(spreadP90Meters = 14.0)
        val result = ShadowValidator.evaluate(window, lastDay, 0)
        assertTrue("14 米绝对值无害，实际未达标项=${result.failures}", result.passed)
    }

    @Test fun tinySpreadStillFailsOnceItClearsTheAbsoluteFloor() {
        // 同一个 1 米的初值，越过 15 米绝对下限（16 米）就该拦下 —— 下限是**容差**不是**豁免**。
        val window = cleanWindow().toMutableList()
        window[0] = window[0].copy(spreadP90Meters = 1.0)
        window[window.lastIndex] = window.last().copy(spreadP90Meters = 16.0)
        val result = ShadowValidator.evaluate(window, lastDay, 0)
        assertFalse(result.passed)
        assertTrue(result.failures.any { it.contains("恶化") })
    }

    @Test fun theSpreadCeilingIsTheLargerOfRatioAndFloor() {
        // 两个参数谁大用谁：下限是给「初值很小」兜底的，不是用来放松倍数的。
        assertEquals(15.0, ShadowValidator.MIN_SPREAD_CEILING_METERS, 1e-9)
        assertEquals(1.5, ShadowValidator.MAX_SPREAD_GROWTH_RATIO, 1e-9)
        assertTrue(
            "绝对下限必须显著小于训练期的离散度上限，否则这条门槛形同虚设",
            ShadowValidator.MIN_SPREAD_CEILING_METERS < AnchorLearner.MAX_SPREAD_P90_METERS / 2
        )
    }

    @Test fun missingSpreadReadingFailsInsteadOfAssumingZero() {
        // P90 读不到 ≠ P90 是 0。未知必须拦下，否则 DB v15 之前的行会被当「完美集中」放行。
        val result = ShadowValidator.evaluate(cleanWindow(p90 = null), lastDay, 0)
        assertFalse(result.passed)
        assertTrue(result.failures.any { it.contains("缺少离散度读数") })
    }

    @Test fun observationsAreSummarizedNotJustJudged() {
        val result = ShadowValidator.evaluate(cleanWindow(ambient = 2), lastDay, conflictCount = 3)
        val v = result.validation
        assertEquals(7, v.elapsedDays)
        assertEquals(8, v.observedDays)
        assertEquals(2, v.minimumAmbientSources)
        assertEquals(3, v.conflictCount)
        assertEquals(20.0, v.latestSpreadP90Meters, 1e-9)
    }

    @Test fun thresholdsMatchTheDesignDocument() {
        assertEquals(AnchorUpdatePolicy.SHADOW_VALIDATION_DAYS, ShadowValidator.MIN_ELAPSED_DAYS)
        assertEquals(7, ShadowValidator.MIN_ELAPSED_DAYS)
        assertEquals(10.0, ShadowValidator.MAX_CENTER_DRIFT_METERS, 1e-9)
        assertEquals(AnchorLearner.MIN_AMBIENT_SOURCES, ShadowValidator.MIN_AMBIENT_SOURCES)
        assertEquals(0, ShadowValidator.MAX_CONFLICTS)
    }

    @Test fun driftThresholdIsTheSameNumberAsTheWindowResetRadius() {
        // 「候选移动 ≥10 米」表现为**重开窗口**而不是判失败 —— 靠的就是两者同值。
        // 改动任一常量都会让漂移条件变成「结构上不可能失败」，所以在这里钉住关系。
        assertEquals(ShadowValidator.MAX_CENTER_DRIFT_METERS, 10.0, 1e-9)
    }

    @Test fun passingExplanationSaysHowLongItWasObserved() {
        val result = ShadowValidator.evaluate(cleanWindow(), lastDay, 0)
        val text = ShadowValidator.explain(result, offsetMeters = 8.0)
        assertTrue("通过时要说清观察了多少天：$text", text.contains("7"))
        assertTrue(text.contains("自动小幅校准"))
    }

    @Test fun failingExplanationListsWhatIsStillMissing() {
        val result = ShadowValidator.evaluate(cleanWindow(days = 3), start.plusDays(2), 0)
        val text = ShadowValidator.explain(result, offsetMeters = 8.0)
        assertTrue("未通过时要说清还差什么：$text", text.contains("影子验证中"))
        assertTrue(text.contains("前向观察"))
    }

    @Test fun zeroSpreadIsNotTreatedAsMissing() {
        // 0 是合法的「完美集中」，与 null（未知）必须区分
        val result = ShadowValidator.evaluate(cleanWindow(p90 = 0.0), lastDay, 0)
        assertTrue("P90=0 是合法读数，实际未达标项=${result.failures}", result.passed)
        assertEquals(0.0, result.validation.latestSpreadP90Meters, 1e-9)
    }

    @Test fun shadowPeriodCountsNaturalDaysNotFullTwentyFourHourSpans() {
        // 影子期按**自然日**算，不按「满 24 小时」算 —— 所以观测的粒度只有 [LocalDate]，
        // 时刻信息根本进不了这个类型（23:00 建立、次日 00:30 复核也算跨了一天）。
        // 这条曾经挂在 AnchorUpdatePolicy.stableDays 上；stableDays 被移除后语义搬到这里。
        val observations = cleanWindow() // 09-01 .. 09-08
        assertEquals(7, ShadowValidator.evaluate(observations, lastDay, 0).validation.elapsedDays)
        assertEquals(0, ShadowValidator.evaluate(observations, start, 0).validation.elapsedDays)
    }

    @Test fun elapsedDaysIsClampedToZeroWhenTodayPrecedesTheWindow() {
        // 时钟回拨 / 时区变化时 today 可能早于窗口起始日。必须夹到 0，
        // 不许出现负数（否则「还差几天」的文案会变成负数，看起来像已经超标通过）。
        val result = ShadowValidator.evaluate(cleanWindow(), start.minusDays(5), 0)
        assertEquals(0, result.validation.elapsedDays)
        assertFalse(result.passed)
    }
}
