package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "work_segments",
    foreignKeys = [ForeignKey(
        entity = WorkRecordEntity::class,
        parentColumns = ["id"],
        childColumns = ["recordId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("recordId")]
)
data class WorkSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordId: Long,
    val startTime: Long,
    val endTime: Long,
    val minutes: Int,
    val deductRest: Boolean = false,
    val note: String? = null
)
