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
import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.ui.theme.AppTheme
import java.util.Locale

/*
 * 界面稿 v4 屏 13（计薪规则）已迁到 PayRules.kt —— 计薪规则 v2 需要展示
 * 分段常量 + 月度参数 + 当月推算，塞回本文件会淹掉导航结构。
 *
 * 本文件保留屏 14/15/18：常规采集间隔、Burst 上限、清空确认。
 */

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
internal fun SamplingAndPowerPage(vm: WorkTimeViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader("采样与功耗", "精度、常规采样和快速确认集中设置", onBack = onBack)
        Spacer(Modifier.height(14.dp))
        SectionTitle("定位精度")
        SettingsGroup {
            listOf(
                Triple(UserSettingsEntity.LOCATION_ACCURACY_POWER_SAVING, "省电", "耗电最低，边界判断可能较慢"),
                Triple(UserSettingsEntity.LOCATION_ACCURACY_BALANCED, "平衡", "推荐，兼顾准确度与续航"),
                Triple(UserSettingsEntity.LOCATION_ACCURACY_HIGH, "高精度", "进出判断更敏感，耗电更高")
            ).forEachIndexed { index, (value, title, note) ->
                if (index > 0) ThinDivider()
                ChoiceRow(title, note, settings.locationAccuracyMode == value) { vm.saveAccuracyMode(value) }
            }
        }
        Spacer(Modifier.height(14.dp))
        SectionTitle("常规采集间隔")
        SettingsGroup {
            SAMPLING_INTERVAL_OPTIONS.forEachIndexed { index, (minutes, note) ->
                if (index > 0) ThinDivider()
                ChoiceRow("$minutes 分钟", note, settings.samplingIntervalMinutes == minutes) {
                    vm.saveSamplingInterval(minutes)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        SectionTitle("快速确认上限")
        SettingsGroup {
            BURST_CAP_OPTIONS.forEachIndexed { index, (minutes, note) ->
                if (index > 0) ThinDivider()
                ChoiceRow("$minutes 分钟", note, settings.burstCapMinutes == minutes) {
                    vm.saveBurstCap(minutes)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "地点或行程状态变化时会临时提高采样频率；取得可靠证据或达到上限后自动回落。",
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.muted
        )
    }
}

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
