package com.example.worktimetracker.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.location.permission.PermissionManager
import com.example.worktimetracker.location.permission.PermissionStatus
import com.example.worktimetracker.location.service.ForegroundLocationService
import com.example.worktimetracker.ui.app.WorkTimeViewModel

private enum class SettingsPage { ROOT, LOCATION, RULES, PERMISSIONS, DATA, LOGS }
private enum class LocationTarget { COMPANY, HOME }

@Composable
fun SettingsScreen(vm: WorkTimeViewModel) {
    var page by remember { mutableStateOf(SettingsPage.ROOT) }
    BackHandler(page != SettingsPage.ROOT) { page = SettingsPage.ROOT }
    when (page) {
        SettingsPage.ROOT -> SettingsHome(vm, onOpen = { page = it })
        SettingsPage.LOCATION -> LocationSettingsPage(vm, onBack = { page = SettingsPage.ROOT })
        SettingsPage.RULES -> AutoRulesPage(vm, onBack = { page = SettingsPage.ROOT })
        SettingsPage.PERMISSIONS -> PermissionSettingsPage(vm, onBack = { page = SettingsPage.ROOT })
        SettingsPage.DATA -> DataSettingsPage(vm, onBack = { page = SettingsPage.ROOT })
        SettingsPage.LOGS -> LogsPage(vm, onBack = { page = SettingsPage.ROOT })
    }
}

@Composable
private fun SettingsHome(vm: WorkTimeViewModel, onOpen: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    var showTimes by remember { mutableStateOf(false) }
    var showDefault by remember { mutableStateOf(false) }
    val permissions = PermissionManager.check(context)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader("设置", "常用信息放在前面，其他功能按需进入")
        Spacer(Modifier.height(14.dp))
        TrackingStatusCard(permissions, onClick = { onOpen(SettingsPage.PERMISSIONS) })
        Spacer(Modifier.height(14.dp))
        SectionTitle("工作")
        SettingsGroup {
            SettingsRow(
                Icons.Outlined.Schedule,
                "上下班时间",
                "${formatClock(settings.workStartMinutes)} — ${formatClock(settings.workEndMinutes)}"
            ) { showTimes = true }
            ThinDivider()
            SettingsRow(
                Icons.Outlined.Timer,
                "默认工时",
                if (settings.hasDefaultHours) formatMinutes(settings.defaultWorkMinutes ?: 0) else "关闭 · 按实际定位计算"
            ) { showDefault = true }
            ThinDivider()
            SettingsRow(
                Icons.Outlined.LocationOn,
                "公司与家庭",
                locationSummary(settings),
                tint = AppGreen
            ) { onOpen(SettingsPage.LOCATION) }
            ThinDivider()
            SettingsRow(
                Icons.Outlined.Tune,
                "自动识别规则",
                "休息扣除${settings.restDeductionMinutes}分 · 离岗确认${settings.leaveCompanyConfirmMinutes}分",
                tint = AppPurple
            ) { onOpen(SettingsPage.RULES) }
        }
        Spacer(Modifier.height(14.dp))
        SectionTitle("系统与数据")
        SettingsGroup {
            SettingsRow(
                Icons.Outlined.Security,
                "权限与自动记录",
                if (permissions.ready) "权限完整" else "有权限需要处理",
                tint = if (permissions.ready) AppGreen else AppOrange
            ) { onOpen(SettingsPage.PERMISSIONS) }
            ThinDivider()
            SettingsRow(Icons.Outlined.Backup, "导出与备份", "Excel、PDF、CSV 和 JSON", tint = AppBlue) {
                onOpen(SettingsPage.DATA)
            }
            ThinDivider()
            SettingsRow(Icons.Outlined.BugReport, "运行日志", "定位异常时用于检查", tint = AppMuted) {
                onOpen(SettingsPage.LOGS)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("工时记录助手 · 本地单机版", color = AppMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(12.dp))
    }

    if (showTimes) {
        WorkTimePickerDialog(
            settings.workStartMinutes,
            settings.workEndMinutes,
            onDismiss = { showTimes = false }
        ) { start, end ->
            vm.saveWorkTimes((start / 60).toString(), (start % 60).toString(), (end / 60).toString(), (end % 60).toString())
            showTimes = false
        }
    }
    if (showDefault) {
        DefaultHoursDialog(settings, vm, onDismiss = { showDefault = false })
    }
}

@Composable
private fun TrackingStatusCard(status: PermissionStatus, onClick: () -> Unit) {
    val ready = status.ready
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = if (ready) AppGreen.copy(alpha = 0.09f) else AppOrange.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                null,
                tint = if (ready) AppGreen else AppOrange,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (ready) "自动记录准备就绪" else "自动记录需要检查", fontWeight = FontWeight.Bold)
                Text(
                    if (ready) "定位、后台定位和通知权限均已开启" else "点击查看缺少的权限或启动记录服务",
                    color = AppMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text("查看", color = if (ready) AppGreen else AppOrange, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun locationSummary(settings: UserSettingsEntity): String {
    val company = if (settings.companyLat != null && settings.companyLng != null) "公司已设置" else "公司未设置"
    val home = if (settings.homeLat != null && settings.homeLng != null) "家庭已设置" else "家庭未设置"
    return "$company · $home"
}

@Composable
private fun DefaultHoursDialog(settings: UserSettingsEntity, vm: WorkTimeViewModel, onDismiss: () -> Unit) {
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
                        Text("开启后不再按定位时长扣休息", color = AppMuted, style = MaterialTheme.typography.bodySmall)
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
                            Text("${hours}小时", color = AppBlue, fontWeight = FontWeight.Bold)
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

@Composable
private fun LocationSettingsPage(vm: WorkTimeViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val lastLocation by vm.lastKnownLocationText.collectAsState()
    val searchMessage by vm.placeSearchMessage.collectAsState()
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
            colors = CardDefaults.cardColors(containerColor = AppBlue.copy(alpha = 0.08f)),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MyLocation, null, tint = AppBlue)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("最近一次定位", fontWeight = FontWeight.SemiBold)
                    Text(lastLocation, color = AppMuted, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { vm.refreshLastKnownLocation() }) { Icon(Icons.Outlined.Refresh, "刷新") }
            }
        }
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
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = if (isSet) AppGreen else AppMuted)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(if (isSet) "位置已设置" else "尚未设置", color = if (isSet) AppGreen else AppOrange, style = MaterialTheme.typography.bodySmall)
                }
                Text("半径 ${radius}米", color = AppMuted)
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
                Text("输入公司、园区、小区或道路名称，应用会直接保存搜索结果。", color = AppMuted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(keyword, { keyword = it }, label = { Text("地点名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, color = if (message.contains("成功")) AppGreen else AppMuted, style = MaterialTheme.typography.bodySmall)
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
        Text("修改后立即生效。固定工时和手动工时不会重复扣除休息。", color = AppMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
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
    val status = remember(refresh) { PermissionManager.check(context) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp)) {
        ScreenHeader("权限与自动记录", "OriginOS 6 后台运行检查", onBack)
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            PermissionRow("精确定位", "用于判断公司和家庭范围", status.fineLocation)
            ThinDivider()
            PermissionRow("后台定位", "退出应用后继续记录", status.backgroundLocation)
            ThinDivider()
            PermissionRow("通知", "显示常驻记录状态和异常提醒", status.notifications)
        }
        Spacer(Modifier.height(14.dp))
        if (!status.fineLocation || !status.notifications) {
            Button(
                onClick = {
                    permissionLauncher.launch(buildList {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.toTypedArray())
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("申请定位和通知权限") }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Security, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("打开系统权限设置")
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
        if (serviceMessage.isNotBlank()) Text(serviceMessage, color = AppGreen, modifier = Modifier.padding(10.dp))
        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = AppOrange.copy(alpha = 0.09f)), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("OriginOS 6 还需要", fontWeight = FontWeight.Bold)
                Text("在系统设置中允许自启动、后台高耗电，并将电池管理设为不限制。", color = AppMuted)
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, summary: String, granted: Boolean) {
    Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (granted) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            null,
            tint = if (granted) AppGreen else AppOrange
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(summary, color = AppMuted, style = MaterialTheme.typography.bodySmall)
        }
        Text(if (granted) "已开启" else "未开启", color = if (granted) AppGreen else AppOrange)
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
            SettingsRow(Icons.Outlined.FolderOpen, "生成或恢复文件", "Excel、PDF、CSV、JSON", tint = AppBlue) { showExport = true }
            ThinDivider()
            SettingsRow(Icons.Outlined.DeleteOutline, "清除所有记录", "保留工作时间、位置和规则设置", tint = AppRed) { showClear = true }
        }
        Spacer(Modifier.height(12.dp))
        Text("导出的文件会打开系统分享面板，不再要求你查找内部文件路径。", color = AppMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
    }
    if (showExport) ExportBottomSheet(vm, onDismiss = { showExport = false })
    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("清除全部记录？") },
            text = { Text("将删除工时、手动修改、定位和运行日志；工作时间、公司与家庭位置不会删除。") },
            confirmButton = {
                TextButton(onClick = { vm.clearAllLocalData(); showClear = false }) { Text("确认清除", color = AppRed) }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun LogsPage(vm: WorkTimeViewModel, onBack: () -> Unit) {
    val logs by vm.recentLogs.collectAsState()
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
        if (logs.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
                Text("暂无运行日志", color = AppMuted, modifier = Modifier.fillMaxWidth().padding(24.dp))
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
