package com.example.worktimetracker

import com.example.worktimetracker.domain.location.AnchorCandidateStatus
import com.example.worktimetracker.domain.location.AnchorUpdateAction
import com.example.worktimetracker.domain.location.AnchorUpdatePolicy
import com.example.worktimetracker.domain.location.GeoPoint
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锚点候选的判定与平滑（方案 §三.2）。
 *
 * 这组测试守的是**不对称代价**：漏判 → 用户被自己改的位置打脸；误判 → 位置永远飘。
 * 所以「绝不自动大改」和「熬不够 7 天绝不动」这两条被单独钉死。
 */
class AnchorUpdatePolicyTest {

    @Test fun offsetOver100mAlwaysAsksTheUserEvenAfterLongStability() {
        assertEquals(
            AnchorUpdateAction.NEEDS_USER_CONFIRM,
            AnchorUpdatePolicy.decide(offsetMeters = 101.0, candidateStableDays = 365)
        )
    }

    @Test fun exactly100mIsNotYetAUserQuestion() {
        // 边界属于「影子」而不是「请用户确认」—— 阈值写法是 >，不能写成 >=
        assertEquals(
            AnchorUpdateAction.SHADOW,
            AnchorUpdatePolicy.decide(offsetMeters = 100.0, candidateStableDays = 30)
        )
    }

    @Test fun smallOffsetStillShadowsBeforeValidationPeriod() {
        assertEquals(
            AnchorUpdateAction.SHADOW,
            AnchorUpdatePolicy.decide(offsetMeters = 5.0, candidateStableDays = 3)
        )
    }

    @Test fun smallOffsetAutoSmoothsOnlyAfterSevenDays() {
        assertEquals(
            AnchorUpdateAction.AUTO_SMOOTH,
            AnchorUpdatePolicy.decide(offsetMeters = 5.0, candidateStableDays = 7)
        )
    }

    @Test fun midRangeOffsetKeepsShadowingForever() {
        // 60 米：够不到自动档，也够不到请用户确认档 → 一直影子，绝不自动生效
        assertEquals(
            AnchorUpdateAction.SHADOW,
            AnchorUpdatePolicy.decide(offsetMeters = 60.0, candidateStableDays = 365)
        )
    }

    @Test fun exactly30mIsWithinTheAutoSmoothBand() {
        assertEquals(
            AnchorUpdateAction.AUTO_SMOOTH,
            AnchorUpdatePolicy.decide(offsetMeters = 30.0, candidateStableDays = 7)
        )
    }

    @Test fun everyActionMapsToAConcreteStatus() {
        assertEquals(AnchorCandidateStatus.AUTO_APPLIED, AnchorUpdatePolicy.statusOf(AnchorUpdateAction.AUTO_SMOOTH))
        assertEquals(AnchorCandidateStatus.SHADOW, AnchorUpdatePolicy.statusOf(AnchorUpdateAction.SHADOW))
        assertEquals(
            AnchorCandidateStatus.NEEDS_USER_CONFIRM,
            AnchorUpdatePolicy.statusOf(AnchorUpdateAction.NEEDS_USER_CONFIRM)
        )
        assertEquals(AnchorCandidateStatus.REJECTED, AnchorUpdatePolicy.statusOf(AnchorUpdateAction.REJECTED))
    }

    @Test fun smoothingNeverReplacesTheAnchorOutright() {
        val old = GeoPoint(31.0, 121.0)
        val candidate = GeoPoint(32.0, 122.0)
        val smoothed = AnchorUpdatePolicy.smooth(old, candidate)
        assertEquals(0.8, AnchorUpdatePolicy.SMOOTH_OLD_WEIGHT, 1e-9)
        assertEquals(0.2, AnchorUpdatePolicy.SMOOTH_NEW_WEIGHT, 1e-9)
        assertEquals(31.2, smoothed.latitude, 1e-9)
        assertEquals(121.2, smoothed.longitude, 1e-9)
        // 结果必须更靠近老锚点，否则「小幅平滑」就名不副实
        assertTrue(kotlin.math.abs(smoothed.latitude - old.latitude) < kotlin.math.abs(smoothed.latitude - candidate.latitude))
    }

    @Test fun smoothingWithZeroWeightKeepsTheOldAnchor() {
        val old = GeoPoint(31.0, 121.0)
        val smoothed = AnchorUpdatePolicy.smooth(old, GeoPoint(32.0, 122.0), newWeight = 0.0)
        assertEquals(31.0, smoothed.latitude, 1e-9)
        assertEquals(121.0, smoothed.longitude, 1e-9)
    }

    @Test fun confidenceStaysInsideTheUnitRangeForAbsurdInputs() {
        val lo = AnchorUpdatePolicy.confidence(sampleCount = -5, distinctDays = -1, ambientSourceCount = 0, offsetMeters = 0.0)
        val hi = AnchorUpdatePolicy.confidence(sampleCount = 9999, distinctDays = 9999, ambientSourceCount = 99, offsetMeters = 0.0)
        assertTrue("置信度不得为负：$lo", lo >= 0.0)
        assertTrue("置信度不得大于 1：$hi", hi <= 1.0)
    }

    @Test fun moreSamplesAndDaysRaiseConfidence() {
        val thin = AnchorUpdatePolicy.confidence(sampleCount = 12, distinctDays = 6, ambientSourceCount = 2, offsetMeters = 0.0)
        val fat = AnchorUpdatePolicy.confidence(sampleCount = 30, distinctDays = 10, ambientSourceCount = 3, offsetMeters = 0.0)
        assertTrue("样本越多置信度越高（$fat 应大于 $thin）", fat > thin)
    }

    @Test fun biggerOffsetLowersConfidence() {
        val near = AnchorUpdatePolicy.confidence(sampleCount = 30, distinctDays = 10, ambientSourceCount = 3, offsetMeters = 0.0)
        val far = AnchorUpdatePolicy.confidence(sampleCount = 30, distinctDays = 10, ambientSourceCount = 3, offsetMeters = 90.0)
        assertTrue("大偏移绝不能给高置信（$far 应小于 $near）", far < near)
    }

    @Test fun stableDaysCountsCalendarDaysNotFullDays() {
        val zone = ZoneId.of("Asia/Shanghai")
        val first = LocalDateTime.of(2026, 9, 1, 23, 0).atZone(zone).toInstant().toEpochMilli()
        val now = LocalDateTime.of(2026, 9, 8, 0, 30).atZone(zone).toInstant().toEpochMilli()
        // 实耗不到 7 个 24 小时，但跨了 7 个自然日 —— 影子期按自然日算
        assertEquals(7L, AnchorUpdatePolicy.stableDays(first, now, zone))
    }

    @Test fun stableDaysIsZeroWhenThereIsNoElapsedDay() {
        val zone = ZoneId.of("Asia/Shanghai")
        val same = LocalDateTime.of(2026, 9, 8, 12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(0L, AnchorUpdatePolicy.stableDays(same, same, zone))
        assertEquals(0L, AnchorUpdatePolicy.stableDays(same, same - 60_000L, zone))
    }

    @Test fun stableDaysBetweenCoercesNegativeToZero() {
        assertEquals(0L, AnchorUpdatePolicy.stableDaysBetween(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 1)))
        assertEquals(9L, AnchorUpdatePolicy.stableDaysBetween(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 10)))
    }

    @Test fun explanationTellsTheUserWhatToDo() {
        val shadow = AnchorUpdatePolicy.explain(AnchorUpdateAction.SHADOW, 45.0, 3)
        assertTrue("影子文案要说明不动判定：$shadow", shadow.contains("影子"))
        assertTrue("影子文案要带偏移：$shadow", shadow.contains("45"))
        assertTrue("影子文案要带天数进度：$shadow", shadow.contains("7"))

        val ask = AnchorUpdatePolicy.explain(AnchorUpdateAction.NEEDS_USER_CONFIRM, 260.0, 30)
        assertTrue("确诊文案要给出行动指引：$ask", ask.contains("手动更新地点"))

        val auto = AnchorUpdatePolicy.explain(AnchorUpdateAction.AUTO_SMOOTH, 8.0, 9)
        assertTrue("自动文案要说清是自动小幅校准：$auto", auto.contains("自动小幅校准"))
    }

    @Test fun thresholdsMatchTheDesignDocument() {
        assertEquals(30.0, AnchorUpdatePolicy.AUTO_SMOOTH_MAX_OFFSET_METERS, 1e-9)
        assertEquals(100.0, AnchorUpdatePolicy.SHADOW_MAX_OFFSET_METERS, 1e-9)
        assertEquals(7L, AnchorUpdatePolicy.SHADOW_VALIDATION_DAYS)
        assertTrue(
            "影子上限必须大于自动上限",
            AnchorUpdatePolicy.SHADOW_MAX_OFFSET_METERS > AnchorUpdatePolicy.AUTO_SMOOTH_MAX_OFFSET_METERS
        )
    }
}
