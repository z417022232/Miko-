package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 各定位来源（gnss/wifi/bluetooth/cell/motion）的健康状态，用于后台可靠性评估。
 */
@Entity(tableName = "location_health")
data class LocationHealthEntity(
    @PrimaryKey val name: String,
    val lastCallbackAt: Long,
    val lastSuccessAt: Long,
    val registered: Boolean,
    val recoveryCount: Int,
    val lastFailure: String?
)
