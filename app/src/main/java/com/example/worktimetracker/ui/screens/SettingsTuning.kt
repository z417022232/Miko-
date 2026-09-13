package com.example.worktimetracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.worktimetracker.ui.app.WorkTimeViewModel
import com.example.worktimetracker.ui.theme.AppTheme
import java.util.Locale

/*
 * 界面稿 v4 屏 13/14/15/18：计薪规则、常规采集间隔、Burst 上限、清空确认。
 *
 * 与 SettingsScreen.kt 分开是为了让 ROOT 的四组骨架保持可读——
 * 这四个页面各自都带一段"为什么是这样"的说明文案，塞回主文件会淹掉导航结构。
 */

// ---------------------------------------------------------------------------
// 13 · 计薪规则
// ---------------------------------------------------------------------------

/**
 * 计薪规则页。
 *
 * 口径已由用户 2026-09-13 明确：**只按「工时 × 基本时薪」，不做 1.5× / 2.0× / 3.0× 倍率**。
 * 所以稿子里的倍率五行换成"时薪 / 每日标准工时 / 结算日"三项，并在页脚把"不含倍率"写死，
 * 免得用户以为少算了钱。
 */
@Composable
internal fun PayRulesPage(vm: WorkTimeViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    var showRate by remember { mutableStateOf(false) }
    var showDefault by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader("计薪规则", "只影响工资估算，不影响工时记录", onBack = onBack)
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingsRow(
                Icons.Outlined.Paid,
                "基本时薪",
                formatHourlyRate(settings.hourlyRateCents) ?: "未设置 · 设置后日历与今日页显示金额"
            ) { showRate = true }
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
            ThinDivider()
            SettingsRow(
                Icons.Outlined.EventNote,
                "月度结算日",
                "每月 15 日（次月发放上月工资）",
                tint = AppTheme.colors.muted,
                showChevron = false
            ) {}
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "本期只按「工时 × 基本时薪」估算，不含平时加班 1.5×、休息日 2.0×、法定节假日 3.0× 倍率。\n" +
                "「加班」只是把每天超出 8 小时的部分单独列出来看看，不参与金额计算。\n" +
                "实际发多少以厂里工资单为准。",
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.muted
        )
        Spacer(Modifier.height(12.dp))
    }

    if (showRate) {
        HourlyRateDialog(settings.hourlyRateCents, vm, onDismiss = { showRate = false })
    }
    if (showDefault) {
        DefaultHoursDialog(settings, vm, onDismiss = { showDefault = false })
    }
}

@Composable
private fun HourlyRateDialog(currentCents: Long, vm: WorkTimeViewModel, onDismiss: () -> Unit) {
    var text by remember(currentCents) {
        mutableStateOf(if (currentCents > 0L) "%.2f".format(Locale.CHINA, currentCents / 100.0) else "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("基本时薪") },
        text = {
            Column {
                Text(
                    "填你厂里的基本时薪（元/小时）。有了它，日历和今日页才会显示工资估算。",
                    color = AppTheme.colors.muted,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        if (input.matches(Regex("""\d{0,6}([.]\d{0,2})?"""))) text = input
                    },
                    label = { Text("时薪") },
                    prefix = { Text("¥ ") },
                    suffix = { Text("/ 小时") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.saveHourlyRate(text); onDismiss() },
                enabled = text.toDoubleOrNull() != null
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                // 允许清除：写 0 而不是留空，viewModel 的解析器不接受空串
                if (currentCents > 0L) {
                    TextButton(onClick = { vm.saveHourlyRate("0"); onDismiss() }) { Text("清除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

// ---------------------------------------------------------------------------
// 14 · 常规采集间隔
// ---------------------------------------------------------------------------

/** 采集间隔可选档位：[分钟] to [代价说明]。 */
private val SAMPLING_INTERVAL_OPTIONS = listOf(
    1 to "最准，耗电最高",
    3 to "偏准，耗电偏高",
    5 to "推荐 · 准确度与续航的平衡",
    10 to "最省电，判定有延迟"
)

@Composable
internal fun SamplingIntervalPage(vm: WorkTimeViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val current = settings.samplingIntervalMinutes

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader("常规采集间隔", "间隔越短，进出车间的判定越及时，但耗电越高", onBack = onBack)
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SAMPLING_INTERVAL_OPTIONS.forEachIndexed { index, (minutes, note) ->
                if (index > 0) ThinDivider()
                ChoiceRow(
                    title = "$minutes 分钟",
                    note = note,
                    selected = current == minutes
                ) { vm.saveSamplingInterval(minutes) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "地点变更后 App 会临时进入 Burst 快速采集（1 分钟一次），不受这一项影响；" +
                "Burst 的上限在「Burst 上限」页单独设置。",
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.muted
        )
        Spacer(Modifier.height(12.dp))
    }
}

// ---------------------------------------------------------------------------
// 15 · Burst 上限
// ---------------------------------------------------------------------------

private val BURST_CAP_OPTIONS = listOf(
    3 to "最省电，确认偏慢",
    5 to "较省电",
    10 to "默认 · 硬顶上限，不能更高"
)

@Composable
internal fun BurstCapPage(vm: WorkTimeViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val current = settings.burstCapMinutes

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader("Burst 上限", "上限调低更省电，但可能漏掉短时进出", onBack = onBack)
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            BURST_CAP_OPTIONS.forEachIndexed { index, (minutes, note) ->
                if (index > 0) ThinDivider()
                ChoiceRow(
                    title = "$minutes 分钟",
                    note = note,
                    selected = current == minutes
                ) { vm.saveBurstCap(minutes) }
            }
        }
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            ReadOnlyRow(Icons.Outlined.Speed, "常规间隔", "${settings.samplingIntervalMinutes} min")
            ThinDivider()
            ReadOnlyRow(Icons.Outlined.Bolt, "Burst 期间", "1 min")
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Burst 是什么：检测到地点变更时，App 会临时把采集频率提到 1 分钟一次，尽快确认结果；" +
                "持续到上限后自动回落到常规间隔，避免长时间高功耗。\n\n" +
                "超过上限后必须回落，这是防止后台被系统限制的硬约束——所以上限最高只能到 10 分钟，" +
                "调低可以，调高不行。",
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.muted
        )
        Spacer(Modifier.height(12.dp))
    }
}

// ---------------------------------------------------------------------------
// 18 · 清空本地记录
// ---------------------------------------------------------------------------

/**
 * 危险操作确认。
 *
 * 三条出路（界面稿要求"默认视觉重心不在红色按钮上"）：
 * 先导出再清空（主按钮） / 取消 / 确认清空（红色，放右侧尾部）。
 */
@Composable
internal fun ClearLocalDataDialog(
    onExportFirst: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清空本地记录") },
        text = {
            Column {
                Text("将删除全部工时记录、地点配置与诊断日志，且无法恢复。", color = AppTheme.colors.red)
                Spacer(Modifier.height(8.dp))
                Text(
                    "建议先导出工时记录再操作。",
                    color = AppTheme.colors.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onExportFirst) { Text("先导出，再清空") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = onConfirm) {
                    Text("确认清空", color = AppTheme.colors.red)
                }
            }
        }
    )
}

// ---------------------------------------------------------------------------
// 共用小件
// ---------------------------------------------------------------------------

/** 单选项：标题 + 代价说明 + 右侧选中标记。整行可点，点击即生效（不需要再点保存）。 */
@Composable
private fun ChoiceRow(title: String, note: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(note, color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) {
            Box(
                Modifier
                    .size(9.dp)
                    .background(AppTheme.colors.blue, CircleShape)
            )
        }
    }
}

/** 只读信息行：图标 + 标题 + 右侧数值，不能点。 */
@Composable
private fun ReadOnlyRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                icon,
                contentDescription = null,
                tint = AppTheme.colors.blue,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.size(12.dp))
            Text(title)
        }
        Text(value, color = AppTheme.colors.muted, fontWeight = FontWeight.Medium)
    }
}
