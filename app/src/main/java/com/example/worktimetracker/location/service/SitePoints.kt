package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.dao.SiteDao
import com.example.worktimetracker.data.entity.LearnedPlaceModelEntity
import com.example.worktimetracker.data.entity.SiteEntity
import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.domain.engine.SitePoint
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import com.example.worktimetracker.domain.engine.SiteResolver
import com.example.worktimetracker.domain.location.GeoPoint
import com.example.worktimetracker.domain.location.LearnedPlaceModel
import com.example.worktimetracker.domain.location.PlaceModelResolver
import com.example.worktimetracker.domain.location.PlaceType

/**
 * 数据层 → 融合判定的地点视图（v4.3）。
 *
 * 放在 location/service 而不是 domain：domain 保持纯 Kotlin 无 Room 依赖，
 * 只有这里允许 import 数据实体。
 */

fun SiteEntity.toSitePoint(): SitePoint = SitePoint(
    id = id,
    name = name,
    siteType = siteType,
    latitude = latitude,
    longitude = longitude,
    radiusMeters = radiusMeters,
    isPrimary = isPrimary,
    enabled = enabled,
    migrated = migrated
)

fun List<SiteEntity>.toSitePoints(): List<SitePoint> = map { it.toSitePoint() }

/**
 * 生效地点集合 = 数据库站点 + 旧设置兜底。
 *
 * 兜底只在「某一类型一个带坐标的地点都没有」时才追加一条虚拟地点：
 * 用户把地点全删了、或升级过程中 sites 表为空，判定都不会突然失灵。
 */
fun UserSettingsEntity.effectiveSites(dbSites: List<SiteEntity>): List<SitePoint> =
    SiteResolver.effective(
        sites = dbSites.toSitePoints(),
        companyLat = companyLat,
        companyLng = companyLng,
        companyRadiusMeters = companyRadiusMeters,
        homeLat = homeLat,
        homeLng = homeLng,
        homeRadiusMeters = homeRadiusMeters
    )


/**
 * 用户在地点管理里手动选定的证据源 → 地点（v4.3）。
 *
 * 返回「哈希 → COMPANY/HOME」的扁平映射：融合层只关心某个标识属于哪一类地点，
 * 不关心它挂在哪条站点上。原始 SSID / BSSID 从不参与这个映射（库里只有哈希）。
 */
suspend fun SiteDao.declaredHashPlaces(): Map<String, ResolvedPlace> = runCatching {
    val sites = all().associateBy { it.id }
    allSources().mapNotNull { source ->
        val site = sites[source.siteId] ?: return@mapNotNull null
        source.identifierHash to
            if (site.siteType == SiteEntity.TYPE_NON_WORK) ResolvedPlace.HOME else ResolvedPlace.COMPANY
    }.toMap()
}.getOrDefault(emptyMap())

/**
 * 持久化行 → 领域模型（阶段2 位置闭环的**消费端转换**）。
 *
 * 两套锚点在这里**并存**地搬过去，由 [PlaceModelResolver] 决定取哪个 ——
 * 转换层不做任何取舍，避免「取用策略」散落成两处。
 */
fun LearnedPlaceModelEntity.toLearnedPlaceModel(): LearnedPlaceModel = LearnedPlaceModel(
    placeId = placeId,
    type = PlaceType.ofSiteType(placeType),
    configuredAnchor = if (configuredLat != null && configuredLng != null) {
        GeoPoint(configuredLat, configuredLng)
    } else null,
    learnedAnchor = if (hasLearned) GeoPoint(learnedLat!!, learnedLng!!) else null,
    coreRadiusMeters = coreRadiusMeters,
    transitionRadiusMeters = transitionRadiusMeters,
    anchorConfidence = anchorConfidence,
    fingerprintConfidence = fingerprintConfidence,
    modelVersion = modelVersion,
    autoApplied = autoApplied,
    updatedAt = updatedAt
)

/**
 * 把学习锚点叠加到生效站点上（阶段2 位置闭环的**唯一消费点**）。
 *
 * 三条硬边界：
 *  1. **[models] 为空 → 原样返回同一个列表实例**。新装/刚升级的库里这张表是空的，
 *     于是检测路径逐字节不变 —— 「零回归」不是靠自觉，是结构上不可能变；
 *  2. **虚拟站点天然免疫**。[SitePoint.SYNTHETIC_ID] 这类由旧设置合成的地点用负 id，
 *     而 `sites.id` 是 AUTOINCREMENT（从 1 起），所以这里直接按 `id <= 0` 挡掉。
 *     学习侧本来就只遍历 `sites` 表、不会给虚拟站点建模型行，这里是**双保险** ——
 *     万一将来有人手工插了一行负 id 的模型，也不会悄悄挪动兜底地点的圆心；
 *  3. **半径不动**。学习锚点只把圆心挪 ≤30 米（[PlaceModelResolver] 保证），
 *     用户配置的可达圈半径一个字都不改，避免「校准」变成「悄悄放大判定范围」。
 */
fun List<SitePoint>.withLearnedAnchors(models: List<LearnedPlaceModelEntity>): List<SitePoint> {
    if (models.isEmpty()) return this
    val byPlaceId = models.associateBy { it.placeId }
    return map { site ->
        if (site.id <= 0L) return@map site
        val entity = byPlaceId[site.id] ?: return@map site
        if (!site.hasGps) return@map site
        val configured = GeoPoint(site.latitude!!, site.longitude!!)
        val effective = PlaceModelResolver.effectiveAnchor(entity.toLearnedPlaceModel(), configured)
            ?: return@map site
        if (effective.latitude == site.latitude && effective.longitude == site.longitude) return@map site
        site.copy(latitude = effective.latitude, longitude = effective.longitude)
    }
}
