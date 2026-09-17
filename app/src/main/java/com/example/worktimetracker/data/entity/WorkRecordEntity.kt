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
    val homeDepartureTime: Long? = null,
    val homeArrivalTime: Long? = null,
    val actualMinutes: Int? = null,
    val finalMinutes: Int = 0,
    /**
     * `finalMinutes` 的**来源**（方案 §十一 阶段1「固定工时与实际工时分离」）。
     *
     * 取值见 [com.example.worktimetracker.domain.model.FinalMinutesSource]；
     * null = 迁移前的老记录，来源未知（不得回填猜测）。
     * 学习（阶段4/5）只采信 `ACTUAL`：固定工时/推算/人工都不是「真实出勤时长」。
     */
    val finalMinutesSource: String? = null,
    /**
     * 该工作日**首次被观测到**的时刻（方案 §十一 阶段1「候选时间与确认时间分离」）。
     *
     * 与 `createdAt`（落库时刻）的区别：`work_state` 里的候选时刻可能因断流、
     * 重启而推迟，本字段保留的是**最早那条可靠证据的时间**，
     * 与 `TrajectoryAnchorEngine` 的 `firstObservedAt` 语义一致。
     */
    val firstObservedAt: Long? = null,
    val isManual: Boolean = false,
    val manualFieldsMask: Int = 0,
    val needsReview: Boolean = false,
    /** A2: needsReview 的结构化原因（如 "R3 21:00-21:29 灰区"）。null 表示无需复核。 */
    val reviewReason: String? = null,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
