package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 短期证据观察明细：只保留 30 天，且每个来源最多 10,000 条。
 */
@Entity(
    tableName = "evidence_observations",
    indices = [Index("eventTime"), Index(value = ["source", "placeHint"])]
)
data class EvidenceObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventTime: Long,
    val receivedAt: Long,
    val source: String,
    val quality: Double,
    val placeHint: String,
    val identifierHash: String?,
    val signal: Int?,
    val usedForEvent: Boolean,
    /** 绝对定位来源标记（gps/network/passive），环境证据为 wifi/bluetooth/cell */
    val provider: String? = null,
    /** 原始精度（米），环境证据为 null；排查误判时区分 GPS 15m 与 Network 25m */
    val accuracyMeters: Float? = null
)
