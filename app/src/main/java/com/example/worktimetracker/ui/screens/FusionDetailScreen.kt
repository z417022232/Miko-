package com.example.worktimetracker.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.worktimetracker.domain.evidence.FusionBreakdown
import com.example.worktimetracker.domain.evidence.FusedStatusFormatter
import com.example.worktimetracker.domain.evidence.FusedStatusSnapshot
import com.example.worktimetracker.location.evidence.AmbientScanPolicy
import com.example.worktimetracker.location.service.EvidenceContinuityPolicy
import com.example.worktimetracker.ui.app.WorkTimeViewModel
import com.example.worktimetracker.ui.theme.AppTheme
import java.time.Instant
import java.time.ZoneId

/**
 * 融合决策详情（界面稿 04）。
 *
 * 这一页存在的意义只有一个：**让"为什么这次算在上班"可被复查**。
 * 所以它只呈现服务已经发布的证据与判定，不做任何二次推断——
 * 页面上出现的每个数字都能在诊断日志里找到对应来源。
 */
@Composable
fun FusionDetailScreen(vm: WorkTimeViewModel, onBack: () -> Unit) {
    val fused by vm.fusedStatus.collectAsState()
    val logs by vm.recentLogs.collectAsState()
    val settings by vm.settings.collectAsState()

    LaunchedEffect(Unit) { vm.refreshLogsOnce() }

    val lines = remember(fused?.sourceBreakdown) { FusionBreakdown.parse(fused?.sourceBreakdown) }
    val recentFusions = remember(logs) {
        logs.filter { it.startsWith("FUSION：") }.take(5)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader(title = "融合决策", subtitle = "本次判定用了哪些证据", onBack = onBack)
        Spacer(Modifier.height(14.dp))
        DecisionHero(fused)
        Spacer(Modifier.height(12.dp))

        if (lines.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("还没有证据明细", fontWeight = FontWeight.SemiBold)
                    Text(
                        "融合明细在前台服务每完成一轮定位/环境扫描后产生。若一直为空，" +
                            "请到「设置 · 诊断日志」确认自动记录服务是否在运行。",
                        color = AppTheme.colors.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("各来源明细", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    lines.forEachIndexed { index, line ->
                        if (index > 0) {
                            Spacer(Modifier.height(10.dp))
                            ThinDivider()
                            Spacer(Modifier.height(10.dp))
                        }
                        SourceLineRow(
                            label = FusionBreakdown.sourceLabel(line.source),
                            detail = FusionBreakdown.detailLabel(line),
                            quality = line.quality,
                            strength = FusionBreakdown.strengthLabel(line.quality)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        // 委托属性（by collectAsState）无法 smart cast，先取成局部变量
        val snapshot = fused
        if (snapshot != null) {
            val conflict = TodayStatusPresenterRef.conflictText(snapshot)
            if (conflict != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AppTheme.colors.orange.copy(alpha = 0.10f)),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("冲突说明", fontWeight = FontWeight.SemiBold, color = AppTheme.colors.orange)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            FusedStatusFormatter.reasonLabel(snapshot.reason),
                            color = AppTheme.colors.muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "冲突期间自动记录不会因此改写工时（弱证据不驱动状态变更）。",
                            color = AppTheme.colors.muted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("当前策略", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                PolicyRow(
                    "连续性窗口",
                    "${EvidenceContinuityPolicy.DEFAULT_MAX_GAP_MILLIS / 60_000} min",
                    "超过则视为连续性中断"
                )
                Spacer(Modifier.height(8.dp))
                PolicyRow(
                    "证据冷却",
                    "${AmbientScanPolicy.SCAN_COOLDOWN_MILLIS / 60_000} min",
                    "两次环境扫描之间的最小间隔"
                )
                Spacer(Modifier.height(8.dp))
                PolicyRow(
                    "常规采集间隔",
                    "${settings.samplingIntervalMinutes} min",
                    "到「常规采集间隔」可改"
                )
                Spacer(Modifier.height(8.dp))
                PolicyRow(
                    "Burst 上限",
                    "${settings.burstCapMinutes} min",
                    "到达上限必须回落，属硬约束"
                )
            }
        }

        if (recentFusions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("最近决策", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    recentFusions.forEach { entry ->
                        Text(
                            entry.removePrefix("FUSION："),
                            color = AppTheme.colors.muted,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 转发到纯推导层，避免这一页与今日页各写一份冲突判定。 */
private object TodayStatusPresenterRef {
    fun conflictText(snapshot: FusedStatusSnapshot): String? =
        com.example.worktimetracker.ui.TodayStatusPresenter.conflictText(snapshot)
}

@Composable
private fun DecisionHero(fused: FusedStatusSnapshot?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            if (fused == null) {
                Text("暂无判定", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "前台定位服务尚未发布融合结果。到「设置 · 诊断日志」可以确认服务状态。",
                    color = AppTheme.colors.muted,
                    style = MaterialTheme.typography.bodySmall
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    FusionBreakdown.decisionSummary(fused.place.name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = decisionColor(fused.decision)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    clockOf(fused.updatedAt),
                    color = AppTheme.colors.muted,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                FusedStatusFormatter.headline(fused),
                color = AppTheme.colors.muted,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TodayStatusPresenterRef.conflictText(fused)?.let {
                    StatPill(it, AppTheme.colors.orange)
                }
            }
        }
    }
}

@Composable
private fun StatPill(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun SourceLineRow(label: String, detail: String, quality: Double, strength: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(detail, color = AppTheme.colors.muted, style = MaterialTheme.typography.labelSmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(FusionBreakdown.percentLabel(quality), fontWeight = FontWeight.SemiBold)
            Text(
                strength,
                color = if (quality >= 0.60) AppTheme.colors.green else AppTheme.colors.muted,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun PolicyRow(title: String, value: String, hint: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(hint, color = AppTheme.colors.muted, style = MaterialTheme.typography.labelSmall)
        }
        Text(value, fontWeight = FontWeight.Bold, color = AppTheme.colors.blue)
    }
}

private fun clockOf(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).let {
        "%02d:%02d:%02d".format(it.hour, it.minute, it.second)
    }
