package com.example.worktimetracker

import com.example.worktimetracker.domain.location.AnchorCandidateStatus
import com.example.worktimetracker.domain.location.AnchorUpdateAction
import com.example.worktimetracker.domain.location.AnchorUpdatePolicy
import com.example.worktimetracker.domain.location.GeoPoint
import com.example.worktimetracker.domain.location.ShadowObservation
import com.example.worktimetracker.domain.location.ShadowValidator
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锚点候选的判定与平滑（方案 §三.2）。
 *
 * 这组测试守的是**不对称代价**：漏判 → 用户被自己改的位置打脸；误判 → 位置永远飘。
 * 所以「绝不自动大改」和「影子不通过绝不动」这两条被单独钉死。
 *
 * ⚠️ 自 v9.1 起 `decide` / `explain` 的第三参数从「稳定天数」换成了
 * [ShadowValidator.Result]。这不只是签名变化：**时间够了不再等于放行**，
 * 中心漂移、断档、环境来源变弱、指纹冲突、离散度恶化任何一条不满足都不许生效。
 */
class AnchorUpdatePolicyTest {

    // ------------------------------------------------------------ 影子验证快照夹具
    //
    // 两种快照一律**由 ShadowValidator 真实算出来**，不手搓 `ShadowValidation` 数据类 ——
    // 手搓有可能造出现实中不存在的结果（例如 failures 为空但天数为 0），
    // 那测试就成了在验证一个假前提。用真判定器产生的 Result，夹具与线上永远同源。

    private val windowStart = LocalDate.of(2026, 9, 1)

    /** 跨 7 个自然日（共 8 天）连续干净观测 → 影子验证**通过**。 */
    private val passedShadow = window(days = 8, todayOffset = 7)

    /** 空窗口 → 影子验证**不通过**（还没开始观察）。 */
    private val failedShadow = ShadowValidator.evaluate(
        observations = emptyList(),
        today = windowStart,
        conflictCount = 0
    )

    /** 只跨 2 个自然日 → 不通过，且失败项里带「N/7 天」进度（用于文案断言）。 */
    private val inProgressShadow = window(days = 3, todayOffset = 2)

    private fun window(days: Int, todayOffset: Int) = ShadowValidator.evaluate(
        observations = (0 until days).map {
            ShadowObservation(
                day = windowStart.plusDays(it.toLong()),
                center = GeoPoint(31.0, 121.0),
                ambientSources = 2,
                spreadP90Meters = 12.0
            )
        },
        today = windowStart.plusDays(todayOffset.toLong()),
        conflictCount = 0
    )

    // ---------------------------------------------------------------------- 判定

    @Test fun offsetOver100mAlwaysAsksTheUserEvenAfterValidationPassed() {
        assertEquals(
            AnchorUpdateAction.NEEDS_USER_CONFIRM,
            AnchorUpdatePolicy.decide(offsetMeters = 101.0, shadow = passedShadow)
        )
    }

    @Test fun exactly100mIsNotYetAUserQuestion() {
        // 边界属于「影子」而不是「请用户确认」—— 阈值写法是 >，不能写成 >=
        assertEquals(
            AnchorUpdateAction.SHADOW,
            AnchorUpdatePolicy.decide(offsetMeters = 100.0, shadow = passedShadow)
        )
    }

    @Test fun smallOffsetStillShadowsWhileValidationIsIncomplete() {
        assertEquals(
            AnchorUpdateAction.SHADOW,
            AnchorUpdatePolicy.decide(offsetMeters = 5.0, shadow = inProgressShadow)
        )
    }

    @Test fun smallOffsetAutoSmoothsOnlyAfterValidationPasses() {
        assertEquals(
            AnchorUpdateAction.AUTO_SMOOTH,
            AnchorUpdatePolicy.decide(offsetMeters = 5.0, shadow = passedShadow)
        )
    }

    @Test fun midRangeOffsetKeepsShadowingForever() {
        // 60 米：够不到自动档，也够不到请用户确认档 → 一直影子，绝不自动生效
        assertEquals(
            AnchorUpdateAction.SHADOW,
            AnchorUpdatePolicy.decide(offsetMeters = 60.0, shadow = passedShadow)
        )
    }

    @Test fun exactly30mIsWithinTheAutoSmoothBand() {
        assertEquals(
            AnchorUpdateAction.AUTO_SMOOTH,
            AnchorUpdatePolicy.decide(offsetMeters = 30.0, shadow = passedShadow)
        )
    }

    @Test fun evenATinyOffsetNeverAutoSmoothsOnAFailedShadow() {
        // v9.1 的核心语义：偏差再小，只要影子验证没过就一律停在影子档。
        // 没有这条，新增的六个条件就等于白加 —— 因为「小偏移」是绝大多数情况。
        assertEquals(
            AnchorUpdateAction.SHADOW,
            AnchorUpdatePolicy.decide(offsetMeters = 0.5, shadow = failedShadow)
        )
    }

    @Test fun offsetCheckOutranksValidationBecauseTooFarIsNotACalibrationProblem() {
        // 顺序不能调：>100 米的「候选」不是校准误差，是搬家/换公司，必须直接问用户，
        // 不能因为影子验证还没跑完就降级成「继续观察」——那会把一个显然的问题藏起来。
        assertEquals(
            AnchorUpdateAction.NEEDS_USER_CONFIRM,
            AnchorUpdatePolicy.decide(offsetMeters = 260.0, shadow = failedShadow)
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

    @Test fun onlyAutoSmoothProducesAnAppliedStatus() {
        // 「哪些动作允许改判定」只有一个答案。以后新增动作时这条会拦住误加。
        val applied = AnchorUpdateAction.entries.filter {
            AnchorUpdatePolicy.statusOf(it) == AnchorCandidateStatus.AUTO_APPLIED
        }
        assertEquals(listOf(AnchorUpdateAction.AUTO_SMOOTH), applied)
    }

    // ---------------------------------------------------------------------- 平滑

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

    // -------------------------------------------------------------------- 置信度

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

    // ---------------------------------------------------------------------- 文案

    @Test fun explanationTellsTheUserWhatToDo() {
        val shadow = AnchorUpdatePolicy.explain(AnchorUpdateAction.SHADOW, 45.0, inProgressShadow)
        assertTrue("影子文案要说明不动判定：$shadow", shadow.contains("影子"))
        assertTrue("影子文案要带偏移：$shadow", shadow.contains("45"))
        assertTrue("影子文案要带天数进度：$shadow", shadow.contains("7"))

        val ask = AnchorUpdatePolicy.explain(AnchorUpdateAction.NEEDS_USER_CONFIRM, 260.0, passedShadow)
        assertTrue("确诊文案要给出行动指引：$ask", ask.contains("手动更新地点"))

        val auto = AnchorUpdatePolicy.explain(AnchorUpdateAction.AUTO_SMOOTH, 8.0, passedShadow)
        assertTrue("自动文案要说清是自动小幅校准：$auto", auto.contains("自动小幅校准"))
    }

    @Test fun shadowExplanationIsDelegatedNotDuplicated() {
        // 影子档文案只有一处出处（ShadowValidator），本类只转发。
        // 一旦有人把文案复制到这边，两份就会各自漂移 —— 用户看到的原因与判据不再对应。
        assertEquals(
            ShadowValidator.explain(inProgressShadow, offsetMeters = 45.0),
            AnchorUpdatePolicy.explain(AnchorUpdateAction.SHADOW, 45.0, inProgressShadow)
        )
    }

    // ---------------------------------------------------------------------- 阈值

    @Test fun thresholdsMatchTheDesignDocument() {
        assertEquals(30.0, AnchorUpdatePolicy.AUTO_SMOOTH_MAX_OFFSET_METERS, 1e-9)
        assertEquals(100.0, AnchorUpdatePolicy.SHADOW_MAX_OFFSET_METERS, 1e-9)
        assertEquals(7L, AnchorUpdatePolicy.SHADOW_VALIDATION_DAYS)
        assertTrue(
            "影子上限必须大于自动上限",
            AnchorUpdatePolicy.SHADOW_MAX_OFFSET_METERS > AnchorUpdatePolicy.AUTO_SMOOTH_MAX_OFFSET_METERS
        )
    }

    @Test fun theShadowPeriodThresholdHasASingleOwner() {
        // 影子天数门槛只允许有一个来源（AnchorUpdatePolicy），ShadowValidator 引用它。
        // 两处各写一个 7 的话，改一处就会出现「验证器说过了、策略说没过」。
        assertEquals(
            AnchorUpdatePolicy.SHADOW_VALIDATION_DAYS,
            ShadowValidator.MIN_ELAPSED_DAYS.toLong()
        )
    }
}
