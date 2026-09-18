package com.example.worktimetracker

import com.example.worktimetracker.domain.location.AnchorUpdatePolicy
import com.example.worktimetracker.domain.location.EffectiveAnchorSource
import com.example.worktimetracker.domain.location.GeoPoint
import com.example.worktimetracker.domain.location.PlaceLearningPreference
import com.example.worktimetracker.domain.location.PlaceLearningPhase
import com.example.worktimetracker.domain.location.PlaceLearningStatusPresenter
import com.example.worktimetracker.domain.location.ShadowObservation
import com.example.worktimetracker.domain.location.ShadowValidator
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学习状态的展示（地点管理页的「最小学习状态」）。
 *
 * 这类文案最容易悄悄出错：改了一个门槛常量却忘了改文案，界面会拿着旧数字解释新行为，
 * 而没有任何测试会响。所以这里把「文案里必须出现的数」也一并钉死。
 *
 * 另一个重点是**不许说谎**：算法动作与实际取用可能不一致
 * （算法说可以自动、置信度却没到线），必须显式表达成 [PlaceLearningPhase.PENDING_APPLY]，
 * 而不是照着算法动作显示「已启用」。
 */
class PlaceLearningStatusPresenterTest {

    private val windowStart = LocalDate.of(2026, 9, 1)

    /** 造一个「每天一条、中心不动、环境 2 类」的干净窗口。 */
    private fun window(
        days: Int,
        todayOffset: Int,
        spread: Double? = 12.0
    ) = ShadowValidator.evaluate(
        observations = (0 until days).map {
            ShadowObservation(
                day = windowStart.plusDays(it.toLong()),
                center = GeoPoint(31.0, 121.0),
                ambientSources = 2,
                spreadP90Meters = spread
            )
        },
        today = windowStart.plusDays(todayOffset.toLong()),
        conflictCount = 0
    )

    /** 跨 7 个自然日（共 8 天）连续干净观测 → 通过。 */
    private val passed = window(days = 8, todayOffset = 7)

    /** 只跨 2 个自然日 → 观察中，不通过。 */
    private val inProgress = window(days = 3, todayOffset = 2)

    /**
     * 窗口内中心逐步漂走 → 漂移超限，不通过。
     *
     * ⚠️ 漂移是相对**窗口首个**中心算的（`ShadowValidation.maxCenterDriftMeters`），
     * 不是相对用户配置锚点 —— 所以「所有观测都用同一个偏移中心」是**零漂移**，
     * 那种夹具根本触发不了这条门槛，测出来的是个假前提。
     */
    private val drifted = ShadowValidator.evaluate(
        observations = (0 until 8).map { step ->
            ShadowObservation(
                day = windowStart.plusDays(step.toLong()),
                // 每天挪 40 米，到第 8 天已距首日 280 米（门槛 10 米）
                center = GeoPoint(31.0 + step * 40.0 / 111_200.0, 121.0),
                ambientSources = 2,
                spreadP90Meters = 12.0
            )
        },
        today = windowStart.plusDays(7),
        conflictCount = 0
    )

    private fun input(
        preference: PlaceLearningPreference? = null,
        hasLearnedAnchor: Boolean = true,
        offset: Double? = 20.0,
        samples: Int = 24,
        days: Int = 6,
        shadow: ShadowValidator.Result? = inProgress,
        source: EffectiveAnchorSource = EffectiveAnchorSource.USER_CONFIGURED
    ) = PlaceLearningStatusPresenter.Input(
        placeId = 1L,
        preference = preference,
        hasLearnedAnchor = hasLearnedAnchor,
        candidateOffsetMeters = offset,
        trainingSampleCount = samples,
        trainingDistinctDays = days,
        shadow = shadow,
        effectiveAnchorSource = source
    )

    private fun disabled() = PlaceLearningPreference(placeId = 1L, autoApplyEnabled = false, updatedAt = 0L)

    // ------------------------------------------------------------ 阶段判定

    @Test fun withoutAnyWindowItIsStillLearning() {
        val status = PlaceLearningStatusPresenter.present(
            input(shadow = null, offset = null, samples = 0, days = 0, hasLearnedAnchor = false)
        )
        assertEquals(PlaceLearningPhase.NOT_STARTED, status.phase)
        assertTrue("没有窗口时一律用用户设置", status.usesConfiguredAnchor)
        assertFalse(status.canDisable)
        assertFalse(status.canReEnable)
    }

    @Test fun anIncompleteWindowIsShownAsShadowing() {
        val status = PlaceLearningStatusPresenter.present(input())
        assertEquals(PlaceLearningPhase.SHADOW, status.phase)
        assertTrue(status.usesConfiguredAnchor)
        assertTrue("影子档也必须能提前关掉", status.canDisable)
        assertFalse(status.canReEnable)
        assertTrue("要说清还差什么", status.failures.isNotEmpty())
    }

    @Test fun aDriftingCandidateIsShownAsShadowingWithTheReason() {
        val status = PlaceLearningStatusPresenter.present(input(shadow = drifted))
        assertEquals(PlaceLearningPhase.SHADOW, status.phase)
        assertTrue(
            "失败项里要能看到漂移，实际=${status.failures}",
            status.failures.any { it.contains("漂移") }
        )
    }

    @Test fun over100mAwaitsTheUserEvenWhenValidationPassed() {
        val status = PlaceLearningStatusPresenter.present(input(shadow = passed, offset = 120.0))
        assertEquals(PlaceLearningPhase.NEEDS_CONFIRM, status.phase)
        assertTrue(status.usesConfiguredAnchor)
    }

    @Test fun exactly100mIsNotYetAUserQuestion() {
        // 边界属于影子档而不是「等你确认」—— 阈值写法是 >，不能写成 >=。
        // 与 AnchorUpdatePolicyTest.exactly100mIsNotYetAUserQuestion 同一口径。
        // 注意这时算法动作也是 SHADOW（不是 AUTO_SMOOTH）：
        // 偏移 30~100 米即便验证通过，也仍停在影子档，不许自动生效。
        val status = PlaceLearningStatusPresenter.present(input(shadow = passed, offset = 100.0))
        assertEquals(PlaceLearningPhase.SHADOW, status.phase)
        assertTrue(status.usesConfiguredAnchor)
    }

    @Test fun aPassedValidationWithAnOffsetBeyondTheAutoBandStaysInShadow() {
        // 31 米：刚过自动档（30 米），验证也通过 —— 结局仍是「影子，不动判定」。
        // 这条守的是「自动档很窄」这件事本身：门槛不小心放宽会立刻表现在这里。
        val status = PlaceLearningStatusPresenter.present(input(shadow = passed, offset = 31.0))
        assertEquals(PlaceLearningPhase.SHADOW, status.phase)
    }

    @Test fun thirtyMetresIsStillInsideTheAutoBand() {
        // 边界归允许侧：30 米仍在自动档内（与 ≤30 的写法一致）。
        val status = PlaceLearningStatusPresenter.present(
            input(shadow = passed, offset = 30.0, source = EffectiveAnchorSource.USER_CONFIGURED)
        )
        assertEquals(PlaceLearningPhase.PENDING_APPLY, status.phase)
    }

    @Test fun aPassedValidationThatIsNotYetTakenIsNeverCalledEnabled() {
        // 算法说可以自动（AUTO_SMOOTH）、但取用层没拿它（置信度/写入时序）→
        // 必须显示「验证已通过，尚未生效」，不许显示「已启用」。
        val status = PlaceLearningStatusPresenter.present(
            input(shadow = passed, offset = 20.0, source = EffectiveAnchorSource.USER_CONFIGURED)
        )
        assertEquals(PlaceLearningPhase.PENDING_APPLY, status.phase)
        assertTrue(status.usesConfiguredAnchor)
        // 「停用」在这里仍然给：用户完全可以在它生效**之前**就关掉，
        // 不必等它先动过一次判定再去回退 —— 那期间判定已经被悄悄改过了。
        assertTrue(status.canDisable)
        assertFalse(status.canReEnable)
    }

    @Test fun anActuallyTakenAnchorIsShownAsEnabled() {
        val status = PlaceLearningStatusPresenter.present(
            input(shadow = passed, offset = 20.0, source = EffectiveAnchorSource.LEARNED)
        )
        assertEquals(PlaceLearningPhase.AUTO_APPLIED, status.phase)
        assertFalse(status.usesConfiguredAnchor)
        assertTrue(status.canDisable)
        assertFalse(status.canReEnable)
    }

    @Test fun theEffectiveSourceWinsOverTheAlgorithmAction() {
        // 算法可能已经不再认为该走自动（窗口刚重开），但模型这一刻仍被取用。
        // 此时界面必须如实说「正在用学习锚点」，否则用户会以为它已经停了。
        val status = PlaceLearningStatusPresenter.present(
            input(shadow = inProgress, offset = 20.0, source = EffectiveAnchorSource.LEARNED)
        )
        assertEquals(PlaceLearningPhase.AUTO_APPLIED, status.phase)
        assertFalse(status.usesConfiguredAnchor)
    }

    // ------------------------------------------------------------ 停用态

    @Test fun aPausedPlaceSaysSoAndOffersReEnabling() {
        val status = PlaceLearningStatusPresenter.present(
            input(preference = disabled(), shadow = passed, source = EffectiveAnchorSource.USER_CONFIGURED)
        )
        assertEquals(PlaceLearningPhase.PAUSED, status.phase)
        assertTrue(status.usesConfiguredAnchor)
        assertFalse("已经停了，不再给「停用」按钮", status.canDisable)
        assertTrue(status.canReEnable)
    }

    @Test fun pausingOverridesEvenTheTakenAnchor() {
        // 界面显示「已停用」而判定还在用学习锚点，这种组合不允许出现 ——
        // 所以停用态先于来源判定返回。
        val status = PlaceLearningStatusPresenter.present(
            input(preference = disabled(), shadow = passed, source = EffectiveAnchorSource.LEARNED)
        )
        assertEquals(PlaceLearningPhase.PAUSED, status.phase)
    }

    @Test fun anEnabledPreferenceDoesNotChangeThePhase() {
        val enabled = PlaceLearningPreference(placeId = 1L, autoApplyEnabled = true, updatedAt = 0L)
        val withRow = PlaceLearningStatusPresenter.present(input(preference = enabled, shadow = passed, offset = 20.0))
        val withoutRow = PlaceLearningStatusPresenter.present(input(preference = null, shadow = passed, offset = 20.0))
        assertEquals(withoutRow.phase, withRow.phase)
    }

    // -------------------------------------------------- 必须展示的六项事实

    @Test fun theShadowViewCarriesEveryRequiredReading() {
        val status = PlaceLearningStatusPresenter.present(input(shadow = inProgress, offset = 20.0))
        val labels = status.facts.map { it.label }
        listOf("训练样本", "前向验证", "候选偏移", "中心漂移", "环境来源", "离散度 P90").forEach {
            assertTrue("缺少「$it」，实际=$labels", labels.contains(it))
        }
        // 用户最需要知道的一句话：现在判定在用谁。
        assertTrue(status.usesConfiguredAnchor)
    }

    @Test fun theProgressFactUsesTheSameWindowLengthAsTheThreshold() {
        val status = PlaceLearningStatusPresenter.present(input(shadow = inProgress))
        val progress = status.facts.first { it.label == "前向验证" }.value
        assertTrue(
            "天数必须与门槛同源，实际=$progress",
            progress.contains("/${ShadowValidator.MIN_ELAPSED_DAYS} 天")
        )
    }

    @Test fun aWindowThatOnlyHasTodayNeverClaimsEveryDayIsCovered() {
        // 真机实测（2026-09-17）出现过「0/7 天 · 每天都有样本」：
        // 算术上成立（窗口里唯一那天确实有样本），但读起来像在报好消息，
        // 实际含义是「刚开始观察」。技术正确但误导的文案要改掉。
        val justStarted = window(days = 1, todayOffset = 0)
        val status = PlaceLearningStatusPresenter.present(input(shadow = justStarted, offset = 7.0))
        val progress = status.facts.first { it.label == "前向验证" }.value
        assertFalse("不许说「每天都有样本」，实际=$progress", progress.contains("每天都有样本"))
        assertTrue("要说清是刚开始，实际=$progress", progress.contains("今天开始观察"))
    }

    @Test fun theDriftAndOffsetFactsQuoteTheirOwnLimits() {
        val status = PlaceLearningStatusPresenter.present(input(shadow = inProgress, offset = 20.0))
        val drift = status.facts.first { it.label == "中心漂移" }.value
        val offset = status.facts.first { it.label == "候选偏移" }.value
        assertTrue("漂移文案要带上限，实际=$drift", drift.contains(ShadowValidator.MAX_CENTER_DRIFT_METERS.toInt().toString()))
        assertTrue(
            "偏移文案要带自动档，实际=$offset",
            offset.contains(AnchorUpdatePolicy.AUTO_SMOOTH_MAX_OFFSET_METERS.toInt().toString())
        )
    }

    @Test fun aMissingSpreadReadingIsReportedAsUnknownNotAsZero() {
        // v15 之前的候选行没有离散度。null = 未知，0 米 = 「完美集中」，两者相反。
        // 把未知显示成 0 米，读这条文案的人会得到完全错误的结论。
        val status = PlaceLearningStatusPresenter.present(
            input(shadow = window(days = 8, todayOffset = 7, spread = null))
        )
        val text = status.facts.first { it.label == "离散度 P90" }.value
        assertFalse("不允许出现 0 m：实际=$text", text.startsWith("0 m"))
        assertTrue("要明说没有读数，实际=$text", text.contains("无读数"))
    }

    @Test fun anEmptyWindowReportsUnknownSpreadToo() {
        val empty = ShadowValidator.evaluate(emptyList(), windowStart, conflictCount = 0)
        assertNull("判定器本身就不该把未知压成 0", empty.validation.latestSpreadP90Meters)
        val status = PlaceLearningStatusPresenter.present(input(shadow = empty, offset = 20.0))
        val text = status.facts.first { it.label == "离散度 P90" }.value
        assertTrue("实际=$text", text.contains("无读数"))
    }

    @Test fun theTrainingNumbersComeStraightFromTheInput() {
        val status = PlaceLearningStatusPresenter.present(input(samples = 37, days = 9))
        val value = status.facts.first { it.label == "训练样本" }.value
        assertTrue("要看得到实测样本数，实际=$value", value.contains("37"))
        assertTrue("要看得到跨天数，实际=$value", value.contains("9"))
    }

    @Test fun startingFromScratchSaysSoInsteadOfShowingZeros() {
        val status = PlaceLearningStatusPresenter.present(
            input(shadow = null, offset = null, samples = 0, days = 0, hasLearnedAnchor = false)
        )
        assertEquals("还没有", status.facts.first { it.label == "训练样本" }.value)
    }

    @Test fun thePausedViewStillShowsTheLearningData() {
        // 「停用不删数据」：停用态也必须能看到训练与验证进度，
        // 否则用户会以为按一下暂停就把几周的观察清掉了。
        val status = PlaceLearningStatusPresenter.present(input(preference = disabled(), shadow = inProgress))
        val labels = status.facts.map { it.label }
        assertTrue(labels.contains("训练样本"))
        assertTrue(labels.contains("前向验证"))
        assertTrue(status.facts.any { it.value.contains("已暂停") })
    }
}
