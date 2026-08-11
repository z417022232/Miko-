package com.example.worktimetracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.example.worktimetracker.location.service.ForegroundLocationService
import com.example.worktimetracker.location.recovery.ServiceRecovery
import com.example.worktimetracker.location.recovery.ServiceRecoveryPolicy
import com.example.worktimetracker.ui.app.WorkTimeViewModel
import com.example.worktimetracker.ui.screens.CalendarScreen
import com.example.worktimetracker.ui.screens.SettingsScreen
import com.example.worktimetracker.ui.screens.StatisticsScreen
import com.example.worktimetracker.ui.screens.WorkTimePickerDialog
import com.example.worktimetracker.ui.screens.formatClock
import com.example.worktimetracker.ui.theme.WorkTimeTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WorkTimeTrackerTheme { AppRoot() } }
    }

    override fun onStart() {
        super.onStart()
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasLocationPermission) {
            ServiceRecovery.start(this, ServiceRecoveryPolicy.RecoveryTrigger.USER_VISIBLE)
        }
    }
}

private enum class MainTab(val label: String) {
    RECORDS("记录"),
    STATISTICS("统计"),
    SETTINGS("设置")
}

@Composable
fun AppRoot() {
    val vm: WorkTimeViewModel = viewModel()
    val onboardingDone by vm.onboardingDone.collectAsState()
    if (!onboardingDone) {
        OnboardingScreen(vm)
        return
    }

    var tab by remember { mutableStateOf(MainTab.RECORDS) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 2.dp) {
                MainTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = {
                            Icon(
                                imageVector = when (item) {
                                    MainTab.RECORDS -> Icons.Outlined.CalendarMonth
                                    MainTab.STATISTICS -> Icons.Outlined.BarChart
                                    MainTab.SETTINGS -> Icons.Outlined.Settings
                                },
                                contentDescription = item.label
                            )
                        },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (tab) {
                MainTab.RECORDS -> CalendarScreen(vm)
                MainTab.STATISTICS -> StatisticsScreen(vm)
                MainTab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
private fun OnboardingScreen(vm: WorkTimeViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val lastLocation by vm.lastKnownLocationText.collectAsState()
    var startMinutes by remember(settings.workStartMinutes) { mutableIntStateOf(settings.workStartMinutes) }
    var endMinutes by remember(settings.workEndMinutes) { mutableIntStateOf(settings.workEndMinutes) }
    var showTimePicker by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp)
    ) {
        Text("欢迎使用", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "只需设置一次，应用会在后台自动整理每天的上下班和工时。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        SetupCard(
            icon = { Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.colorScheme.primary) },
            title = "交接时间",
            summary = "${formatClock(startMinutes)}  —  ${formatClock(endMinutes)}",
            action = "修改",
            onClick = { showTimePicker = true }
        )
        Spacer(Modifier.height(12.dp))
        SetupCard(
            icon = { Icon(Icons.Outlined.LocationOn, null, tint = MaterialTheme.colorScheme.primary) },
            title = "公司与家庭",
            summary = if (lastLocation == "暂无定位") "先获取当前位置，再分别保存" else "最近定位：$lastLocation",
            action = "获取定位",
            onClick = {
                val permissions = buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()
                permissionLauncher.launch(permissions)
                val intent = Intent(context, ForegroundLocationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.refreshLastKnownLocation() }, modifier = Modifier.weight(1f)) {
                Text("刷新定位")
            }
            TextButton(onClick = { vm.useLastLocationForCompany() }, modifier = Modifier.weight(1f)) {
                Text("设为公司")
            }
            TextButton(onClick = { vm.useLastLocationForHome() }, modifier = Modifier.weight(1f)) {
                Text("设为家庭")
            }
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                vm.saveWorkTimes(
                    (startMinutes / 60).toString(),
                    (startMinutes % 60).toString(),
                    (endMinutes / 60).toString(),
                    (endMinutes % 60).toString()
                )
                vm.finishOnboarding()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("完成设置")
        }
        TextButton(onClick = { vm.finishOnboarding() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("稍后设置")
        }
    }

    if (showTimePicker) {
        WorkTimePickerDialog(
            startMinutes = startMinutes,
            endMinutes = endMinutes,
            onDismiss = { showTimePicker = false },
            onConfirm = { start, end ->
                startMinutes = start
                endMinutes = end
                showTimePicker = false
            }
        )
    }
}

@Composable
private fun SetupCard(
    icon: @Composable () -> Unit,
    title: String,
    summary: String,
    action: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            icon()
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(action, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}
