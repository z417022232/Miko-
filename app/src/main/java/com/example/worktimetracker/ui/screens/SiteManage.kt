package com.example.worktimetracker.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.worktimetracker.data.entity.SiteEntity
import com.example.worktimetracker.data.entity.SiteEvidenceSourceEntity
import com.example.worktimetracker.domain.location.PlaceLearningStatus
import com.example.worktimetracker.location.evidence.ScannedWifi
import com.example.worktimetracker.ui.app.SiteDraft
import com.example.worktimetracker.ui.app.SiteRowUi
import com.example.worktimetracker.ui.app.WifiScanUi
import com.example.worktimetracker.ui.app.WorkTimeViewModel
import com.example.worktimetracker.ui.theme.AppTheme
import java.util.Locale

/*
 * 界面稿 v4 屏 09/10/11：地点管理 · 新增/编辑地点 · 选取 Wi-Fi 网络。
 *
 * 这一组页面把 v4.2 之前「一个公司 + 一个家」的硬编码模型换成 sites 表驱动的多地点：
 * 车间、仓库、家各是一条记录，各带一组证据源（Wi-Fi 哈希 / GPS 半径）。
 * 判定侧由 SiteResolver 统一收敛成 COMPANY / HOME 两个锚点，状态机完全不知道有多个地点。
 *
 * 两处刻意的实现取舍（与稿子的差异，都在页内写明）：
 * 1. 保存门槛**只有名称**，不要求「至少 1 个证据源」。
 *    原因：证据源以 siteId 为外键，新地点在保存前**不可能**有证据源；
 *    旧实现要求 `name.isNotBlank() && 证据源数 > 0`，于是新地点的「保存」永远是灰的，
 *    而 Wi-Fi 行又被 `isNew` 挡住（「保存地点后即可选取」且不可点）——
 *    用户彻底无法新增地点（2026-09-16 报障）。现在点 Wi-Fi 会先落地点再跳选取页。
 * 2. 蓝牙信标本期不给手动选取：它的哈希口径包含 manufacturer data / UUID 列表，
 *    前台手动扫很难与后台采集器逐位对齐，选错了反而污染证据。页面如实标注「跟随环境自动学习」。
 */

private enum class SitePage { LIST, EDIT, WIFI }

/** 地点管理入口：三页内部路由。 */
@Composable
internal fun SiteManageHost(
    vm: WorkTimeViewModel,
    onBack: () -> Unit
) {
    var page by remember { mutableStateOf(SitePage.LIST) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var wifiSiteId by remember { mutableStateOf<Long?>(null) }

    BackHandler(page != SitePage.LIST) { page = SitePage.LIST }

    when (page) {
        SitePage.LIST -> SiteListPage(
            vm = vm,
            onBack = onBack,
            onEdit = { id -> editingId = id; page = SitePage.EDIT }
        )
        SitePage.EDIT -> SiteEditPage(
            vm = vm,
            siteId = editingId,
            onBack = { page = SitePage.LIST },
            onPickWifi = { id -> wifiSiteId = id; page = SitePage.WIFI }
        )
        SitePage.WIFI -> WifiPickPage(
            vm = vm,
            siteId = wifiSiteId,
            onBack = { page = SitePage.EDIT }
        )
    }
}

// ---------------------------------------------------------------------------
// 09 · 地点管理
// ---------------------------------------------------------------------------

@Composable
private fun SiteListPage(
    vm: WorkTimeViewModel,
    onBack: () -> Unit,
    onEdit: (Long?) -> Unit
) {
    val sites by vm.sites.collectAsState()
    val sources by vm.siteEvidenceSources.collectAsState()
    val lastLocationText by vm.lastKnownLocationText.collectAsState()
    val health by vm.sourceHealth.collectAsState()
    val refresh by vm.evidenceRefresh.collectAsState()
    val learning by vm.learningStatuses.collectAsState()
    var here by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    LaunchedEffect(Unit) {
        vm.refreshLastKnownLocation()
        vm.reloadSourceHealth()
        // 学习状态只读、随时可跑；进来取一次，切开关后再取（见 setLearningAutoApply）
        vm.reloadLearningStatuses()
        vm.loadCurrentLocation { lat, lng -> if (lat != null && lng != null) here = lat to lng }
    }

    val rows: List<SiteRowUi> = vm.siteRows(here?.first, here?.second)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader(
            "地点管理",
            "每个地点靠证据源匹配",
            onBack = onBack,
            action = { TextButton(onClick = { onEdit(null) }) { Text("新增") } }
        )
        Spacer(Modifier.height(14.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.blue.copy(alpha = 0.08f)),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.GpsFixed, null, tint = AppTheme.colors.blue)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("最近一次定位", fontWeight = FontWeight.SemiBold)
                        Text(lastLocationText, color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 刷新入口唯一：整行右侧的「立即刷新状态」一次重取四源 + 最近定位，
                // 不再在标题行重复放一个「刷新」（两处同义按钮只会让人犹豫点哪个）。
                EvidenceSourceRow(
                    health = health,
                    refreshing = refresh.running,
                    onRefresh = {
                        // 真正的「重取一次」：拉起前台服务做一次性定位 + 环境扫描，
                        // 而不是重读库里那行旧坐标（旧实现因此看起来「点了没反应」）
                        vm.refreshEvidenceNow()
                        vm.loadCurrentLocation { lat, lng -> if (lat != null && lng != null) here = lat to lng }
                    }
                )
            }
        }
        if (refresh.message != null) {
            Spacer(Modifier.height(8.dp))
            EvidenceRefreshBanner(
                message = refresh.message,
                isRunning = refresh.running,
                onConsume = { vm.clearEvidenceRefreshMessage() }
            )
        }
        Spacer(Modifier.height(14.dp))

        if (rows.isEmpty()) {
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("还没有地点", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "添加「加工厂」「家」这些你常待的地方，App 才知道什么时候算出勤。",
                        color = AppTheme.colors.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            SettingsGroup {
                rows.forEachIndexed { index, row ->
                    if (index > 0) ThinDivider()
                    SiteListRow(
                        row = row,
                        sourceSummary = evidenceBreakdown(
                            hasGps = row.site.hasGps,
                            wifi = sources.count {
                                it.siteId == row.site.id &&
                                    it.sourceType == SiteEvidenceSourceEntity.TYPE_WIFI
                            },
                            bluetooth = sources.count {
                                it.siteId == row.site.id &&
                                    it.sourceType == SiteEvidenceSourceEntity.TYPE_BLUETOOTH
                            }
                        ),
                        // 只对**带坐标**的地点显示学习状态：没有坐标的地点不参与锚点学习，
                        // 给它显示「尚未开始学习」等于暗示它将来会开始 —— 那是假信息。
                        learningStatus = if (row.site.hasGps) learning[row.site.id] else null,
                        onEdit = { onEdit(row.site.id) },
                        onToggle = { vm.setSiteEnabled(row.site.id, it) },
                        onSetLearning = { vm.setLearningAutoApply(row.site.id, it) }
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // 学习校准的说明只在**真的有地点可能进入学习**时出现：
        // 一个带坐标的地点都没有时讲「停用会保留数据」只会让人困惑。
        if (rows.any { it.site.hasGps }) {
            SettingsGroup { LearningDisableNotice() }
            Spacer(Modifier.height(14.dp))
        }

        SettingsGroup {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("地点怎么用", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "每个地点靠证据源匹配。证据源越多、越稳定，判定越快、越省电。" +
                        "地点名称是保存门槛；GPS和环境证据可以保存后继续补充。",
                    color = AppTheme.colors.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        SettingsGroup {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("证据源说明", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                EvidenceNote("Wi-Fi BSSID", "最强，室内稳定", AppTheme.colors.green)
                EvidenceNote("蓝牙信标", "强，需要现场部署", AppTheme.colors.blue)
                EvidenceNote("GPS", "室外可靠，室内漂移", AppTheme.colors.orange)
                EvidenceNote("基站", "最弱，仅作兜底且不驱动状态变更", AppTheme.colors.muted)
            }
        }
        Spacer(Modifier.height(14.dp))

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SiteListRow(
    row: SiteRowUi,
    sourceSummary: String,
    learningStatus: PlaceLearningStatus?,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSetLearning: (Boolean) -> Unit
) {
    // 外层从 Row 改成 Column：学习小节要占满整行宽度，
    // 挤在「地点信息 + 开关」那一行里会被压成窄条，明细数字全部换行。
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .padding(
                    start = 16.dp,
                    end = 8.dp,
                    top = 12.dp,
                    // 有学习小节时下边距留给它，避免「距当前位置」那一行贴着分隔线
                    bottom = if (learningStatus == null) 12.dp else 2.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.site.name,
                        fontWeight = FontWeight.SemiBold,
                        color = if (row.site.enabled) AppTheme.colors.textPrimary else AppTheme.colors.muted
                    )
                    if (row.site.isPrimary) {
                        Spacer(Modifier.size(8.dp))
                        StatusPill("主", AppTheme.colors.blue)
                    }
                    if (row.site.siteType == SiteEntity.TYPE_NON_WORK) {
                        Spacer(Modifier.size(8.dp))
                        StatusPill("不计工时", AppTheme.colors.muted)
                    }
                    if (row.site.migrated) {
                        Spacer(Modifier.size(8.dp))
                        StatusPill("来自旧设置", AppTheme.colors.orange)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(sourceSummary, color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
                row.distanceMeters?.let { distance ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "距当前位置约 ${metersText(distance)}",
                        color = AppTheme.colors.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Switch(checked = row.site.enabled, onCheckedChange = onToggle)
        }
        if (learningStatus != null) {
            SiteLearningSection(
                status = learningStatus,
                onSetEnabled = onSetLearning,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            )
        }
    }
}

@Composable
private fun EvidenceNote(name: String, note: String, tint: androidx.compose.ui.graphics.Color) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(tint, CircleShape))
        Spacer(Modifier.size(9.dp))
        Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.size(8.dp))
        Text(note, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
    }
}

// ---------------------------------------------------------------------------
// 10 · 新增 / 编辑地点
// ---------------------------------------------------------------------------

@Composable
private fun SiteEditPage(
    vm: WorkTimeViewModel,
    siteId: Long?,
    onBack: () -> Unit,
    onPickWifi: (Long) -> Unit
) {
    val sites by vm.sites.collectAsState()
    val sources by vm.siteEvidenceSources.collectAsState()
    val site = remember(sites, siteId) { sites.firstOrNull { it.id == siteId } }

    var draft by remember(siteId) {
        mutableStateOf(site?.let { SiteDraft.from(it) } ?: SiteDraft())
    }
    // 站点列表异步到达时补一次初值（进入编辑页时 sites 可能还没加载完）
    LaunchedEffect(site?.id) {
        if (site != null && draft.id == null) draft = SiteDraft.from(site)
    }

    var showDelete by remember { mutableStateOf(false) }
    var showRadius by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showCoordinates by remember { mutableStateOf(false) }
    var locationHint by remember { mutableStateOf("") }
    var actionHint by remember { mutableStateOf("") }

    val wifiCount = sources.count {
        it.siteId == siteId && it.sourceType == SiteEvidenceSourceEntity.TYPE_WIFI
    }
    val bluetoothCount = sources.count {
        it.siteId == siteId && it.sourceType == SiteEvidenceSourceEntity.TYPE_BLUETOOTH
    }
    val gpsEvidence = if (draft.gpsReady) 1 else 0
    val totalEvidence = wifiCount + bluetoothCount + gpsEvidence
    val isNew = siteId == null

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader(
            if (isNew) "新增地点" else "编辑地点",
            "判定靠证据源，不靠猜",
            onBack = onBack
        )
        Spacer(Modifier.height(14.dp))

        SettingsGroup {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("名称", style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it.take(24)) },
                    placeholder = { Text("例如：加工厂 · A 车间") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        SectionTitle("地点类型")
        SettingsGroup {
            SiteOptionRow(
                title = "工作地点",
                note = "进圈即算到岗，计入工时",
                selected = draft.isWork
            ) { draft = draft.copy(siteType = SiteEntity.TYPE_WORK) }
            ThinDivider()
            SiteOptionRow(
                title = "非工作地点",
                note = "回到这里算下班，不计入工时",
                selected = !draft.isWork
            ) { draft = draft.copy(siteType = SiteEntity.TYPE_NON_WORK) }
            ThinDivider()
            SwitchRow(
                title = "计入工时",
                note = if (draft.isWork) "由地点类型决定：工作地点计入" else "由地点类型决定：非工作地点不计入",
                checked = draft.isWork
            ) { draft = draft.copy(siteType = if (it) SiteEntity.TYPE_WORK else SiteEntity.TYPE_NON_WORK) }
            ThinDivider()
            SwitchRow(
                title = "设为主地点",
                note = "多个地点同时命中时优先匹配它",
                checked = draft.isPrimary
            ) { draft = draft.copy(isPrimary = it) }
        }
        Spacer(Modifier.height(14.dp))

        SectionTitle("证据源")
        SettingsGroup {
            SettingsRow(
                Icons.Outlined.Wifi,
                "Wi-Fi 网络",
                when {
                    wifiCount > 0 -> "已选 $wifiCount 个"
                    isNew -> "点这里先存下地点，紧接着选取"
                    else -> "未选 · 点这里扫描并勾选"
                },
                tint = if (wifiCount > 0) AppTheme.colors.green else AppTheme.colors.blue,
                showChevron = true,
                onClick = {
                    // 新增地点还没有 siteId，证据源挂不上去 —— 先落地点再跳选取页。
                    // 旧实现把这行写成「保存地点后即可选取」且不可点，用户就卡在
                    // 「Wi-Fi 选不了 + 保存是灰的」这个死循环里，整个新增功能等于没有。
                    if (isNew) {
                        if (draft.name.isBlank()) {
                            actionHint = "先给这个地点起个名字，保存后立刻能选 Wi-Fi"
                        } else {
                            vm.saveSite(draft) { newId -> onPickWifi(newId) }
                        }
                    } else {
                        onPickWifi(siteId!!)
                    }
                }
            )
            ThinDivider()
            SettingsRow(
                Icons.Outlined.Bluetooth,
                "蓝牙信标",
                if (bluetoothCount > 0) "已自动学习 $bluetoothCount 个" else "跟随环境自动学习，无需手动选择",
                tint = AppTheme.colors.muted,
                showChevron = false,
                onClick = {}
            )
            ThinDivider()
            SettingsRow(
                Icons.Outlined.GpsFixed,
                "GPS 坐标",
                if (draft.gpsReady) {
                    "${draft.latitude!!.format(5)}, ${draft.longitude!!.format(5)} · 半径 ${draft.radiusMeters} m"
                } else {
                    locationHint.ifEmpty { "未设置 · 站到地点上点「使用当前位置」" }
                },
                tint = if (draft.gpsReady) AppTheme.colors.green else AppTheme.colors.orange,
                showChevron = false,
                onClick = { showRadius = true }
            )
            ThinDivider()
            SettingsRow(
                Icons.Outlined.Place,
                "使用当前位置",
                "到达地点后再点，坐标才准",
                tint = AppTheme.colors.blue,
                showChevron = false,
                onClick = {
                    vm.loadCurrentLocation { lat, lng ->
                        if (lat != null && lng != null) {
                            draft = draft.copy(latitude = lat, longitude = lng)
                            locationHint = ""
                        } else {
                            locationHint = "暂无定位 · 请先到室外等一次定位"
                        }
                    }
                }
            )
            ThinDivider()
            SettingsRow(
                Icons.Outlined.Search,
                "搜索地点",
                "按公司、园区、小区或道路名称查找坐标",
                tint = AppTheme.colors.blue,
                showChevron = true,
                onClick = { showSearch = true }
            )
            if (draft.gpsReady) {
                ThinDivider()
                SettingsRow(
                    Icons.Outlined.GpsFixed,
                    "重新分析学习位置",
                    "使用最近30天轨迹与环境指纹生成校准候选",
                    tint = AppTheme.colors.purple,
                    showChevron = false,
                    onClick = { vm.rerunPlaceLearning() }
                )
            }
        }
        TextButton(onClick = { showCoordinates = !showCoordinates }) {
            Text(if (showCoordinates) "收起手动坐标" else "高级：手动输入坐标")
        }
        if (showCoordinates) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.latitude?.toString().orEmpty(),
                    onValueChange = { draft = draft.copy(latitude = it.toDoubleOrNull()) },
                    label = { Text("纬度") }, singleLine = true, modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = draft.longitude?.toString().orEmpty(),
                    onValueChange = { draft = draft.copy(longitude = it.toDoubleOrNull()) },
                    label = { Text("经度") }, singleLine = true, modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        Text(
            when {
                totalEvidence > 0 ->
                    "已选 $totalEvidence 个证据源。证据源越多、越稳定，进出车间判定越快也越省电。"
                isNew ->
                    "先保存地点，紧接着会带你选 Wi-Fi；也可以先点「使用当前位置」拿 GPS 坐标。" +
                        "证据源只是建议，不是门槛 —— 名字填好就能存。"
                else ->
                    "这个地点还没有证据源。点「Wi-Fi 网络」扫描并勾选，或先「使用当前位置」拿 GPS 坐标。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (totalEvidence > 0) AppTheme.colors.muted else AppTheme.colors.orange
        )
        if (actionHint.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                actionHint,
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.orange
            )
        }
        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(
                onClick = {
                    val saved = draft
                    vm.saveSite(saved) { newId ->
                        // 新增地点后，证据源这时才有 siteId 可挂：直接带用户去选 Wi-Fi
                        if (saved.id == null) onPickWifi(newId) else onBack()
                    }
                },
                // 唯一的硬门槛是名称。
                // ⚠️ 不要再把「至少 1 个证据源」放回 enabled —— 新地点在保存前不可能有证据源
                // （site_evidence_sources 以 siteId 为外键），那会让新增功能永久不可用。
                enabled = draft.name.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text("保存") }
        }

        if (!isNew) {
            Spacer(Modifier.height(14.dp))
            SettingsGroup {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showDelete = true }
                        .padding(16.dp)
                ) {
                    Text("删除这个地点", color = AppTheme.colors.red, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "同时删掉它的 ${wifiCount + bluetoothCount} 个证据源；已记录的工时不受影响。",
                        color = AppTheme.colors.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    if (showRadius) {
        RadiusDialog(draft.radiusMeters, onDismiss = { showRadius = false }) {
            draft = draft.copy(radiusMeters = it)
            showRadius = false
        }
    }

    if (showSearch) {
        var keyword by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSearch = false },
            title = { Text("搜索地点") },
            text = {
                Column {
                    OutlinedTextField(keyword, { keyword = it }, label = { Text("地点名称") }, singleLine = true)
                    if (locationHint.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(locationHint, color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.searchPlace(keyword) { lat, lng, message ->
                        locationHint = message
                        if (lat != null && lng != null) {
                            draft = draft.copy(latitude = lat, longitude = lng)
                            showSearch = false
                        }
                    }
                }) { Text("搜索并使用") }
            },
            dismissButton = { TextButton(onClick = { showSearch = false }) { Text("取消") } }
        )
    }

    if (showDelete && siteId != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除地点") },
            text = {
                Text(
                    "删除「${draft.name.ifBlank { "该地点" }}」会同时删掉它的 ${wifiCount + bluetoothCount} 个证据源。" +
                        "已被工时记录引用的历史数据不受影响。"
                )
            },
            confirmButton = {
                TextButton(onClick = { showDelete = false; vm.deleteSite(siteId); onBack() }) {
                    Text("删除", color = AppTheme.colors.red)
                }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SiteOptionRow(
    title: String,
    note: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(note, color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) Box(Modifier.size(9.dp).background(AppTheme.colors.blue, CircleShape))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    note: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(note, color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun RadiusDialog(current: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var value by remember(current) { mutableStateOf(current.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GPS 半径") },
        text = {
            Column {
                Text(
                    "判定半径：GPS 落在这个圈里就算到了这个地点。" +
                        "厂区大可以放宽，住宅楼里建议收紧，避免隔墙误判。",
                    color = AppTheme.colors.muted,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                Text("${value.toInt()} m", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = SiteEntity.MIN_RADIUS_METERS.toFloat()..SiteEntity.MAX_RADIUS_METERS.toFloat(),
                    steps = 18
                )
                Text(
                    "可调范围 ${SiteEntity.MIN_RADIUS_METERS}–${SiteEntity.MAX_RADIUS_METERS} m，" +
                        "默认 ${SiteEntity.DEFAULT_RADIUS_METERS} m",
                    color = AppTheme.colors.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.toInt()) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ---------------------------------------------------------------------------
// 11 · 选取 Wi-Fi 网络
// ---------------------------------------------------------------------------

@Composable
private fun WifiPickPage(
    vm: WorkTimeViewModel,
    siteId: Long?,
    onBack: () -> Unit
) {
    val scan by vm.wifiScan.collectAsState()
    val sources by vm.siteEvidenceSources.collectAsState()

    val persistedKey = remember(sources, siteId) {
        sources.filter {
            it.siteId == siteId && it.sourceType == SiteEvidenceSourceEntity.TYPE_WIFI
        }.map { it.identifierHash }.sorted().joinToString(",")
    }
    var selected by remember(siteId) { mutableStateOf<Set<String>>(emptySet()) }
    // 已保存过的 Wi-Fi 要回显勾选：本地库只存哈希，扫不到时用计数提示（见下方 missing）
    LaunchedEffect(siteId, persistedKey) {
        val persisted = persistedKey.split(",").filter { it.isNotEmpty() }.toSet()
        selected = selected + persisted
    }

    // 页面打开即扫一次：用户站到现场才进这一页，等手动点会白等一轮
    LaunchedEffect(siteId) { vm.scanWifi() }

    val candidates = (scan as? WifiScanUi.Ready)?.candidates.orEmpty()
    val missing = selected - candidates.map { it.identifierHash }.toSet()
    val recommended = candidates.count { it.signal >= RECOMMEND_RSSI_DBM }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader(
            "选取 Wi-Fi 网络",
            "信号越强越可靠",
            onBack = onBack
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "在能稳定扫到这个地点的 AP 都选上，进出时判定会更准。信号越强、越稳定越可靠；" +
                "只保存加密指纹，不保存网络名称。",
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.muted
        )
        Spacer(Modifier.height(14.dp))

        when (val state = scan) {
            WifiScanUi.Scanning, WifiScanUi.Idle -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Wifi, null, tint = AppTheme.colors.blue)
                        Spacer(Modifier.height(8.dp))
                        Text("正在扫描…", color = AppTheme.colors.muted)
                    }
                }
            }
            is WifiScanUi.Failed -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AppTheme.colors.orange.copy(alpha = 0.10f)),
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("没扫到可用的 Wi-Fi", fontWeight = FontWeight.SemiBold, color = AppTheme.colors.orange)
                        Spacer(Modifier.height(6.dp))
                        Text(state.message, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
                    }
                }
            }
            is WifiScanUi.Ready -> {
                SettingsGroup {
                    candidates.forEachIndexed { index, wifi ->
                        if (index > 0) ThinDivider()
                        WifiRow(
                            wifi = wifi,
                            checked = wifi.identifierHash in selected,
                            recommended = wifi.signal >= RECOMMEND_RSSI_DBM
                        ) { now ->
                            selected = if (now) selected + wifi.identifierHash
                            else selected - wifi.identifierHash
                        }
                    }
                }
                if (missing.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "以下 ${missing.size} 个已选网络本次没扫到（可能已经关机、改名或换了位置）。" +
                            "它们仍在参与判定，取消勾选即可移除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.muted
                    )
                    Spacer(Modifier.height(8.dp))
                    // 未扫到的已选网络也要能取消：否则用户换掉路由器后无法清理旧指纹
                    SettingsGroup {
                        missing.sorted().forEachIndexed { index, hash ->
                            if (index > 0) ThinDivider()
                            MissingWifiRow(hash) { selected = selected - hash }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            WifiStat("已选", "${selected.size} 个", AppTheme.colors.blue)
            WifiStat("扫描到", "${candidates.size} 个", AppTheme.colors.muted)
            WifiStat("推荐", "$recommended 个", AppTheme.colors.green)
        }
        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { vm.scanWifi() }, modifier = Modifier.weight(1f)) {
                Text("重新扫描")
            }
            Button(
                onClick = {
                    val id = siteId ?: return@Button
                    vm.replaceWifiSources(
                        id,
                        selected.map { hash ->
                            val hit = candidates.firstOrNull { it.identifierHash == hash }
                            ScannedWifi(
                                identifierHash = hash,
                                label = "",
                                signal = hit?.signal ?: 0,
                                secure = hit?.secure ?: false
                            )
                        }
                    )
                    onBack()
                },
                enabled = siteId != null,
                modifier = Modifier.weight(1f)
            ) { Text("确定（${selected.size}）") }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun WifiRow(
    wifi: ScannedWifi,
    checked: Boolean,
    recommended: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle(it) })
        Column(Modifier.weight(1f)) {
            Text(wifi.label.ifBlank { "隐藏网络" }, maxLines = 1)
            Text(
                if (recommended) "信号强 · 建议选上" else "信号弱 · 距离较远，可能造成误判",
                color = if (recommended) AppTheme.colors.green else AppTheme.colors.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            "${wifi.signal} dBm",
            color = if (recommended) AppTheme.colors.green else AppTheme.colors.muted,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * 「已选但本次没扫到」的网络。
 *
 * 库里只有哈希，没有 SSID，所以这里只能显示哈希前缀——它是加盐 SHA-256，
 * 反推不出网络名，拿来给用户区分「这是哪一条旧记录」是安全的。
 */
@Composable
private fun MissingWifiRow(hash: String, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onRemove)
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = true, onCheckedChange = { onRemove() })
        Column(Modifier.weight(1f)) {
            Text("已选网络 · ${hash.take(8)}…", maxLines = 1)
            Text(
                "本次未扫到 · 点一下移除",
                color = AppTheme.colors.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun WifiStat(label: String, value: String, tint: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.SemiBold, color = tint)
        Text(label, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
    }
}

// ---------------------------------------------------------------------------
// 文案小件
// ---------------------------------------------------------------------------

/**
 * 证据源构成文案（列表页副标题）。
 *
 * GPS 坐标也算一类证据源——它有坐标就能单独参与判定，所以「0 个证据源」只在
 * 三类都没有时出现。把构成写出来而不是只写数字，是让用户一眼看出缺哪一类。
 */
internal fun evidenceBreakdown(hasGps: Boolean, wifi: Int, bluetooth: Int): String {
    val parts = buildList {
        if (wifi > 0) add("Wi-Fi")
        if (bluetooth > 0) add("蓝牙信标")
        if (hasGps) add("GPS")
    }
    val total = wifi + bluetooth + if (hasGps) 1 else 0
    return if (parts.isEmpty()) "0 个证据源 · 无法参与判定"
    else "${parts.joinToString(" + ")} · $total 个证据源"
}

/** 距离文案：1km 以内用米，超出用公里，都只保留一位。 */
internal fun metersText(meters: Double): String =
    if (meters < 1000.0) "${meters.toInt()} m"
    else "%.1f km".format(Locale.CHINA, meters / 1000.0)

/** 推荐阈值：-70 dBm 以内算「同一间屋里的 AP」。 */
internal const val RECOMMEND_RSSI_DBM = -70

private fun Double.format(digits: Int): String = "%.${digits}f".format(Locale.CHINA, this)
