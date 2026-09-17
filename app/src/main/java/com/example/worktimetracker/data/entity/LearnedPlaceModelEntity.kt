package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 地点学习模型（方案 §三.1，DB v14）。
 *
 * **核心约束：两套锚点不能互相覆盖。**
 * - `configuredLat/Lng` = 用户首次设置/手动修改的锚点，**永久保留、只由用户改**（原则 1）；
 * - `learnedLat/Lng` = 模型学出来的中心，自动平滑更新，但**绝不回写 configured**。
 *
 * 检测路径取哪个锚点由
 * [com.example.worktimetracker.domain.location.PlaceModelResolver.effectiveAnchor] 决定：
 * 学习锚点只有在「已自动生效」且偏移在自动档内时才被采用，否则一律回落到用户配置锚点。
 * 于是「用户手动设置优先」这条原则由**取用顺序**保证，而不是靠自觉。
 *
 * `placeId` 对应 `sites.id`；`sites` 里没有的兜底虚拟地点（设置里的 companyLat 等）
 * 不建模型行 —— 那种情况 `configuredLat/Lng` 本来就为空，无从比较偏移。
 */
@Entity(tableName = "learned_place_models")
data class LearnedPlaceModelEntity(
    /** = `sites.id` */
    @PrimaryKey val placeId: Long,
    /** `WORK` / `NON_WORK`（与 `sites.siteType` 同口径） */
    val placeType: String,
    /** 用户配置锚点快照（写入时的值，用于算偏移与回滚） */
    val configuredLat: Double? = null,
    val configuredLng: Double? = null,
    /** 学习锚点；null = 还没学出来 */
    val learnedLat: Double? = null,
    val learnedLng: Double? = null,
    /** 确认到达用（核心区） */
    val coreRadiusMeters: Double = 100.0,
    /** 靠近 / 离开 / 路过用（过渡区，必须大于核心区） */
    val transitionRadiusMeters: Double = 250.0,
    /** 锚点置信度 0..1；低于 `PlaceModelResolver.AUTO_APPLY_CONFIDENCE` 不参与自动生效 */
    val anchorConfidence: Double = 0.0,
    /** 环境指纹对该地点的支持度 0..1（方案 §三.3 的 fingerprintConfidence） */
    val fingerprintConfidence: Double = 0.0,
    /** 生效版本号，指向 [LearningModelMetaEntity] 中同类型的某一行 */
    val modelVersion: Long = 0,
    /** 当前学习锚点是否为「已自动生效」状态（true 才会被取用） */
    val autoApplied: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val hasLearned: Boolean get() = learnedLat != null && learnedLng != null
}
