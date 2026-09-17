package com.example.worktimetracker

import com.example.worktimetracker.domain.location.GeoPoint
import com.example.worktimetracker.domain.location.LearnedPlaceModel
import com.example.worktimetracker.domain.location.PlaceModelResolver
import com.example.worktimetracker.domain.location.PlaceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锚点取用策略 —— 「**用户手动设置和修改的结果优先级最高**」（方案 §一 原则 1）的唯一执行点。
 *
 * 这组测试的存在理由：这条原则一旦破了，表现是「用户改了位置，App 又自己改回去」，
 * 现象隐蔽、复现困难。所以在源头钉死：只要有任何一条不满足，
 * **必须**回落到用户配置锚点，不许猜。
 */
class PlaceModelResolverTest {

    private val configured = GeoPoint(31.0000, 121.0000)

    /** 纬度方向挪 [meters] 米（1e-3 度 ≈ 111.2 米，够用且不依赖具体公式）。 */
    private fun latOffset(meters: Double) = GeoPoint(31.0000 + meters / 111_200.0, 121.0000)

    private fun model(
        learned: GeoPoint? = latOffset(5.0),
        configuredAnchor: GeoPoint? = configured,
        confidence: Double = 0.90,
        autoApplied: Boolean = true,
        type: PlaceType = PlaceType.WORK
    ) = LearnedPlaceModel(
        placeId = 1L,
        type = type,
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

    @Test fun withoutAnyModelTheConfiguredAnchorWins() {
        assertSame(configured, PlaceModelResolver.effectiveAnchor(null, configured))
    }

    @Test fun modelWithoutLearnedAnchorFallsBackToConfigured() {
        val resolved = PlaceModelResolver.effectiveAnchor(model(learned = null), configured)
        assertSame(configured, resolved)
    }

    @Test fun notAutoAppliedIsNeverUsedEvenIfItLooksGreat() {
        // 置信度 0.9、只差 5 米 —— 但还在影子期，一个字都不许动
        val resolved = PlaceModelResolver.effectiveAnchor(model(autoApplied = false), configured)
        assertSame(configured, resolved)
    }

    @Test fun lowConfidenceIsNeverUsedEvenIfAutoApplied() {
        val resolved = PlaceModelResolver.effectiveAnchor(model(confidence = 0.69), configured)
        assertSame(configured, resolved)
    }

    @Test fun confidenceExactlyAtTheFloorIsAccepted() {
        val learned = latOffset(5.0)
        val resolved = PlaceModelResolver.effectiveAnchor(model(learned = learned, confidence = 0.70), configured)
        assertSame(learned, resolved)
    }

    @Test fun closeLearnedAnchorIsUsedWhenAutoApplied() {
        val learned = latOffset(20.0)
        val resolved = PlaceModelResolver.effectiveAnchor(model(learned = learned), configured)
        assertSame("20 米在自动档内，应当取用学习锚点", learned, resolved)
    }

    @Test fun farLearnedAnchorIsRejectedEvenIfAutoApplied() {
        // 双保险：偏移超出自动档，即便库里写着 autoApplied 也不许取用
        val resolved = PlaceModelResolver.effectiveAnchor(model(learned = latOffset(200.0)), configured)
        assertSame(configured, resolved)
    }

    @Test fun learnedAnchorIsUsedWhenThereIsNoConfiguredAnchorAtAll() {
        val learned = latOffset(0.0)
        val resolved = PlaceModelResolver.effectiveAnchor(model(learned = learned, configuredAnchor = null), null)
        assertSame("两套锚点都没有时才允许学习锚点兜底", learned, resolved)
    }

    @Test fun configuredAnchorArgumentIsUsedWhenTheModelHasNoSnapshot() {
        val resolved = PlaceModelResolver.effectiveAnchor(model(configuredAnchor = null), configured)
        // 模型里没有配置快照 → 用调用方给的配置锚点算偏移，仍在自动档内 → 取学习锚点
        assertTrue(resolved != null)
        assertTrue("应当取用学习锚点，实际=$resolved", resolved!!.latitude > configured.latitude)
    }

    @Test fun nothingAtAllResolvesToNull() {
        assertNull(PlaceModelResolver.effectiveAnchor(null, null))
        assertNull(PlaceModelResolver.effectiveAnchor(model(learned = null, configuredAnchor = null), null))
    }

    @Test fun offsetIsNullWhenEitherSideIsMissing() {
        assertNull(PlaceModelResolver.offsetMeters(model(learned = null)))
        assertNull(PlaceModelResolver.offsetMeters(model(configuredAnchor = null)))
        assertNull(PlaceModelResolver.offsetMeters(model(learned = null, configuredAnchor = null)))
    }

    @Test fun offsetMatchesTheMovedDistance() {
        val meters = 40.0
        val offset = PlaceModelResolver.offsetMeters(model(learned = latOffset(meters)))
        assertTrue("偏移应接近 $meters 米，实际=$offset", offset!! > meters - 2 && offset < meters + 2)
    }

    @Test fun autoApplyOffsetCeilingEqualsThePolicyAutoSmoothBand() {
        // 两处门槛必须同源，否则会出现「策略说能自动、取用说不能用」的分裂
        assertEquals(30.0, PlaceModelResolver.AUTO_APPLY_MAX_OFFSET_METERS, 1e-9)
        assertEquals(0.70, PlaceModelResolver.AUTO_APPLY_CONFIDENCE, 1e-9)
    }

    @Test fun placeTypeMapsFromSiteTypeColumns() {
        assertEquals(PlaceType.WORK, PlaceType.ofSiteType("WORK"))
        assertEquals(PlaceType.NON_WORK, PlaceType.ofSiteType("NON_WORK"))
        assertEquals("未知类型按工作地点处理（主判定对象）", PlaceType.WORK, PlaceType.ofSiteType(null))
        assertEquals(PlaceType.WORK, PlaceType.ofSiteType("SOMETHING_ELSE"))
    }

    @Test fun hasLearnedOnlyDependsOnTheLearnedAnchor() {
        assertTrue(model(learned = latOffset(0.0)).hasLearned)
        assertFalse(model(learned = null).hasLearned)
    }
}
