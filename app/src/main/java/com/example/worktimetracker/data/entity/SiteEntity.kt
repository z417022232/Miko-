package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户声明的「地点」（v4 界面稿 / DB v11）。
 *
 * 取代早期「单公司 + 单家庭」的硬编码模型：同一个人可以同时有
 * 「加工厂·A 车间（主）」「加工厂·仓库」「家」等多个地点，每个地点各带一组
 * 证据源（Wi-Fi / 蓝牙 / GPS 半径）来匹配。
 *
 * 兼容策略：迁移时把旧的 companyLat/Lng/radius 展开成一条 `siteType=WORK,
 * isPrimary=true` 的记录、homeLat/Lng/radius 展开成一条 `NON_WORK` 记录，
 * 于是老读数路径（WorkSettings）继续可用，新界面走 sites 表。
 *
 * 字段命名注意：主键与布尔列都避开 SQLite 关键字（用 `isPrimary` 而不是 `primary`）。
 */
@Entity(
    tableName = "sites",
    indices = [Index("isPrimary"), Index("siteType")]
)
data class SiteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** [TYPE_WORK] = 计入工时；[TYPE_NON_WORK] = 不计工时（如"家"）。 */
    val siteType: String = TYPE_WORK,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int = DEFAULT_RADIUS_METERS,
    /** 主工作地点：融合判定里优先匹配，同一时刻至多一条为 true。 */
    val isPrimary: Boolean = false,
    val enabled: Boolean = true,
    /** 由旧字段自动迁移生成，仅用于界面提示「来自旧设置」。 */
    val migrated: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** 至少要有 GPS 坐标或一条证据源，才允许保存（与界面文案一致）。 */
    val hasGps: Boolean get() = latitude != null && longitude != null

    companion object {
        const val TYPE_WORK = "WORK"
        const val TYPE_NON_WORK = "NON_WORK"
        const val DEFAULT_RADIUS_METERS = 150

        /** 证据源说明里的推荐半径上限，超出后判定会明显变钝。 */
        const val MIN_RADIUS_METERS = 50
        const val MAX_RADIUS_METERS = 1000
    }
}
