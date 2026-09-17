package com.example.worktimetracker

import com.example.worktimetracker.data.entity.LearnedPlaceModelEntity
import com.example.worktimetracker.data.entity.PlaceLearningPreferenceEntity
import com.example.worktimetracker.domain.engine.SitePoint
import com.example.worktimetracker.location.service.withLearnedAnchors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户偏好（DB v16）在**消费端**的表现：`withLearnedAnchors(models, preferences)`。
 *
 * 两条必须同时守住的性质：
 *
 * 1. **零回归** —— 偏好列表为空时，行为与 v15（没有偏好这个概念）**逐字段相同**。
 *    这是「缺行 = 允许」在消费端的落点：老库升级上来表是空的，
 *    检测路径必须一个字节都不变；
 * 2. **停用真的停** —— 有停用行时，该地点的圆心不许被挪动。
 *
 * ⚠️ 第 1 条是本文件最重要的测试。它把「迁移不需要回填数据」从一个说法变成一条断言。
 */
class LearnedAnchorPreferenceConsumptionTest {

    private val configuredLat = 31.0000
    private val configuredLng = 121.0000

    private fun lat(meters: Double) = configuredLat + meters / 111_200.0

    private fun site(id: Long = 1L, lat: Double? = configuredLat, lng: Double? = configuredLng) = SitePoint(
        id = id,
        name = "地点$id",
        siteType = "WORK",
        latitude = lat,
        longitude = lng,
        radiusMeters = 150
    )

    private fun model(
        placeId: Long = 1L,
        learnedLat: Double? = lat(20.0),
        confidence: Double = 0.90,
        autoApplied: Boolean = true
    ) = LearnedPlaceModelEntity(
        placeId = placeId,
        placeType = "WORK",
        configuredLat = configuredLat,
        configuredLng = configuredLng,
        learnedLat = learnedLat,
        learnedLng = configuredLng,
        coreRadiusMeters = 100.0,
        transitionRadiusMeters = 250.0,
        anchorConfidence = confidence,
        modelVersion = 1L,
        autoApplied = autoApplied,
        updatedAt = 0L
    )

    private fun preference(placeId: Long = 1L, enabled: Boolean) =
        PlaceLearningPreferenceEntity(placeId = placeId, autoApplyEnabled = enabled, updatedAt = 0L)

    // ---------------------------------------------------- 零回归（缺行 = 允许）

    @Test fun anEmptyPreferenceTableChangesNothingAtAll() {
        // 老库升级到 v16 之后 place_learning_preferences 是空表 ——
        // 这一条直接断言「空偏好 == 没有偏好这个概念」。
        val sites = listOf(site(id = 1L), site(id = 2L))
        val models = listOf(model(placeId = 1L), model(placeId = 2L, learnedLat = lat(5.0)))
        val before = sites.withLearnedAnchors(models)
        val after = sites.withLearnedAnchors(models, emptyList())
        assertEquals(before.size, after.size)
        before.indices.forEach { i ->
            assertEquals(before[i].latitude!!, after[i].latitude!!, 1e-12)
            assertEquals(before[i].longitude!!, after[i].longitude!!, 1e-12)
        }
    }

    @Test fun anEmptyModelListStillReturnsTheSameInstance() {
        val sites = listOf(site())
        assertSame(sites, sites.withLearnedAnchors(emptyList(), emptyList()))
    }

    @Test fun theDefaultParameterKeepsOldCallSitesSourceCompatible() {
        // 默认参数 = 空偏好：老的调用点不传偏好时，语义不变。
        val sites = listOf(site())
        val models = listOf(model())
        assertEquals(
            sites.withLearnedAnchors(models).first().latitude!!,
            sites.withLearnedAnchors(models, emptyList()).first().latitude!!,
            1e-12
        )
    }

    @Test fun anEnabledRowIsIdenticalToAMissingRow() {
        val sites = listOf(site())
        val models = listOf(model())
        val withEnabledRow = sites.withLearnedAnchors(models, listOf(preference(enabled = true)))
        val withMissingRow = sites.withLearnedAnchors(models, emptyList())
        assertEquals(withMissingRow.first().latitude!!, withEnabledRow.first().latitude!!, 1e-12)
        assertTrue("启用时学习锚点照常生效", withEnabledRow.first().latitude!! > configuredLat)
    }

    // ---------------------------------------------------------------- 停用

    @Test fun aDisabledRowFreezesTheConfiguredAnchor() {
        // 模型各项条件全满足（autoApplied / 0.90 / 差 20 米），唯一变量是用户停用。
        val result = listOf(site()).withLearnedAnchors(listOf(model()), listOf(preference(enabled = false)))
        assertEquals("停用后圆心不许被学习锚点挪动", configuredLat, result.first().latitude!!, 1e-9)
    }

    @Test fun disablingIsScopedToThePlaceThatWasDisabled() {
        val sites = listOf(site(id = 1L), site(id = 2L))
        val models = listOf(model(placeId = 1L), model(placeId = 2L))
        val result = sites.withLearnedAnchors(models, listOf(preference(placeId = 1L, enabled = false)))
        assertEquals("地点 1 已停用", configuredLat, result[0].latitude!!, 1e-9)
        assertTrue("地点 2 未停用，照常校准", result[1].latitude!! > configuredLat)
    }

    @Test fun aDisabledRowIsAlsoIgnoredWhenItsValueIsTrueButForAnotherPlace() {
        // 只为别的 placeId 存在的偏好行，不许影响本地点。
        val result = listOf(site(id = 1L)).withLearnedAnchors(
            listOf(model(placeId = 1L)),
            listOf(preference(placeId = 99L, enabled = false))
        )
        assertTrue("别的地点被停用与本地点无关", result.first().latitude!! > configuredLat)
    }

    @Test fun radiusAndIdentityAreStillUntouchedUnderPreferences() {
        val sites = listOf(site())
        val result = sites.withLearnedAnchors(listOf(model(learnedLat = configuredLat)), emptyList())
        assertSame("学习锚点与配置锚点重合时不产生新对象", sites.first(), result.first())
        assertEquals(150, result.first().radiusMeters)
    }
}
