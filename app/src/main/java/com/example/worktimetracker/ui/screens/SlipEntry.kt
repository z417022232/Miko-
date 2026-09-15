package com.example.worktimetracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.worktimetracker.domain.payroll.MonthChoice
import com.example.worktimetracker.domain.payroll.SlipItemKey
import com.example.worktimetracker.domain.payroll.SlipItemNature
import com.example.worktimetracker.domain.payroll.SlipItemStage
import com.example.worktimetracker.domain.payroll.SlipReconciler
import com.example.worktimetracker.domain.payroll.SlipStatus
import com.example.worktimetracker.ui.PayrollPresenter
import com.example.worktimetracker.ui.app.forecast.ForecastViewModel
import com.example.worktimetracker.ui.app.forecast.SlipEditorState
import com.example.worktimetracker.ui.app.forecast.SlipItemDraft
import com.example.worktimetracker.ui.theme.AppTheme
import java.time.YearMonth

/*
 * 界面稿 v6 第一步（DB v13）：工资条录入与核对。
 *
 * 交付要点（对应设计稿 §4 / §7 第一步）：
 *   1. 分项金额三态：**留空 = 未填写**、`0` = 明确为零、金额；未填写不进合计。
 *   2. 两条**独立**校验：① Σ收入 − Σ收入侧扣减 = 应发；② 应发 − Σ应发后扣减 = 实发。
 *      绝不把「应发」与「实发」直接相减（那是社保 + 公积金 + 个税，不是错账）。
 *   3. 历史草稿由迁移灌表头 + `SlipDraftSeeder` 灌可确定项；浮动项留空 → 校验差额即「待补项合计」。
 *   4. 「病假工资」等含义有歧义的项默认「待裁决」，由用户确认属性，不自动猜。
 *
 * 与 `monthly_salaries` 的关系：**只读比对**，实发不一致只提示、绝不改那条记录。
 */

private val MONEY_INPUT = Regex("""\d{0,8}([.]\d{0,2})?""")

@Composable
internal fun SlipEntryPage(
    onBack: () -> Unit,
    /**
     * 进来时用户**所在的那个月**（`yyyy-MM`）；null = 自然月。
     * 只作为锚点，真正打开哪个计薪月由 `ForecastViewModel.openAt` 判定。
     */
    initialMonth: String? = null,
    vm: ForecastViewModel = viewModel(),
) {
    val month by vm.month.collectAsState()
    val editor by vm.editor.collectAsState()
    val message by vm.message.collectAsState()
    val choice by vm.pendingChoice.collectAsState()
    val slips by vm.slips.collectAsState()

    // 锚点判定：本月没条子且上月也空着 -> VM 挂起 pendingChoice，下面弹框问用户。
    // 有明确答案（本月已有条 / 上月已录）时直接定月份，不打扰。
    LaunchedEffect(initialMonth) {
        vm.openAt(initialMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() })
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader(
            "工资条录入与核对",
            "照工资条分项录入 · 应发/实发分开校验 · 保留原始数字不自动修正",
            onBack
        )
        Spacer(Modifier.height(8.dp))
        SlipMonthPager(month, vm::previousMonth, vm::nextMonth, vm::today)

        // 上一个计薪月还空着 -> 提醒可补录（不阻塞，用户自己翻页即可）
        val prevMonth = month.minusMonths(1)
        if (slips.isNotEmpty() && slips.none { it.payrollMonth == prevMonth.toString() }) {
            Text(
                "上一个计薪月（${payrollMonthLabel(prevMonth)}）还没有工资条，可用「‹ 上一月」补录。",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.colors.orange
            )
        }

        // 有歧义：这笔算上月补录还是本月工资？用户答完才定
        choice?.let {
            MonthChoiceDialog(
                choice = it,
                onPick = vm::chooseMonth,
                onDismiss = vm::dismissMonthChoice,
            )
        }
        Spacer(Modifier.height(8.dp))

        val state = editor
        if (state == null) {
            Text("正在读取工资条…", color = AppTheme.colors.muted)
            return@Column
        }

        val check = state.check
        CheckCard(state, check)
        Spacer(Modifier.height(6.dp))

        // ------------------------------------------------------------ 表头
        SectionTitle("工资条表头")
        SettingsGroup {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("计薪月 ${state.payrollMonth}", fontWeight = FontWeight.SemiBold)
                if (state.revision > 1 || state.confirmedAt != null) {
                    Text(
                        "第 ${state.revision} 版" + if (state.confirmedAt != null) " · 已确认" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.colors.muted
                    )
                }
                SlipField("发薪日期（YYYY-MM-DD）", state.paymentDate, vm::updatePaymentDate)
                SlipField("条上应发工资（元）", state.grossText, vm::updateGross)
                SlipField("条上实发工资（元）", state.netText, vm::updateNet)
                SlipField("条上计薪出勤天数", state.slipAttendDays, vm::updateAttendDays, decimal = false)
                SlipField("条上计薪夜班数", state.slipNightShifts, vm::updateNightShifts, decimal = false)
                state.recordedNetCents?.let { recorded ->
                    Text(
                        "已录入实发 ${formatCents(recorded)}（权威值 · 本页不会改动它）",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.colors.muted
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ------------------------------------------------------------ 状态
        SectionTitle("状态")
        SettingsGroup {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "「已确认」才可作为学习样本；确认后每次改动会自动升版（revision +1）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.muted
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SlipStatus.entries.forEach { s ->
                        FilterChip(
                            selected = state.status == s,
                            onClick = { vm.setStatus(s) },
                            label = { Text(s.label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ------------------------------------------------------------ 分项
        SectionTitle("工资条分项")
        SettingsGroup {
            var first = true
            SlipItemStage.entries.forEach { stage ->
                if (!first) ThinDivider()
                first = false
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(stageLabel(stage), fontWeight = FontWeight.SemiBold)
                    Text(stageHint(stage), style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.muted)
                }
                SlipItemKey.displayOrder
                    .filter { it.stage == stage }
                    .forEach { key ->
                        ThinDivider()
                        SlipItemRow(
                            key = key,
                            draft = state.items[key] ?: SlipItemDraft(),
                            onText = { vm.updateItem(key, it) },
                            onToggleReview = { vm.toggleItemReview(key) }
                        )
                    }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ------------------------------------------------------------ 备注
        SettingsGroup {
            Column(Modifier.padding(14.dp)) {
                SlipField("备注（可选）", state.note, vm::updateNote)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ------------------------------------------------------------ 操作
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.generateDraft() }, modifier = Modifier.weight(1f)) {
                Text("按计薪参数填")
            }
            OutlinedButton(onClick = { vm.fillFromPreviousMonth() }, modifier = Modifier.weight(1f)) {
                Text("参照上月")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.save() }, modifier = Modifier.weight(1f)) { Text("保存") }
            Button(
                onClick = { vm.setStatus(SlipStatus.CONFIRMED); vm.save() },
                modifier = Modifier.weight(1f)
            ) { Text("保存并确认") }
        }

        if (message.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(message, color = AppTheme.colors.green, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "口径说明：\n" +
                "· 留空 = 未填写，**不参与合计**；填 0 = 明确为零，参与合计。两者不能混。\n" +
                "· 校验①只比「收入项 − 收入侧扣减」与条上应发；校验②只比「应发 − 应发后扣减」与条上实发。\n" +
                "   两条分开看，才不会被社保公积金个税这些正常扣项干扰。\n" +
                "· 历史月份的草稿只填了能由计薪参数确定的部分，浮动项（绩效/效益/高温/夜班/病假）留空，\n" +
                "   校验差额就是「还差多少没录」。\n" +
                "· 本页所有分录都是新表 `salary_slips` / `salary_slip_items`；`monthly_salaries` 只读。",
            style = MaterialTheme.typography.labelSmall,
            color = AppTheme.colors.muted
        )
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------
// 校验结果
// ---------------------------------------------------------------------------

@Composable
private fun CheckCard(state: SlipEditorState, check: SlipReconciler.SlipCheck) {
    SettingsGroup {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (check.isBalanced) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                    null,
                    tint = if (check.isBalanced) AppTheme.colors.green else AppTheme.colors.orange,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(statusHeadline(state), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            CheckLine(
                "① 收入 − 收入侧扣减 = 应发",
                declaredCents = check.declaredGrossCents,
                computedCents = check.computedGrossCents,
                diffCents = check.grossDiffCents
            )
            Spacer(Modifier.height(8.dp))
            CheckLine(
                "② 应发 − 应发后扣减 = 实发",
                declaredCents = state.declaredNetCents,
                computedCents = check.computedNetCents,
                diffCents = check.netDiffCents
            )
            Spacer(Modifier.height(10.dp))
            ThinDivider()
            Spacer(Modifier.height(10.dp))
            if (check.unfilledKeys.isNotEmpty()) {
                Text(
                    "未填写 ${check.unfilledKeys.size} 项（不进合计）：" +
                        check.unfilledKeys.joinToString("、") { it.label },
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.orange
                )
                Spacer(Modifier.height(6.dp))
            } else {
                Text(
                    "分项已填全。",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.muted
                )
                Spacer(Modifier.height(6.dp))
            }
            check.issues.forEachIndexed { index, issue ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                Text(
                    "· ${issue.hint}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (issue.kind == SlipReconciler.IssueKind.MONTHLY_SALARY_MISMATCH) {
                        AppTheme.colors.muted
                    } else {
                        AppTheme.colors.orange
                    }
                )
            }
            if (check.issues.isEmpty()) {
                Text(
                    "两条校验都平，且没有待裁决的属性。",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.green
                )
            }
        }
    }
}

@Composable
private fun CheckLine(
    title: String,
    declaredCents: Long?,
    computedCents: Long,
    diffCents: Long?,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        if (declaredCents == null) {
            Text(
                "条上值未填写 ｜ 已填项推算 ${formatCents(computedCents)}",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.colors.muted
            )
        } else {
            val balanced = diffCents == 0L
            Text(
                "条上 ${formatCents(declaredCents)} ｜ 已填项推算 ${formatCents(computedCents)} ｜ " +
                    if (balanced) "差额 ¥0.00 ✓" else "差额 ${formatCents(diffCents ?: 0L)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (balanced) AppTheme.colors.green else AppTheme.colors.orange
            )
        }
    }
}

private fun statusHeadline(state: SlipEditorState): String {
    val check = state.check
    return when {
        !state.hasSlip -> "还没有这条工资条"
        check.isBalanced -> "校验通过 · 可以确认"
        !check.isComplete -> "草稿 · 还有未填写项"
        else -> "待核对 · 校验有差额"
    }
}

// ---------------------------------------------------------------------------
// 分项行
// ---------------------------------------------------------------------------

@Composable
private fun SlipItemRow(
    key: SlipItemKey,
    draft: SlipItemDraft,
    onText: (String) -> Unit,
    onToggleReview: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(key.label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (key.nature != SlipItemNature.FIXED) {
                Text(
                    natureLabel(key.nature),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.muted
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft.text,
                onValueChange = { input ->
                    if (input.isEmpty() || input.matches(MONEY_INPUT)) onText(input)
                },
                placeholder = { Text("未填写") },
                singleLine = true,
                isError = draft.text.isNotBlank() && PayrollPresenter.parseMoneyOrNull(draft.text) == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            if (draft.text.isEmpty()) {
                TextButton(onClick = { onText("0") }) { Text("记 0") }
            } else {
                TextButton(onClick = { onText("") }) { Text("清空") }
            }
        }
        Text(
            itemStateHint(draft),
            style = MaterialTheme.typography.labelSmall,
            color = if (draft.text.isNotBlank() && PayrollPresenter.parseMoneyOrNull(draft.text) == null) {
                AppTheme.colors.red
            } else {
                AppTheme.colors.muted
            }
        )
        if (key.reviewByDefault || draft.needsReview) {
            TextButton(onClick = onToggleReview) {
                Text(if (draft.needsReview) "此项待裁决（点击标记为已裁决）" else "标记为待裁决")
            }
        }
    }
}

private fun itemStateHint(draft: SlipItemDraft): String {
    if (draft.text.isBlank()) return "未填写（不参与合计）"
    val cents = PayrollPresenter.parseMoneyOrNull(draft.text) ?: return "格式不对"
    return if (cents == 0L) "明确为零（参与合计）" else "已填 ${formatCents(cents)}（参与合计）"
}

// ---------------------------------------------------------------------------
// 小部件
// ---------------------------------------------------------------------------

@Composable
private fun SlipField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    decimal: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            val ok = if (decimal) {
                input.isEmpty() || input.matches(MONEY_INPUT)
            } else {
                input.length <= 2 && input.all { it.isDigit() }
            }
            if (ok) onValueChange(input)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SlipMonthPager(
    month: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
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

private fun stageLabel(stage: SlipItemStage): String = when (stage) {
    SlipItemStage.INCOME -> "收入项"
    SlipItemStage.DEDUCT_PRE_GROSS -> "收入侧扣减"
    SlipItemStage.DEDUCT_POST_GROSS -> "应发后扣减"
}

private fun stageHint(stage: SlipItemStage): String = when (stage) {
    SlipItemStage.INCOME -> "进校验① 的应发"
    SlipItemStage.DEDUCT_PRE_GROSS -> "应发之前扣，所以也影响应发（进校验①）"
    SlipItemStage.DEDUCT_POST_GROSS -> "只影响到手（进校验②），不参与应发"
}

private fun natureLabel(nature: SlipItemNature): String = when (nature) {
    SlipItemNature.FIXED -> "固定"
    SlipItemNature.FLOATING -> "浮动"
    SlipItemNature.ONE_TIME -> "一次性"
}

// ---------------------------------------------------------------------------
// 「这笔是哪个月的工资」提问框
// ---------------------------------------------------------------------------

/**
 * 歧义裁决框。
 *
 * 场景：`monthly_salaries.month` = **发薪月** = 计薪月 + 1。站在 10 月的日历上点录入时，
 * 刚发下来的「9 月工资」还没录、而当月也想录 —— 两个都对，所以必须问，不能替用户猜。
 */
@Composable
private fun MonthChoiceDialog(
    choice: MonthChoice,
    onPick: (YearMonth) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("这笔工资是哪个月的？") },
        text = {
            Column {
                Text(
                    "${payrollMonthLabel(choice.anchor)}的日历上还没有工资条，" +
                        "而${payrollMonthLabel(choice.previous)}也还空着，分不清这笔是补上月还是录当月。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "计薪月是干活那个月，发薪月 = 计薪月 + 1：" +
                        "${payrollMonthLabel(choice.previous)}的工资在 " +
                        "${payrollMonthLabel(choice.previous.plusMonths(1))} 发。",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.muted
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(choice.previous) }) {
                Text("${payrollMonthLabel(choice.previous)}（补录）")
            }
        },
        dismissButton = {
            TextButton(onClick = { onPick(choice.anchor) }) {
                Text("${payrollMonthLabel(choice.anchor)}")
            }
        }
    )
}

private fun payrollMonthLabel(month: YearMonth): String = "${month.year} 年 ${month.monthValue} 月"
