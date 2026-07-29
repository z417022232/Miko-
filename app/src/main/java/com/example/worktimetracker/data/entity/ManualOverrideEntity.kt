package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "manual_overrides",
    foreignKeys = [ForeignKey(
        entity = WorkRecordEntity::class,
        parentColumns = ["id"],
        childColumns = ["recordId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("recordId")]
)
data class ManualOverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordId: Long,
    val oldValue: String? = null,
    val newValue: String,
    val reason: String? = null,
    val modifiedAt: Long = System.currentTimeMillis()
)
