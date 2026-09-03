package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 自动学习的环境指纹（已脱敏：identifierHash 为加盐哈希，不含原始 SSID/BSSID/地址）。
 */
@Entity(
    tableName = "environment_fingerprints",
    primaryKeys = ["place", "source", "identifierHash"],
    indices = [Index("lastObservedAt"), Index(value = ["place", "source", "level"])]
)
data class EnvironmentFingerprintEntity(
    val place: String,
    val source: String,
    val identifierHash: String,
    val observationCount: Int,
    val distinctDayCount: Int,
    val lastObservedDay: String,
    val lastObservedAt: Long,
    val minSignal: Int,
    val maxSignal: Int,
    val level: String,
    val discriminative: Boolean
)
