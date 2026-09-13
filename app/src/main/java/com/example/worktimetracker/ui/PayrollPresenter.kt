package com.example.worktimetracker.ui

import com.example.worktimetracker.domain.payroll.PayRateKey
import com.example.worktimetracker.domain.payroll.PayRateUnit
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * 计薪规则 v2 的纯展示/解析逻辑（无 Compose、无 IO，可直接单测）。
 *
 * 放在这里而不是塞进 Composable，是因为「元 ↔ 分」「小时 ↔ 小时×100」「百分比 ↔ 基点」
 * 三套单位的换算一旦写散，很容易出现「界面显示 3% 但存进库是 3」这种静默错。
 */
object PayrollPresenter {

    /** 当月出勤统计 —— 全部由 App 自己的工时记录算出，不落库。 */
    data class AttendanceStats(
        val attendDays: Int,
        val nightShiftDays: Int,
        val totalMinutes: Int,
    )

    /**
     * 出勤统计口径：
     * - [attendDays]：`finalMinutes > 0` 的天数（出勤折算系数的分子）
     * - [nightShiftDays]：出勤且 `shift == "夜班"` 的天数（夜班津贴的乘数）
     * - [totalMinutes]：当月计薪分钟合计
     *
     * ⚠️ `UiDayRecord.shift` 是**显示标签**（"白班"/"夜班"），不是枚举名。
     */
    fun attendanceStats(records: List<UiDayRecord>): AttendanceStats {
        val worked = records.filter { it.finalMinutes > 0 }
        return AttendanceStats(
            attendDays = worked.size,
            nightShiftDays = worked.count { it.shift == "夜班" },
            totalMinutes = worked.sumOf { it.finalMinutes },
        )
    }

    // ------------------------------------------------------------ 单位换算

    /** 参数值 → 输入框里的文本（不带单位，用户可直接编辑）。 */
    fun valueText(key: PayRateKey, value: Long): String = when (key.unit) {
        PayRateUnit.MONEY_CENTS -> "%.2f".format(Locale.CHINA, value / 100.0)
        PayRateUnit.HOURS_X100 -> "%.2f".format(Locale.CHINA, value / 100.0)
        PayRateUnit.RATE_BP -> "%.2f".format(Locale.CHINA, value / 100.0)
    }

    /** 参数值 → 带单位的展示串。 */
    fun displayValue(key: PayRateKey, value: Long): String = when (key.unit) {
        PayRateUnit.MONEY_CENTS -> "¥%,.2f".format(Locale.CHINA, value / 100.0)
        PayRateUnit.HOURS_X100 -> "%.2f 小时".format(Locale.CHINA, value / 100.0)
        PayRateUnit.RATE_BP -> "%.2f%%".format(Locale.CHINA, value / 100.0)
    }

    /** 输入框后缀（UI 提示单位）。 */
    fun unitSuffix(key: PayRateKey): String = when (key.unit) {
        PayRateUnit.MONEY_CENTS -> "元"
        PayRateUnit.HOURS_X100 -> "小时"
        PayRateUnit.RATE_BP -> "%"
    }

    /**
     * 用户输入 → 参数值。非法返回 null（调用方据此禁用「保存」）。
     *
     * 三种单位都接受 `3` / `3.5` 这种写法；税率额外容忍带 `%`。
     */
    fun parseValue(key: PayRateKey, text: String): Long? {
        val cleaned = text.trim().removeSuffix("%").replace(",", "").trim()
        if (cleaned.isEmpty()) return null
        val dec = cleaned.toBigDecimalOrNull() ?: return null
        if (dec.signum() < 0) return null
        val scaled = dec.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
        val max = when (key.unit) {
            PayRateUnit.MONEY_CENTS -> 100_000_000L      // 100 万元
            PayRateUnit.HOURS_X100 -> 100_000L           // 1000 小时
            PayRateUnit.RATE_BP -> 10_000L               // 100%
        }
        return scaled.coerceAtMost(max)
    }

    /** 绩效系数解析（月度参数）：`1.0` / `0.8`，非法返回 null。 */
    fun parseCoefficient(text: String): BigDecimal? {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return null
        val dec = cleaned.toBigDecimalOrNull() ?: return null
        if (dec.signum() < 0 || dec > BigDecimal("10")) return null
        return dec
    }

    /** 金额输入 → 分；空串视为「不覆盖」返回 null。 */
    fun parseMoneyOrNull(text: String): Long? {
        val cleaned = text.trim().replace(",", "")
        if (cleaned.isEmpty()) return null
        val dec = cleaned.toBigDecimalOrNull() ?: return null
        if (dec.signum() < 0) return null
        return dec.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    /** 分 → 输入框文本（0 显示为 0.00）。 */
    fun moneyText(cents: Long?): String =
        if (cents == null) "" else "%.2f".format(Locale.CHINA, cents / 100.0)

    // ------------------------------------------------------------ 日历 / 日工资

    /**
     * 当日工资标签，如 `9/14 工资 ≈ ¥306.50`。
     *
     * [cents] 为 null 表示还没有基准月（全新安装 / 尚无任何实发录入）→ 返回 null，
     * 界面显示「暂无基准」而不是猜一个数。
     */
    fun dailyPayLabel(month: Int, day: Int, cents: Long?): String? {
        if (cents == null) return null
        return "$month/$day 工资 ≈ ¥%,.2f".format(Locale.CHINA, cents / 100.0)
    }

    /** 基准单价说明文案，如「按 2026-07 实发 ¥7,243.34 ÷ 260h 校准」。 */
    fun baselineText(payrollMonth: String, netCents: Long, minutes: Int): String {
        val hours = minutes / 60.0
        return "按 %s 实发 ¥%,.2f ÷ %.0fh 校准".format(Locale.CHINA, payrollMonth, netCents / 100.0, hours)
    }
}
