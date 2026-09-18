package com.example.worktimetracker

import com.example.worktimetracker.data.entity.LearnedPlaceModelEntity
import com.example.worktimetracker.data.entity.PlaceLearningPreferenceEntity
import com.example.worktimetracker.data.entity.SiteEntity
import com.example.worktimetracker.location.recovery.GeofenceRecovery
import org.junit.Assert.assertEquals
import org.junit.Test

class GeofenceRecoveryTest {
    @Test fun allEnabledGpsSitesUseEffectiveAnchorsAndStableUniqueIds() {
        val sites = listOf(
            site(id = 11, name = "一厂", lat = 31.0, lng = 121.0),
            site(id = 22, name = "二厂", lat = 32.0, lng = 122.0),
            site(id = 33, name = "停用", lat = 33.0, lng = 123.0, enabled = false),
            site(id = 44, name = "无坐标", lat = null, lng = null)
        )
        val learnedLat = 31.0 + 5.0 / 111_200.0
        val models = listOf(
            model(placeId = 11, configuredLat = 31.0, configuredLng = 121.0, learnedLat = learnedLat, learnedLng = 121.0),
            model(placeId = 22, configuredLat = 32.0, configuredLng = 122.0, learnedLat = 32.0 + 5.0 / 111_200.0, learnedLng = 122.0)
        )
        val preferences = listOf(PlaceLearningPreferenceEntity(placeId = 22, autoApplyEnabled = false, updatedAt = 0))

        val targets = GeofenceRecovery.resolveTargets(sites, models, preferences)

        assertEquals(listOf("work-time-site-11", "work-time-site-22"), targets.map { it.requestId })
        assertEquals(learnedLat, targets[0].latitude, 0.0000001)
        assertEquals(32.0, targets[1].latitude, 0.0000001)
        assertEquals(listOf(11L, 22L), targets.map { it.siteId })
    }

    @Test fun noEnabledSiteWithCoordinatesProducesNoTargets() {
        val sites = listOf(
            site(id = 1, name = "disabled", lat = 1.0, lng = 2.0, enabled = false),
            site(id = 2, name = "missing", lat = null, lng = null)
        )
        assertEquals(emptyList<Any>(), GeofenceRecovery.resolveTargets(sites, emptyList(), emptyList()))
    }

    private fun site(id: Long, name: String, lat: Double?, lng: Double?, enabled: Boolean = true) = SiteEntity(
        id = id, name = name, latitude = lat, longitude = lng, radiusMeters = 180, enabled = enabled,
        createdAt = 0, updatedAt = 0
    )

    private fun model(
        placeId: Long,
        configuredLat: Double,
        configuredLng: Double,
        learnedLat: Double,
        learnedLng: Double
    ) = LearnedPlaceModelEntity(
        placeId = placeId,
        placeType = SiteEntity.TYPE_WORK,
        configuredLat = configuredLat,
        configuredLng = configuredLng,
        learnedLat = learnedLat,
        learnedLng = learnedLng,
        anchorConfidence = 0.95,
        autoApplied = true,
        modelVersion = 1,
        updatedAt = 0
    )
}
