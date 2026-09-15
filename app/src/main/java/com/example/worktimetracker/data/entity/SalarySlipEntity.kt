package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.worktimetracker.domain.payroll.SlipStatus

/**
 * 工资条主表（DB v13，「计薪预测与发薪对账」第一步）。
 *
 * 与 `monthly_salaries` 的分工：
 * - `monthly_salaries` = 用户原来的「只录一个实发」记录，**只读、永不改**（保留兼容）。
 * - 本表 = 结构化工资条，含应发、实发、条上出勤/夜班数、确认状态、修订号。
 *
 * 主键是**计薪月**（`YYYY-MM`），与 `monthly_salaries.payrollMonth`、`monthly_pay_params.payrollMonth`
 * 同一口径（发薪日是次月 15 日，所以 7 月工资的 `monthly_salaries.month` 是 `2026-08`）。
 */
@Entity(tableName = "salary_slips")
data class SalarySlipEntity(
    /** 计薪月，格式 `YYYY-MM` */
    @PrimaryKey val payrollMonth: String,
    /** 实际发薪日期 `YYYY-MM-DD`；空串 = 未知 */
    val paymentDate: String = "",
    /** [SlipStatus] 的 `name` */
    val status: String = SlipStatus.DRAFT.name,
    /** 工资条上印的「计薪出勤天数」——用于与本机工时记录对账 */
    val slipAttendDays: Int? = null,
    /** 工资条上印的「计薪夜班数」——用于核对夜班津贴天数 */
    val slipNightShifts: Int? = null,
    /** 条上「应发工资」（分）。null = 未填写 */
    val declaredGrossCents: Long? = null,
    /** 条上「实发工资」（分）。null = 未填写 */
    val declaredNetCents: Long? = null,
    /** 确认时间（epoch millis）。null = 未确认 → **不参与学习** */
    val confirmedAt: Long? = null,
    /** 修订号：已确认后每次改动 +1；预测快照与对账按旧 revision 引用，不回写 */
    val revision: Int = 1,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
