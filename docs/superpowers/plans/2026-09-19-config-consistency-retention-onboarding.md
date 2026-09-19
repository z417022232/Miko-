# 配置一致性、数据保留与首次建站实施计划

> **Spec:** `docs/superpowers/specs/2026-09-19-config-consistency-retention-onboarding-design.md`  
> **Execution:** 严格 TDD；每个任务独立提交；不得运行正式包名 connected tests。

## 目标

完成半径与工资缓存真实生效、删除死代码、DB v18 日志保留 UI、有界通知 ID、首次引导真实建站和围栏刷新，并保持用户历史工时、工资与地点数据不变。

## 文件职责

- `domain/location/SiteRadiusPolicy.kt`：地点半径与模型快照的纯映射。
- `location/service/SiteConfigurationRefresher.kt`：地点保存后的模型半径同步、服务缓存失效与围栏刷新编排。
- `ui/app/WorkTimeViewModel.kt`：UI 状态和写操作；所有工资写入统一调用派生状态刷新。
- `domain/maintenance/LogRetentionPolicy.kt`：标准/永久保留纯决策。
- `location/maintenance/LogMaintenanceService.kt`：每日一次数据库裁剪。
- `domain/notification/NotificationIdPool.kt`：固定大小循环通知槽位。
- `domain/onboarding/OnboardingSitePolicy.kt`：公司/家庭站点的新增或更新纯决策。
- `location/recovery/GeofenceRecovery.kt`：串行注册全部权威站点围栏。

## 全局约束与失败输入

- 地点无坐标、站点 ID 非正数、半径越界：不注册围栏，不制造虚拟学习模型。
- 学习模型不存在：只保存站点，不创建空模型。
- 工资金额非法或月份非法：不写数据库、不刷新成错误缓存。
- 日志时间戳位于未来：不得因 cutoff 算术删除。
- 永久保留：所有自动裁剪返回零删除。
- 围栏外部调用失败：站点事实保留，记录错误并允许以后重试。
- 同时发生多次地点保存：模型同步按最后数据库事实执行，围栏注册由现有 Mutex 串行化。

---

## Task 1：统一地点半径并让修改即时生效

**Files**
- Create: `app/src/main/java/com/example/worktimetracker/domain/location/SiteRadiusPolicy.kt`
- Create: `app/src/main/java/com/example/worktimetracker/location/service/SiteConfigurationRefresher.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/data/dao/LearningModelDao.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/ui/app/WorkTimeViewModel.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/recovery/GeofenceRecovery.kt`
- Test: `app/src/test/java/com/example/worktimetracker/SiteRadiusPolicyTest.kt`
- Test: `app/src/test/java/com/example/worktimetracker/SiteConfigurationContractTest.kt`

**Interfaces**

```kotlin
object SiteRadiusPolicy {
    const val TRANSITION_RADIUS_FACTOR = 1.67
    data class Snapshot(val coreMeters: Double, val transitionMeters: Double)
    fun snapshot(radiusMeters: Int): Snapshot
}

class SiteConfigurationRefresher(private val db: AppDatabase, private val context: Context) {
    suspend fun afterSiteChanged(placeId: Long?)
}
```

- [ ] 写失败测试：50、150、1000 米输入得到相同 core 半径，transition 恒不小于 core；学习圆心变化不能改变半径。
- [ ] 写失败契约测试：`saveSite`、`deleteSite`、`setSiteEnabled` 均调用 `afterSiteChanged`。
- [ ] 运行：

```powershell
& E:\AndroidBuildJDK\codex-afunix-fix\Invoke-CodexGradle.ps1 -ProjectDir $PWD testDebugUnitTest --tests '*SiteRadiusPolicyTest' --tests '*SiteConfigurationContractTest'
```

预期：因新类型和调用不存在而失败。

- [ ] 实现纯策略；DAO 新增仅更新既有模型半径的定向 UPDATE，不创建空模型。
- [ ] `SiteConfigurationRefresher` 在 Room transaction 后读取最新站点半径、同步模型快照，然后调用 `ServiceRecovery.invalidateSiteCache` 与 `GeofenceRecovery.register`。
- [ ] `AnchorLearningService` 创建或刷新模型时始终从当前 `SiteEntity.radiusMeters` 写快照，不沿用旧模型半径。
- [ ] 三个地点写入口统一调用 refresher；外部围栏失败仅记录日志。
- [ ] 重跑定向测试并提交：

```powershell
git add app
git commit -m "fix: make site radius changes authoritative"
```

---

## Task 2：集中刷新工资派生缓存

**Files**
- Modify: `app/src/main/java/com/example/worktimetracker/ui/app/WorkTimeViewModel.kt`
- Test: `app/src/test/java/com/example/worktimetracker/PayrollCacheInvalidationContractTest.kt`
- Test: `app/src/test/java/com/example/worktimetracker/PayrollBaselineRefreshTest.kt`

**Interface**

```kotlin
private suspend fun refreshPayrollDerivedState() {
    _payRateSegments.value = db.payrollDao().rateSegments()
    _payParams.value = db.payrollDao().allPayParams().associateBy { it.payrollMonth }
    _payBaseline.value = computePayBaseline()
    recomputeMonthPayroll(_month.value, _records.value)
}
```

- [ ] 写失败测试：基准工资从 A 月改到 B 月后，当日工资与整月预测使用 B 月新基准。
- [ ] 写源码契约测试，覆盖 `saveMonthlySalary`、`saveMonthlySalaryFor`、工资条确认、费率增删、月参数保存、备份恢复和工资清理。
- [ ] 运行定向测试并确认红灯。
- [ ] 用单一 `refreshPayrollDerivedState` 替换分散刷新；每次数据库写成功后再刷新。
- [ ] 用递增 generation 或串行 Mutex 防止较旧刷新覆盖较新写入。
- [ ] 重跑测试并提交：

```powershell
git commit -am "fix: invalidate payroll derived caches after writes"
```

---

## Task 3：删除死接口和无用权限

**Files**
- Delete: `app/src/main/java/com/example/worktimetracker/data/repository/WorkRecordRepository.kt`
- Delete: `app/src/main/java/com/example/worktimetracker/data/repository/SettingsRepository.kt`
- Delete: `app/src/main/java/com/example/worktimetracker/data/repository/LocationRepository.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/example/worktimetracker/ui/app/WorkTimeViewModel.kt`
- Test: `app/src/test/java/com/example/worktimetracker/DeadApiContractTest.kt`

- [ ] 写契约测试：源码树不存在三个 Repository、`SYSTEM_ALERT_WINDOW` 和七个函数名。
- [ ] 确认测试先失败。
- [ ] 删除三个文件、权限和以下函数：`saveLocations`、`searchPlaceAndSet`、`prepareCompanyCalibration`、`acceptCompanyCalibration`、`cancelCompanyCalibration`、`monthlyPayParams`、`sourcesForSite`。
- [ ] 再次全仓 `rg`，只允许契约测试中的禁止字符串。
- [ ] 编译并提交：

```powershell
git add -A
git commit -m "refactor: remove unused repositories permissions and APIs"
```

---

## Task 4：DB v18 日志保留模式、裁剪服务与设置 UI

**Files**
- Modify: `app/src/main/java/com/example/worktimetracker/data/entity/UserSettingsEntity.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/WorkTimeApplication.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/data/database/AppDatabase.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/data/dao/AppLogDao.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/data/dao/LocationLogDao.kt`
- Create: `app/src/main/java/com/example/worktimetracker/domain/maintenance/LogRetentionPolicy.kt`
- Create: `app/src/main/java/com/example/worktimetracker/location/maintenance/LogMaintenanceService.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/ui/app/WorkTimeViewModel.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/ui/screens/SettingsScreen.kt`
- Test: `app/src/test/java/com/example/worktimetracker/LogRetentionPolicyTest.kt`
- Test: `app/src/test/java/com/example/worktimetracker/LogRetentionUiContractTest.kt`
- Test: migration schema verification script under ignored `diagnostics/tools/_mig18.py`

**Interfaces**

```kotlin
object LogRetentionMode {
    const val STANDARD = "STANDARD"
    const val FOREVER = "FOREVER"
    fun normalize(raw: String?): String
}

data class LogRetentionPlan(
    val locationCutoffMillis: Long?,
    val appLogCutoffMillis: Long?,
    val maxAppLogRows: Int?
)

object LogRetentionPolicy {
    const val LOCATION_DAYS = 180L
    const val APP_LOG_DAYS = 90L
    const val MAX_APP_LOG_ROWS = 10_000
    fun plan(mode: String?, now: Long): LogRetentionPlan
}
```

- [ ] 写失败测试：标准模式边界、永久模式零 cutoff、未知模式回落 STANDARD、未来时间戳不删除。
- [ ] 写失败 UI 契约测试：设置页含“标准保留”“永久保留”和风险说明，ViewModel 有保存入口。
- [ ] DB 版本升至18，增加 `MIGRATION_17_18`：`logRetentionMode TEXT NOT NULL DEFAULT 'STANDARD'`。
- [ ] DAO 增加按 cutoff 删除和“仅保留最新10,000条”的 SQL；不得加载整表到内存。
- [ ] `LogMaintenanceService.runIfDue(now)` 用 SharedPreferences 保存本地自然日；FOREVER 直接返回。
- [ ] 在 Application 启动和健康巡检调用 `runIfDue`，并用进程内 Mutex 防并发。
- [ ] 设置页增加双选 UI，保存后立即触发一次维护；FOREVER 不触发删除。
- [ ] 用 v17 schema 构造内存库并逐列比对 v18；确认 user_version、默认值、索引和原有表不变。
- [ ] 定向测试通过后提交：

```powershell
git add app docs
git commit -m "feat: add configurable log retention and migration v18"
```

---

## Task 5：有界通知 ID 池

**Files**
- Create: `app/src/main/java/com/example/worktimetracker/domain/notification/NotificationIdPool.kt`
- Create: `app/src/main/java/com/example/worktimetracker/notification/NotificationIdStore.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/service/ForegroundLocationService.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/recovery/RecoveryNotifier.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/recovery/LocationHealthWorker.kt`
- Test: `app/src/test/java/com/example/worktimetracker/NotificationIdPoolTest.kt`

**Interface**

```kotlin
object NotificationIdPool {
    const val FOREGROUND_ID = 1001
    const val RECOVERY_ID = 2002
    const val EVENT_FIRST_ID = 3000
    const val EVENT_POOL_SIZE = 8
    fun eventId(sequence: Long): Int
}
```

- [ ] 写失败测试：固定 ID 不变，事件序列循环在3000..3007，负数与 `Long.MAX_VALUE` 不溢出。
- [ ] 实现持久化递增序列；事件通知取下一个池槽，状态通知覆盖固定 ID。
- [ ] 删除 `System.currentTimeMillis() % Int.MAX_VALUE` 通知 ID。
- [ ] 验证最多存在8个事件通知槽并提交：

```powershell
git commit -am "fix: bound event notification identifiers"
```

---

## Task 6：首次引导创建真实站点并联动围栏

**Files**
- Create: `app/src/main/java/com/example/worktimetracker/domain/onboarding/OnboardingSitePolicy.kt`
- Create: `app/src/main/java/com/example/worktimetracker/location/service/OnboardingSiteService.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/ui/app/WorkTimeViewModel.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/MainActivity.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/recovery/GeofenceRecovery.kt`
- Test: `app/src/test/java/com/example/worktimetracker/OnboardingSitePolicyTest.kt`
- Test: `app/src/test/java/com/example/worktimetracker/OnboardingSiteContractTest.kt`

**Interfaces**

```kotlin
enum class OnboardingSiteKind { COMPANY, HOME }

data class OnboardingSiteDecision(
    val existingSiteId: Long?,
    val siteType: String,
    val makePrimary: Boolean,
    val displayName: String
)

class OnboardingSiteService(private val db: AppDatabase, private val context: Context) {
    suspend fun save(kind: OnboardingSiteKind, latitude: Double, longitude: Double): Long
}
```

- [ ] 写失败测试：公司/家庭类型映射、公司主地点、重复保存更新同类型迁移/引导站点、不生成重复行。
- [ ] 写失败 UI 契约测试：按钮调用新的站点入口，成功状态可见，完成时缺失提示可见。
- [ ] 服务在 transaction 内清主地点、upsert 站点、同步旧坐标；事务后调用 Task 1 refresher。
- [ ] ViewModel 暴露 `setOnboardingSite(kind)` 与公司/家庭完成状态；旧 `useLastLocationForCompany/Home` 迁移或删除。
- [ ] 引导页显示“公司已设置/家庭已设置”；缺项时允许确认后稍后设置。
- [ ] 围栏失败返回可理解提示但不删除站点。
- [ ] 定向测试通过后提交：

```powershell
git add app
git commit -m "feat: create real sites during onboarding"
```

---

## Task 7：全量回归、迁移验证和发布构建

**Files**
- Modify: `app/build.gradle.kts`
- Update: `docs/superpowers/specs/2026-09-19-config-consistency-retention-onboarding-design.md`（仅在实现产生已裁决偏差时）

- [ ] 全仓检查零调用与禁止权限：

```powershell
rg -n "WorkRecordRepository|SettingsRepository|LocationRepository|SYSTEM_ALERT_WINDOW|saveLocations|searchPlaceAndSet|prepareCompanyCalibration|acceptCompanyCalibration|cancelCompanyCalibration|monthlyPayParams|sourcesForSite" app/src/main app/src/test
```

预期：只出现明确的禁止项契约测试或零结果。

- [ ] 运行 v17→v18 schema 比对脚本，保存 PASS 输出到任务记录，不提交真实数据库。
- [ ] 递增 `versionCode` 与 `versionName`。
- [ ] 完整构建：

```powershell
& E:\AndroidBuildJDK\codex-afunix-fix\Invoke-CodexGradle.ps1 -ProjectDir $PWD clean testDebugUnitTest assembleDebug
```

- [ ] 汇总测试 XML：tests、failures、errors 必须明确为零失败。
- [ ] `git diff --check`、检查 APK badging、计算 SHA-256。
- [ ] 不运行正式包名 connected tests；真机安装前另行比较证书摘要并备份数据。
- [ ] 最终提交：

```powershell
git add app docs
git commit -m "chore: release config consistency and retention update"
```

## Review Focus

1. 地点半径修改后，学习圆心路径和普通配置路径是否仍使用同一半径。
2. 月度实发保存的异步刷新是否可能被较早协程覆盖。
3. FOREVER 模式是否在所有自动维护入口都真正零删除。
4. 首次引导重复点击是否创建重复站点或两个主工作地点。
5. 围栏注册失败是否错误回滚已经保存的站点。
6. DB v18 是否保留 v17 的全部表、索引和默认值。
7. 通知事件池是否因负数或大序列溢出到固定通知 ID 区间。
