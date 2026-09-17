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

/** 判定路径实际采用了哪个锚点。 */
enum class EffectiveAnchorSource {
    /** 用户设置/手动修改的位置 */
    USER_CONFIGURED,

    /** 学习校准后的位置 */
    LEARNED
}

/**
 * 一次取锚点的结果：**点** + **它是谁**。
 *
 * 把来源和点绑在同一个返回值里，是因为「显示依据」和「实际取用」必须是同一个结论。
 * 若让展示层自己去猜（拿取到的点和 `learnedAnchor` 比一比），
 * 当学习锚点恰好等于配置锚点时就会猜错 —— 而那时两者行为相同、错也看不出来，
 * 于是这个 bug 会一直躺着，直到某天两者不相等才以「界面说 A、实际用 B」的形式爆掉。
 */
data class EffectiveAnchor(val point: GeoPoint?, val source: EffectiveAnchorSource)

/**
 * 锚点取用策略 —— 检测路径到底该用哪个锚点。
 *
 * 这是「**用户手动设置和修改的结果优先级最高**」（方案 §一 原则 1）的**唯一执行点**。
 * 把它单独做成一个可单测的 object，而不是散在服务里写 if，是因为这条原则一旦破了，
 * 表现是「用户改了位置，App 又自己改回去」—— 极难排查，必须在源头钉死。
 *
 * 取用顺序：
 * 0. 用户**停用过**学习校准（[PlaceLearningPreference.autoApplyEnabled] = false）→ 用用户配置锚点；
 * 1. 学习锚点存在、且**已自动生效**、且置信度达线、且偏移在自动档内 → 用学习锚点；
 * 2. 否则 → 用用户配置锚点；
 * 3. 两个都没有 → null（调用方走原有兜底，例如 `sites` 表为空时用设置里的坐标）。
 *
 * ⚠️ 第 0 步刻意放在**取用时**而不是「停用时把 `autoApplied` 改掉」：
 * `autoApplied` 记的是「算法是否通过」这个事实，用户偏好没有资格改写它。
 * 放在这里，用户停用/开启就只影响一行偏好数据，模型状态永远是算法自己的账。
 */
object PlaceModelResolver {

    /** 自动生效所需的最低锚点置信度。 */
    const val AUTO_APPLY_CONFIDENCE = 0.70

    /** 自动生效允许的最大偏移：超过它即便 autoApplied 也不取用（防御性双保险）。 */
    const val AUTO_APPLY_MAX_OFFSET_METERS = AnchorUpdatePolicy.AUTO_SMOOTH_MAX_OFFSET_METERS

    private val analyzer = LocationStatusAnalyzer()

    /**
     * 取生效锚点（兼容重载：不带偏好 = 用户没表过态）。
     *
     * 与带 [preference] 的版本**共用同一份实现**，不另写一遍判断 ——
     * 两个入口各写一份的话，将来只改一处就会出现「UI 显示的取用结果与实际不符」。
     */
    fun effectiveAnchor(model: LearnedPlaceModel?, configured: GeoPoint?): GeoPoint? =
        resolve(model, configured, preference = null).point

    /**
     * 取生效锚点，并带用户偏好。
     *
     * ⚠️ [model] 为 null（还没学过）、[LearnedPlaceModel.autoApplied] 为 false、
     * 或用户 [PlaceLearningPreference.autoApplyEnabled] = false 时
     * **必须**回落到 [configured]，不得「猜一个」。这是原则 1 的硬边界。
     *
     * 用户停用时**原样返回 [configured]**（包括它为 null 的情况）：
     * 停用的语义是「不要用学习锚点」，此时若因为 configured 为空就回退到学习锚点，
     * 等于用户按了暂停却仍在生效 —— 那是本次改动最不能出现的结果。
     */
    fun effectiveAnchor(
        model: LearnedPlaceModel?,
        configured: GeoPoint?,
        preference: PlaceLearningPreference?
    ): GeoPoint? = resolve(model, configured, preference).point

    /**
     * 取生效锚点**连同来源**（[EffectiveAnchor]）。展示层必须用这个，
     * 不要自己拿点和 `learnedAnchor` 比 —— 见 [EffectiveAnchor] 的说明。
     */
    fun resolve(
        model: LearnedPlaceModel?,
        configured: GeoPoint?,
        preference: PlaceLearningPreference?
    ): EffectiveAnchor {
        // 第 0 步：用户停用 → 一律回落用户设置。放在最前面，任何学习侧条件都不再评估。
        if (preference?.autoApplyEnabled == false) {
            return EffectiveAnchor(configured, EffectiveAnchorSource.USER_CONFIGURED)
        }
        val fallback = EffectiveAnchor(configured, EffectiveAnchorSource.USER_CONFIGURED)
        if (model == null) return fallback
        val learned = model.learnedAnchor ?: return fallback
        if (!model.autoApplied) return fallback
        if (model.anchorConfidence < AUTO_APPLY_CONFIDENCE) return fallback
        val configuredPoint = model.configuredAnchor ?: configured
            ?: return EffectiveAnchor(learned, EffectiveAnchorSource.LEARNED)
        val offset = analyzer.distanceMeters(
            configuredPoint.latitude, configuredPoint.longitude,
            learned.latitude, learned.longitude
        )
        // 偏移超出自动档 → 学习锚点只能在影子区待着，绝不能悄悄挪动判定
        return if (offset <= AUTO_APPLY_MAX_OFFSET_METERS) {
            EffectiveAnchor(learned, EffectiveAnchorSource.LEARNED)
        } else {
            fallback
        }
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
