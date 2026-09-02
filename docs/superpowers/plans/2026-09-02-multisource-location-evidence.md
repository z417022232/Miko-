# 多源定位证据与后台可靠性 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以 GNSS 为主证据，融合基站、附近 Wi-Fi、附近蓝牙和运动传感器，自动学习家/公司环境，并修复锁屏后台定位链路失效、恢复误记和重复注册问题。

**Architecture:** 新增独立的证据模型、环境指纹学习器、证据融合器和 Android 采集适配器；`ForegroundLocationService` 只负责编排采集与状态机，不直接实现学习或评分。Room 7→8 迁移保存脱敏指纹、短期观察和健康状态，现有 `TrajectoryAnchorEngine` 继续作为唯一工时事件状态机。

**Tech Stack:** Kotlin、Android SDK 35、Room 2.6.1、Coroutines、WorkManager 2.10.0、JUnit 4、AndroidX Room MigrationTestHelper、Gradle。

**Spec:** `docs/superpowers/specs/2026-09-02-multisource-location-evidence-design.md`

## Global Constraints

- 公司进入半径继续使用用户设置，当前设备值为 250 米；离开只创建候选并使用滞回与移动证据确认。
- GNSS 精度超过 100 米必须输出 `UNKNOWN`。
- 事件顺序固定为 `在家 → 离家 → 到公司 → 工作中 → 离公司 → 到家`。
- 手动记录和手动修正优先，自动学习、重放和推算不得覆盖。
- 用户设置的离岗确认分钟数实时生效。
- 不增加首页后台状态卡，不增加环境指纹重新学习按钮。
- 环境原始标识只在内存短暂存在；数据库和普通日志仅保存加盐哈希。
- 不删除工时、工资、人工修正、待确认项或已有有效定位记录。
- 使用同一签名覆盖安装，不卸载应用，不关闭、停止或修改 FlClash。
- 用户实际使用验收前不推送 GitHub、不合并分支。

## File Structure

新增的核心文件：

- `domain/evidence/EvidenceModels.kt`：统一证据、地点和融合结果类型。
- `domain/evidence/EvidenceFusionEngine.kt`：纯 Kotlin 多源证据优先级与冲突规则。
- `domain/evidence/FingerprintLearningPolicy.kt`：纯 Kotlin 自动学习、晋级、衰减和跨地点降权规则。
- `location/evidence/EnvironmentIdentifierHasher.kt`：本地盐与稳定哈希。
- `location/evidence/AmbientScanPolicy.kt`：扫描触发、冷却和省电策略。
- `location/evidence/MotionEvidenceController.kt`：显著运动与加速度降级适配。
- `location/evidence/WifiEvidenceCollector.kt`：Wi-Fi 快照采集。
- `location/evidence/BluetoothEvidenceCollector.kt`：蓝牙短时采集。
- `location/evidence/CellEvidenceCollector.kt`：服务小区与相邻小区快照采集。
- `location/evidence/EvidenceCoordinator.kt`：采集、学习、持久化、融合的单一编排入口。
- `data/entity/EnvironmentFingerprintEntity.kt`、`EvidenceObservationEntity.kt`、`LocationHealthEntity.kt`：Room 8 新表。
- `data/dao/EnvironmentEvidenceDao.kt`：指纹、观察和健康状态的数据访问与限量清理。

现有文件保持职责：

- `ForegroundLocationService.kt`：生命周期、Android 监听注册和将融合结果送入状态机。
- `TrajectoryAnchorEngine.kt`：事件顺序和候选确认。
- `ServiceHealthSnapshot.kt`、`ServiceRecovery.kt`、`LocationHealthWorker.kt`：健康评估与合法恢复。
- `PermissionManager.kt`、`PermissionSettingsRouter.kt`、`SettingsScreen.kt`：权限状态和“一键修复下一项”。

---

### Task 1: 统一证据模型与融合引擎

**Files:**
- Create: `app/src/main/java/com/example/worktimetracker/domain/evidence/EvidenceModels.kt`
- Create: `app/src/main/java/com/example/worktimetracker/domain/evidence/EvidenceFusionEngine.kt`
- Test: `app/src/test/java/com/example/worktimetracker/EvidenceFusionEngineTest.kt`

**Interfaces:**
- Consumes: 各采集器产生的 `EvidenceObservation`。
- Produces: `EvidenceFusionEngine.resolve(observations: List<EvidenceObservation>, now: Long, previous: ResolvedPlace): FusedEvidence`。

- [ ] **Step 1: 写融合优先级失败测试**

```kotlin
class EvidenceFusionEngineTest {
    private val engine = EvidenceFusionEngine()
    private fun e(source: EvidenceSource, place: ResolvedPlace, quality: Double, at: Long = 1_000_000L) =
        EvidenceObservation(at, at, source, quality, place, null, null)

    @Test fun strongGnssWinsOverAuxiliaryConflict() {
        val result = engine.resolve(listOf(
            e(EvidenceSource.GNSS, ResolvedPlace.COMPANY, 0.95),
            e(EvidenceSource.CELL, ResolvedPlace.HOME, 0.90),
            e(EvidenceSource.WIFI, ResolvedPlace.HOME, 0.90)
        ), 1_000_000L, ResolvedPlace.UNKNOWN)
        assertEquals(ResolvedPlace.COMPANY, result.place)
        assertEquals(1_000_000L, result.firstReliableAt)
    }

    @Test fun oneAuxiliarySourceCannotConfirmPlace() {
        val result = engine.resolve(
            listOf(e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.95)),
            1_000_000L, ResolvedPlace.HOME
        )
        assertEquals(ResolvedPlace.UNKNOWN, result.place)
    }

    @Test fun twoStableAuxiliarySourcesCanConfirmPlace() {
        val result = engine.resolve(listOf(
            e(EvidenceSource.WIFI, ResolvedPlace.COMPANY, 0.80),
            e(EvidenceSource.CELL, ResolvedPlace.COMPANY, 0.75)
        ), 1_000_000L, ResolvedPlace.UNKNOWN)
        assertEquals(ResolvedPlace.COMPANY, result.place)
        assertTrue(result.confidence >= 0.70)
    }

    @Test fun staleEvidenceIsIgnoredAndConflictIsUnknown() {
        val result = engine.resolve(listOf(
            e(EvidenceSource.WIFI, ResolvedPlace.HOME, 0.90, 100_000L),
            e(EvidenceSource.CELL, ResolvedPlace.COMPANY, 0.90)
        ), 1_000_000L, ResolvedPlace.COMPANY)
        assertEquals(ResolvedPlace.UNKNOWN, result.place)
    }
}
```

- [ ] **Step 2: 运行聚焦测试并确认失败**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.EvidenceFusionEngineTest"`

Expected: FAIL，因为 `EvidenceSource`、`EvidenceObservation` 和 `EvidenceFusionEngine` 尚不存在。

- [ ] **Step 3: 实现最小证据类型与融合规则**

```kotlin
enum class EvidenceSource { GNSS, CELL, WIFI, BLUETOOTH, MOTION, SHIFT_WINDOW }
enum class ResolvedPlace { HOME, COMPANY, OTHER, MOVING, UNKNOWN }

data class EvidenceObservation(
    val eventTime: Long,
    val receivedAt: Long,
    val source: EvidenceSource,
    val quality: Double,
    val placeHint: ResolvedPlace,
    val identifierHash: String?,
    val signal: Int?
)

data class FusedEvidence(
    val place: ResolvedPlace,
    val confidence: Double,
    val firstReliableAt: Long?,
    val sources: Set<EvidenceSource>
)
```

`EvidenceFusionEngine` 必须先过滤未来时间、GNSS 超过 2 分钟、环境证据超过 10 分钟的观察；质量至少 0.80 的 GNSS 直接优先。无 GNSS 时，相同地点至少两类 `CELL/WIFI/BLUETOOTH` 且质量和不低于 1.40 才返回地点；只有一类环境来源时，即使伴随时间窗口也保持 `UNKNOWN`，除非同时存在 `MOTION` 且总质量不低于 1.80。家和公司得分差小于 0.15 时返回 `UNKNOWN`。

- [ ] **Step 4: 运行融合测试并确认通过**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.EvidenceFusionEngineTest"`

Expected: PASS。

- [ ] **Step 5: 提交融合引擎**

```powershell
git add app/src/main/java/com/example/worktimetracker/domain/evidence app/src/test/java/com/example/worktimetracker/EvidenceFusionEngineTest.kt
git commit -m "新增多源定位证据融合引擎"
```

---

### Task 2: 自动环境指纹学习、哈希与衰减

**Files:**
- Create: `app/src/main/java/com/example/worktimetracker/domain/evidence/FingerprintLearningPolicy.kt`
- Create: `app/src/main/java/com/example/worktimetracker/location/evidence/EnvironmentIdentifierHasher.kt`
- Test: `app/src/test/java/com/example/worktimetracker/FingerprintLearningPolicyTest.kt`
- Test: `app/src/test/java/com/example/worktimetracker/EnvironmentIdentifierHasherTest.kt`

**Interfaces:**
- Consumes: `LearningSample`，其地点已由精度不超过 50 米且核心区域稳定 5 分钟的 GNSS 确认。
- Produces: `FingerprintLearningPolicy.update(current: FingerprintState?, sample: LearningSample): FingerprintState` 和 `decay(current: FingerprintState, now: Long): FingerprintState`。

- [ ] **Step 1: 写学习门槛和隐私失败测试**

```kotlin
class FingerprintLearningPolicyTest {
    private val policy = FingerprintLearningPolicy()

    @Test fun requiresReliableCoreGnssAndFiveMinutes() {
        val rejected = policy.accepts(LearningGate(51f, 10 * 60_000L, true, false, false, false))
        val accepted = policy.accepts(LearningGate(20f, 5 * 60_000L, true, false, false, false))
        assertFalse(rejected)
        assertTrue(accepted)
    }

    @Test fun promotesOnlyAfterSixObservationsAcrossThreeDays() {
        var state: FingerprintState? = null
        listOf("2026-09-01", "2026-09-01", "2026-09-02", "2026-09-02", "2026-09-03", "2026-09-03")
            .forEachIndexed { index, day -> state = policy.update(state, LearningSample(day, 1_000L + index, -60)) }
        assertEquals(FingerprintLevel.STABLE, state!!.level)
        assertEquals(3, state!!.distinctDayCount)
    }

    @Test fun thirtyDaysMissingStartsDecayAndCrossPlaceDisablesFeature() {
        val stable = FingerprintState(6, 3, "2026-09-03", 1_000L, -70, -50, FingerprintLevel.STABLE, true)
        val decayed = policy.decay(stable, 1_000L + 31L * 24 * 60 * 60_000)
        assertEquals(FingerprintLevel.DECAYING, decayed.level)
        assertFalse(policy.markCrossPlace(stable).discriminative)
    }
}

class EnvironmentIdentifierHasherTest {
    @Test fun hashIsStableAndDoesNotContainRawIdentifier() {
        val salt = ByteArray(32) { it.toByte() }
        val first = EnvironmentIdentifierHasher.hash(salt, listOf("wifi", "WorkGuest", "aa:bb:cc:dd:ee:ff"))
        val second = EnvironmentIdentifierHasher.hash(salt, listOf("wifi", "WorkGuest", "aa:bb:cc:dd:ee:ff"))
        assertEquals(first, second)
        assertFalse(first.contains("WorkGuest"))
        assertEquals(64, first.length)
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.FingerprintLearningPolicyTest" --tests "com.example.worktimetracker.EnvironmentIdentifierHasherTest"`

Expected: FAIL，因为学习策略和哈希类尚不存在。

- [ ] **Step 3: 实现学习策略**

实现以下精确类型：

```kotlin
enum class FingerprintLevel { NEW, CANDIDATE, STABLE, DECAYING, DISABLED }
data class LearningGate(val accuracyMeters: Float, val stableMillis: Long, val inCore: Boolean,
    val inferred: Boolean, val manualReplay: Boolean, val anomalousShift: Boolean)
data class LearningSample(val localDay: String, val observedAt: Long, val signal: Int)
data class FingerprintState(val observationCount: Int, val distinctDayCount: Int,
    val lastObservedDay: String, val lastObservedAt: Long, val minSignal: Int, val maxSignal: Int,
    val level: FingerprintLevel, val discriminative: Boolean)
```

`accepts` 必须要求 `accuracyMeters <= 50f`、`stableMillis >= 300_000L`、`inCore`，并拒绝 `inferred/manualReplay/anomalousShift`。`update` 只在日期晚于 `lastObservedDay` 时增加不同日期数；观察数至少 6 且日期数至少 3 才为 `STABLE`。`decay` 在 30 天后切换为 `DECAYING`，90 天后切换为 `DISABLED`。

- [ ] **Step 4: 实现应用本地盐和 SHA-256**

`EnvironmentIdentifierHasher.hash` 使用 UTF-8、NUL 分隔字段与 SHA-256。新增 `EnvironmentSaltStore(context).getOrCreate()`，在 `SharedPreferences("environment_fingerprint_secret")` 中以 Base64 保存 32 字节 `SecureRandom` 盐；普通日志不得输出盐或原始字段。

- [ ] **Step 5: 运行测试并确认通过**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.FingerprintLearningPolicyTest" --tests "com.example.worktimetracker.EnvironmentIdentifierHasherTest"`

Expected: PASS。

- [ ] **Step 6: 提交学习策略**

```powershell
git add app/src/main/java/com/example/worktimetracker/domain/evidence/FingerprintLearningPolicy.kt app/src/main/java/com/example/worktimetracker/location/evidence/EnvironmentIdentifierHasher.kt app/src/test/java/com/example/worktimetracker/FingerprintLearningPolicyTest.kt app/src/test/java/com/example/worktimetracker/EnvironmentIdentifierHasherTest.kt
git commit -m "新增自动环境指纹学习与隐私哈希"
```

---

### Task 3: Room 7→8 环境证据迁移

**Files:**
- Create: `app/src/main/java/com/example/worktimetracker/data/entity/EnvironmentFingerprintEntity.kt`
- Create: `app/src/main/java/com/example/worktimetracker/data/entity/EvidenceObservationEntity.kt`
- Create: `app/src/main/java/com/example/worktimetracker/data/entity/LocationHealthEntity.kt`
- Create: `app/src/main/java/com/example/worktimetracker/data/dao/EnvironmentEvidenceDao.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/data/database/AppDatabase.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/WorkTimeApplication.kt`
- Create: `app/src/androidTest/java/com/example/worktimetracker/Migration7To8Test.kt`

**Interfaces:**
- Consumes: Task 1/2 的来源、地点和指纹等级字符串。
- Produces: `environmentEvidenceDao()`，支持 upsert、最近观察、健康状态和限量清理。

- [ ] **Step 1: 写迁移失败测试**

测试使用 `MigrationTestHelper` 创建版本 7 数据库，写入一条 `work_records`、一条 `monthly_salaries`、一条 `manual_overrides` 和 `work_state`，运行 `MIGRATION_7_8` 后断言原表计数和值不变，并断言三个新表可写入、索引存在。

```kotlin
helper.runMigrationsAndValidate(TEST_DB, 8, true, WorkTimeApplication.MIGRATION_7_8).use { db ->
    db.query("SELECT COUNT(*) FROM work_records").use { cursor ->
        cursor.moveToFirst()
        assertEquals(1, cursor.getInt(0))
    }
    db.query("SELECT netSalaryCents FROM monthly_salaries").use { cursor ->
        cursor.moveToFirst()
        assertEquals(123456L, cursor.getLong(0))
    }
    db.execSQL("INSERT INTO location_health(name,lastCallbackAt,lastSuccessAt,registered,recoveryCount,lastFailure) VALUES('gnss',1,1,1,0,NULL)")
    db.query("SELECT COUNT(*) FROM location_health").use { cursor ->
        cursor.moveToFirst()
        assertEquals(1, cursor.getInt(0))
    }
}

private companion object { const val TEST_DB = "migration-7-8" }
```

- [ ] **Step 2: 运行迁移测试并确认失败**

Run: `./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.worktimetracker.Migration7To8Test`

Expected: FAIL，因为版本 8 schema 与 `MIGRATION_7_8` 尚不存在。

- [ ] **Step 3: 创建实体和 DAO**

使用以下主键与字段：

```kotlin
@Entity(tableName = "environment_fingerprints", primaryKeys = ["place", "source", "identifierHash"],
    indices = [Index("lastObservedAt"), Index(value = ["place", "source", "level"])])
data class EnvironmentFingerprintEntity(val place: String, val source: String, val identifierHash: String,
    val observationCount: Int, val distinctDayCount: Int, val lastObservedDay: String,
    val lastObservedAt: Long, val minSignal: Int, val maxSignal: Int,
    val level: String, val discriminative: Boolean)

@Entity(tableName = "evidence_observations", indices = [Index("eventTime"), Index(value = ["source", "placeHint"])])
data class EvidenceObservationEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventTime: Long, val receivedAt: Long, val source: String, val quality: Double,
    val placeHint: String, val identifierHash: String?, val signal: Int?, val usedForEvent: Boolean)

@Entity(tableName = "location_health")
data class LocationHealthEntity(@PrimaryKey val name: String, val lastCallbackAt: Long,
    val lastSuccessAt: Long, val registered: Boolean, val recoveryCount: Int, val lastFailure: String?)
```

DAO 必须包含 `upsertFingerprint`、`fingerprints(place, source)`、`insertObservation`、`recentObservations(since)`、`upsertHealth`、`allHealth`、`deleteObservationsBefore(cutoff)` 和 `trimObservations(keep)`。

- [ ] **Step 4: 添加精确 SQL 迁移**

将数据库版本改为 8，注册三个实体与 DAO，并创建三个表及索引。`MIGRATION_7_8` 只执行 `CREATE TABLE`/`CREATE INDEX`，不得更新或删除现有表。将迁移加入 `.addMigrations(...)`。

- [ ] **Step 5: 导出 schema 并运行迁移测试**

Run: `./gradlew.bat kspDebugKotlin connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.worktimetracker.Migration7To8Test`

Expected: PASS，并生成 `app/schemas/com.example.worktimetracker.data.database.AppDatabase/8.json`。

- [ ] **Step 6: 提交数据库迁移**

```powershell
git add app/src/main/java/com/example/worktimetracker/data app/src/main/java/com/example/worktimetracker/WorkTimeApplication.kt app/src/androidTest/java/com/example/worktimetracker/Migration7To8Test.kt app/schemas
git commit -m "新增环境证据数据库与安全迁移"
```

---

### Task 4: 扫描与运动触发策略

**Files:**
- Create: `app/src/main/java/com/example/worktimetracker/location/evidence/AmbientScanPolicy.kt`
- Create: `app/src/main/java/com/example/worktimetracker/location/evidence/MotionEvidenceController.kt`
- Test: `app/src/test/java/com/example/worktimetracker/AmbientScanPolicyTest.kt`

**Interfaces:**
- Consumes: 服务状态、GNSS 回调时间、运动触发和班次窗口状态。
- Produces: `AmbientScanPolicy.evaluate(input: ScanPolicyInput): ScanDecision` 和 `MotionEvidenceController.start()/stop()`。

- [ ] **Step 1: 写扫描冷却失败测试**

```kotlin
class AmbientScanPolicyTest {
    private val policy = AmbientScanPolicy()

    @Test fun motionRequestsShortBurstButDuplicateDoesNotRescan() {
        val first = policy.evaluate(ScanPolicyInput(1_000_000L, 0L, true, false, false, false))
        val duplicate = policy.evaluate(ScanPolicyInput(1_010_000L, 1_000_000L, true, false, false, false))
        assertEquals(ScanDecision.BURST, first)
        assertEquals(ScanDecision.NONE, duplicate)
    }

    @Test fun stableKnownPlaceDoesNotScanAndStaleGnssDoes() {
        assertEquals(ScanDecision.NONE,
            policy.evaluate(ScanPolicyInput(2_000_000L, 0L, false, false, false, true)))
        assertEquals(ScanDecision.BURST,
            policy.evaluate(ScanPolicyInput(2_000_000L, 0L, false, true, false, false)))
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.AmbientScanPolicyTest"`

Expected: FAIL，因为策略尚不存在。

- [ ] **Step 3: 实现扫描策略**

```kotlin
enum class ScanDecision { NONE, SNAPSHOT, BURST }
data class ScanPolicyInput(val now: Long, val lastScanAt: Long, val significantMotion: Boolean,
    val gnssStale: Boolean, val nearShiftWindow: Boolean, val stableKnownPlace: Boolean)
```

规则：扫描冷却至少 5 分钟；显著运动或 GNSS 超过 20 分钟无回调返回 `BURST`；班次窗口返回 `SNAPSHOT`；稳定已知地点且无运动返回 `NONE`。

- [ ] **Step 4: 实现运动控制器**

`MotionEvidenceController` 优先注册 `Sensor.TYPE_SIGNIFICANT_MOTION`；触发后调用 `onSignificantMotion(eventTime)` 并立即重新注册。设备缺失该传感器时，以 `SENSOR_DELAY_NORMAL` 注册加速度计，只有连续样本的线性加速度幅值越过阈值才回调；`stop()` 必须移除所有监听。不得持久化原始样本。

- [ ] **Step 5: 运行测试并提交**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.AmbientScanPolicyTest"`

Expected: PASS。

```powershell
git add app/src/main/java/com/example/worktimetracker/location/evidence/AmbientScanPolicy.kt app/src/main/java/com/example/worktimetracker/location/evidence/MotionEvidenceController.kt app/src/test/java/com/example/worktimetracker/AmbientScanPolicyTest.kt
git commit -m "新增省电环境扫描与运动唤醒策略"
```

---

### Task 5: Wi-Fi、蓝牙与基站采集适配器

**Files:**
- Create: `app/src/main/java/com/example/worktimetracker/location/evidence/WifiEvidenceCollector.kt`
- Create: `app/src/main/java/com/example/worktimetracker/location/evidence/BluetoothEvidenceCollector.kt`
- Create: `app/src/main/java/com/example/worktimetracker/location/evidence/CellEvidenceCollector.kt`
- Create: `app/src/main/java/com/example/worktimetracker/location/evidence/CollectorSnapshot.kt`
- Test: `app/src/test/java/com/example/worktimetracker/CollectorSnapshotTest.kt`

**Interfaces:**
- Consumes: Android 扫描结果与 Task 2 的本地盐哈希器。
- Produces: `suspend fun snapshot(now: Long): CollectorResult`；失败返回空特征列表和结构化失败原因，不抛出到服务主循环。

- [ ] **Step 1: 写脱敏、去重和限量失败测试**

```kotlin
class CollectorSnapshotTest {
    @Test fun mergesDuplicateIdentifiersAndKeepsStrongestSignal() {
        val merged = CollectorSnapshot.merge(listOf(
            CollectorFeature(EvidenceSource.WIFI, "hash-a", -80),
            CollectorFeature(EvidenceSource.WIFI, "hash-a", -55),
            CollectorFeature(EvidenceSource.WIFI, "hash-b", -70)
        ), limit = 20)
        assertEquals(2, merged.size)
        assertEquals(-55, merged.first { it.identifierHash == "hash-a" }.signal)
    }

    @Test fun snapshotNeverContainsRawName() {
        val feature = CollectorFeature(EvidenceSource.BLUETOOTH, "f".repeat(64), -60)
        assertEquals(64, feature.identifierHash.length)
        assertFalse(feature.identifierHash.contains("Headset"))
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.CollectorSnapshotTest"`

Expected: FAIL，因为采集快照类型尚不存在。

- [ ] **Step 3: 实现统一采集结果**

```kotlin
data class CollectorFeature(val source: EvidenceSource, val identifierHash: String, val signal: Int)
data class CollectorResult(val features: List<CollectorFeature>, val collectedAt: Long,
    val failure: CollectorFailure? = null)
enum class CollectorFailure { PERMISSION, DISABLED, THROTTLED, EMPTY, SECURITY, SYSTEM }
```

`CollectorSnapshot.merge` 按 `source + identifierHash` 去重，保留最强信号，每类最多 20 个特征。

- [ ] **Step 4: 实现 Wi-Fi 采集器**

使用 `WifiManager.scanResults` 读取系统已有快照；只有 `AmbientScanPolicy` 返回扫描决定时才尝试 `startScan()`。哈希字段使用 `SSID + BSSID`，不将原值交给日志。权限或系统定位不满足时返回 `PERMISSION`/`DISABLED`；`startScan()` 返回 false 时标记 `THROTTLED`，仍可使用足够新鲜的已有结果。

- [ ] **Step 5: 实现蓝牙采集器**

使用 `BluetoothLeScanner` 开启 15 秒低功耗扫描窗口；哈希字段使用广播名称、服务 UUID、厂商数据键和设备地址的组合。只返回加盐哈希与 RSSI。扫描停止、服务销毁和权限撤销都必须调用 `stopScan`。不连接、不配对设备。

- [ ] **Step 6: 实现基站采集器**

使用 `TelephonyManager.allCellInfo`，分别规范化 LTE/NR/WCDMA/GSM 标识；哈希 MCC、MNC、区域码、小区标识，信号使用对应 `CellSignalStrength` 的 dBm。服务小区与相邻小区共同形成快照；任何 `SecurityException` 转换为 `CollectorFailure.PERMISSION`。

- [ ] **Step 7: 运行测试并提交**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.CollectorSnapshotTest"`

Expected: PASS。

```powershell
git add app/src/main/java/com/example/worktimetracker/location/evidence app/src/test/java/com/example/worktimetracker/CollectorSnapshotTest.kt
git commit -m "新增脱敏环境无线与基站采集器"
```

---

### Task 6: 证据协调器、自动学习和限量持久化

**Files:**
- Create: `app/src/main/java/com/example/worktimetracker/location/evidence/EvidenceCoordinator.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/data/dao/EnvironmentEvidenceDao.kt`
- Test: `app/src/test/java/com/example/worktimetracker/EvidenceCoordinatorTest.kt`

**Interfaces:**
- Consumes: GNSS 观察、环境快照、运动事件、Room 指纹。
- Produces: `suspend fun onGnss(input: GnssInput): FusedEvidence`、`suspend fun onMotion(at: Long)`、`suspend fun collectAmbient(now: Long): FusedEvidence`。

- [ ] **Step 1: 写协调器失败测试**

使用内存 fake DAO 与 fake collectors，验证：可靠 GNSS 核心区域稳定 5 分钟后才调用 `upsertFingerprint`；同一分钟重复观察只保存一条；环境明细 30 天前被清理；每次最多保存 60 个环境特征；采集失败只更新健康状态，不改变已知地点。

```kotlin
@Test fun failedAmbientCollectionDoesNotEmitDeparture() = runTest {
    val result = coordinator.collectAmbient(2_000_000L)
    assertEquals(ResolvedPlace.UNKNOWN, result.place)
    assertEquals(0, fakeDao.usedForEventCount)
    assertEquals("wifi", fakeDao.lastHealth!!.name)
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.EvidenceCoordinatorTest"`

Expected: FAIL，因为协调器尚不存在。

- [ ] **Step 3: 实现协调器**

协调器构造函数注入 `EnvironmentEvidenceDao`、三个 collector、`FingerprintLearningPolicy`、`EvidenceFusionEngine`、`Clock`。GNSS 输入必须包含经纬度分类结果、精度、核心区域标记、稳定开始时间、是否推算/人工回放/异常班次。只有 `LearningGate` 通过后才将当前环境快照写入指纹。

环境匹配采用稳定且 `discriminative=true` 的指纹，按交集比例和信号范围计算 0.0～1.0 质量。每轮输出融合结果后，将 `usedForEvent` 更新为真实值。

- [ ] **Step 4: 增加限量清理**

每次服务启动和每天首次写入时：

- 删除 `eventTime < now - 30 days` 的观察；
- 每个来源只保留最近 10,000 条观察；
- 30 天未见指纹切换为 `DECAYING`；
- 90 天未见指纹切换为 `DISABLED`；
- 不删除工时、工资、定位日志和人工修正。

- [ ] **Step 5: 运行测试并提交**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.EvidenceCoordinatorTest"`

Expected: PASS。

```powershell
git add app/src/main/java/com/example/worktimetracker/location/evidence/EvidenceCoordinator.kt app/src/main/java/com/example/worktimetracker/data/dao/EnvironmentEvidenceDao.kt app/src/test/java/com/example/worktimetracker/EvidenceCoordinatorTest.kt
git commit -m "集成环境证据学习融合与限量保存"
```

---

### Task 7: 状态机连续性、离岗与到家顺序修复

**Files:**
- Modify: `app/src/main/java/com/example/worktimetracker/location/service/TrajectoryAnchorEngine.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/service/LocationEventProcessor.kt`
- Create: `app/src/main/java/com/example/worktimetracker/location/service/EvidenceContinuityPolicy.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/service/ConfirmedSession.kt`
- Test: `app/src/test/java/com/example/worktimetracker/TrajectoryAnchorEngineTest.kt`
- Create: `app/src/test/java/com/example/worktimetracker/EvidenceContinuityPolicyTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `FusedEvidence` 转换成状态机 `Fix`。
- Produces: 只含顺序合法事件的 `TrajectoryAnchorEngine.Decision`。

- [ ] **Step 1: 添加已知回归失败测试**

测试必须覆盖：

```kotlin
@Test fun homeWhileRestDoesNotStartCommute() {
    val decision = engine.next(
        WorkStateEntity(currentState = "REST"),
        fix(1_000L, LocationType.HOME, homeAnchor = 20.0), config
    )
    assertEquals("REST", decision.nextState.currentState)
    assertTrue(decision.events.isEmpty())
}

@Test fun nearCompanyCandidateExpiresAcrossFifteenHourGap() {
    val state = WorkStateEntity(currentState = "NEAR_COMPANY", sessionId = "s",
        candidateCompanyArrivalTime = 100L, stableCompanyCount = 1, lastLocationTime = 100L)
    val decision = engine.next(state,
        fix(54_000_100L, LocationType.COMPANY, company = 70.0, companyAnchor = 60.0), config)
    assertTrue(decision.events.filterIsInstance<TrajectoryAnchorEngine.Event.CompanyArrival>().isEmpty())
    assertEquals(54_000_100L, decision.nextState.candidateCompanyArrivalTime)
    assertEquals(1, decision.nextState.stableCompanyCount)
}

@Test fun lateHomeAfterFinishedCompletesExistingSession() {
    val state = WorkStateEntity(currentState = "FINISHED", sessionId = "s", sessionStart = 1_000L,
        confirmedDepartureTime = 2_000L, homeArrivalTime = null)
    val decision = engine.next(state,
        fix(3_000L, LocationType.HOME, company = 2_000.0, companyAnchor = 1_900.0, homeAnchor = 20.0), config)
    assertEquals("REST", decision.nextState.currentState)
    assertEquals(3_000L, decision.nextState.homeArrivalTime)
    assertEquals(3_000L,
        decision.events.filterIsInstance<TrajectoryAnchorEngine.Event.HomeArrival>().single().occurredAt)
}

@Test fun fiveMinuteUserSettingIsUsedExactly() {
    val fiveMinutes = config.copy(leaveConfirmMinutes = 5)
    val state = WorkStateEntity(currentState = "TEMP_LEAVE", sessionId = "s", sessionStart = 100L,
        candidateCompanyDepartureTime = 1_000L, movingAwayCount = 2)
    val before = engine.next(state,
        fix(300_999L, LocationType.OTHER, company = 500.0, companyAnchor = 500.0, moving = true), fiveMinutes)
    val due = engine.next(before.nextState,
        fix(301_000L, LocationType.OTHER, company = 500.0, companyAnchor = 500.0, moving = true), fiveMinutes)
    assertEquals("TEMP_LEAVE", before.nextState.currentState)
    assertEquals(1_000L,
        due.events.filterIsInstance<TrajectoryAnchorEngine.Event.CompanyDeparture>().single().occurredAt)
}

@Test fun homeBeforeCompanyDepartureIsRejected() {
    val state = WorkStateEntity(currentState = "TEMP_LEAVE", sessionId = "s", sessionStart = 100L,
        candidateCompanyDepartureTime = 2_000L, candidateHomeArrivalTime = 1_000L, movingAwayCount = 2)
    val decision = engine.next(state,
        fix(1_300_000L, LocationType.HOME, company = 2_000.0, companyAnchor = 1_900.0,
            homeAnchor = 20.0, moving = true), config)
    assertTrue(decision.events.filterIsInstance<TrajectoryAnchorEngine.Event.HomeArrival>().isEmpty())
}

@Test fun environmentDisappearanceAloneDoesNotLeaveCompany() {
    val state = WorkStateEntity(currentState = "WORKING", sessionId = "s", sessionStart = 100L)
    val decision = engine.next(state, fix(1_000L, LocationType.UNKNOWN), config)
    assertEquals("WORKING", decision.nextState.currentState)
    assertTrue(decision.events.isEmpty())
}
```

`EvidenceContinuityPolicy` 的聚焦测试断言相邻可靠证据间隔超过 20 分钟时，稳定计数和候选到达连续性失效，但已确认 `WORKING` 会话本身不被丢弃。

- [ ] **Step 2: 运行状态机测试并确认失败**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.TrajectoryAnchorEngineTest" --tests "com.example.worktimetracker.EvidenceContinuityPolicyTest"`

Expected: 至少新增的长间隔、晚到家和环境消失测试 FAIL。

- [ ] **Step 3: 实现连续性与顺序保护**

`EvidenceContinuityPolicy.isContinuous(previousTime, currentTime)` 使用 20 分钟上限。候选到达跨越上限时清除 `candidateCompanyArrivalTime/stableCompanyCount`；候选离岗跨越回调空窗时保留首次候选时间，但不得因时间流逝自动确认，仍需移动、持续远离或到家证据。

从 `FINISHED` 收到可靠 `HOME` 时只补齐同一 `sessionId` 的到家证据并转 `REST`，不得创建新会话。`REST + HOME` 永远不设置 `movingAway`，服务侧删除 `type == HOME` 作为离开公司的通用移动证据。

- [ ] **Step 4: 保留人工字段保护**

晚到家合并通过 `ProtectedRecordMerge.merge` 写入；若 `HOME_ARRIVAL` 已在 `manualFieldsMask` 中，保持人工值。不得改变人工到岗、离岗、班次和工时。

- [ ] **Step 5: 运行测试并提交**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.TrajectoryAnchorEngineTest" --tests "com.example.worktimetracker.EvidenceContinuityPolicyTest" --tests "com.example.worktimetracker.ProtectedRecordMergeTest"`

Expected: PASS。

```powershell
git add app/src/main/java/com/example/worktimetracker/location/service app/src/test/java/com/example/worktimetracker/TrajectoryAnchorEngineTest.kt app/src/test/java/com/example/worktimetracker/EvidenceContinuityPolicyTest.kt
git commit -m "修复多源证据下的工时事件顺序与连续性"
```

---

### Task 8: 将多源证据接入前台服务并避免定位风暴

**Files:**
- Modify: `app/src/main/java/com/example/worktimetracker/location/service/ForegroundLocationService.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/service/LocationRegistrationPolicy.kt`
- Create: `app/src/main/java/com/example/worktimetracker/location/service/SourceRegistrationState.kt`
- Test: `app/src/test/java/com/example/worktimetracker/LocationRegistrationPolicyTest.kt`
- Create: `app/src/test/java/com/example/worktimetracker/SourceRegistrationStateTest.kt`

**Interfaces:**
- Consumes: `EvidenceCoordinator` 与 Android 生命周期。
- Produces: 每类来源至多一个活动监听；融合结果按事件时间串行进入状态机。

- [ ] **Step 1: 写重复注册与恢复首点失败测试**

```kotlin
@Test fun sameConfigurationDoesNotRegisterAgain() {
    val state = SourceRegistrationState()
    assertTrue(state.begin("gps", 300_000L))
    assertFalse(state.begin("gps", 300_000L))
    assertTrue(state.begin("gps", 600_000L))
}

@Test fun providerRecoveryFirstFixIsBaselineOnly() {
    val state = SourceRegistrationState()
    state.providerRecovered("gps")
    assertFalse(state.mayEmitEvidence("gps"))
    assertTrue(state.mayEmitEvidence("gps"))
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.SourceRegistrationStateTest" --tests "com.example.worktimetracker.LocationRegistrationPolicyTest"`

Expected: FAIL，因为统一来源注册状态尚不存在。

- [ ] **Step 3: 实现来源注册状态**

`SourceRegistrationState` 按来源保存当前配置、注册状态、最后回调和恢复基线标记。配置相同返回 false；配置变化先移除旧监听再注册一次；恢复后的第一次回调返回 false，第二次开始返回 true。

- [ ] **Step 4: 重构服务编排**

在 `onCreate` 中创建 `EvidenceCoordinator` 和 `MotionEvidenceController`；在 `onDestroy` 中停止传感器、Wi-Fi/蓝牙扫描及所有定位监听。`onLocationChanged` 仍先通过 `LocationFixGate`，随后把 GNSS 观察交给 coordinator。所有通过时间去重的定位继续写 `location_logs` 和健康状态；只有 coordinator 返回非 `UNKNOWN` 时才调用 `TrajectoryAnchorEngine`，低精度定位不得改变工时状态。

扫描间隔变化只更新 `pendingSamplingIntervalMillis` 一次；重新配置前统一 `removeUpdates(this)`，禁止多 Provider 累积注册。watchdog 依据当前采样间隔和 `SourceRegistrationState.lastCallback` 判断，不把静止且设置了 50 米最小距离的无回调直接视为故障。

- [ ] **Step 5: 接入运动与环境扫描**

显著运动调用 `coordinator.onMotion(eventTime)`，再按 `AmbientScanPolicy` 请求一次短扫描；GNSS 20 分钟无回调时请求辅助快照。辅助结果进入同一个 `Channel.CONFLATED` 串行处理，不直接并发写 Room。

- [ ] **Step 6: 运行服务策略测试并提交**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.SourceRegistrationStateTest" --tests "com.example.worktimetracker.LocationRegistrationPolicyTest" --tests "com.example.worktimetracker.LocationProcessingGateTest"`

Expected: PASS。

```powershell
git add app/src/main/java/com/example/worktimetracker/location/service app/src/test/java/com/example/worktimetracker/SourceRegistrationStateTest.kt app/src/test/java/com/example/worktimetracker/LocationRegistrationPolicyTest.kt
git commit -m "接入多源证据并消除后台重复定位注册"
```

---

### Task 9: 权限页与清单权限

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/example/worktimetracker/location/permission/PermissionManager.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/permission/PermissionSettingsRouter.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/ui/screens/SettingsScreen.kt`
- Modify: `app/src/test/java/com/example/worktimetracker/PermissionRepairPriorityTest.kt`

**Interfaces:**
- Consumes: Android 版本和运行时授权状态。
- Produces: `PermissionStatus.nearbyDevices`、`activityRecognition`，并让“修复下一项”按顺序处理。

- [ ] **Step 1: 写权限优先级失败测试**

扩展测试，顺序固定为：精确位置 → 后台位置 → 附近设备 → 活动识别 → 通知 → 电池不受限制 → Vivo 自启动。Android 12 以下附近设备视为已满足，Android 10 以下活动识别视为已满足。

- [ ] **Step 2: 运行测试并确认失败**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.PermissionRepairPriorityTest"`

Expected: FAIL，因为新权限状态和优先级尚不存在。

- [ ] **Step 3: 更新 Manifest**

加入：

```xml
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
```

因为扫描结果用于推断地点，`NEARBY_WIFI_DEVICES` 和 `BLUETOOTH_SCAN` 不声明 `neverForLocation`。

- [ ] **Step 4: 更新权限检测和设置页**

`PermissionStatus` 新增 `nearbyDevices`、`activityRecognition`，`ready` 包含两者。在权限页增加“附近设备”和“活动识别”两行；运行时使用 `RequestMultiplePermissions` 请求 `BLUETOOTH_SCAN/NEARBY_WIFI_DEVICES`，单独请求 `ACTIVITY_RECOGNITION`。不增加环境指纹按钮或首页状态卡。

- [ ] **Step 5: 运行测试并提交**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.PermissionRepairPriorityTest"`

Expected: PASS。

```powershell
git add app/src/main/AndroidManifest.xml app/src/main/java/com/example/worktimetracker/location/permission app/src/main/java/com/example/worktimetracker/ui/screens/SettingsScreen.kt app/src/test/java/com/example/worktimetracker/PermissionRepairPriorityTest.kt
git commit -m "补充多源定位权限与一键修复入口"
```

---

### Task 10: 持久化健康状态与合法后台恢复

**Files:**
- Modify: `app/src/main/java/com/example/worktimetracker/location/recovery/ServiceHealthSnapshot.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/recovery/ServiceRecovery.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/recovery/LocationHealthWorker.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/receiver/BootReceiver.kt`
- Modify: `app/src/test/java/com/example/worktimetracker/ServiceHealthSnapshotTest.kt`
- Modify: `app/src/test/java/com/example/worktimetracker/ServiceRecoveryPolicyTest.kt`

**Interfaces:**
- Consumes: `location_health` 中各来源状态。
- Produces: `HealthAction` 精确指出需要重建的来源；后台健康任务不直接启动位置前台服务。

- [ ] **Step 1: 写分来源健康失败测试**

增加测试：服务心跳新鲜但 GNSS 过期时返回 `REREGISTER_GNSS`；GNSS 新鲜但蓝牙权限缺失返回 `AUXILIARY_DEGRADED` 而非服务失败；服务心跳超过 25 分钟返回 `NOTIFY_TAP_TO_RECOVER`；相同失败在 60 分钟内只通知一次。

- [ ] **Step 2: 运行测试并确认失败**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.ServiceHealthSnapshotTest" --tests "com.example.worktimetracker.ServiceRecoveryPolicyTest"`

Expected: FAIL，因为健康快照尚未区分来源。

- [ ] **Step 3: 扩展健康模型**

`ServiceHealthSnapshot` 新增 `sourceHealth: Map<String, SourceHealth>`；`SourceHealth` 包含最后回调、最后成功、是否注册、恢复次数和失败原因。`HealthAction` 增加 `REREGISTER_GNSS`、`REREGISTER_MOTION`、`AUXILIARY_DEGRADED`。辅助来源失效不触发“系统定位关闭”。

- [ ] **Step 4: 更新恢复入口**

`LocationHealthWorker` 只更新健康状态、写限流日志、发布可点击恢复通知。`BOOT_COMPLETED`、`USER_UNLOCKED`、`MY_PACKAGE_REPLACED` 继续通过 `ServiceRecoveryPolicy.BOOT` 检查精确/粗略/后台位置权限后启动前台定位服务。用户可见启动和通知点击使用 `USER_VISIBLE`。禁止从 `BACKGROUND_HEALTH_CHECK` 调用 `startForegroundService`。

- [ ] **Step 5: 运行测试并提交**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.ServiceHealthSnapshotTest" --tests "com.example.worktimetracker.ServiceRecoveryPolicyTest" --tests "com.example.worktimetracker.AutostartVerificationTest"`

Expected: PASS。

```powershell
git add app/src/main/java/com/example/worktimetracker/location/recovery app/src/main/java/com/example/worktimetracker/location/receiver/BootReceiver.kt app/src/test/java/com/example/worktimetracker/ServiceHealthSnapshotTest.kt app/src/test/java/com/example/worktimetracker/ServiceRecoveryPolicyTest.kt
git commit -m "增强定位链路健康监控与合法后台恢复"
```

---

### Task 11: 完整回归、性能边界与构建

**Files:**
- Modify: `app/src/test/java/com/example/worktimetracker/LocationSamplingPolicyTest.kt`
- Create: `app/src/test/java/com/example/worktimetracker/EvidenceRetentionPolicyTest.kt`
- Modify: `README.md`（仅补充权限、自动学习和隐私行为说明，不覆盖用户要求以外内容）

**Interfaces:**
- Consumes: Tasks 1–10 的全部实现。
- Produces: 可安装 Debug APK 与完整验证证据。

- [ ] **Step 1: 添加性能边界测试**

断言稳定已知地点不持续扫描、同一分钟证据合并、单轮最多 60 个环境特征、观察明细只保留 30 天、每来源最多 10,000 条、采样间隔切换不递归重注册。

- [ ] **Step 2: 运行新增聚焦测试**

Run: `./gradlew.bat testDebugUnitTest --tests "com.example.worktimetracker.*Evidence*" --tests "com.example.worktimetracker.*Location*" --tests "com.example.worktimetracker.*Service*" --tests "com.example.worktimetracker.TrajectoryAnchorEngineTest"`

Expected: PASS。

- [ ] **Step 3: 运行全部单元测试**

Run: `./gradlew.bat testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`，无失败测试。

- [ ] **Step 4: 运行数据库迁移测试**

Run: `./gradlew.bat connectedDebugAndroidTest`

Expected: `Migration5To6Test`、`Migration6To7Test`、`Migration7To8Test` 全部 PASS。

- [ ] **Step 5: 运行静态检查与构建**

Run: `./gradlew.bat lintDebug assembleDebug`

Expected: `BUILD SUCCESSFUL`，APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 6: 检查工作区和提交回归结果**

```powershell
git diff --check
git status --short
git add app/src/test app/src/androidTest README.md app/schemas
git commit -m "补充多源定位完整回归与使用说明"
```

---

### Task 12: 同签名覆盖安装与真机验收

**Files:**
- Verify only: `app/build/outputs/apk/debug/app-debug.apk`
- Device package: `com.example.worktimetracker`

**Interfaces:**
- Consumes: Task 11 的 APK。
- Produces: 保留历史数据的已安装版本、设备诊断基线和用户验收候选。

- [ ] **Step 1: 读取安装前基线**

使用项目现有 ADB，记录包版本、`stopped` 状态、前台服务、权限、数据库 schema 版本，以及工时、工资、人工修正、待确认项、定位日志的计数。只读查询，不停止应用，不触碰 FlClash。

- [ ] **Step 2: 比较证书**

分别导出已安装 APK 和读取构建 APK，比较证书 SHA-256。只有摘要完全一致才继续。

```powershell
$adb = 'C:\Users\Administrator\Documents\Codex\2026-07-22\referenced-chatgpt-conversation-this-is-untrusted\work\android-env\android-sdk\platform-tools\adb.exe'
$apksigner = 'C:\Users\Administrator\Documents\Codex\2026-07-22\referenced-chatgpt-conversation-this-is-untrusted\work\android-env\android-sdk\build-tools\35.0.0\apksigner.bat'
$installedPath = ((& $adb shell pm path com.example.worktimetracker) -replace '^package:', '').Trim()
& $adb pull $installedPath "$env:TEMP\worktime-installed.apk"
& $apksigner verify --print-certs "$env:TEMP\worktime-installed.apk"
& $apksigner verify --print-certs 'C:\Users\Administrator\Documents\Codex\2026-07-29\android-work-hours-repair\work\github-miko\app\build\outputs\apk\debug\app-debug.apk'
```

- [ ] **Step 3: 覆盖安装**

Run: `& 'C:\Users\Administrator\Documents\Codex\2026-07-22\referenced-chatgpt-conversation-this-is-untrusted\work\android-env\android-sdk\platform-tools\adb.exe' install -r -g 'C:\Users\Administrator\Documents\Codex\2026-07-29\android-work-hours-repair\work\github-miko\app\build\outputs\apk\debug\app-debug.apk'`

Expected: `Success`；不得执行卸载。

- [ ] **Step 4: 核对安装后数据**

重新读取 schema 版本和各表计数，断言原有工时、工资、人工修正和待确认项数量与安装前一致；确认三个新表存在且初始为空或仅含安装后的健康状态。

- [ ] **Step 5: 核对权限和监听**

打开权限页，按“修复下一项”依次授予附近设备与活动识别权限。确认前台服务通知存在、GNSS/运动监听已注册、Wi-Fi/蓝牙/基站无权限时只降级而不停止服务。

- [ ] **Step 6: 锁屏与恢复验收**

完成以下只读核验：锁屏至少 2 小时；显著运动后出现一次扫描窗口；稳定地点不持续扫描；模拟 Provider 暂停/恢复时首点仅建立基线；恢复时间不成为到岗或离岗时间；无重复注册和定位风暴。

- [ ] **Step 7: 完整夜班验收**

由用户完成一次真实夜班通勤，核对离家、到公司、离公司、到家四个事件、班次归属、工时和待确认来源。推算事件必须显示待确认，人工修正必须保持。

- [ ] **Step 8: 等待用户验收**

向用户报告测试、证书、安装、数据计数、后台状态和真实事件结果。用户明确验收通过后，才执行 `git push`、创建或更新 PR，并合并到 `main`。
