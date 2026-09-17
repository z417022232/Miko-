package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 锚点候选（方案 §三.2「锚点自动校准」流程，DB v14）。
 *
 * 流程：高精度 GPS → 核心区稳定停留 → 环境指纹支持 → 聚类剔离群 → **候选** →
 * 影子运行验证 → 自动平滑更新 / 请求用户确认。
 *
 * 候选表是这条链路的**落地点**：每次学习都写一行，`status` 记录它走到哪一步。
 * 它只描述「观察到什么」，**不参与判定** —— 判定读的是
 * [LearnedPlaceModelEntity]，而且只有 `autoApplied = true` 时才被取用。
 *
 * ⚠️ 状态机与地点判定**永远不读这张表**（方案 §十一 阶段2 的「影子验证」语义）。
 */
@Entity(
    tableName = "place_anchor_candidates",
    indices = [Index(value = ["placeId", "status"]), Index(value = ["placeId", "lastSeenAt"])]
)
data class PlaceAnchorCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** = `sites.id` */
    val placeId: Long,
    /** 聚类后的候选中心（剔除离群点之后） */
    val centerLat: Double,
    val centerLng: Double,
    /** 参与聚类的有效样本数与其中跨了几个自然日 */
    val sampleCount: Int,
    val distinctDayCount: Int,
    /** 支持该地点的环境来源类数（Wi-Fi/蓝牙/基站，≥2 才算达标） */
    val ambientSourceCount: Int,
    /** 核心区连续稳定停留时长（毫秒） */
    val stableMillis: Long,
    /** 候选中心首次 / 最近一次出现的时刻（用于「连续 7 天稳定」判定） */
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    /** 候选中心与**用户配置锚点**的偏移（米）。分档依据，永不用于覆盖配置锚点。 */
    val offsetMeters: Double,
    /** [com.example.worktimetracker.domain.location.AnchorCandidateStatus] 的 name */
    val status: String,
    /** 该候选归属的模型版本（便于回滚时一并追溯） */
    val modelVersion: Long = 0,
    /** 一句话解释：为什么判定成这个状态（方案 §一 原则 6「每次预测必须能解释依据」） */
    val explanation: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
