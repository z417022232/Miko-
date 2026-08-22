# 轨迹锚点与后台防崩溃修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 使用经用户确认的地点校准、可持久化的轨迹锚点状态机和原子记录事务，修复进出时间偏差、8月19日缺失事件以及定位异常导致的前台服务崩溃。

**Architecture:** 将纯轨迹决策、数据库事务协调、服务异常隔离和设置页校准拆成独立单元。Room 6→7迁移增加候选事件、会话标识和人工字段保护；前台服务只负责接收定位并调用事务协调器，单条失败回滚且不结束进程。后台健康状态把服务心跳、定位回调和可靠定位分开，Android 15禁止恢复时只发送一次用户可操作通知。

**Tech Stack:** Kotlin, Android LocationManager, Jetpack Compose Material 3, Room 2.6.1, WorkManager 2.10.0, kotlinx-coroutines, JUnit 4, AndroidX MigrationTestHelper, Gradle 8.9, Android Gradle Plugin 8.7.3, minSdk 29, targetSdk 35.

**Spec:** `docs/superpowers/specs/2026-08-21-trajectory-anchor-crash-recovery-design.md`

## Global Constraints

- 保留现有工时、工资、定位、人工修改、设置、包名、签名和前台服务通知。
- 公司中心只有在用户确认校准结果后才更新；历史聚类不能静默修改设置。
- 用户半径只生成候选和调整采样，不能直接生成最终事件。
- 精度超过100米继续判为`UNKNOWN`。
- 离岗确认分钟数只决定确认，不改变首次可靠事件时间。
- 人工字段永不覆盖，人工记录中的空字段允许自动补齐并标记待确认。
- 状态、记录和状态日志在同一Room事务中提交；失败全部回滚。
- 单条定位异常不得终止前台服务。
- Android 15禁止后台启动定位前台服务时发送一次恢复通知，不绕过系统限制。
- 不在首页新增后台状态卡；只修改设置页。
- 不删除或卸载应用，不操作FlClash，不运行会改变真实记录的连接设备测试。
- 使用现有同一签名执行`pm install -r -g`覆盖安装，用户验收前不推送或合并。

---

## File Structure

- Create `domain/engine/LocationAnchorCalibration.kt`: 中位中心、离群点过滤和稳定半径计算。
- Create `location/service/TrajectoryAnchorEngine.kt`: 纯轨迹状态转换，输出候选/确认事件。
- Create `location/service/LocationProcessingCoordinator.kt`: Room事务内合并状态、记录和日志。
- Create `location/service/SessionReconciler.kt`: 服务启动时幂等修复半完成会话。
- Create `location/service/LocationFailureLimiter.kt`: 单条异常计数、限频和降级决策。
- Create `location/recovery/ServiceHealthSnapshot.kt`: 服务心跳、回调和可靠定位的纯状态判断。
- Create `location/permission/LocationCalibrationStore.kt`: 保存用户确认的稳定半径和校准时间。
- Modify Room entities and `WorkTimeApplication.kt`: 6→7迁移与人工字段位。
- Modify `ForegroundLocationService.kt`: 调用协调器、定时服务心跳、异常隔离和有界日志。
- Modify `SettingsScreen.kt` and `WorkTimeViewModel.kt`: 公司位置校准与后台状态。
- Modify `HistoricalRecordRepair.kt`: 只标记8月19日证据不足，不伪造到家时间。

### Task 1: Room 6→7迁移与人工字段保护

**Files:**
- Modify: `app/src/main/java/com/example/worktimetracker/data/entity/WorkStateEntity.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/data/entity/WorkRecordEntity.kt`
- Create: `app/src/main/java/com/example/worktimetracker/data/entity/ManualField.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/data/database/AppDatabase.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/WorkTimeApplication.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/domain/engine/ManualRecordEditor.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/domain/engine/ReviewRecordEditor.kt`
- Create: `app/src/androidTest/java/com/example/worktimetracker/Migration6To7Test.kt`
- Create: `app/src/test/java/com/example/worktimetracker/ManualFieldMaskTest.kt`

**Interfaces:**
- Produces: `enum class ManualField(val bit: Int)` and `ManualFieldMask.add(mask, field)`, `contains(mask, field)`.
- Produces: `WorkStateEntity.sessionId` and persisted candidate/confirmation fields from the spec.
- Produces: `WorkRecordEntity.manualFieldsMask: Int`.
- Consumes: existing Room schema 6 and migration test asset `app/schemas/.../6.json`.

- [ ] **Step 1: Write failing manual-field tests**

```kotlin
class ManualFieldMaskTest {
    @Test fun onlySelectedFieldsAreProtected() {
        val mask = ManualFieldMask.add(0, ManualField.FINAL_MINUTES)
        assertTrue(ManualFieldMask.contains(mask, ManualField.FINAL_MINUTES))
        assertFalse(ManualFieldMask.contains(mask, ManualField.HOME_ARRIVAL))
    }

    @Test fun legacyManualMaskProtectsPresentFieldsButNotMissingFields() {
        val record = WorkRecordEntity(
            workDate = "2026-08-19", status = "MANUAL", shift = "NIGHT_SHIFT",
            startTime = 100L, endTime = null, finalMinutes = 660, isManual = true
        )
        val mask = ManualFieldMask.fromLegacy(record)
        assertTrue(ManualFieldMask.contains(mask, ManualField.SHIFT))
        assertTrue(ManualFieldMask.contains(mask, ManualField.COMPANY_ARRIVAL))
        assertTrue(ManualFieldMask.contains(mask, ManualField.FINAL_MINUTES))
        assertFalse(ManualFieldMask.contains(mask, ManualField.COMPANY_DEPARTURE))
        assertFalse(ManualFieldMask.contains(mask, ManualField.HOME_ARRIVAL))
    }
}
```

- [ ] **Step 2: Run RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.ManualFieldMaskTest
```

Expected: compilation fails because `ManualField` and `ManualFieldMask` do not exist.

- [ ] **Step 3: Implement the bit mask and entity fields**

Use exact bits:

```kotlin
enum class ManualField(val bit: Int) {
    SHIFT(1 shl 0), COMPANY_ARRIVAL(1 shl 1), COMPANY_DEPARTURE(1 shl 2),
    HOME_DEPARTURE(1 shl 3), HOME_ARRIVAL(1 shl 4), FINAL_MINUTES(1 shl 5), NOTE(1 shl 6)
}
```

`WorkStateEntity` adds nullable timestamps and zero counters named in the spec. `WorkRecordEntity.manualFieldsMask` defaults to `0`.

- [ ] **Step 4: Write the failing migration test**

Create a schema-6 database containing one automatic record, one complete manual record, one manual record with null departure/home arrival, salary rows, location rows, and one `work_state` row with `tempLeaveStart`. Run `MIGRATION_6_7` and assert:

```kotlin
assertEquals(7, db.version)
assertEquals(3, count(db, "work_records"))
assertEquals(2, count(db, "monthly_salaries"))
assertEquals(4, count(db, "location_logs"))
assertNull(value(db, "work_records", "manualFieldsMask", "workDate='2026-08-18'")?.takeIf { it != 0L })
assertFalse(maskFor("2026-08-19").has(ManualField.COMPANY_DEPARTURE))
assertFalse(maskFor("2026-08-19").has(ManualField.HOME_ARRIVAL))
assertEquals(tempLeaveStart, value(db, "work_state", "candidateCompanyDepartureTime", "id=1"))
```

- [ ] **Step 5: Run migration RED**

Run:

```powershell
.\gradlew.bat assembleDebugAndroidTest
```

Expected: compilation fails because schema version 7 and `MIGRATION_6_7` do not exist.

- [ ] **Step 6: Implement `MIGRATION_6_7`**

Add every new nullable column with `ALTER TABLE`, add counters as `INTEGER NOT NULL DEFAULT 0`, set `candidateCompanyDepartureTime=tempLeaveStart`, and compute `manualFieldsMask` using SQL `CASE` expressions so only non-null legacy fields are protected. Do not delete or rewrite salary, location, override, or work-record rows.

- [ ] **Step 7: Make editors set exact protection bits**

`ManualRecordEditor` protects shift, final minutes and note; `ReviewRecordEditor` protects every field actually submitted. Existing masks are preserved with bitwise OR.

- [ ] **Step 8: Run GREEN and commit**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.ManualFieldMaskTest assembleDebugAndroidTest assembleDebug
```

Commit only Task 1 files:

```powershell
git commit -m "增加会话候选字段和人工字段保护"
```

### Task 2: 公司位置稳健校准

**Files:**
- Create: `app/src/main/java/com/example/worktimetracker/domain/engine/LocationAnchorCalibration.kt`
- Create: `app/src/main/java/com/example/worktimetracker/location/permission/LocationCalibrationStore.kt`
- Test: `app/src/test/java/com/example/worktimetracker/LocationAnchorCalibrationTest.kt`

**Interfaces:**
- Produces: `LocationAnchorCalibration.Point(latitude, longitude, accuracyMeters, time)`.
- Produces: `LocationAnchorCalibration.Result(centerLat, centerLng, stableRadiusMeters, acceptedCount, rejectedCount)`.
- Produces: `calculate(points): Result?`; returns null with fewer than 5 accepted points or p90 radius greater than 150m.
- Produces: `LocationCalibrationStore.companyStableRadius()`, `companyCalibratedAt()`, `saveCompany(radius, calibratedAt)`.

- [ ] **Step 1: Write failing calibration tests**

```kotlin
@Test fun medianCalibrationRejectsOneFarOutlier() {
    val result = calibration.calculate(companyCluster(10) + farPoint())!!
    assertTrue(distance(result.centerLat, result.centerLng, expectedLat, expectedLng) < 20.0)
    assertEquals(10, result.acceptedCount)
    assertEquals(1, result.rejectedCount)
    assertTrue(result.stableRadiusMeters in 60..150)
}

@Test fun calibrationRejectsTooFewAccuratePoints() {
    assertNull(calibration.calculate(companyCluster(4)))
}

@Test fun calibrationRejectsScatteredPoints() {
    assertNull(calibration.calculate(scatteredPointsWithP90Over150Meters()))
}
```

- [ ] **Step 2: Run RED**

Run `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.LocationAnchorCalibrationTest`.

Expected: unresolved `LocationAnchorCalibration`.

- [ ] **Step 3: Implement robust calculation**

Filter accuracy over 30m, compute median latitude/longitude, reject points farther than `max(75m, medianDistance * 3)`, recompute center, derive p90 radius, then clamp accepted stable radius to `60..150`. Return null for the failure conditions above.

- [ ] **Step 4: Implement private calibration store**

Use preference file `location_calibration`, keys `company_stable_radius` and `company_calibrated_at`. Do not write coordinates here; confirmed coordinates continue to use `user_settings`.

- [ ] **Step 5: Run GREEN and commit**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.LocationAnchorCalibrationTest assembleDebug
```

Commit: `实现公司位置稳健校准算法`.

### Task 3: 可持久化轨迹锚点状态机

**Files:**
- Create: `app/src/main/java/com/example/worktimetracker/location/service/TrajectoryAnchorEngine.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/service/LocationEventProcessor.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/service/ConfirmedSession.kt`
- Test: `app/src/test/java/com/example/worktimetracker/TrajectoryAnchorEngineTest.kt`
- Test: `app/src/test/java/com/example/worktimetracker/AugustTrajectoryReplayTest.kt`

**Interfaces:**
- Consumes: `WorkStateEntity`, `LocationType`, distance to home/company, speed, accuracy, provider, fix time, user radii, stable radii and leave-confirm minutes.
- Produces: `TrajectoryAnchorEngine.Fix` and `Decision(nextState, confirmedEvent, confidence)`.
- Produces event sealed types `HomeDeparture`, `CompanyArrival`, `CompanyDeparture`, `HomeArrival` with `occurredAt` and `confirmedAt`.
- Preserves `LocationEventProcessor.classify()` as the 100m accuracy gate.

- [ ] **Step 1: Write RED tests for candidate versus final events**

```kotlin
@Test fun enteringCandidateRadiusDoesNotConfirmArrival() {
    val decision = engine.next(leavingHome(), fix(companyDistance=220.0, companyAnchorDistance=180.0))
    assertNull(decision.confirmedEvent)
}

@Test fun twoStableAnchorPointsConfirmFirstAnchorTime() {
    val first = engine.next(leavingHome(), fix(time=100L, companyAnchorDistance=70.0))
    val second = engine.next(first.nextState, fix(time=160L, companyAnchorDistance=60.0))
    assertEquals(100L, (second.confirmedEvent as CompanyArrival).occurredAt)
    assertEquals(160L, second.confirmedEvent.confirmedAt)
}
```

- [ ] **Step 2: Write RED tests for edge hysteresis**

Replay `281m → 211m → 810m` while the stable-anchor distance never returns below 100m. Assert the candidate remains the first outward time. Add a separate test with two stable-anchor points that cancels the candidate.

- [ ] **Step 3: Write RED tests for first-home preservation and session reset**

```kotlin
@Test fun firstHomeTimeSurvivesLaterLeaveConfirmation() {
    val atHome = engine.next(tempLeave(candidateAt=100L), fix(time=200L, type=HOME, homeAnchorDistance=40.0))
    val confirmed = engine.next(atHome.nextState, fix(time=1400L, type=HOME, homeAnchorDistance=20.0))
    assertEquals(200L, (confirmed.confirmedEvent as HomeArrival).occurredAt)
    assertEquals(1400L, confirmed.confirmedEvent.confirmedAt)
}

@Test fun newSessionClearsEveryPreviousSessionField() {
    val next = engine.next(finishedWithStaleFields(), outwardHomeFix(time=500L))
    assertNotEquals(oldSessionId, next.nextState.sessionId)
    assertNull(next.nextState.candidateHomeArrivalTime)
    assertNull(next.nextState.confirmedDepartureTime)
}
```

- [ ] **Step 4: Run RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.TrajectoryAnchorEngineTest --tests com.example.worktimetracker.AugustTrajectoryReplayTest
```

Expected: missing engine/event types.

- [ ] **Step 5: Implement minimal pure engine**

Use the thresholds from the spec: candidate user radius, calibrated stable radius `60..150`, outer departure evidence at user radius + 100m, two stable points to confirm or cancel, and persisted first-event timestamps. `UNKNOWN` never advances counters or events.

- [ ] **Step 6: Add sanitized August replay fixtures**

Include only time offsets, provider, accuracy, home/company distance and type—never coordinates. Assert:

- 8/17 and 8/18 store first home time rather than delayed confirmation time.
- 8/20 edge oscillation keeps the first departure candidate.
- 8/19 stale prior home time cannot enter the new session.
- low-accuracy network arrival does not independently confirm company arrival.
- history-based session cap remains median duration plus 4 hours and never exceeds 18 hours; exceeding it keeps reliable events and marks review.

- [ ] **Step 7: Run GREEN and commit**

Run focused tests plus `LocationEventProcessorTest` and `ConfirmedSessionTest`, then `assembleDebug`.

Commit: `实现轨迹锚点和边缘迟滞状态机`.

### Task 4: 原子定位处理与人工空字段补齐

**Files:**
- Create: `app/src/main/java/com/example/worktimetracker/location/service/LocationProcessingCoordinator.kt`
- Create: `app/src/main/java/com/example/worktimetracker/location/service/ProtectedRecordMerge.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/data/dao/AppLogDao.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/service/ForegroundLocationService.kt`
- Test: `app/src/test/java/com/example/worktimetracker/ProtectedRecordMergeTest.kt`
- Test: `app/src/androidTest/java/com/example/worktimetracker/LocationProcessingTransactionTest.kt`

**Interfaces:**
- Produces: `ProtectedRecordMerge.merge(existing, automatic): WorkRecordEntity`.
- Produces: `LocationProcessingCoordinator.process(fix: AcceptedLocationFix): ProcessResult`.
- Consumes: `TrajectoryAnchorEngine.Decision`, Room `withTransaction`, DAOs and `manualFieldsMask`.

- [ ] **Step 1: Write failing protected-merge tests**

```kotlin
@Test fun automaticMergeFillsUnlockedNullsWithoutChangingManualHours() {
    val existing = manualRecord(end=null, homeArrival=null, minutes=660,
        mask=maskOf(SHIFT, COMPANY_ARRIVAL, FINAL_MINUTES))
    val merged = ProtectedRecordMerge.merge(existing, automaticRecord(end=900L, homeArrival=1000L, minutes=720))
    assertEquals(660, merged.finalMinutes)
    assertEquals(900L, merged.endTime)
    assertEquals(1000L, merged.homeArrivalTime)
    assertTrue(merged.needsReview)
}

@Test fun automaticMergeNeverChangesProtectedTimes() {
    val existing = manualRecord(end=900L, mask=maskOf(COMPANY_DEPARTURE))
    assertEquals(900L, ProtectedRecordMerge.merge(existing, automaticRecord(end=1000L)).endTime)
}
```

- [ ] **Step 2: Run merge RED, implement, and run GREEN**

Run the focused test, implement field-by-field copying based on `manualFieldsMask`, and rerun until green.

- [ ] **Step 3: Write failing transaction rollback test**

Use an in-memory Room database and a test hook that throws after state computation but before record insertion. Assert state, record and state-log counts remain unchanged. Then process without the hook and assert all three commit.

- [ ] **Step 4: Run transaction RED**

Run `gradlew.bat assembleDebugAndroidTest`; expected failure is missing coordinator/test hook.

- [ ] **Step 5: Implement coordinator transaction**

Use `database.withTransaction { ... }`. Insert the location log, read state, run engine, merge the record, save state and write transition log inside the same block. Remove the former sequence in `ForegroundLocationService.processLocation` that saves state before record construction.

- [ ] **Step 6: Run GREEN and commit**

Run focused unit tests, `assembleDebugAndroidTest`, `testDebugUnitTest` and `assembleDebug`.

Commit: `原子保存定位状态和工时记录`.

### Task 5: 单条异常隔离与启动对账

**Files:**
- Create: `app/src/main/java/com/example/worktimetracker/location/service/LocationFailureLimiter.kt`
- Create: `app/src/main/java/com/example/worktimetracker/location/service/SessionReconciler.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/service/ForegroundLocationService.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/data/dao/LocationLogDao.kt`
- Test: `app/src/test/java/com/example/worktimetracker/LocationFailureLimiterTest.kt`
- Test: `app/src/test/java/com/example/worktimetracker/SessionReconcilerTest.kt`
- Test: `app/src/test/java/com/example/worktimetracker/LocationProcessingLoopTest.kt`

**Interfaces:**
- Produces: `LocationFailureLimiter.record(errorKey, now): Action` where action is `LOG`, `NOTIFY_AND_THROTTLE`, or `SUPPRESS`.
- Produces: `SessionReconciler.plan(state, record, evidence): ReconciliationPlan`.
- Produces: `processPendingSafely(fix)` behavior in service; exceptions never escape the processing coroutine.

- [ ] **Step 1: Write failing failure-limiter tests**

Assert first error logs, repeated same-key errors within 15 minutes suppress, fifth consecutive error requests one notification and throttle, and a successful fix resets the consecutive count.

- [ ] **Step 2: Write failing processing-loop regression**

Feed three fixes into a fake coordinator: success, `IllegalArgumentException`, success. Assert the third fix is processed and the loop remains active.

- [ ] **Step 3: Write failing reconciliation tests**

Cover:

- `FINISHED` plus missing record departure and valid same-session candidates produces a fill plan.
- stale home arrival from another session is ignored.
- missing home evidence keeps home arrival null and marks review.
- rerunning an already applied plan produces `None`.
- protected manual values remain unchanged.

- [ ] **Step 4: Run RED**

Run all three focused test classes; expected unresolved types.

- [ ] **Step 5: Implement limiter, safe loop and reconciler**

Wrap each coordinator call, not the entire service, in `try/catch`. On failure, let the Room transaction roll back, invoke limiter, write a sanitized error log outside the failed transaction, and continue. Run reconciliation once during service `onCreate` before accepting live fixes.

- [ ] **Step 6: Run GREEN and commit**

Run focused tests, full unit tests and `assembleDebug`.

Commit: `隔离定位异常并对账半完成会话`.

### Task 6: 服务心跳、定位中断与合法恢复

**Files:**
- Create: `app/src/main/java/com/example/worktimetracker/location/recovery/ServiceHealthSnapshot.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/recovery/ServiceRecovery.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/recovery/LocationHealthWorker.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/recovery/ServiceRecoveryPolicy.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/service/ForegroundLocationService.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/notification/NotificationChannels.kt`
- Test: `app/src/test/java/com/example/worktimetracker/ServiceHealthSnapshotTest.kt`
- Test: `app/src/test/java/com/example/worktimetracker/ServiceRecoveryPolicyTest.kt`

**Interfaces:**
- Produces: `ServiceHealthSnapshot(serviceHeartbeat, lastCallback, lastReliableFix, providerAvailable)`，明确区分服务退出和定位提供器不可用。
- Produces: `ServiceHealthPolicy.evaluate(snapshot, now): HealthAction`.
- Produces separate persistence methods `serviceHeartbeat`, `locationCallback`, `reliableLocation`.

- [ ] **Step 1: Write failing health-state tests**

```kotlin
@Test fun liveServiceWithoutRecentFixIsProviderStaleNotServiceDead() {
    val action = policy.evaluate(snapshot(serviceHeartbeat=now-2*60_000L, lastReliableFix=now-40*60_000L), now)
    assertEquals(HealthAction.REREGISTER_LOCATION, action)
}

@Test fun missingServiceHeartbeatRequiresRecoveryNotificationWhenBackgroundStartBlocked() {
    assertEquals(HealthAction.NOTIFY_TAP_TO_RECOVER,
        policy.evaluate(snapshot(serviceHeartbeat=now-30*60_000L, providerAvailable=true), now))
}
```

- [ ] **Step 2: Run RED and implement pure policy**

Run focused tests, implement the snapshot and exact health actions, rerun green.

- [ ] **Step 3: Split persisted heartbeats**

Use `location_service_health` keys `service_heartbeat`, `last_location_callback`, `last_reliable_location`, `provider_available`, and `last_recovery_notification`. Update service heartbeat every 5 minutes with a handler independent of location callbacks.

- [ ] **Step 4: Implement health worker actions**

- live service + stale callback: record one rate-limited provider warning;
- dead service + legal trigger unavailable: post one notification with `PendingIntent` opening the permission/service settings page;
- never call location FGS from `BACKGROUND_HEALTH_CHECK` on Android 15;
- keep boot/unlock/update/geofence recovery and avoid duplicate registration.

- [ ] **Step 5: Run GREEN and commit**

Run service health tests, recovery tests, full unit tests and build.

Commit: `区分服务心跳和定位中断状态`.

### Task 7: 设置页公司校准与后台状态

**Files:**
- Modify: `app/src/main/java/com/example/worktimetracker/ui/app/WorkTimeViewModel.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/data/dao/LocationLogDao.kt`
- Create: `app/src/main/java/com/example/worktimetracker/ui/CompanyCalibrationUiState.kt`
- Test: `app/src/test/java/com/example/worktimetracker/CompanyCalibrationUiStateTest.kt`

**Interfaces:**
- Produces: `CompanyCalibrationUiState.Idle`, `Collecting(count, required)`, `Ready(result, offsetMeters)`, `Failed(message)`.
- Produces ViewModel methods `startCompanyCalibration()`, `acceptCompanyCalibration()`, `cancelCompanyCalibration()`.
- Consumes calibration engine/store, latest service health snapshot and current settings.

- [ ] **Step 1: Write failing UI-state tests**

Assert 4 accurate samples remain collecting, 10 accurate clustered samples become ready, scattered samples fail without changing settings, cancel clears pending result, and accept is the only transition that emits new coordinates.

- [ ] **Step 2: Run RED and implement state reducer**

Keep collection logic pure and testable. Add `LocationLogDao.observeCalibrationFixes(since: Long, limit: Int): Flow<List<LocationLogEntity>>`; `startCompanyCalibration()` captures `startedAt`, observes only fixes newer than that timestamp with accuracy at most 30m, and stops after 10 accepted samples or 2 minutes. ViewModel persists coordinates only on accept.

- [ ] **Step 3: Implement settings UI**

Add “重新校准公司位置”, progress `已采集 N/10`, result offset, confirm/cancel, and failure copy. Add service heartbeat, recent reliable location and provider status under the existing permission page. Keep Vivo three-state semantics and no home-screen card.

- [ ] **Step 4: Run GREEN and commit**

Run focused tests, full unit tests, lint and assemble.

Commit: `设置页支持公司校准和后台状态检查`.

### Task 8: 8月19日安全历史标记与性能限频

**Files:**
- Modify: `app/src/main/java/com/example/worktimetracker/data/HistoricalRecordRepair.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/service/LocationSamplingPolicy.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/service/ForegroundLocationService.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/data/dao/LocationLogDao.kt`
- Test: `app/src/test/java/com/example/worktimetracker/HistoricalRecordRepairTest.kt`
- Test: `app/src/test/java/com/example/worktimetracker/LocationSamplingPolicyTest.kt`
- Test: `app/src/test/java/com/example/worktimetracker/DiagnosticRateLimitTest.kt`

**Interfaces:**
- Produces: historical repair decision that preserves existing manual fields and returns a review note with candidate evidence.
- Produces: sampling escalation expiration after 15 minutes.
- Produces bounded DAO queries by time range and row limit.

- [ ] **Step 1: Write failing August 19 repair test**

Use sanitized evidence with 08:59 first candidate, 09:14 sustained departure, no fixes until 18:55. Assert:

```kotlin
assertNull(repaired.homeArrivalTime)
assertTrue(repaired.needsReview)
assertTrue(repaired.note!!.contains("08:59"))
assertTrue(repaired.note!!.contains("09:14"))
assertFalse(repaired.note!!.contains("18:55到家"))
```

- [ ] **Step 2: Write failing sampling and log-limit tests**

Assert edge/movement escalation returns 1 minute for at most 15 minutes, then 5 minutes in work window or stable interval; repeated watchdog/error logs inside the rate window produce only one insert decision.

- [ ] **Step 3: Run RED, implement minimal behavior, run GREEN**

Historical repair must be keyed by a new once-only preference version and operate only on matching incomplete records. It may update review metadata but cannot invent home arrival or overwrite manual fields.

- [ ] **Step 4: Verify bounded queries**

Replace any repair/UI call that loads all location logs with time-bounded queries. Keep database export paths unchanged.

- [ ] **Step 5: Run full checks and commit**

Run `testDebugUnitTest lintDebug assembleDebug`.

Commit: `安全标记历史缺失并限制高频采样日志`.

### Task 9: 全量验证、同签名安装和实机验收门

**Files:**
- Modify: `README.md`
- No source changes unless a newly reproduced defect first receives a failing test.

**Interfaces:**
- Consumes: all previous tasks.
- Produces: verified same-key APK installed without data loss; no remote publication before acceptance.

- [ ] **Step 1: Update README**

Document candidate radius versus stable anchor, company calibration, event versus confirmation time, service/provider health states, manual field protection, and Android 15 recovery boundary.

- [ ] **Step 2: Run fresh full verification**

```powershell
$env:ANDROID_HOME='C:\Users\Administrator\Documents\Codex\2026-07-22\referenced-chatgpt-conversation-this-is-untrusted\work\android-env\android-sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Require exit code 0. Do not run connected instrumentation against the user's live database.

- [ ] **Step 3: Record pre-install invariants read-only**

Using the known `adb.exe`, verify authorization, package path, certificate SHA-256, package stopped state, granted permissions, current service, database version, counts for work records/manual overrides/salaries/location logs, salary sum, company/home radius, work window, review count and representative recent records from a copied database snapshot.

- [ ] **Step 4: Compare signing certificates**

Use `apksigner verify --print-certs` on installed `base.apk` and built `app-debug.apk`. Require identical signer SHA-256 before installation.

- [ ] **Step 5: Install without deleting data**

```powershell
adb push app\build\outputs\apk\debug\app-debug.apk /data/local/tmp/worktime-anchor-recovery.apk
adb shell pm install -r -g /data/local/tmp/worktime-anchor-recovery.apk
adb shell rm /data/local/tmp/worktime-anchor-recovery.apk
```

Never uninstall the package.

- [ ] **Step 6: Verify post-install invariants**

Re-copy the database and require all pre-install record, override, salary, location and amount invariants to match except the schema version and explicitly expected review metadata. Verify foreground service, periodic worker, geofence receiver, no scoped crash, and no duplicate listeners.

- [ ] **Step 7: Verify safe device behavior**

- Open company calibration, collect or inspect readiness, then cancel so coordinates remain unchanged.
- Confirm settings page distinguishes service heartbeat, reliable fix and provider state.
- Replay sanitized August trajectories through JVM tests, not the live device.
- Trigger a synthetic coordinator failure only in JVM/in-memory tests and confirm the next fix is processed.
- Confirm 8月19日 remains review-required with unknown home arrival rather than 18:55.
- Confirm FlClash process/package state was not changed.

- [ ] **Step 8: Stop at user acceptance gate**

Report exact automated and device evidence. Do not push, open a PR, merge or delete the branch until the user tests and explicitly accepts.

### Task 10: 用户验收后发布与合并

**Files:**
- No source changes unless the user reports a reproduced defect with a failing test.

**Interfaces:**
- Consumes explicit user acceptance.
- Produces pushed branch, GitHub PR and merged `main` only when requested.

- [ ] **Step 1: Require explicit acceptance**

Proceed only after the user states the installed build is accepted.

- [ ] **Step 2: Run fresh final verification**

Run `gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` and require exit 0.

- [ ] **Step 3: Inspect and publish intentionally**

Require clean `git status -sb`, run `git diff --check`, push `fix/trajectory-anchor-crash-recovery`, create a PR against `main`, and include root causes, migration/data invariants and device verification in the PR body.

- [ ] **Step 4: Merge only after the requested integration choice**

Use `superpowers:finishing-a-development-branch`, merge the accepted PR, rerun full checks on merged `main`, and report PR URL and merge commit SHA. Never force-push.
