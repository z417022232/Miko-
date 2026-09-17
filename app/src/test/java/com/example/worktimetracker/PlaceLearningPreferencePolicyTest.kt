package com.example.worktimetracker

import com.example.worktimetracker.domain.location.AnchorUpdatePolicy
import com.example.worktimetracker.domain.location.PlaceLearningPreference
import com.example.worktimetracker.domain.location.PlaceLearningPreferencePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学习校准的**停用 / 重新开启**决策（DB v16）。
 *
 * 这组测试守的是「三层语义不许混」：
 * - 模型是否有效（`learning_model_meta.status`）；
 * - 算法是否通过（`learned_place_models.autoApplied`）；
 * - 用户是否允许用（`place_learning_preferences.autoApplyEnabled`）。
 *
 * 混层的具体后果是**谎话**：用户按了暂停，模型却被标成 RETIRED ——
 * 之后无法区分「模型坏了」和「用户关了」，而这两件事该做的处理完全不同。
 * 所以「停用**不许**动模型」被单独钉成一条测试。
 */
class PlaceLearningPreferencePolicyTest {

    private val now = 1_700_000_000_000L

    // ------------------------------------------------------------------ 停用

    @Test fun disablingOnlyWritesThePreference() {
        val effect = PlaceLearningPreferencePolicy.disable(placeId = 7L, now = now)
        assertEquals(7L, effect.preference.placeId)
        assertFalse(effect.preference.autoApplyEnabled)
        assertEquals(now, effect.preference.updatedAt)
    }

    @Test fun disablingNeverTouchesTheModelState() {
        // 用户偏好没有资格改写「算法是否通过」这个事实。
        // null = 不要动模型；写成 false 就会丢掉「其实已经验证通过」这个事实。
        assertNull(
            "停用必须不动 autoApplied；否则「用户关了」会被记成「算法没通过」",
            PlaceLearningPreferencePolicy.disable(placeId = 1L, now = now).modelAutoAppliedAfter
        )
    }

    @Test fun disablingDoesNotReopenTheShadowWindow() {
        // 停用只是「不用它」，没有理由作废已经攒下的前向观察 ——
        // 作废了的话，用户停用一下就白白丢掉几天观察，而这是用户没要求的代价。
        assertFalse(PlaceLearningPreferencePolicy.disable(placeId = 1L, now = now).reopenShadowWindow)
    }

    @Test fun disablingExplainsItself() {
        assertTrue(PlaceLearningPreferencePolicy.disable(1L, now).explanation.isNotBlank())
    }

    // ------------------------------------------------------------------ 开启

    @Test fun enablingWritesThePreference() {
        val effect = PlaceLearningPreferencePolicy.enable(placeId = 7L, now = now)
        assertTrue(effect.preference.autoApplyEnabled)
        assertEquals(now, effect.preference.updatedAt)
    }

    @Test fun enablingRevokesTheTakeableState() {
        // 开启时必须吊销 autoApplied，否则下一次学习前它立刻生效，重新验证等于没做。
        assertEquals(
            false,
            PlaceLearningPreferencePolicy.enable(placeId = 1L, now = now).modelAutoAppliedAfter
        )
    }

    @Test fun enablingReopensTheForwardShadowWindow() {
        // 只吊销 autoApplied 还不够：窗口里已经攒够 7 天，
        // 同一轮学习会立刻重新判通过 —— 必须连窗口一起重开。
        assertTrue(PlaceLearningPreferencePolicy.enable(placeId = 1L, now = now).reopenShadowWindow)
    }

    @Test fun theTwoRevocationStepsAreBothRequiredAndNeitherIsRedundant() {
        // 这条测试的意义：证明上面两条**都不是多余的**。
        // 只要少任何一条，就构造得出「开启后立刻生效」的输入：
        //  - 少了「吊销 autoApplied」→ 模型带着旧的 autoApplied=true 直接生效；
        //  - 少了「重开窗口」     → 窗口仍是 8 天，下一轮学习立刻判通过。
        val effect = PlaceLearningPreferencePolicy.enable(placeId = 1L, now = now)
        assertEquals("少了它就立刻生效", false, effect.modelAutoAppliedAfter)
        assertTrue("少了它下一轮学习立刻判通过", effect.reopenShadowWindow)
    }

    @Test fun enablingMentionsTheReValidationWindowLength() {
        val text = PlaceLearningPreferencePolicy.enable(1L, now).explanation
        assertTrue(
            "文案要写明天数，且必须与门槛同源；实际=$text",
            text.contains(AnchorUpdatePolicy.SHADOW_VALIDATION_DAYS.toString())
        )
    }

    // -------------------------------------------------------------- 缺行语义

    @Test fun aMissingRowMeansEnabled() {
        val preference = PlaceLearningPreferencePolicy.of(placeId = 3L, autoApplyEnabled = null, updatedAt = 0L)
        assertTrue(preference.autoApplyEnabled)
        assertEquals(PlaceLearningPreference.DEFAULT_AUTO_APPLY_ENABLED, preference.autoApplyEnabled)
    }

    @Test fun anExplicitRowIsCarriedThroughVerbatim() {
        assertFalse(PlaceLearningPreferencePolicy.of(1L, autoApplyEnabled = false, updatedAt = 9L).autoApplyEnabled)
        assertTrue(PlaceLearningPreferencePolicy.of(1L, autoApplyEnabled = true, updatedAt = 9L).autoApplyEnabled)
    }

    @Test fun disableThenEnableIsNotTheSameAsNeverHavingDisabled() {
        // 停用 → 开启 之后必须重新验证，这是刻意的：停用期间那段观察不连续。
        val disabled = PlaceLearningPreferencePolicy.disable(1L, now)
        val enabled = PlaceLearningPreferencePolicy.enable(1L, now + 1000)
        assertFalse(disabled.preference.autoApplyEnabled)
        assertTrue(enabled.preference.autoApplyEnabled)
        assertNotNull("重新开启仍要吊销可生效状态", enabled.modelAutoAppliedAfter)
        assertTrue(enabled.reopenShadowWindow)
    }
}
