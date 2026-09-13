package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.domain.evidence.ResolvedPlace
import com.example.worktimetracker.domain.model.LocationType

/**
 * 融合判定用的「地点」视图（v4.3 / DB v11 的 sites 表）。
 *
 * 纯 Kotlin、无 Android 与 Room 依赖，便于单测；由数据层映射而来
 * （见 `com.example.worktimetracker.location.service.SitePointsKt` 的 `toSitePoint()`）。
 *
 * 语义映射（**这是多地点能力的唯一收敛点**）：
 * - `siteType = WORK`     → [ResolvedPlace.COMPANY]（计入工时）
 * - `siteType = NON_WORK` → [ResolvedPlace.HOME]（不计工时）
 *
 * 工时状态机只认 COMPANY/HOME 两种「锚点」，所以多个工作地点（车间 / 仓库 / 分厂）
 * 会统一折算成 COMPANY 锚点：走到任何一个工作地点都算到岗，离开全部工作地点才算离岗。
 * 这样多地点**不新增状态**，也不修改 [TrajectoryAnchorEngine] 的任何分支。
 */
data class SitePoint(
    val id: Long,
    val name: String,
    val siteType: String,
    val latitude: Double?,
    val longitude: Double?,
    val radiusMeters: Int,
    val isPrimary: Boolean = false,
    val enabled: Boolean = true,
    val migrated: Boolean = false
) {
    val hasGps: Boolean get() = latitude != null && longitude != null

    val resolvedPlace: ResolvedPlace
        get() = if (siteType == SiteResolver.TYPE_NON_WORK) ResolvedPlace.HOME else ResolvedPlace.COMPANY

    val isWork: Boolean get() = resolvedPlace == ResolvedPlace.COMPANY

    companion object {
        /** 由旧设置（companyLat/homeLat）合成的兜底地点用的哨兵 id，不会与数据库自增 id 相撞。 */
        const val SYNTHETIC_ID = -1L
        const val SYNTHETIC_ID_HOME = -2L
    }
}

/**
 * 多地点距离判定（v4.3）。
 *
 * 设计边界（与「算法冻结项」一致）：
 * - 本类只做**几何比较**：点到各地点的球面距离 + 半径比较，不做任何状态转换；
 * - 输出仍收敛为 COMPANY / HOME / OTHER 三态，[TrajectoryAnchorEngine] 完全不知情；
 * - 主地点（`isPrimary`）只在「多个地点同时在半径内」时用于打破平局，不改变单向判定。
 */
object SiteResolver {

    const val TYPE_WORK = "WORK"
    const val TYPE_NON_WORK = "NON_WORK"

    /** 一次地点匹配结果：命中的地点与它到当前点的距离。 */
    data class Match(val site: SitePoint, val distanceMeters: Double) {
        val place: ResolvedPlace get() = site.resolvedPlace
        val withinRadius: Boolean get() = distanceMeters <= site.radiusMeters
    }

    private val analyzer = LocationStatusAnalyzer()

    /** 可参与判定的地点：启用 + 有 GPS 坐标。 */
    fun usable(sites: List<SitePoint>): List<SitePoint> = sites.filter { it.enabled && it.hasGps }

    /** 指定类型里距离最近的地点（**不看半径**，供状态机算「离公司多远」用）。 */
    fun nearest(latitude: Double, longitude: Double, sites: List<SitePoint>, siteType: String): Match? =
        usable(sites)
            .filter { it.siteType == siteType }
            .map { Match(it, analyzer.distanceMeters(latitude, longitude, it.latitude!!, it.longitude!!)) }
            .minByOrNull { it.distanceMeters }

    /** 所有类型里距离最近的地点（不看半径，不看类型）。 */
    fun nearestAny(latitude: Double, longitude: Double, sites: List<SitePoint>): Match? =
        usable(sites)
            .map { Match(it, analyzer.distanceMeters(latitude, longitude, it.latitude!!, it.longitude!!)) }
            .minByOrNull { it.distanceMeters }

    /**
     * 在半径内命中的地点；多个同时命中时**主地点优先，其次更近者优先**。
     * 半径外的地点一律不返回——「靠近但没进圈」不算到达。
     */
    fun matching(latitude: Double, longitude: Double, sites: List<SitePoint>): Match? =
        usable(sites)
            .map { Match(it, analyzer.distanceMeters(latitude, longitude, it.latitude!!, it.longitude!!)) }
            .filter { it.withinRadius }
            .minWithOrNull(
                compareByDescending<Match> { it.site.isPrimary }.thenBy { it.distanceMeters }
            )

    /** 多地点地理兜底分类：命中的工作地点 → COMPANY，非工作地点 → HOME，都不命中 → OTHER。 */
    fun classify(latitude: Double, longitude: Double, sites: List<SitePoint>): LocationType =
        when (matching(latitude, longitude, sites)?.place) {
            ResolvedPlace.COMPANY -> LocationType.COMPANY
            ResolvedPlace.HOME -> LocationType.HOME
            else -> LocationType.OTHER
        }

    /**
     * 生效地点集合 = 数据库地点 + 旧设置兜底。
     *
     * 兜底只在「该类型一个带坐标的地点都没有」时触发，用 `user_settings` 的
     * companyLat/companyLng/homeLat/homeLng 合成一条虚拟地点（[SitePoint.SYNTHETIC_ID]）。
     * 数据库地点是从旧字段迁移来的且半径一致，所以正常情况下兜底永不生效，
     * 老装机行为与 v4.2 完全一致。
     */
    fun effective(
        sites: List<SitePoint>,
        companyLat: Double?,
        companyLng: Double?,
        companyRadiusMeters: Int,
        homeLat: Double?,
        homeLng: Double?,
        homeRadiusMeters: Int
    ): List<SitePoint> {
        val enabled = sites.filter { it.enabled }
        val result = enabled.toMutableList()
        if (enabled.none { it.isWork && it.hasGps } &&
            companyLat != null && companyLng != null
        ) {
            result += SitePoint(
                id = SitePoint.SYNTHETIC_ID,
                name = "公司",
                siteType = TYPE_WORK,
                latitude = companyLat,
                longitude = companyLng,
                radiusMeters = companyRadiusMeters,
                isPrimary = true,
                migrated = true
            )
        }
        if (enabled.none { !it.isWork && it.hasGps } &&
            homeLat != null && homeLng != null
        ) {
            result += SitePoint(
                id = SitePoint.SYNTHETIC_ID_HOME,
                name = "家",
                siteType = TYPE_NON_WORK,
                latitude = homeLat,
                longitude = homeLng,
                radiusMeters = homeRadiusMeters,
                migrated = true
            )
        }
        return result
    }

    /** 列表页展示用：当前位置到该地点的距离（无坐标或未知返回 null）。 */
    fun distanceTo(
        latitude: Double?,
        longitude: Double?,
        site: SitePoint
    ): Double? {
        if (latitude == null || longitude == null || !site.hasGps) return null
        return analyzer.distanceMeters(latitude, longitude, site.latitude!!, site.longitude!!)
    }
}
