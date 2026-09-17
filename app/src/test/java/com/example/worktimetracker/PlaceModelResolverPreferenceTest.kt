package com.example.worktimetracker

import com.example.worktimetracker.domain.location.EffectiveAnchorSource
import com.example.worktimetracker.domain.location.GeoPoint
import com.example.worktimetracker.domain.location.LearnedPlaceModel
import com.example.worktimetracker.domain.location.PlaceLearningPreference
import com.example.worktimetracker.domain.location.PlaceModelResolver
import com.example.worktimetracker.domain.location.PlaceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 用户偏好（DB v16）在**取用层**的表现 —— 停用是「取用时挡掉」，不是「改模型状态」。
 *
 * 这一层是「用户偏好 / 算法事实 / 模型状态」三层分离的落点：
 * 停用**只**影响这里，`learned_place_models` 一个字节都不改。
 * 于是「停用 → 重新开启」不会因为中途重训而漂移 ——
 * 模型状态自始至终是算法自己的账，用户偏好只在取用时参与。
 */
class PlaceModelResolverPreferenceTest {

    private val configured = GeoPoint(31.0000, 121.0000)

    /** 纬度方向挪 [meters] 米。 */
    private fun lat(meters: Double) = GeoPoint(31.0000 + meters / 111_200.0, 121.0000)

    private fun model(
        learned: GeoPoint? = lat(5.0),
        configuredAnchor: GeoPoint? = configured,
        confidence: Double = 0.90,
        autoApplied: Boolean = true
    ) = LearnedPlaceModel(
        placeId = 1L,
        type = PlaceType.WORK,
        configuredAnchor = configuredAnchor,
        learnedAnchor = learned,
        coreRadiusMeters = 100.0,
        transitionRadiusMeters = 250.0,
        anchorConfidence = confidence,
        fingerprintConfidence = 0.0,
        modelVersion = 1L,
        autoApplied = autoApplied,
        updatedAt = 0L
    )

    private fun preference(enabled: Boolean) =
        PlaceLearningPreference(placeId = 1L, autoApplyEnabled = enabled, updatedAt = 0L)

    // ------------------------------------------------------------------ 停用

    @Test fun disabledAlwaysFallsBackToTheConfiguredAnchor() {
        // 模型各项条件全满足（autoApplied / 置信度 0.9 / 只差 5 米），唯一变量是用户停用。
        val resolved = PlaceModelResolver.effectiveAnchor(model(), configured, preference(enabled = false))
        assertSame("停用后必须回落到用户设置位置", configured, resolved)
    }

    @Test fun disablingWorksWithoutMutatingTheModel() {
        // 这条就是「三层分离」的证明：传进去的模型**仍是 autoApplied = true**，
        // 取用层照样拒绝 —— 说明停用没有依赖「先把模型改坏」。
        val untouched = model(autoApplied = true)
        assertSame(configured, PlaceModelResolver.effectiveAnchor(untouched, configured, preference(false)))
        assertEquals("模型状态不许被用户偏好改写", true, untouched.autoApplied)
    }

    @Test fun disabledWithNoConfiguredAnchorReturnsNullInsteadOfTheLearnedOne() {
        // 停用的语义是「不要用学习锚点」。此时若因为 configured 为空就放行学习锚点，
        // 等于用户按了暂停却仍在生效 —— 这是本次改动最不能出现的结果。
        val resolved = PlaceModelResolver.effectiveAnchor(
            model(configuredAnchor = null),
            configured = null,
            preference = preference(enabled = false)
        )
        assertNull(resolved)
    }

    @Test fun disabledIsReportedAsTheConfiguredSource() {
        val result = PlaceModelResolver.resolve(model(), configured, preference(enabled = false))
        assertSame(configured, result.point)
        assertEquals(EffectiveAnchorSource.USER_CONFIGURED, result.source)
    }

    // ------------------------------------------------------------------ 启用

    @Test fun anExplicitlyEnabledPreferenceBehavesLikeNoPreferenceAtAll() {
        val learned = lat(5.0)
        val withPreference = PlaceModelResolver.effectiveAnchor(model(learned = learned), configured, preference(true))
        val withoutPreference = PlaceModelResolver.effectiveAnchor(model(learned = learned), configured)
        assertSame(learned, withPreference)
        assertSame(withoutPreference, withPreference)
    }

    @Test fun aMissingRowIsTreatedAsEnabled() {
        // 缺行 = 允许。老库升到 v16 之后表是空的，必须完全等价于「从来没这回事」。
        val learned = lat(5.0)
        assertSame(
            learned,
            PlaceModelResolver.effectiveAnchor(model(learned = learned), configured, preference = null)
        )
    }

    @Test fun theTwoArgumentOverloadIsExactlyTheNullPreferenceCase() {
        // 两个入口必须共用一份实现：否则将来只改一处就会出现
        // 「界面显示的取用结果与实际判定不符」。
        val cases = listOf(
            model(learned = lat(5.0)) to configured,
            model(learned = lat(200.0)) to configured,
            model(autoApplied = false) to configured,
            model(confidence = 0.69) to configured,
            model(learned = null) to configured,
            model(configuredAnchor = null) to null,
            null to configured
        )
        cases.forEachIndexed { index, (m, c) ->
            val twoArg = PlaceModelResolver.effectiveAnchor(m, c)
            val threeArg = PlaceModelResolver.effectiveAnchor(m, c, preference = null)
            assertSame("case #$index 两个重载必须给出同一个对象", twoArg, threeArg)
        }
    }

    // ------------------------------------------------------------------ 来源

    @Test fun theSourceIsLearnedOnlyWhenTheLearnedAnchorIsActuallyUsed() {
        val learned = lat(5.0)
        val result = PlaceModelResolver.resolve(model(learned = learned), configured, preference(enabled = true))
        assertSame(learned, result.point)
        assertEquals(EffectiveAnchorSource.LEARNED, result.source)
    }

    @Test fun theSourceIsConfiguredWheneverTheLearnedAnchorIsRefused() {
        // 每一个拒绝理由都必须如实报成「用你设置的位置」——
        // 少报一个，界面就会显示「已启用学习校准」而判定其实没变。
        val refused = listOf(
            "还没学过" to null,
            "没有学习锚点" to model(learned = null),
            "影子期未过" to model(autoApplied = false),
            "置信度不够" to model(confidence = 0.69),
            "偏移超自动档" to model(learned = lat(200.0))
        )
        refused.forEach { (why, m) ->
            assertEquals(
                "「$why」必须报成用户配置来源",
                EffectiveAnchorSource.USER_CONFIGURED,
                PlaceModelResolver.resolve(m, configured, preference(enabled = true)).source
            )
        }
    }

    @Test fun coincidingAnchorsStillGetAnUnambiguousSourceFromTheResolver() {
        // 学习锚点与配置锚点重合时，「拿取到的点和 learnedAnchor 比一比」这种猜法就完全失效了
        // （两个坐标一模一样，猜 LEARNED 与猜 USER_CONFIGURED 都「说得通」）。
        // 所以来源必须由判定路径给出，而不是展示层自己推。
        // 这里的真值是 LEARNED：偏移 0 在自动档内、其余条件也全满足，学习锚点确实被取用了。
        val same = GeoPoint(configured.latitude, configured.longitude)
        val result = PlaceModelResolver.resolve(model(learned = same), configured, preference(enabled = true))
        assertEquals(EffectiveAnchorSource.LEARNED, result.source)
        assertEquals(configured.latitude, result.point!!.latitude, 1e-9)
    }

    @Test fun preferenceDefaultsToEnabledPerTheDomainConstant() {
        assertEquals(true, PlaceLearningPreference.DEFAULT_AUTO_APPLY_ENABLED)
    }
}
