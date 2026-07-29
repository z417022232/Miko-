package com.example.worktimetracker.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.worktimetracker.export.ExportManager
import com.example.worktimetracker.ui.app.WorkTimeViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBottomSheet(vm: WorkTimeViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val month by vm.month.collectAsState()
    val records by vm.records.collectAsState()
    val settings by vm.settings.collectAsState()
    val legacyImportMessage by vm.legacyImportMessage.collectAsState()
    var message by remember { mutableStateOf("生成后会打开系统分享面板") }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("文件无法读取")
            }.onSuccess {
                vm.restoreBackupJson(it)
                message = "备份已导入，日历正在刷新"
            }.onFailure {
                message = "恢复失败：${it.message ?: "文件格式不正确"}"
            }
        }
    }
    val legacyCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("文件无法读取")
            }.onSuccess {
                vm.importLegacyAttendanceCsv(it)
            }.onFailure {
                message = "导入失败：${it.message ?: "文件格式不正确"}"
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 30.dp)) {
            Text("导出与备份", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${month.year}年${month.monthValue}月", color = AppMuted)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExportActionCard(
                    "Excel",
                    "每日明细",
                    Icons.Outlined.TableChart,
                    AppGreen,
                    Modifier.weight(1f)
                ) {
                    runCatching { ExportManager.exportExcel(context, month, records) }
                        .onSuccess { shareFile(context, it, "application/vnd.ms-excel") }
                        .onFailure { message = "导出失败：${it.message}" }
                }
                ExportActionCard(
                    "PDF",
                    "月度报告",
                    Icons.Outlined.PictureAsPdf,
                    AppRed,
                    Modifier.weight(1f)
                ) {
                    runCatching { ExportManager.exportPdfReport(context, month, records) }
                        .onSuccess { shareFile(context, it, "application/pdf") }
                        .onFailure { message = "导出失败：${it.message}" }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExportActionCard(
                    "CSV",
                    "通用表格",
                    Icons.Outlined.Description,
                    AppBlue,
                    Modifier.weight(1f)
                ) {
                    runCatching { ExportManager.exportCsv(context, month, records) }
                        .onSuccess { shareFile(context, it, "text/csv") }
                        .onFailure { message = "导出失败：${it.message}" }
                }
                ExportActionCard(
                    "备份",
                    "全部设置",
                    Icons.Outlined.Backup,
                    AppPurple,
                    Modifier.weight(1f)
                ) {
                    runCatching { ExportManager.exportBackupJson(context, month, records, settings) }
                        .onSuccess { shareFile(context, it, "application/json") }
                        .onFailure { message = "备份失败：${it.message}" }
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Restore, null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.size(8.dp))
                Text("从备份文件恢复")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { legacyCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.UploadFile, null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.size(8.dp))
                Text("导入旧软件考勤 CSV")
            }
            Text(
                legacyImportMessage.ifBlank { message },
                color = AppMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun ExportActionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.09f)),
        modifier = modifier
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(25.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, color = AppMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun shareFile(context: Context, file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享文件"))
}
