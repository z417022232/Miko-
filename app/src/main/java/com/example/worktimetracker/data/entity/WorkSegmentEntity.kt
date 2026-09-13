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
    /** 补录时段类型：WORK = 在岗（计入）/ OFF_SITE = 离厂（不计入）。见界面稿「补录时段」。 */
    val segmentType: String = TYPE_WORK,
    /** 归属地点（sites.id），可为空表示未指认地点。 */
    val siteId: Long? = null,
    /** 地点显示名快照：地点被改名或删除后，历史时段仍然可读。 */
    val siteLabel: String? = null,
    val note: String? = null
) {
    companion object {
        const val TYPE_WORK = "WORK"
        const val TYPE_OFF_SITE = "OFF_SITE"
    }
}
