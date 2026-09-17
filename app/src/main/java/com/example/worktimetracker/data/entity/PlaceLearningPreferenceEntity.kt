package com.example.worktimetracker.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户对「地点学习校准」的偏好（DB v16）。
 *
 * ## 为什么和 `learned_place_models` 分开
 *
 * 三层语义不能混（详见 `domain.location.PlaceLearningPreference` 的类注释）：
 * - `learning_model_meta.status` = 模型版本是否作废（**算法**决定）；
 * - `learned_place_models.autoApplied` = 影子验证是否通过（**算法**决定）；
 * - 本表 `autoApplyEnabled` = 用户是否允许自动校准 **用它**（**只有用户**决定）。
 *
 * 混在一张表里之后，「模型为什么没生效」就再也答不清：
 * 无法区分「模型坏了」和「用户关了」，而这两件事该做的处理完全不同。
 *
 * ## 缺行 = 启用（迁移因此不需要回填）
 *
 * 表里没有某地点的行 ⟺ 允许自动校准。所以 DB v15 → v16 迁移**只建表、不写数据**：
 * 老库升上来天然就是全启用，零回归是结构上成立的，不依赖迁移脚本写对。
 * 只有用户真的停用过，才会出现第一行。
 *
 * ## 粘性
 *
 * 停用之后学习照常跑：照常观察、照常落候选行、照常升模型版本。
 * 只有**取锚点**这一步会被 `PlaceModelResolver` 挡掉 ——
 * 这样「停用 → 重新开启」不会因为中途重训而漂移，用户偏好与算法事实各记各的账。
 */
@Entity(tableName = "place_learning_preferences")
data class PlaceLearningPreferenceEntity(
    /** = `sites.id`。`sites.id` 是 AUTOINCREMENT（不复用），所以删地点留下的残留行不会误伤新地点。 */
    @PrimaryKey val placeId: Long,
    /**
     * 是否允许学习锚点参与自动校准。
     *
     * `DEFAULT 1` 与「缺行 = 启用」同口径：两条路径都指向「默认允许」，
     * 不会出现「有行且默认 0」这种与缺行语义相反的角落。
     */
    @ColumnInfo(defaultValue = "1") val autoApplyEnabled: Boolean = true,
    /** 用户最后一次改动这个开关的时刻 */
    val updatedAt: Long = System.currentTimeMillis()
)
