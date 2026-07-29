package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "work_records",
    indices = [Index(value = ["workDate"], unique = true)]
)
data class WorkRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workDate: String,
    val status: String,
    val shift: String? = null,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val actualMinutes: Int? = null,
    val finalMinutes: Int = 0,
    val isManual: Boolean = false,
    val needsReview: Boolean = false,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
