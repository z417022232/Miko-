package com.example.worktimetracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.worktimetracker.domain.location.AnchorUpdatePolicy
import com.example.worktimetracker.domain.location.PlaceLearningPhase
import com.example.worktimetracker.domain.location.PlaceLearningStatus
import com.example.worktimetracker.ui.theme.AppTheme

/*
 * 地点管理页的「学习校准」小节（方案 §十一 阶段2 的最小学习状态 + 回退入口）。
 *
 * 三条设计约束：
 *
 * 1. **判定用哪个锚点必须写在最显眼处**。用户来这一页最想知道的不是「学得多好」，
 *    而是「现在到底按哪个位置算」。所以「当前判定使用：…」放在第一行，
 *    而且它的真值来自 PlaceModelResolver（同一个取用函数），不是界面推断的。
 *
 * 2. **暂停是粘性的，文案要如实说**。按一次「暂停」之后，
 *    学习状态冻结、数据一行不删；恢复时从冻结点继续。
 *    这两句必须写清楚，否则用户会以为按了暂停就丢了几周的成果，
 *    或者以为开回来没生效是 bug。
 *
 * 3. **折叠态也要可操作**。默认折叠（列表里一次可能有多个地点），
 *    但阶段标签与「判定用哪个锚点」必须一眼可见 —— 折叠不该藏住结论。
 */

/** 阶段 → 主题色。颜色本身就承担「好不好」的信息，不要只用文字。 */
@Composable
private fun PlaceLearningPhase.tint(): Color = when (this) {
    PlaceLearningPhase.AUTO_APPLIED -> AppTheme.colors.green
    PlaceLearningPhase.PENDING_APPLY -> AppTheme.colors.blue
    PlaceLearningPhase.SHADOW -> AppTheme.colors.blue
    PlaceLearningPhase.NEEDS_CONFIRM -> AppTheme.colors.orange
    PlaceLearningPhase.PAUSED -> AppTheme.colors.muted
    PlaceLearningPhase.NOT_STARTED -> AppTheme.colors.muted
}

/**
 * @param onSetEnabled true = 重新开启自动校准，false = 停用（粘性，不删学习数据）
 */
@Composable
internal fun SiteLearningSection(
    status: PlaceLearningStatus,
    onSetEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember(status.placeId) { mutableStateOf(false) }
    val tint = status.phase.tint()

    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(status.phase.label, tint)
            Spacer(Modifier.size(8.dp))
            Text(
                if (status.usesConfiguredAnchor) {
                    "当前判定使用：你设置的位置"
                } else {
                    "当前判定使用：学习校准位置"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status.usesConfiguredAnchor) AppTheme.colors.muted else tint,
                fontWeight = if (status.usesConfiguredAnchor) FontWeight.Normal else FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (expanded) "收起" else "查看依据",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.blue
            )
        }

        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                status.headline,
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.muted
            )
            Spacer(Modifier.height(8.dp))
            status.facts.forEach { fact ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        fact.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.muted,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        fact.value,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            if (status.failures.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "还差：" + status.failures.joinToString("；"),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.orange
                )
            }
        }

        if (status.canDisable || status.canReEnable) {
            Spacer(Modifier.height(2.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (status.canDisable) {
                    TextButton(onClick = { onSetEnabled(false) }) {
                        Text("暂停学习校准", color = AppTheme.colors.orange)
                    }
                }
                if (status.canReEnable) {
                    TextButton(onClick = { onSetEnabled(true) }) {
                        Text("恢复学习校准", color = AppTheme.colors.blue)
                    }
                }
            }
        }
    }
}

/**
 * 「停用学习校准」的确认文案。
 *
 * 为什么值得单独一块：这一步看着像破坏性操作（名字里有「停用」），
 * 实际**一行数据都不删**。不写清楚，用户会因为怕丢数据而不敢按；
 * 写清楚之后，它就是一个随时可逆的开关。
 *
 * 天数直接引 [AnchorUpdatePolicy.SHADOW_VALIDATION_DAYS]，不在这里另立常量 ——
 * 界面文案与门槛各写一份的话，改了门槛而忘了改文案时不会有任何测试变红，
 * 用户就会读到「需要再验证 7 天」却等了 10 天。
 */
@Composable
internal fun LearningDisableNotice() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "关于「暂停学习校准」",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "暂停后立即冻结候选、模型和验证进度，判定回到你设置的位置。" +
                "恢复后从冻结前的状态继续，不会清零或重开验证窗口。",
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.muted
        )
    }
}
