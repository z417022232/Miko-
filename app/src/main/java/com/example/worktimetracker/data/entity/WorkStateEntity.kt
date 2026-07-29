package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "work_state")
data class WorkStateEntity(
    @PrimaryKey val id: Int = 1,
    val currentState: String = "REST",
    val lastLocationTime: Long? = null,
    val lastLatitude: Double? = null,
    val lastLongitude: Double? = null,
    val sessionStart: Long? = null,
    val tempLeaveStart: Long? = null,
    val sessionType: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
