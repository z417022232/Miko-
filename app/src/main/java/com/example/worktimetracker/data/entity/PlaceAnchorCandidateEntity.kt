package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 锚点候选（方案 §三.2「锚点自动校准」流程，DB v14 / v15）。
 *
 * 流程：高精度 GPS → 核心区稳定停留 → 环境指纹支持 → 聚类剔离群 → **候选** →
 * 影子运行验证 → 自动平滑更新 / 请求用户确认。
 *
 * 候选表是这条链路的**落地点**，而且自 v15 起是**「一个影子窗口每天一行」的观测日志**：
 * - 同一自然日重复学习 → **原地更新**该行；
 * - 跨到新的一天且候选没变 → **插入新行**，并沿用同一个 [firstSeenAt]；
 * - 候选移动 ≥10 米或状态变化 → 插入新行且 `firstSeenAt = now`（**重开影子窗口**）。
 *
 * 于是「影子窗口」= **共享同一个 [firstSeenAt] 的一组行**，而
 * [com.example.worktimetracker.domain.location.ShadowValidator] 的六个条件
 * 全部可以从这组行**重建**出来 —— 这就是「模型必须能从原始数据全量重建」在候选层的落法。
 *
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
    /**
     * 影子窗口的起始时刻。**同一窗口内的所有行共用它** —— 它是窗口的身份，
     * 也是「前向观察了几天」的计时起点（**不追溯历史稳定性**，见 `ShadowValidator` 的说明）。
     */
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    /** 候选中心与**用户配置锚点**的偏移（米）。分档依据，永不用于覆盖配置锚点。 */
    val offsetMeters: Double,
    /** [com.example.worktimetracker.domain.location.AnchorCandidateStatus] 的 name */
    val status: String,
    /**
     * 该次观测的 P90 离散度（米），DB v15 起。
     * null = v15 之前的行，**未知**（不许当 0，0 是「完美集中」）→ 影子验证会因缺读数而不通过。
     */
    val spreadP90Meters: Double? = null,
    /** 该候选归属的模型版本（便于回滚时一并追溯） */
    val modelVersion: Long = 0,
    /** 一句话解释：为什么判定成这个状态（方案 §一 原则 6「每次预测必须能解释依据」） */
    val explanation: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
