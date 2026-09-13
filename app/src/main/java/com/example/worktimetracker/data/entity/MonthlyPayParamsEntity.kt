package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一个计薪月的**浮动参数**（DB v12，计薪规则 v2）。
 *
 * 与 `pay_rate_segments`（跨月复用的分段常量）分工：
 * 这里放「每个月都不一样」的东西 —— 绩效系数、效益奖金、高温补贴、补发、病假工资等。
 *
 * 主键是**计薪月**（如 `2026-07`），与 `monthly_salaries.payrollMonth` 同一口径
 * （发薪日次月 15 日，所以 7 月工资的 `monthly_salaries.month` 是 `2026-08`）。
 *
 * 全部字段都可空/默认 0：没填就按 0 参与推算，不影响既有数据。
 */
@Entity(tableName = "monthly_pay_params")
data class MonthlyPayParamsEntity(
    /** 计薪月，格式 `YYYY-MM` */
    @PrimaryKey val payrollMonth: String,
    /** 绩效系数，文本存（如 `1.0` / `0.8`）；解析失败视为 1.0 */
    val perfCoefficient: String? = null,
    /** Δ月：绩效工资基数的月度增量（分） */
    val perfBaseDeltaCents: Long = 0L,
    /** 绩效工资直接覆盖（分，非空则忽略 (基数+Δ)×系数×出勤折算 的乘积） */
    val perfAmountCents: Long? = null,
    val benefitBonusCents: Long = 0L,
    val heatAllowanceCents: Long = 0L,
    val sickPayCents: Long = 0L,
    val backPayCents: Long = 0L,
    val otherAddCents: Long = 0L,
    /** 社保月度覆盖（分，空则用分段常量）。工资条 1–6 月 783.30、7 月 948.11 */
    val socialOverrideCents: Long? = null,
    /** 公积金月度覆盖（分，空则用分段常量） */
    val housingFundOverrideCents: Long? = null,
    /** 夜班天数覆盖（空则用 App 工时记录统计出的夜班天数） */
    val nightShiftsOverride: Int? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** 是否全为空 —— 用来决定要不要在库里留这一行。 */
    val isEmpty: Boolean
        get() = perfCoefficient.isNullOrBlank() &&
            perfBaseDeltaCents == 0L && perfAmountCents == null &&
            benefitBonusCents == 0L && heatAllowanceCents == 0L &&
            sickPayCents == 0L && backPayCents == 0L && otherAddCents == 0L &&
            socialOverrideCents == null && housingFundOverrideCents == null &&
            nightShiftsOverride == null
}
