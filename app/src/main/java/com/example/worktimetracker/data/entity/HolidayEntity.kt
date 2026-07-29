package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "holidays")
data class HolidayEntity(
    @PrimaryKey val date: String,
    val name: String,
    val type: String,
    val source: String = "local"
)
