package com.example.worktimetracker.data.entity

import androidx.room.Entity

/**
 * 工资条分项（DB v13）。
 *
 * **核心语义：[amountCents] 可空。**
 * - `null` = **未填写** → 不进合计，进入「未填写清单」
 * - `0`    = **明确为零**（工资条上确实印着 0）→ 进合计
 *
 * 这正是 v5.x 的老毛病：`monthly_pay_params` 的字段全是非空 `Long = 0L`，
 * 于是"没填"和"填了 0"被混为一谈，浮动项一未填就被当 0 参与推算。
 *
 * 主键 `(payrollMonth, itemKey)` —— `payrollMonth` 是主键最左列，
 * 按月份查询天然走主键前缀，无需额外索引。
 */
@Entity(
    tableName = "salary_slip_items",
    primaryKeys = ["payrollMonth", "itemKey"]
)
data class SalarySlipItemEntity(
    val payrollMonth: String,
    /** [com.example.worktimetracker.domain.payroll.SlipItemKey.storageKey] */
    val itemKey: String,
    /** 条上印的名称（原样保留，便于对着纸质条核对） */
    val rawLabel: String? = null,
    /** 金额（分）。null = 未填写；0 = 明确为零 */
    val amountCents: Long? = null,
    /** [com.example.worktimetracker.domain.payroll.SlipItemStage] 的 `name` */
    val stage: String,
    /** [com.example.worktimetracker.domain.payroll.SlipItemNature] 的 `name` */
    val nature: String,
    /** 原始输入的展示文本（如 `331.03` / `—`）——「保留原始数字，不自动修正」 */
    val rawText: String? = null,
    /** 含义待用户裁决（如"病假"是收入还是扣款） */
    val needsReview: Boolean = false,
    val note: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
