package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.dao.SiteDao
import com.example.worktimetracker.data.entity.SiteEntity
import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.domain.engine.SitePoint
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import com.example.worktimetracker.domain.engine.SiteResolver

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
