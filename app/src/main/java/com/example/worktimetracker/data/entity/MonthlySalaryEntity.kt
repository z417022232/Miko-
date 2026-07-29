package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "monthly_salaries")
data class MonthlySalaryEntity(
    @PrimaryKey val month: String,
    val netSalaryCents: Long,
    val payrollMonth: String = "",
    val paymentDate: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
