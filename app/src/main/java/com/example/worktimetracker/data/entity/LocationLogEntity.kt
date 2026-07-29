package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "location_logs", indices = [Index("time")])
data class LocationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val locationType: String,
    val provider: String? = null
)
