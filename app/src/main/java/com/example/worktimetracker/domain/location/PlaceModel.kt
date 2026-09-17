package com.example.worktimetracker.domain.location

import com.example.worktimetracker.domain.engine.LocationStatusAnalyzer

/** 一个地理点。刻意用 Double 而不是 Android 的 Location —— domain 层要保持纯 Kotlin。 */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** 地点类型，与 `sites.siteType` 同口径（WORK / NON_WORK）。 */
enum class PlaceType(val siteType: String) {
    WORK("WORK"),
    NON_WORK("NON_WORK");

    companion object {
        fun ofSiteType(raw: String?): PlaceType =
            if (raw == NON_WORK.siteType) NON_WORK else WORK
    }
}

/**
 * 地点学习模型（方案 §三.1 的领域视图）。
 *
 * 与 `data.entity.LearnedPlaceModelEntity` 的分工：那个是持久化行，这个是纯领域对象。
 * 两者字段一一对应，转换在数据层做（同 `SitePoint`/`SiteEntity` 的既有做法）。
 *
 * **两套锚点并存且互不覆盖**：
 * - [configuredAnchor] 用户设置，永久保留；
 * - [learnedAnchor] 模型学出来的中心。
 */
data class LearnedPlaceModel(
    val placeId: Long,
    val type: PlaceType,
    val configuredAnchor: GeoPoint?,
    val learnedAnchor: GeoPoint?,
    /** 核心区半径：确认到达用 */
    val coreRadiusMeters: Double,
    /** 过渡区半径：靠近 / 离开 / 路过用，恒 ≥ [coreRadiusMeters] */
    val transitionRadiusMeters: Double,
    val anchorConfidence: Double,
    val fingerprintConfidence: Double,
    val modelVersion: Long,
    /** 学习锚点是否已自动生效（true 才允许被检测路径取用） */
    val autoApplied: Boolean,
    val updatedAt: Long
) {
    val hasLearned: Boolean get() = learnedAnchor != null
}

/**
 * 锚点取用策略 —— 检测路径到底该用哪个锚点。
 *
 * 这是「**用户手动设置和修改的结果优先级最高**」（方案 §一 原则 1）的**唯一执行点**。
 * 把它单独做成一个可单测的 object，而不是散在服务里写 if，是因为这条原则一旦破了，
 * 表现是「用户改了位置，App 又自己改回去」—— 极难排查，必须在源头钉死。
 *
 * 取用顺序：
 * 1. 学习锚点存在、且**已自动生效**、且置信度达线、且偏移在自动档内 → 用学习锚点；
 * 2. 否则 → 用用户配置锚点；
 * 3. 两个都没有 → null（调用方走原有兜底，例如 `sites` 表为空时用设置里的坐标）。
 */
object PlaceModelResolver {

    /** 自动生效所需的最低锚点置信度。 */
    const val AUTO_APPLY_CONFIDENCE = 0.70

    /** 自动生效允许的最大偏移：超过它即便 autoApplied 也不取用（防御性双保险）。 */
    const val AUTO_APPLY_MAX_OFFSET_METERS = AnchorUpdatePolicy.AUTO_SMOOTH_MAX_OFFSET_METERS

    private val analyzer = LocationStatusAnalyzer()

    /**
     * 取生效锚点。
     *
     * ⚠️ [model] 为 null（还没学过）或 [LearnedPlaceModel.autoApplied] 为 false 时
     * **必须**回落到 [configured]，不得「猜一个」。这是原则 1 的硬边界。
     */
    fun effectiveAnchor(model: LearnedPlaceModel?, configured: GeoPoint?): GeoPoint? {
        if (model == null) return configured
        val learned = model.learnedAnchor ?: return configured
        if (!model.autoApplied) return configured
        if (model.anchorConfidence < AUTO_APPLY_CONFIDENCE) return configured
        val configuredPoint = model.configuredAnchor ?: configured ?: return learned
        val offset = analyzer.distanceMeters(
            configuredPoint.latitude, configuredPoint.longitude,
            learned.latitude, learned.longitude
        )
        // 偏移超出自动档 → 学习锚点只能在影子区待着，绝不能悄悄挪动判定
        return if (offset <= AUTO_APPLY_MAX_OFFSET_METERS) learned else configured
    }

    /** 学习锚点相对配置锚点的偏移（米）；缺任一侧返回 null。 */
    fun offsetMeters(model: LearnedPlaceModel): Double? {
        val configured = model.configuredAnchor ?: return null
        val learned = model.learnedAnchor ?: return null
        return analyzer.distanceMeters(
            configured.latitude, configured.longitude,
            learned.latitude, learned.longitude
        )
    }
}
