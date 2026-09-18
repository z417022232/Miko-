package com.example.worktimetracker.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.evidence.FusedStatusFormatter
import com.example.worktimetracker.domain.evidence.FusedStatusSnapshot
import com.example.worktimetracker.location.permission.PermissionManager
import com.example.worktimetracker.location.permission.PermissionItem
import com.example.worktimetracker.location.permission.PermissionRepairPriority
import com.example.worktimetracker.location.permission.PermissionSettingsRouter
import com.example.worktimetracker.location.permission.PermissionStatus
import com.example.worktimetracker.location.permission.AutostartState
import com.example.worktimetracker.location.permission.AutostartVerificationStore
import com.example.worktimetracker.location.service.ForegroundLocationService
import com.example.worktimetracker.location.recovery.ServiceRecovery
import com.example.worktimetracker.ui.app.HolidayResultTone
import com.example.worktimetracker.ui.app.HolidayStatusPresenter
import com.example.worktimetracker.ui.app.HolidayStatusUi
import com.example.worktimetracker.ui.app.WorkTimeViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.example.worktimetracker.ui.theme.AppTheme
import com.example.worktimetracker.ui.theme.ThemeMode

private enum class SettingsPage {
    ROOT, LOCATION, RULES, PERMISSIONS, DATA, LOGS, HOLIDAY, THEME,
    /** v4 界面稿新增：计薪规则 / 常规采集间隔 / Burst 上限 */
    PAY_RULES, POWER,
    /** v4.3 界面稿 09/10/11：多地点管理（LOCATION 保留为校准兜底入口） */
    SITES
    // 注：v6 的 SLIP_ENTRY 已移除 —— 工资条录入的唯一入口是日历月卡的「录入工资条 ›」
    // （带月份锚点）。计薪规则页那条用的是自然月锚点，两条入口语义不一致，v7.2 去重。
}
private enum class LocationTarget { COMPANY, HOME }

@Composable
fun SettingsScreen(
    vm: WorkTimeViewModel,
    themeMode: ThemeMode = ThemeMode.default,
    onThemeModeChange: (ThemeMode) -> Unit = {}
) {
    var page by remember { mutableStateOf(SettingsPage.ROOT) }
    BackHandler(page != SettingsPage.ROOT) { page = SettingsPage.ROOT }
    when (page) {
        SettingsPage.ROOT -> SettingsHome(vm, themeMode = themeMode, onOpen = { page = it })
        SettingsPage.SITES -> SiteManageHost(
            vm = vm,
            onBack = { page = SettingsPage.ROOT },
            onOpenLegacyLocations = { page = SettingsPage.LOCATION }
        )
        SettingsPage.LOCATION -> LocationSettingsPage(vm, onBack = { page = SettingsPage.ROOT })
        SettingsPage.RULES -> AutoRulesPage(vm, onBack = { page = SettingsPage.ROOT })
        SettingsPage.PERMISSIONS -> PermissionSettingsPage(vm, onBack = { page = SettingsPage.ROOT })
        SettingsPage.DATA -> DataSettingsPage(vm, onBack = { page = SettingsPage.ROOT })
        SettingsPage.LOGS -> LogsPage(vm, onBack = { page = SettingsPage.ROOT })
        SettingsPage.HOLIDAY -> HolidayDataPage(vm, onBack = { page = SettingsPage.ROOT })
        SettingsPage.PAY_RULES -> PayRulesPage(
            vm,
            onBack = { page = SettingsPage.ROOT }
        )
        SettingsPage.POWER -> SamplingAndPowerPage(vm, onBack = { page = SettingsPage.ROOT })
        SettingsPage.THEME -> ThemeSettingsPage(
            current = themeMode,
            onChange = onThemeModeChange,
            onBack = { page = SettingsPage.ROOT }
        )
    }
}

@Composable
private fun SettingsHome(
    vm: WorkTimeViewModel,
    themeMode: ThemeMode,
    onOpen: (SettingsPage) -> Unit
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val sites by vm.sites.collectAsState()
    val holidayStatus by vm.holidayStatus.collectAsState()
    var showClear by remember { mutableStateOf(false) }
    val permissions = PermissionManager.check(context)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader("设置", "常用信息放在前面，其他功能按需进入")
        Spacer(Modifier.height(14.dp))
        SectionTitle("记录")
        SettingsGroup {
            SettingsRow(
                Icons.Outlined.Security,
                "权限设置",
                if (permissions.ready) "权限完整 · 自动记录已就绪" else "有权限需要处理",
                tint = if (permissions.ready) AppTheme.colors.green else AppTheme.colors.orange
            ) { onOpen(SettingsPage.PERMISSIONS) }
            ThinDivider()
            SettingsRow(
                Icons.Outlined.LocationOn,
                "地点管理",
                siteSummary(sites),
                tint = AppTheme.colors.green
            ) { onOpen(SettingsPage.SITES) }
            ThinDivider()
            SettingsRow(
                Icons.Outlined.EventAvailable,
                "排班与假日",
                "${formatClock(settings.workStartMinutes)} — ${formatClock(settings.workEndMinutes)} · ${holidaySummary(holidayStatus)}",
                tint = AppTheme.colors.orange
            ) { onOpen(SettingsPage.HOLIDAY) }
            ThinDivider()
            SettingsRow(
                Icons.Outlined.Paid,
                "计薪规则",
                "工资条口径 · 应发 → 社保/公积金/个税 → 预计到手",
                tint = AppTheme.colors.orange
            ) { onOpen(SettingsPage.PAY_RULES) }
            ThinDivider()
            SettingsRow(
                Icons.Outlined.Tune,
                "自动识别规则",
                "休息扣除${settings.restDeductionMinutes}分 · 离岗确认${settings.leaveCompanyConfirmMinutes}分",
                tint = AppTheme.colors.purple
            ) { onOpen(SettingsPage.RULES) }
        }
        Spacer(Modifier.height(14.dp))
        SectionTitle("精度与功耗")
        SettingsGroup {
            SettingsRow(
                Icons.Outlined.Speed,
                "采样与功耗",
                "${accuracyModeLabel(settings.locationAccuracyMode)} · 常规 ${settings.samplingIntervalMinutes} min · 快速 ${settings.burstCapMinutes} min",
                tint = AppTheme.colors.blue
            ) { onOpen(SettingsPage.POWER) }
        }
        Spacer(Modifier.height(14.dp))
        SectionTitle("数据与外观")
        SettingsGroup {
            SettingsRow(
                Icons.Outlined.Backup,
                "导出工时记录",
                "CSV / Excel / PDF / JSON，可先导出再清空",
                tint = AppTheme.colors.blue
            ) { onOpen(SettingsPage.DATA) }
            ThinDivider()
            SettingsRow(
                Icons.Outlined.DarkMode,
                "界面主题",
                "${themeMode.label} · ${themeMode.summary}",
                tint = AppTheme.colors.purple
            ) { onOpen(SettingsPage.THEME) }
        }
        Spacer(Modifier.height(14.dp))
        SectionTitle("诊断")
        SettingsGroup {
            SettingsRow(
                Icons.Outlined.BugReport,
                "诊断日志",
                "状态机 / 证据 / 采集 / 人工",
                tint = AppTheme.colors.muted
            ) { onOpen(SettingsPage.LOGS) }
            ThinDivider()
            SettingsRow(
                Icons.Outlined.DeleteForever,
                "清空本地记录",
                "删除全部工时、地点配置与日志，无法恢复",
                tint = AppTheme.colors.red
            ) { showClear = true }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "工时记录助手 · 本地单机版 · 不联网也能记录",
            color = AppTheme.colors.muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(12.dp))
    }

    if (showClear) {
        ClearLocalDataDialog(
            onExportFirst = { showClear = false; onOpen(SettingsPage.DATA) },
            onConfirm = { vm.clearAllLocalData(); showClear = false },
            onDismiss = { showClear = false }
        )
    }
}

private fun accuracyModeLabel(mode: String): String = when (mode) {
    UserSettingsEntity.LOCATION_ACCURACY_POWER_SAVING -> "省电"
    UserSettingsEntity.LOCATION_ACCURACY_HIGH -> "高精度"
    else -> "平衡"
}

/**
 * 定位精度三档。
 *
 * 改的是系统定位请求的精度档（耗电与判准的取舍），不是一个开关——
 * 所以用三个并列选项而不是 Switch，让用户看得见"选它要付出什么"。
 */
@Composable
private fun AccuracyRow(current: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text("定位精度")
        Text(
            "精度越高，进出车间的判定越准，耗电也越高。",
            color = AppTheme.colors.muted,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                UserSettingsEntity.LOCATION_ACCURACY_POWER_SAVING to "省电",
                UserSettingsEntity.LOCATION_ACCURACY_BALANCED to "平衡",
                UserSettingsEntity.LOCATION_ACCURACY_HIGH to "高"
            ).forEach { (value, label) ->
                FilterChip(
                    selected = current == value,
                    onClick = { onChange(value) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 设置页「地点管理」入口摘要：数量就是用户能一眼看懂的进度（v4.3 多地点）。 */
fun siteSummary(sites: List<com.example.worktimetracker.data.entity.SiteEntity>): String =
    when {
        sites.isEmpty() -> "未设置 · 至少添加一个常待的地方"
        sites.none { it.enabled } -> "${sites.size} 个地点 · 全部已停用"
        else -> "${sites.count { it.enabled }} 个地点"
    }

private fun locationSummary(settings: UserSettingsEntity): String {
    val company = if (settings.companyLat != null && settings.companyLng != null) "公司已设置" else "公司未设置"
    val home = if (settings.homeLat != null && settings.homeLng != null) "家庭已设置" else "家庭未设置"
    return "$company · $home"
}

@Composable
internal fun DefaultHoursDialog(settings: UserSettingsEntity, vm: WorkTimeViewModel, onDismiss: () -> Unit) {
    var enabled by remember(settings.hasDefaultHours) { mutableStateOf(settings.hasDefaultHours) }
    var hours by remember(settings.defaultWorkMinutes) { mutableIntStateOf((settings.defaultWorkMinutes ?: 12 * 60) / 60) }
    var showPicker by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("默认工时") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("使用固定工时", fontWeight = FontWeight.Medium)
                        Text("开启后不再按定位时长扣休息", color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(enabled, onCheckedChange = { enabled = it })
                }
                if (enabled) {
                    Card(
                        onClick = { showPicker = true },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("每天计入")
                            Text("${hours}小时", color = AppTheme.colors.blue, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.setDefaultHoursEnabled(enabled)
                if (enabled) vm.saveDefaultHours(hours.toString())
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
    if (showPicker) {
        NumberWheelDialog("每天计入工时", hours, 1..24, "小时", { showPicker = false }) {
            hours = it
            showPicker = false
        }
    }
}

/** 当前定位判断卡片：实时展示融合决策、置信度、原因与各来源证据明细。 */
@Composable
private fun FusedStatusCard(snapshot: FusedStatusSnapshot?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.LocationOn, null,
                    tint = when (snapshot?.decision) {
                        FusedDecision.CONFIRMED -> AppTheme.colors.blue
                        FusedDecision.MAINTAINED -> AppTheme.colors.blue.copy(alpha = 0.6f)
                        FusedDecision.UNKNOWN -> MaterialTheme.colorScheme.error
                        null -> AppTheme.colors.muted
                    }
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(FusedStatusFormatter.headline(snapshot), fontWeight = FontWeight.SemiBold)
                    if (snapshot == null) {
                        Text(
                            "服务尚未运行或还没有任何位置证据",
                            color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        val decision = FusedStatusFormatter.decisionLabel(snapshot.decision)
                        val confidence = FusedStatusFormatter.confidenceLabel(snapshot)
                        Text(
                            if (confidence != null) "$decision · $confidence" else decision,
                            color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            if (snapshot != null) {
                Spacer(Modifier.size(8.dp))
                Text(FusedStatusFormatter.reasonLabel(snapshot.reason),
                    color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
                FusedStatusFormatter.sourcesLabel(snapshot)?.let {
                    Text("证据来源：$it", color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
                }
                snapshot.sourceBreakdown?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun LocationSettingsPage(vm: WorkTimeViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val lastLocation by vm.lastKnownLocationText.collectAsState()
    val fusedStatus by vm.fusedStatus.collectAsState()
    val searchMessage by vm.placeSearchMessage.collectAsState()
    val calibrationProposal by vm.companyCalibrationProposal.collectAsState()
    var companyLat by remember(settings.companyLat) { mutableStateOf(settings.companyLat?.toString().orEmpty()) }
    var companyLng by remember(settings.companyLng) { mutableStateOf(settings.companyLng?.toString().orEmpty()) }
    var homeLat by remember(settings.homeLat) { mutableStateOf(settings.homeLat?.toString().orEmpty()) }
    var homeLng by remember(settings.homeLng) { mutableStateOf(settings.homeLng?.toString().orEmpty()) }
    var companyRadius by remember(settings.companyRadiusMeters) { mutableIntStateOf(settings.companyRadiusMeters) }
    var homeRadius by remember(settings.homeRadiusMeters) { mutableIntStateOf(settings.homeRadiusMeters) }
    var searchTarget by remember { mutableStateOf<LocationTarget?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader("公司与家庭", "推荐到达地点后使用当前位置", onBack)
        Spacer(Modifier.height(14.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.blue.copy(alpha = 0.08f)),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MyLocation, null, tint = AppTheme.colors.blue)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("最近一次定位", fontWeight = FontWeight.SemiBold)
                    Text(lastLocation, color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { vm.refreshLastKnownLocation() }) { Icon(Icons.Outlined.Refresh, "刷新") }
            }
        }
        Spacer(Modifier.height(12.dp))
        FusedStatusCard(fusedStatus)
        Spacer(Modifier.height(14.dp))
        LocationCard(
            title = "公司",
            isSet = settings.companyLat != null,
            radius = companyRadius,
            icon = Icons.Outlined.WorkOutline,
            onRadius = { companyRadius = it },
            onCurrent = { vm.useLastLocationForCompany() },
            onSearch = { searchTarget = LocationTarget.COMPANY }
        )
        TextButton(onClick = { vm.prepareCompanyCalibration() }, modifier = Modifier.fillMaxWidth()) {
            Text("在公司重新校准位置")
        }
        Spacer(Modifier.height(12.dp))
        LocationCard(
            title = "家庭",
            isSet = settings.homeLat != null,
            radius = homeRadius,
            icon = Icons.Outlined.Home,
            onRadius = { homeRadius = it },
            onCurrent = { vm.useLastLocationForHome() },
            onSearch = { searchTarget = LocationTarget.HOME }
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "收起手动坐标" else "高级：手动输入坐标")
        }
        if (showAdvanced) {
            ManualCoordinateFields("公司", companyLat, companyLng, { companyLat = it }, { companyLng = it })
            Spacer(Modifier.height(8.dp))
            ManualCoordinateFields("家庭", homeLat, homeLng, { homeLat = it }, { homeLng = it })
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                vm.saveLocations(
                    companyLat, companyLng, companyRadius.toString(),
                    homeLat, homeLng, homeRadius.toString()
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存半径与坐标") }
        Spacer(Modifier.height(20.dp))
    }

    calibrationProposal?.let { proposal ->
        AlertDialog(
            onDismissRequest = { vm.cancelCompanyCalibration() },
            title = { Text("确认公司位置校准") },
            text = { Text("已分析${proposal.acceptedCount}个高精度定位，建议中心与当前设置相差约${proposal.offsetMeters}米。确认后才会更新公司位置。") },
            confirmButton = { TextButton(onClick = { vm.acceptCompanyCalibration() }) { Text("确认更新") } },
            dismissButton = { TextButton(onClick = { vm.cancelCompanyCalibration() }) { Text("取消") } }
        )
    }

    searchTarget?.let { target ->
        PlaceSearchDialog(
            target = target,
            message = searchMessage,
            onDismiss = { searchTarget = null },
            onSearch = { keyword -> vm.searchPlaceAndSet(keyword, if (target == LocationTarget.COMPANY) "company" else "home") }
        )
    }
}

@Composable
private fun LocationCard(
    title: String,
    isSet: Boolean,
    radius: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onRadius: (Int) -> Unit,
    onCurrent: () -> Unit,
    onSearch: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = if (isSet) AppTheme.colors.green else AppTheme.colors.muted)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(if (isSet) "位置已设置" else "尚未设置", color = if (isSet) AppTheme.colors.green else AppTheme.colors.orange, style = MaterialTheme.typography.bodySmall)
                }
                Text("半径 ${radius}米", color = AppTheme.colors.muted)
            }
            Slider(
                value = radius.toFloat(),
                onValueChange = { onRadius(it.toInt()) },
                valueRange = 50f..1000f
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCurrent, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.MyLocation, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("当前位置")
                }
                OutlinedButton(onClick = onSearch, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Search, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("搜索地点")
                }
            }
        }
    }
}

@Composable
private fun ManualCoordinateFields(
    title: String,
    latitude: String,
    longitude: String,
    onLatitude: (String) -> Unit,
    onLongitude: (String) -> Unit
) {
    Text("$title 坐标", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(latitude, onLatitude, label = { Text("纬度") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(longitude, onLongitude, label = { Text("经度") }, singleLine = true, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PlaceSearchDialog(
    target: LocationTarget,
    message: String,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit
) {
    var keyword by remember(target) { mutableStateOf("") }
    val label = if (target == LocationTarget.COMPANY) "公司" else "家庭"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索并设为$label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("输入公司、园区、小区或道路名称，应用会直接保存搜索结果。", color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(keyword, { keyword = it }, label = { Text("地点名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, color = if (message.contains("成功")) AppTheme.colors.green else AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSearch(keyword) }) { Text("搜索并保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

@Composable
private fun AutoRulesPage(vm: WorkTimeViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    var picker by remember { mutableStateOf<String?>(null) }
    var rest by remember(settings.restDeductionMinutes) { mutableIntStateOf(settings.restDeductionMinutes) }
    var outside by remember(settings.outsideThresholdMinutes) { mutableIntStateOf(settings.outsideThresholdMinutes) }
    var leave by remember(settings.leaveCompanyConfirmMinutes) { mutableIntStateOf(settings.leaveCompanyConfirmMinutes) }
    var early by remember(settings.earlyLeaveToleranceMinutes) { mutableIntStateOf(settings.earlyLeaveToleranceMinutes) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp)) {
        ScreenHeader("自动识别规则", "默认值已适合多数情况", onBack)
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingsRow(Icons.Outlined.AccessTime, "休息扣除", "未启用固定工时时扣除 ${rest} 分钟") { picker = "rest" }
            ThinDivider()
            SettingsRow(Icons.Outlined.Home, "外出判断", "离开家庭超过 ${outside} 分钟") { picker = "outside" }
            ThinDivider()
            SettingsRow(Icons.Outlined.LocationOn, "离岗确认", "离开公司 ${leave} 分钟未返回") { picker = "leave" }
            ThinDivider()
            SettingsRow(Icons.Outlined.Timer, "下早班容差", "比参考下班提前超过 ${early} 分钟") { picker = "early" }
        }
        Spacer(Modifier.height(12.dp))
        Text("修改后立即生效。固定工时和手动工时不会重复扣除休息。", color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
    }

    when (picker) {
        "rest" -> NumberWheelDialog("休息扣除", rest, 0..240, "分钟", { picker = null }) {
            rest = it; vm.saveAutoRules(rest.toString(), outside.toString(), leave.toString(), early.toString()); picker = null
        }
        "outside" -> NumberWheelDialog("外出判断", outside, 15..1440, "分钟", { picker = null }) {
            outside = it; vm.saveAutoRules(rest.toString(), outside.toString(), leave.toString(), early.toString()); picker = null
        }
        "leave" -> NumberWheelDialog("离岗确认", leave, 5..360, "分钟", { picker = null }) {
            leave = it; vm.saveAutoRules(rest.toString(), outside.toString(), leave.toString(), early.toString()); picker = null
        }
        "early" -> NumberWheelDialog("下早班容差", early, 0..60, "分钟", { picker = null }) {
            early = it; vm.saveAutoRules(rest.toString(), outside.toString(), leave.toString(), early.toString()); picker = null
        }
    }
}

@Composable
private fun PermissionSettingsPage(vm: WorkTimeViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refresh by remember { mutableIntStateOf(0) }
    var serviceMessage by remember { mutableStateOf("") }
    var awaitingAutostartConfirmation by remember { mutableStateOf(false) }
    var showAutostartConfirmation by remember { mutableStateOf(false) }
    val status = remember(refresh) { PermissionManager.check(context) }
    val autostartStore = remember(context) { AutostartVerificationStore(context) }
    val autostartState = remember(refresh) { autostartStore.get() }
    val lastSystemLocationDisabled = remember(refresh) { ServiceRecovery.lastSystemLocationDisabled(context) }
    val lastSystemLocationRecovered = remember(refresh) { ServiceRecovery.lastSystemLocationRecovered(context) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    fun repair(item: PermissionItem) {
        when (item) {
            PermissionItem.FINE_LOCATION -> permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            PermissionItem.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else refresh++
            PermissionItem.NEARBY_DEVICES -> {
                val permissions = buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_SCAN)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
                }
                if (permissions.isEmpty()) refresh++
                else permissionLauncher.launch(permissions.toTypedArray())
            }
            PermissionItem.ACTIVITY_RECOGNITION -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
            } else refresh++
            else -> {
                if (item == PermissionItem.VIVO_AUTOSTART) awaitingAutostartConfirmation = true
                serviceMessage = PermissionSettingsRouter.open(item, context)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refresh++
                if (awaitingAutostartConfirmation) {
                    awaitingAutostartConfirmation = false
                    showAutostartConfirmation = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp)) {
        ScreenHeader("权限与自动记录", "OriginOS 6 后台运行检查", onBack)
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            PermissionRow("精确定位", "用于判断公司和家庭范围", status.fineLocation) { repair(PermissionItem.FINE_LOCATION) }
            ThinDivider()
            PermissionRow("后台定位", "退出应用后继续记录", status.backgroundLocation) { repair(PermissionItem.BACKGROUND_LOCATION) }
            ThinDivider()
            PermissionRow("附近设备", "通过 Wi-Fi 和蓝牙识别公司与家庭环境", status.nearbyDevices) { repair(PermissionItem.NEARBY_DEVICES) }
            ThinDivider()
            PermissionRow("活动识别", "检测显著运动以唤醒环境扫描", status.activityRecognition) { repair(PermissionItem.ACTIVITY_RECOGNITION) }
            ThinDivider()
            PermissionRow("通知", "显示常驻记录状态和异常提醒", status.notifications) { repair(PermissionItem.NOTIFICATIONS) }
            ThinDivider()
            PermissionRow("电池不受限制", "避免 OriginOS 清理自动记录服务", status.batteryUnrestricted) { repair(PermissionItem.BATTERY_UNRESTRICTED) }
            ThinDivider()
            PermissionRow(
                "Vivo 自启动",
                "允许开机和系统清理后恢复记录",
                granted = autostartState != AutostartState.UNKNOWN,
                statusText = when (autostartState) {
                    AutostartState.UNKNOWN -> "去设置"
                    AutostartState.USER_CONFIRMED -> "已开启"
                    AutostartState.BOOT_VERIFIED -> "已验证"
                }
            ) { repair(PermissionItem.VIVO_AUTOSTART) }
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = {
            val next = PermissionRepairPriority.next(status, autostartState != AutostartState.UNKNOWN)
            if (next == null) {
                serviceMessage = "全部权限已就绪：定位、后台运行与自启动确认均已完成，自动记录环境完整。"
            } else {
                repair(next)
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Security, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("修复下一项")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val intent = Intent(context, ForegroundLocationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
                serviceMessage = "自动记录服务已启动，请查看通知栏"
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.size(8.dp))
            Text("启动自动记录服务")
        }
        if (serviceMessage.isNotBlank()) Text(serviceMessage, color = AppTheme.colors.green, modifier = Modifier.padding(10.dp))
        if (lastSystemLocationDisabled > 0L || lastSystemLocationRecovered > 0L) {
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("系统定位状态记录", fontWeight = FontWeight.Bold)
                    Text("最近暂停：${locationEventTime(lastSystemLocationDisabled)}", color = AppTheme.colors.muted)
                    Text("最近恢复：${locationEventTime(lastSystemLocationRecovered)}", color = AppTheme.colors.muted)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = AppTheme.colors.orange.copy(alpha = 0.09f)), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Text("OriginOS 6 还需要", fontWeight = FontWeight.Bold)
                Text("在系统设置中允许自启动、后台高耗电，并将电池管理设为不限制。", color = AppTheme.colors.muted)
            }
        }
    }

    if (showAutostartConfirmation) {
        AlertDialog(
            onDismissRequest = { showAutostartConfirmation = false },
            title = { Text("确认自启动权限") },
            text = { Text("是否已开启工时记录助手自启动？") },
            confirmButton = {
                TextButton(onClick = {
                    autostartStore.confirmByUser()
                    showAutostartConfirmation = false
                    refresh++
                }) { Text("已开启") }
            },
            dismissButton = {
                TextButton(onClick = { showAutostartConfirmation = false }) { Text("暂未开启") }
            }
        )
    }
}

private fun locationEventTime(time: Long): String = if (time <= 0L) "暂无" else
    Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))

@Composable
private fun PermissionRow(
    title: String,
    summary: String,
    granted: Boolean?,
    statusText: String? = null,
    onClick: () -> Unit
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (granted == true) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            null,
            tint = if (granted == true) AppTheme.colors.green else AppTheme.colors.orange
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(summary, color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
        }
        Text(statusText ?: if (granted == true) "已开启" else if (granted == false) "未开启" else "去设置", color = if (granted == true) AppTheme.colors.green else AppTheme.colors.orange)
    }
}

@Composable
private fun DataSettingsPage(vm: WorkTimeViewModel, onBack: () -> Unit) {
    var showExport by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp)) {
        ScreenHeader("导出与备份", "通过系统分享保存到电脑、网盘或聊天", onBack)
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingsRow(Icons.Outlined.FolderOpen, "生成或恢复文件", "Excel、PDF、CSV、JSON", tint = AppTheme.colors.blue) { showExport = true }
            ThinDivider()
            SettingsRow(Icons.Outlined.DeleteOutline, "清除所有记录", "保留工作时间、位置和规则设置", tint = AppTheme.colors.red) { showClear = true }
        }
        Spacer(Modifier.height(12.dp))
        Text("导出的文件会打开系统分享面板，不再要求你查找内部文件路径。", color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
    }
    if (showExport) ExportBottomSheet(vm, onDismiss = { showExport = false })
    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("清除全部记录？") },
            text = { Text("将删除工时、手动修改、定位和运行日志；工作时间、公司与家庭位置不会删除。") },
            confirmButton = {
                TextButton(onClick = { vm.clearAllLocalData(); showClear = false }) { Text("确认清除", color = AppTheme.colors.red) }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun LogsPage(vm: WorkTimeViewModel, onBack: () -> Unit) {
    val logs by vm.recentLogs.collectAsState()
    val journeyStatus by vm.journeyShadowStatus.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp)) {
        ScreenHeader(
            "运行日志",
            "仅在定位异常时查看",
            onBack,
            action = {
                IconButton(onClick = { vm.refreshLogsOnce(); vm.refreshLastKnownLocation() }) {
                    Icon(Icons.Outlined.Refresh, "刷新")
                }
            }
        )
        Spacer(Modifier.height(14.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("新行程算法（影子运行）", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    journeyStatus,
                    color = AppTheme.colors.muted,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "当前仅用于学习与新旧对比，不会修改正式工时或真实采样频率。",
                    color = AppTheme.colors.orange,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (logs.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.extraLarge) {
                Text("暂无运行日志", color = AppTheme.colors.muted, modifier = Modifier.fillMaxWidth().padding(24.dp))
            }
        } else {
            SettingsGroup {
                logs.take(30).forEachIndexed { index, log ->
                    Text(log, modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 11.dp), style = MaterialTheme.typography.bodySmall)
                    if (index != logs.take(30).lastIndex) ThinDivider()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 界面主题
// ---------------------------------------------------------------------------

/** 设置首页那一行的摘要文案。 */
private fun holidaySummary(status: HolidayStatusUi): String {
    val year = LocalDate.now().year
    return when {
        status.updating -> "正在更新…"
        status.lastSuccessAt != null ->
            "${HolidayStatusPresenter.sourceLabel(status, year)} · " +
                HolidayStatusPresenter.updatedAtText(status).removePrefix("最近更新：")
        status.hasDataFor(year) -> "${HolidayStatusPresenter.sourceLabel(status, year)} · 未联网更新过"
        else -> "尚未获取 $year 年安排，点此处理"
    }
}

@Composable
private fun ThemeSettingsPage(
    current: ThemeMode,
    onChange: (ThemeMode) -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader("界面主题", "深色模式减少夜间查看的刺眼感", onBack)
        Spacer(Modifier.height(14.dp))
        SectionTitle("显示模式")
        SettingsGroup {
            ThemeMode.entries.forEachIndexed { index, mode ->
                if (index > 0) ThinDivider()
                ThemeOptionRow(mode, selected = mode == current) { onChange(mode) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "「自动（按时间）」在 07:00–19:00 使用浅色、其余时段使用深色；" +
                "切换在整点生效，不需要重启应用。",
            color = AppTheme.colors.muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ThemeOptionRow(mode: ThemeMode, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(mode.label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(mode.summary, color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) Icon(Icons.Outlined.Check, contentDescription = "已选中", tint = AppTheme.colors.blue)
    }
}

// ---------------------------------------------------------------------------
// 节假日数据
// ---------------------------------------------------------------------------

@Composable
private fun HolidayDataPage(vm: WorkTimeViewModel, onBack: () -> Unit) {
    val status by vm.holidayStatus.collectAsState()
    val today = LocalDate.now()
    val warning = HolidayStatusPresenter.warning(status, today.year, today.monthValue)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader(
            "节假日数据",
            "放假与调休安排以国务院办公厅公告为准",
            onBack,
            action = {
                IconButton(onClick = { vm.refreshHolidays() }, enabled = !status.updating) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "立即更新")
                }
            }
        )
        Spacer(Modifier.height(14.dp))

        SectionTitle("当前状态")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    HolidayStatusPresenter.sourceLabel(status, today.year),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(HolidayStatusPresenter.updatedAtText(status), color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
                HolidayStatusPresenter.hostText(status)?.let {
                    Text(it, color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
                }
                if (status.knownYears.isNotEmpty()) {
                    Text(
                        "已覆盖年份：${status.knownYears.sorted().joinToString("、")}",
                        color = AppTheme.colors.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (warning != null) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = AppTheme.colors.orange.copy(alpha = 0.12f)),
                shape = MaterialTheme.shapes.large
            ) {
                Text(
                    warning,
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (status.message.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            // 失败必须一眼可见：绿色只留给"全部成功"；部分成功用橙色
            val tone = HolidayStatusPresenter.resultTone(status.resultOk, status.message)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when (tone) {
                        HolidayResultTone.SUCCESS -> AppTheme.colors.green.copy(alpha = 0.12f)
                        HolidayResultTone.PARTIAL -> AppTheme.colors.orange.copy(alpha = 0.12f)
                        HolidayResultTone.FAILURE -> AppTheme.colors.red.copy(alpha = 0.10f)
                    }
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Text(
                    status.message,
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        val errorText = status.error
        if (errorText != null && status.message.isBlank()) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = AppTheme.colors.red.copy(alpha = 0.10f)),
                shape = MaterialTheme.shapes.large
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("上次更新未成功", style = MaterialTheme.typography.titleSmall, color = AppTheme.colors.red)
                    Text(errorText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { vm.refreshHolidays() },
            enabled = !status.updating,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (status.updating) "正在更新…" else "立即更新")
        }

        Spacer(Modifier.height(16.dp))
        SettingsGroup {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("为什么要联网", style = MaterialTheme.typography.titleSmall)
                Text(
                    "放假与调休安排由国务院办公厅在每年 11 月前后发布，无法提前写入应用，只能联网获取。数据源由公告机器生成，可追溯到 gov.cn 原文。",
                    color = AppTheme.colors.muted,
                    style = MaterialTheme.typography.bodySmall
                )
                Text("联网失败会怎样", style = MaterialTheme.typography.titleSmall)
                Text(
                    "自动回退到内置公告表。元旦、春节、清明、劳动节、端午、中秋、国庆这些法定节日当天由农历与节气算法推算，" +
                        "不依赖联网，任何年份都能正确标出；只有「哪几天放假、哪几天调休上班」需要公告数据。",
                    color = AppTheme.colors.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
