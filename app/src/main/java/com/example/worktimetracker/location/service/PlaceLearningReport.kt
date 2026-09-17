package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.database.AppDatabase
import com.example.worktimetracker.data.entity.EnvironmentFingerprintEntity
import com.example.worktimetracker.data.entity.LearnedPlaceModelEntity
import com.example.worktimetracker.data.entity.PlaceLearningPreferenceEntity
import com.example.worktimetracker.domain.location.PlaceLearningPreference
import com.example.worktimetracker.domain.location.PlaceLearningPreferencePolicy
import com.example.worktimetracker.domain.location.PlaceLearningStatus
import com.example.worktimetracker.domain.location.PlaceLearningStatusPresenter
import com.example.worktimetracker.domain.location.PlaceModelResolver
import com.example.worktimetracker.domain.location.GeoPoint
import com.example.worktimetracker.domain.location.ShadowValidator
import java.time.LocalDate
import java.time.ZoneId

/**
 * 学习成果的**只读**报表（地点管理页的最小展示）。
 *
 * ## 为什么是「重建」而不是「读结论」
 *
 * 这里不读任何缓存下来的「验证结论」，而是把候选行重新投影成观测序列，
 * 再过一遍 [ShadowValidator]（与学习侧同一份实现，重建规则见 [ShadowWindow]）。
 * 代价是多算一次纯函数，换来的是：**页面上显示的天数、漂移、来源数，
 * 与判定时用的数字来自同一份输入**。若改成「学习时把结论存一行、页面读那一行」，
 * 页面就会展示一份可能过期的结论，而这正是最需要它准确的地方
 * （用户点「查看依据」的时候，通常就是因为觉得判定不对）。
 *
 * ## 只读
 *
 * 本类不写任何表。它跑不跑、跑几次，都不改变算法行为 ——
 * 所以它可以直接挂在界面刷新上，不需要考虑时序。
 */
class PlaceLearningReport(
    private val db: AppDatabase,
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    /** 所有**带坐标**地点的学习状态（带坐标才会被学习覆盖，其余地点没有状态可言）。 */
    suspend fun statuses(now: Long = System.currentTimeMillis()): List<PlaceLearningStatus> {
        val dao = db.learningModelDao()
        val sites = runCatching { db.siteDao().all() }.getOrDefault(emptyList())
            .filter { it.hasGps }
        if (sites.isEmpty()) return emptyList()

        val models = runCatching { dao.allPlaces() }.getOrDefault(emptyList()).associateBy { it.placeId }
        val preferences = runCatching { dao.allPreferences() }.getOrDefault(emptyList())
            .associateBy { it.placeId }
        val fingerprints = runCatching { db.environmentEvidenceDao().allFingerprints() }
            .getOrDefault(emptyList())
        val today = ShadowWindow.dayOf(now, zone)

        return sites.mapNotNull { site ->
            runCatching {
                statusOf(
                    placeId = site.id,
                    configured = GeoPoint(site.latitude!!, site.longitude!!),
                    today = today,
                    model = models[site.id],
                    preference = preferences[site.id],
                    fingerprints = fingerprints
                )
            }.getOrNull()
        }
    }

    private suspend fun statusOf(
        placeId: Long,
        configured: GeoPoint,
        today: LocalDate,
        model: LearnedPlaceModelEntity?,
        preference: PlaceLearningPreferenceEntity?,
        fingerprints: List<EnvironmentFingerprintEntity>
    ): PlaceLearningStatus {
        val dao = db.learningModelDao()
        val latest = dao.latestCandidate(placeId)
        val windowRows = latest?.let { dao.candidatesInWindow(placeId, it.firstSeenAt) }.orEmpty()

        val shadow = if (latest == null) {
            null
        } else {
            ShadowValidator.evaluate(
                observations = ShadowWindow.observations(windowRows, zone),
                today = today,
                conflictCount = ShadowWindow.conflicts(fingerprints, latest.firstSeenAt)
            )
        }

        val domainModel = model?.toLearnedPlaceModel()
        // 偏好缺行 → 领域层按「默认允许」处理（见 PlaceLearningPreference），这里不替它补默认值。
        val domainPreference = preference?.let {
            PlaceLearningPreferencePolicy.of(it.placeId, it.autoApplyEnabled, it.updatedAt)
        }
        // 实际用了哪个锚点，必须问 PlaceModelResolver —— 页面不许自己猜（见 EffectiveAnchor 说明）。
        val effective = PlaceModelResolver.resolve(domainModel, configured, domainPreference)

        return PlaceLearningStatusPresenter.present(
            PlaceLearningStatusPresenter.Input(
                placeId = placeId,
                preference = domainPreference,
                hasLearnedAnchor = model?.hasLearned == true,
                candidateOffsetMeters = latest?.offsetMeters,
                trainingSampleCount = latest?.sampleCount ?: 0,
                trainingDistinctDays = latest?.distinctDayCount ?: 0,
                shadow = shadow,
                effectiveAnchorSource = effective.source
            )
        )
    }
}
