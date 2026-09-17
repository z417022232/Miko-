package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 学习模型的版本元数据（方案 §九「通用模型元数据」，DB v14）。
 *
 * 主键是 `(modelType, modelVersion)` —— 同一个模型类型下**多个版本并存**，
 * 这是「模型版本与回滚」（方案 §十一 阶段2）的前提：
 * 回滚不是删行，而是把新版本置 `RETIRED`，把目标版本置 `ACTIVE`。
 *
 * ⚠️ 这张表只描述**模型**，不承载任何事实数据。事实永远在
 * `work_records` / `monthly_salaries` / `location_logs` 等表里，永不覆盖。
 */
@Entity(
    tableName = "learning_model_meta",
    primaryKeys = ["modelType", "modelVersion"],
    indices = [Index(value = ["modelType", "status"])]
)
data class LearningModelMetaEntity(
    /** [com.example.worktimetracker.domain.learning.LearningModelType] 的 name */
    val modelType: String,
    /** 同类型内单调递增；版本号本身不代表新旧以外的任何语义 */
    val modelVersion: Long,
    /** 训练数据覆盖到的口径（如地点模型存 `yyyy-MM-dd`，工资模型存 `yyyy-MM`）；null = 未知 */
    val trainedThrough: String? = null,
    /** 参与训练的样本数（可解释性用，不参与算法） */
    val sampleCount: Int = 0,
    /** [com.example.worktimetracker.domain.learning.ModelStatus] 的 name */
    val status: String = "ACTIVE",
    val createdAt: Long = System.currentTimeMillis(),
    /** 被置为 RETIRED/INVALID 的时刻；ACTIVE 时为 null */
    val invalidatedAt: Long? = null,
    /** 一句话说明这个版本是谁、基于什么训练出来的（回滚时靠它认人） */
    val note: String? = null
)
