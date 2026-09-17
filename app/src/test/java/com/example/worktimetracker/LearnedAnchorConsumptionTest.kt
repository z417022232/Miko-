package com.example.worktimetracker

import com.example.worktimetracker.data.entity.LearnedPlaceModelEntity
import com.example.worktimetracker.domain.engine.SitePoint
import com.example.worktimetracker.location.service.withLearnedAnchors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 位置闭环的消费端（方案 §三.1 + 原则 1）。
 *
 * 这是「学习结果真的影响判定」的唯一一处，所以边界必须逐条钉死：
 * 库是空的、候选还在影子期、偏移过大、站点没有坐标、虚拟站点 —— 一律**原样返回**。
 */
class LearnedAnchorConsumptionTest {

    private val configuredLat = 31.0000
    private val configuredLng = 121.0000

    /** 纬度方向挪 [meters] 米（1e-3 度 ≈ 111.2 米）。 */
    private fun lat(meters: Double) = configuredLat + meters / 111_200.0

    private fun site(
        id: Long = 1L,
        lat: Double? = configuredLat,
        lng: Double? = configuredLng,
        radius: Int = 150,
        type: String = "WORK"
    ) = SitePoint(
        id = id,
        name = "地点$id",
        siteType = type,
        latitude = lat,
        longitude = lng,
        radiusMeters = radius
    )

    private fun model(
        placeId: Long = 1L,
        learnedLat: Double? = lat(10.0),
        learnedLng: Double? = configuredLng,
        confidence: Double = 0.90,
        autoApplied: Boolean = true,
        type: String = "WORK",
        coreRadius: Double = 100.0
    ) = LearnedPlaceModelEntity(
        placeId = placeId,
        placeType = type,
        configuredLat = configuredLat,
        configuredLng = configuredLng,
        learnedLat = learnedLat,
        learnedLng = learnedLng,
        coreRadiusMeters = coreRadius,
        transitionRadiusMeters = coreRadius * 2.5,
        anchorConfidence = confidence,
        modelVersion = 1L,
        autoApplied = autoApplied,
        updatedAt = 0L
    )

    @Test fun emptyModelsReturnTheExactSameListInstance() {
        // 新装 / 刚升级的库里这张表是空的 —— 走这条分支的检测路径逐字节不变
        val sites = listOf(site(), site(id = 2L, type = "NON_WORK"))
        assertSame(sites, sites.withLearnedAnchors(emptyList()))
    }

    @Test fun unknownPlaceIdIsIgnored() {
        val sites = listOf(site(id = 7L))
        val result = sites.withLearnedAnchors(listOf(model(placeId = 99L)))
        assertEquals(configuredLat, result.first().latitude!!, 1e-9)
    }

    @Test fun shadowCandidateNeverMovesTheAnchor() {
        // 只差 5 米、置信度 0.9 —— 但还在影子期，一个字都不许改
        val result = listOf(site()).withLearnedAnchors(listOf(model(learnedLat = lat(5.0), autoApplied = false)))
        assertEquals(configuredLat, result.first().latitude!!, 1e-9)
    }

    @Test fun lowConfidenceNeverMovesTheAnchor() {
        val result = listOf(site()).withLearnedAnchors(listOf(model(confidence = 0.69)))
        assertEquals(configuredLat, result.first().latitude!!, 1e-9)
    }

    @Test fun autoAppliedCloseAnchorDoesMoveTheCenter() {
        val result = listOf(site()).withLearnedAnchors(listOf(model(learnedLat = lat(20.0))))
        assertTrue("20 米在自动档内，圆心应当移动", result.first().latitude!! > configuredLat)
    }

    @Test fun radiusIsNeverTouchedByLearning() {
        // 「校准」不许变成「悄悄放大判定范围」
        val result = listOf(site(radius = 150)).withLearnedAnchors(
            listOf(model(learnedLat = lat(20.0), coreRadius = 400.0))
        )
        assertEquals(150, result.first().radiusMeters)
    }

    @Test fun farAutoAppliedAnchorIsIgnored() {
        val result = listOf(site()).withLearnedAnchors(listOf(model(learnedLat = lat(200.0))))
        assertEquals(configuredLat, result.first().latitude!!, 1e-9)
    }

    @Test fun siteWithoutGpsIsIgnored() {
        val result = listOf(site(lat = null, lng = null)).withLearnedAnchors(listOf(model()))
        assertEquals(null, result.first().latitude)
        assertEquals(null, result.first().longitude)
    }

    @Test fun syntheticSiteIdsNeverMatchAModel() {
        // 由旧设置合成的兜底地点用负 id，而 sites.id 是 AUTOINCREMENT（从 1 起）。
        // 即便有人手工插了一行 placeId=-1 的模型，也不许挪动兜底地点的圆心。
        val synthetic = SitePoint(
            id = SitePoint.SYNTHETIC_ID,
            name = "公司",
            siteType = "WORK",
            latitude = configuredLat,
            longitude = configuredLng,
            radiusMeters = 150
        )
        val result = listOf(synthetic).withLearnedAnchors(listOf(model(placeId = SitePoint.SYNTHETIC_ID)))
        assertEquals(configuredLat, result.first().latitude!!, 1e-9)
        assertSame(synthetic, result.first())
    }

    @Test fun modelWithoutLearnedCoordinatesIsIgnored() {
        val result = listOf(site()).withLearnedAnchors(listOf(model(learnedLat = null, learnedLng = null)))
        assertEquals(configuredLat, result.first().latitude!!, 1e-9)
    }

    @Test fun severalSitesResolveIndependently() {
        val sites = listOf(site(id = 1L), site(id = 2L, type = "NON_WORK", lat = 30.0, lng = 120.0))
        val models = listOf(
            model(placeId = 1L, learnedLat = lat(20.0)),
            model(placeId = 2L, learnedLat = 30.0, learnedLng = 120.0, autoApplied = false)
        )
        val result = sites.withLearnedAnchors(models)
        assertTrue("工作地点被校准", result[0].latitude!! > configuredLat)
        assertEquals("非工作地点的影子候选不生效", 30.0, result[1].latitude!!, 1e-9)
    }

    @Test fun identityWhenNothingChangesIsPreserved() {
        // 学习锚点与配置锚点重合时不产生新对象，避免无谓的缓存失效
        val sites = listOf(site())
        val result = sites.withLearnedAnchors(listOf(model(learnedLat = configuredLat)))
        assertSame(sites.first(), result.first())
    }
}
