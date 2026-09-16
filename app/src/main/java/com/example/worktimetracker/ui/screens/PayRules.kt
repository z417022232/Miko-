package com.example.worktimetracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.WorkHistory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.worktimetracker.data.entity.MonthlyPayParamsEntity
import com.example.worktimetracker.domain.payroll.PayRateKey
import com.example.worktimetracker.domain.payroll.PayrollBreakdown
import com.example.worktimetracker.ui.PayrollPresenter
import com.example.worktimetracker.ui.app.MonthProjection
import com.example.worktimetracker.ui.app.MonthlyPayDraft
import com.example.worktimetracker.ui.app.PayRateRowUi
import com.example.worktimetracker.ui.app.WorkTimeViewModel
import com.example.worktimetracker.ui.theme.AppTheme
import java.time.YearMonth

/*
 * 界面稿 v4 屏 13 重做（计薪规则 v2 / 2026-09-13）。
 *
 * v1 是「工时 × 基本时薪」的扁平口径，页脚只能写一句"不含倍率"——用户拿着工资条
 * 根本不知道「基本时薪」该填什么（真机实测填的是 0，等于整页没有数）。
 *
 * v2 把公司真实公式做成引擎（见 verification/计薪规则v2-工资条口径.md），本页因此
 * 从「一个输入框」变成两块：
 *   1. 计薪参数（**分段常量**）：调薪只加一段，回看历史月份仍是当时的数值
 *   2. 月度计薪参数：绩效系数 / 效益奖金 / 高温 / 补发 / 病假 / 社保公积金覆盖
 * 页脚改成写清公式来源与「已录入实发优先」的规则。
 */

// ---------------------------------------------------------------------------
// 页面
// ---------------------------------------------------------------------------

@Composable
internal fun PayRulesPage(
    vm: WorkTimeViewModel,
    onBack: () -> Unit,
) {
    val settings by vm.settings.collectAsState()
    val month by vm.month.collectAsState()
    val segments by vm.payRateSegments.collectAsState()
    val paramsMap by vm.payParams.collectAsState()

    var editingKey by remember { mutableStateOf<PayRateKey?>(null) }
    var showParams by remember { mutableStateOf(false) }
    var showDefault by remember { mutableStateOf(false) }

    val monthKey = month.toString()
    val params = paramsMap[monthKey]

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader(
            "计薪规则",
            "按厂里工资条口径推算 · 只影响金额显示，不动工时记录",
            onBack = onBack
        )
        Spacer(Modifier.height(10.dp))

        // ---------------------------------------------------------- 月份
        MonthPager(month = month, onPrev = vm::previousMonth, onNext = vm::nextMonth, onToday = vm::today)
        Spacer(Modifier.height(4.dp))

        SettingsGroup {
            SettingsRow(
                Icons.Outlined.WorkHistory,
                "本月计薪参数",
                if (params == null) "未设置 · 绩效系数按 1.0 计" else monthlyParamsSummary(params)
            ) { showParams = true }
            ThinDivider()
            SettingsRow(
                Icons.Outlined.Timer,
                "每日标准工时",
                if (settings.hasDefaultHours) {
                    "${formatMinutes(settings.defaultWorkMinutes ?: 0)}（固定工时已开启）"
                } else {
                    "未开启 · 按实际定位时长扣休息"
                }
            ) { showDefault = true }
        }
        Spacer(Modifier.height(16.dp))

        // ------------------------------------------------------ 分段常量
        SectionLabel("计薪参数（${month.monthValue} 月适用）")
        SettingsGroup {
            PayRateKey.displayOrder.forEachIndexed { index, key ->
                if (index > 0) ThinDivider()
                val row = remember(segments, monthKey, key) { vm.payRateRow(key, monthKey) }
                SettingsRow(
                    icon = payRateIcon(key),
                    title = key.label,
                    summary = buildString {
                        append(PayrollPresenter.displayValue(key, row.value))
                        row.effectiveFrom?.let { append("　自 $it 起") }
                        if (row.segments.size > 1) append("　（共 ${row.segments.size} 段）")
                    }
                ) { editingKey = key }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "调薪、社保变基数都只要「新增一段」并选生效月份；回看之前的月份仍是当时的数值，不用逐月复制。",
            style = MaterialTheme.typography.labelSmall,
            color = AppTheme.colors.muted
        )
        Spacer(Modifier.height(12.dp))
    }

    editingKey?.let { key ->
        PayRateEditDialog(
            key = key,
            row = vm.payRateRow(key, monthKey),
            monthKey = monthKey,
            vm = vm,
            onDismiss = { editingKey = null }
        )
    }
    if (showParams) {
        MonthlyPayParamsDialog(
            month = month,
            draft = MonthlyPayDraft.of(params),
            vm = vm,
            onDismiss = { showParams = false }
        )
    }
    if (showDefault) {
        DefaultHoursDialog(settings, vm, onDismiss = { showDefault = false })
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = AppTheme.colors.muted,
        modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
    )
}

@Composable
private fun MonthPager(
    month: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextButton(onClick = onPrev) { Text("‹ 上一月") }
        Text(
            "${month.year} 年 ${month.monthValue} 月",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        TextButton(onClick = onNext) { Text("下一月 ›") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onToday) { Text("回到本月") }
    }
}

// ---------------------------------------------------------------------------
// 单一参数编辑（值 + 生效月），并可删除历史分段
// ---------------------------------------------------------------------------

@Composable
private fun PayRateEditDialog(
    key: PayRateKey,
    row: PayRateRowUi,
    monthKey: String,
    vm: WorkTimeViewModel,
    onDismiss: () -> Unit
) {
    var text by remember(key, monthKey) { mutableStateOf(PayrollPresenter.valueText(key, row.value)) }
    var effectiveFrom by remember(key, monthKey) { mutableStateOf(monthKey) }
    var note by remember(key, monthKey) { mutableStateOf("") }
    val validMonth = remember(effectiveFrom) { Regex("""\d{4}-\d{2}""").matches(effectiveFrom.trim()) }
    val parsed = remember(text, key) { PayrollPresenter.parseValue(key, text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(key.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    key.hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.muted
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("数值") },
                    suffix = { Text(PayrollPresenter.unitSuffix(key)) },
                    singleLine = true,
                    isError = text.isNotBlank() && parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = effectiveFrom,
                    onValueChange = { effectiveFrom = it },
                    label = { Text("生效月份（YYYY-MM）") },
                    singleLine = true,
                    isError = !validMonth,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选，如「7 月调薪」）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (row.segments.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "历史分段（删除只影响推算，不动任何工资记录）",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.colors.muted
                    )
                    row.segments.forEach { seg ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "自 ${seg.effectiveFrom} 起　${PayrollPresenter.displayValue(key, seg.value)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTheme.colors.muted
                            )
                            TextButton(onClick = { vm.deletePayRateSegment(seg.id) }) { Text("删除") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.savePayRateSegment(
                        key,
                        text,
                        effectiveFrom.trim(),
                        note.trim().ifBlank { null }
                    )
                    onDismiss()
                },
                enabled = parsed != null && validMonth
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ---------------------------------------------------------------------------
// 月度计薪参数
// ---------------------------------------------------------------------------

@Composable
private fun MonthlyPayParamsDialog(
    month: YearMonth,
    draft: MonthlyPayDraft,
    vm: WorkTimeViewModel,
    onDismiss: () -> Unit
) {
    val monthKey = month.toString()
    // v7.2：这里只留「绩效系数」。其余 10 项（效益奖金/高温/病假/补发/其他加项/社保公积金覆盖/
    // 夜班天数）**不再手工填** —— 它们由「工资条录入」按条上的分项落库后自动喂给引擎，
    // 所以保存时必须走「只改系数、保留其余」的路径，绝不能整行覆盖把导入值抹掉。
    var coefficient by remember(monthKey) { mutableStateOf(draft.perfCoefficient) }

    val coefficientOk = coefficient.isBlank() || PayrollPresenter.parseCoefficient(coefficient) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${month.monthValue} 月计薪参数") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "只填绩效系数。其余分项（效益奖金、高温、病假、补发、社保公积金等）" +
                        "在你「从相册导入工资条」时按条上的数字自动记录，不用手填。",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.muted
                )
                Spacer(Modifier.height(10.dp))
                PayField("绩效系数", coefficient, { coefficient = it }, "如 1.0 / 0.8", error = !coefficientOk)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.savePerfCoefficient(monthKey, coefficient)
                    onDismiss()
                },
                enabled = coefficientOk
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PayField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    decimal: Boolean = true,
    error: Boolean = false
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { input ->
                val ok = if (decimal) {
                    input.isEmpty() || input.matches(Regex("""\d{0,8}([.]\d{0,2})?"""))
                } else {
                    input.length <= 2 && input.all { it.isDigit() }
                }
                if (ok) onValueChange(input)
            },
            label = { Text(label) },
            singleLine = true,
            isError = error,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (error || hint.isNotBlank()) {
            Text(
                if (error) "格式不对" else hint,
                style = MaterialTheme.typography.labelSmall,
                color = if (error) AppTheme.colors.red else AppTheme.colors.muted,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 辅助
// ---------------------------------------------------------------------------

/** 参数 → 图标（只用 material-icons-extended 里确实存在的 outlined 图标）。 */
private fun payRateIcon(key: PayRateKey): ImageVector = when (key) {
    PayRateKey.BASIC_SALARY -> Icons.Outlined.Paid
    PayRateKey.POST_ALLOWANCE -> Icons.Outlined.Receipt
    PayRateKey.SENIOR_ALLOWANCE -> Icons.Outlined.TrendingUp
    PayRateKey.FULL_ATTENDANCE -> Icons.Outlined.Bolt
    PayRateKey.PERF_BASE -> Icons.Outlined.Timer
    PayRateKey.NIGHT_ALLOWANCE_UNIT -> Icons.Outlined.NightsStay
    PayRateKey.OT_PACKAGE_HOURS -> Icons.Outlined.WorkHistory
    PayRateKey.SOCIAL_INSURANCE -> Icons.Outlined.AccountBalance
    PayRateKey.HOUSING_FUND -> Icons.Outlined.Savings
    PayRateKey.TAX_THRESHOLD -> Icons.Outlined.CalendarMonth
    PayRateKey.TAX_RATE_BP -> Icons.Outlined.Percent
}

private fun monthlyParamsSummary(p: MonthlyPayParamsEntity): String {
    val parts = buildList {
        p.perfCoefficient?.takeIf { it.isNotBlank() }?.let { add("系数 $it") }
        if (p.perfBaseDeltaCents != 0L) add("Δ${formatCents(p.perfBaseDeltaCents)}")
        p.perfAmountCents?.let { add("绩效 ${formatCents(it)}") }
        if (p.benefitBonusCents != 0L) add("效益 ${formatCents(p.benefitBonusCents)}")
        if (p.heatAllowanceCents != 0L) add("高温 ${formatCents(p.heatAllowanceCents)}")
        if (p.sickPayCents != 0L) add("病假 ${formatCents(p.sickPayCents)}")
        if (p.backPayCents != 0L) add("补发 ${formatCents(p.backPayCents)}")
        p.socialOverrideCents?.let { add("社保 ${formatCents(it)}") }
        p.housingFundOverrideCents?.let { add("公积金 ${formatCents(it)}") }
        p.nightShiftsOverride?.let { add("夜班 ${it}夜") }
    }
    return if (parts.isEmpty()) "已设置" else parts.joinToString(" · ")
}
