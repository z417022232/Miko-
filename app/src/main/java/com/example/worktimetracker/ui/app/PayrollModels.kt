package com.example.worktimetracker.ui.app

import com.example.worktimetracker.data.entity.MonthlyPayParamsEntity
import com.example.worktimetracker.data.entity.PayRateSegmentEntity
import com.example.worktimetracker.domain.payroll.PayRateKey
import com.example.worktimetracker.ui.PayrollPresenter

/**
 * 计薪规则 v2 的界面模型（放同包，避免 Compose 侧写成 `WorkTimeViewModel.XxxDraft`）。
 *
 * 全部是**只读展示 + 输入草稿**，没有任何落库字段 —— 推算结果永不写库。
 */

/**
 * 日工资的「基准月」：最近一个「已手动录入实发」且「出勤分钟 > 0」的计薪月。
 *
 * 当日工资 = 当日计薪分钟 × (基准月实发 ÷ 基准月出勤分钟)。用真实到手数据校准，
 * 而不是拿推算值去猜（用户 2026-09-13 选定的口径）。
 */
data class PayBaseline(
    val payrollMonth: String,
    val netCents: Long,
    val minutes: Int,
)

/** 计薪规则页的一行：参数 + 该计薪月适用值 + 生效月 + 全部分段历史。 */
data class PayRateRowUi(
    val key: PayRateKey,
    val value: Long,
    val effectiveFrom: String?,
    val segments: List<PayRateSegmentEntity>,
)

/** 「本月计薪参数」对话框的绑定值（全部为输入框文本，空串 = 不设置）。 */
data class MonthlyPayDraft(
    val perfCoefficient: String = "",
    val perfBaseDelta: String = "",
    val perfAmount: String = "",
    val benefitBonus: String = "",
    val heatAllowance: String = "",
    val sickPay: String = "",
    val backPay: String = "",
    val otherAdd: String = "",
    val socialOverride: String = "",
    val housingFundOverride: String = "",
    val nightShiftsOverride: String = "",
) {
    companion object {
        fun of(entity: MonthlyPayParamsEntity?): MonthlyPayDraft {
            if (entity == null) return MonthlyPayDraft()
            fun money(v: Long?): String =
                if (v == null) "" else "%.2f".format(java.util.Locale.CHINA, v / 100.0)
            return MonthlyPayDraft(
                perfCoefficient = entity.perfCoefficient.orEmpty(),
                perfBaseDelta = if (entity.perfBaseDeltaCents == 0L) "" else money(entity.perfBaseDeltaCents),
                perfAmount = money(entity.perfAmountCents),
                benefitBonus = if (entity.benefitBonusCents == 0L) "" else money(entity.benefitBonusCents),
                heatAllowance = if (entity.heatAllowanceCents == 0L) "" else money(entity.heatAllowanceCents),
                sickPay = if (entity.sickPayCents == 0L) "" else money(entity.sickPayCents),
                backPay = if (entity.backPayCents == 0L) "" else money(entity.backPayCents),
                otherAdd = if (entity.otherAddCents == 0L) "" else money(entity.otherAddCents),
                socialOverride = money(entity.socialOverrideCents),
                housingFundOverride = money(entity.housingFundOverrideCents),
                nightShiftsOverride = entity.nightShiftsOverride?.toString().orEmpty(),
            )
        }
    }
}

/**
 * 「整月预估」：把还没记录的日子按标准工时补足后的整月到手。
 *
 * 金额口径 = **预估工时 × 基准月到手单价**（用户 2026-09-14 选定），与日历上的
 * 「当日工资 ≈」同源；和「按公式推算」是两套口径，故意并列显示 ——
 * 公式法用工资条的分项结构（月度浮动项没填时会偏低），单价法用已录入实发校准。
 * 同样**永不落库**。
 */
data class MonthProjection(
    val stats: PayrollPresenter.ProjectionStats,
    val netCents: Long,
    val hourlyCents: Long,
    val standardMinutes: Int,
    val baseline: PayBaseline,
)
