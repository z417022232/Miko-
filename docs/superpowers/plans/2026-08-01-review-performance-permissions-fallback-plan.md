# Review Confirmation, Performance, Permissions, and Shift Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add editable confirmation of review records, reduce UI/background-location work, route each missing permission to its correct setting, and create reviewable records from the nearest completed day/night shift window when commute evidence is missing.

**Architecture:** Keep Room as the source of truth and isolate new rules into pure domain classes that can be tested without Android. `ReviewRecordEditor` owns confirmation validation, `ShiftWindowFallback` owns day/night window selection and missing-edge completion, and `PermissionSettingsRouter` owns Android Intent selection. The ViewModel exposes derived monthly statistics and one stable month Flow, while the location service uses a serial callback channel plus cached settings/profile data.

**Tech Stack:** Kotlin, Android 15 APIs with minSdk 29, Jetpack Compose Material 3, Room, Kotlin coroutines/Flow, JUnit 4, AndroidX WorkManager.

## Global Constraints

- Do not delete or rewrite salary records, manual records, historical location samples, or reliable event timestamps.
- Preserve package name, existing signing key, foreground-service notification, and all current user data during installation.
- Do not add a home-screen background status card.
- Do not stop, close, configure, or otherwise modify FlClash.
- A fallback departure may only be persisted after the selected shift window has ended; no future departure timestamps.
- Every window-completed record remains `needsReview = true` until the user explicitly confirms it.
- Existing reliable timestamps beat fallback boundaries; existing manual or confirmed records beat all automatic changes.
- Do not run `connectedDebugAndroidTest`; validate Room migration against a copied database because instrumentation previously caused target-app removal on this device.

---

## File Structure

- Create `domain/engine/ReviewRecordEditor.kt`: validate and produce a confirmed manual record without losing event fields.
- Create `domain/engine/ShiftWindowFallback.kt`: calculate day/night windows, select the nearest window, and fill only missing event edges.
- Create `location/permission/PermissionSettingsRouter.kt`: map permission status and requested item to safe Android Intents.
- Create `location/service/LocationProcessingGate.kt`: serialize location callback work and expose cache invalidation decisions as pure behavior.
- Modify `WorkTimeViewModel.kt`: maintain one month observation, expose review operations and cached monthly statistics.
- Modify `StatisticsScreen.kt`: review count navigation, review list, editable confirmation dialog, stable list keys.
- Modify `SettingsScreen.kt`: clickable permission rows and “修复下一项”.
- Modify `ForegroundLocationService.kt`: serial callback processing, settings/profile caching, key-event logging, shift-window fallback.
- Modify Room DAOs only with narrow queries/Flows needed by cache invalidation; avoid a schema migration unless implementation proves it necessary.

### Task 1: Shift Window Rule

**Files:**
- Create: `app/src/main/java/com/example/worktimetracker/domain/engine/ShiftWindowFallback.kt`
- Test: `app/src/test/java/com/example/worktimetracker/ShiftWindowFallbackTest.kt`

**Interfaces:**
- Consumes: `WorkSettings`, `ShiftType`, epoch milliseconds, and optional reliable arrival/departure.
- Produces: `ShiftWindowFallback.windowFor(date: LocalDate, shift: ShiftType, settings: WorkSettings): Window`, `nearestWindow(detectedAt: Long, settings: WorkSettings): Window`, and `complete(detectedAt: Long, reliableStart: Long?, reliableEnd: Long?, settings: WorkSettings): Completion`.

- [ ] **Step 1: Write failing tests for reversed windows and nearest selection**

```kotlin
@Test fun `0900 to 2100 creates opposite day and night windows`() {
    val rules = ShiftWindowFallback(ZoneId.of("Asia/Shanghai"))
    val settings = WorkSettings(workStartMinutes = 540, workEndMinutes = 1260)
    val date = LocalDate.of(2026, 8, 1)
    assertEquals("2026-08-01T09:00", rules.windowFor(date, ShiftType.DAY_SHIFT, settings).startLocal.toString())
    assertEquals("2026-08-01T21:00", rules.windowFor(date, ShiftType.DAY_SHIFT, settings).endLocal.toString())
    assertEquals("2026-08-01T21:00", rules.windowFor(date, ShiftType.NIGHT_SHIFT, settings).startLocal.toString())
    assertEquals("2026-08-02T09:00", rules.windowFor(date, ShiftType.NIGHT_SHIFT, settings).endLocal.toString())
}

@Test fun `company detection chooses nearest shift window`() {
    val detected = instant("2026-08-01T22:10:00+08:00")
    assertEquals(ShiftType.NIGHT_SHIFT, rules.nearestWindow(detected, settings).shift)
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.ShiftWindowFallbackTest`

Expected: compilation fails because `ShiftWindowFallback` does not exist.

- [ ] **Step 3: Implement `Window` and deterministic candidate selection**

Create candidates for day and night windows anchored on the detected local date, previous date, and next date. Choose the candidate with the smallest absolute distance from `detectedAt` to its interval; distance is zero inside the interval, otherwise distance to the nearest edge. Resolve a tie in favor of the window whose start is most recent and not in the future.

- [ ] **Step 4: Add failing tests for completion safety**

```kotlin
@Test fun `unfinished window never creates future departure`() {
    val result = rules.complete(instant("2026-08-01T12:00:00+08:00"), null, null, settings)
    assertNull(result.endMillis)
    assertTrue(result.needsReview)
}

@Test fun `completed window fills only missing edges`() {
    val reliableStart = instant("2026-08-01T09:08:00+08:00")
    val result = rules.complete(instant("2026-08-01T22:00:00+08:00"), reliableStart, null, settings)
    assertEquals(reliableStart, result.startMillis)
    assertEquals(instant("2026-08-01T21:00:00+08:00"), result.endMillis)
    assertTrue(result.usedFallbackEnd)
}
```

- [ ] **Step 5: Implement completion and run GREEN**

`Completion` carries `assignedDate`, `shift`, `startMillis`, `endMillis`, `usedFallbackStart`, `usedFallbackEnd`, and `needsReview = usedFallbackStart || usedFallbackEnd`. Never replace a non-null reliable edge.

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.ShiftWindowFallbackTest`

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/example/worktimetracker/domain/engine/ShiftWindowFallback.kt app/src/test/java/com/example/worktimetracker/ShiftWindowFallbackTest.kt
git commit -m "增加缺失轨迹班次窗口规则"
```

### Task 2: Review Confirmation Domain Logic

**Files:**
- Create: `app/src/main/java/com/example/worktimetracker/domain/engine/ReviewRecordEditor.kt`
- Test: `app/src/test/java/com/example/worktimetracker/ReviewRecordEditorTest.kt`

**Interfaces:**
- Consumes: an existing `WorkRecordEntity`, requested shift, start/end epoch milliseconds, final minutes, note, and current time.
- Produces: `ReviewRecordEditor.confirm(...): Result<WorkRecordEntity>`.

- [ ] **Step 1: Write failing confirmation tests**

```kotlin
@Test fun `confirmation preserves home events and becomes manual`() {
    val old = record(needsReview = true, homeDepartureTime = 10L, homeArrivalTime = 40L)
    val result = ReviewRecordEditor.confirm(old, "NIGHT_SHIFT", 20L, 30L, 600, "已核对", 99L).getOrThrow()
    assertFalse(result.needsReview)
    assertTrue(result.isManual)
    assertEquals("MANUAL", result.status)
    assertEquals(10L, result.homeDepartureTime)
    assertEquals(40L, result.homeArrivalTime)
}

@Test fun `confirmation rejects departure before arrival`() {
    assertTrue(ReviewRecordEditor.confirm(record(), "DAY_SHIFT", 30L, 20L, 600, "", 99L).isFailure)
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.ReviewRecordEditorTest`

Expected: compilation fails because `ReviewRecordEditor` does not exist.

- [ ] **Step 3: Implement minimal validation and merge**

Accept only `DAY_SHIFT` or `NIGHT_SHIFT`, require `finalMinutes >= 0`, and require `endMillis > startMillis` when both are present. Copy only user-editable fields and confirmation flags, preserving identifiers, home events, `actualMinutes`, and `createdAt`.

- [ ] **Step 4: Run GREEN and commit**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.ReviewRecordEditorTest`

Expected: PASS.

```powershell
git add app/src/main/java/com/example/worktimetracker/domain/engine/ReviewRecordEditor.kt app/src/test/java/com/example/worktimetracker/ReviewRecordEditorTest.kt
git commit -m "增加待确认记录确认规则"
```

### Task 3: ViewModel Review API and Stable Month Observation

**Files:**
- Modify: `app/src/main/java/com/example/worktimetracker/ui/app/WorkTimeViewModel.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/data/dao/WorkRecordDao.kt`
- Test: `app/src/test/java/com/example/worktimetracker/MonthlyRecordIndexTest.kt`
- Create: `app/src/main/java/com/example/worktimetracker/ui/MonthlyRecordIndex.kt`

**Interfaces:**
- Consumes: `ReviewRecordEditor.confirm` from Task 2 and `observeMonthRecords` from Room.
- Produces: `reviewRecords: StateFlow<List<UiDayRecord>>`, `confirmReview(...)`, and `MonthlyRecordIndex.build(month, rows, today)`.

- [ ] **Step 1: Write a failing index test**

```kotlin
@Test fun `month mapping performs one lookup per date and preserves reviews`() {
    val rows = listOf(entity("2026-08-01", needsReview = true), entity("2026-08-03"))
    val days = MonthlyRecordIndex.build(YearMonth.of(2026, 8), rows, LocalDate.of(2026, 8, 4))
    assertEquals(31, days.size)
    assertTrue(days.first().needsReview)
    assertEquals("休息", days[1].status)
}
```

- [ ] **Step 2: Verify RED, implement indexed mapping, verify GREEN**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.MonthlyRecordIndexTest`

Expected RED: unresolved `MonthlyRecordIndex`.

Implementation: convert `rows.associateBy { it.workDate }` once, then map calendar dates with O(1) lookup.

Expected GREEN: PASS.

- [ ] **Step 3: Refactor month observation**

Replace repeated `loadMonth()` cancellation from ordinary save methods with a month-keyed observation job. `moveToMonth`, `today`, and `jumpToMonth` may restart it only when `YearMonth` changes. Room Flow updates records after upsert automatically. Salary refresh remains a separate month-keyed read.

- [ ] **Step 4: Add `confirmReview`**

```kotlin
fun confirmReview(
    date: LocalDate,
    shift: String,
    startMillis: Long?,
    endMillis: Long?,
    hoursText: String,
    note: String,
    onResult: (String?) -> Unit
)
```

Fetch by date, call `ReviewRecordEditor.confirm`, upsert the result, and insert `ManualOverrideEntity` with old/new shift, time, and minutes. Return a Chinese validation error through `onResult`; Flow removes the record from `reviewRecords` without an explicit month reload.

- [ ] **Step 5: Run focused and existing ViewModel-independent tests, then commit**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.MonthlyRecordIndexTest --tests com.example.worktimetracker.ReviewRecordEditorTest`

Expected: PASS.

```powershell
git add app/src/main/java/com/example/worktimetracker/ui/MonthlyRecordIndex.kt app/src/main/java/com/example/worktimetracker/ui/app/WorkTimeViewModel.kt app/src/main/java/com/example/worktimetracker/data/dao/WorkRecordDao.kt app/src/test/java/com/example/worktimetracker/MonthlyRecordIndexTest.kt
git commit -m "优化月份监听并接入待确认操作"
```

### Task 4: Statistics Review UI

**Files:**
- Modify: `app/src/main/java/com/example/worktimetracker/ui/screens/StatisticsScreen.kt`
- Reuse: time picker components in `app/src/main/java/com/example/worktimetracker/ui/screens/CalendarScreen.kt` by extracting narrowly to `ReviewEditorComponents.kt` only if direct reuse is not possible.
- Test: `app/src/test/java/com/example/worktimetracker/ReviewEditorStateTest.kt`
- Create: `app/src/main/java/com/example/worktimetracker/ui/ReviewEditorState.kt`

**Interfaces:**
- Consumes: `reviewRecords` and `confirmReview` from Task 3.
- Produces: clickable review metric, review-only list/sheet, and editable confirmation dialog.

- [ ] **Step 1: Write failing editor-state tests**

```kotlin
@Test fun `night departure earlier clock is moved to next day`() {
    val state = ReviewEditorState.from(recordDate = LocalDate.of(2026, 8, 1), shift = "NIGHT_SHIFT", startMinute = 21 * 60, endMinute = 9 * 60)
    assertEquals(LocalDate.of(2026, 8, 2), state.endDate)
}

@Test fun `day departure earlier clock reports invalid sequence`() {
    val state = ReviewEditorState.from(LocalDate.of(2026, 8, 1), "DAY_SHIFT", 21 * 60, 9 * 60)
    assertNotNull(state.validationError)
}
```

- [ ] **Step 2: Verify RED, implement state conversion, verify GREEN**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.ReviewEditorStateTest`

Expected RED: unresolved `ReviewEditorState`; expected GREEN after implementation: PASS.

- [ ] **Step 3: Implement the UI flow**

Make the “待确认” metric clickable only when count > 0. Show an in-page section or bottom sheet containing only review rows. Clicking a row opens an editor with day/night chips, arrival date/time, departure date/time, decimal hours, note, validation message, Cancel, and Confirm. Confirmation calls the ViewModel and closes only on success.

- [ ] **Step 4: Stabilize statistics recomposition**

Calculate totals, worked rows, review rows, and weekly bars with `remember(records, month)`. Use `items(items = worked, key = { it.date.toString() })` and the same stable key in the review list.

- [ ] **Step 5: Build and commit**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.ReviewEditorStateTest assembleDebug`

Expected: PASS and APK produced.

```powershell
git add app/src/main/java/com/example/worktimetracker/ui/screens/StatisticsScreen.kt app/src/main/java/com/example/worktimetracker/ui/ReviewEditorState.kt app/src/test/java/com/example/worktimetracker/ReviewEditorStateTest.kt
git commit -m "统计页支持修正并确认记录"
```

### Task 5: Permission Routing

**Files:**
- Create: `app/src/main/java/com/example/worktimetracker/location/permission/PermissionSettingsRouter.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/permission/PermissionManager.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/ui/screens/SettingsScreen.kt`
- Test: `app/src/test/java/com/example/worktimetracker/PermissionRepairPriorityTest.kt`

**Interfaces:**
- Consumes: `PermissionStatus` plus Android SDK/manufacturer information.
- Produces: `PermissionRepairPriority.next(status): PermissionItem?`, `PermissionSettingsRouter.intentFor(item, context): List<Intent>`, and safe `open(item, context): OpenResult`.

- [ ] **Step 1: Write failing priority tests**

```kotlin
@Test fun `repair next follows fixed permission order`() {
    val status = PermissionStatus(fineLocation = true, backgroundLocation = false, notifications = false, batteryUnrestricted = false)
    assertEquals(PermissionItem.BACKGROUND_LOCATION, PermissionRepairPriority.next(status))
}

@Test fun `all detectable permissions complete returns vivo autostart`() {
    val status = PermissionStatus(true, true, true, true)
    assertEquals(PermissionItem.VIVO_AUTOSTART, PermissionRepairPriority.next(status))
}
```

- [ ] **Step 2: Verify RED and implement priority**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.PermissionRepairPriorityTest`

Expected RED: unresolved priority types; expected GREEN after implementation: PASS.

- [ ] **Step 3: Implement Intent candidates and safe fallback**

Use runtime permission launcher for fine/coarse and notification when still requestable. Use `ACTION_APPLICATION_DETAILS_SETTINGS` for background-location and notification details fallback. Use `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with `package:` URI, then `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`. For Vivo, provide explicit known OriginOS/iManager components followed by app details. Before opening, resolve each intent and use the first resolvable one; return a Chinese manual-path message on fallback.

- [ ] **Step 4: Make rows clickable and add “修复下一项”**

Each `PermissionRow` receives `onClick`. The top primary button calls the fixed-priority selector. On `ON_RESUME`, re-run `PermissionManager.check`. Display the router result/fallback message below the button.

- [ ] **Step 5: Run tests, build, and commit**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.PermissionRepairPriorityTest assembleDebug`

Expected: PASS.

```powershell
git add app/src/main/java/com/example/worktimetracker/location/permission/PermissionSettingsRouter.kt app/src/main/java/com/example/worktimetracker/location/permission/PermissionManager.kt app/src/main/java/com/example/worktimetracker/ui/screens/SettingsScreen.kt app/src/test/java/com/example/worktimetracker/PermissionRepairPriorityTest.kt
git commit -m "权限页支持逐项一键修复"
```

### Task 6: Serialized Location Processing and Cache Policy

**Files:**
- Create: `app/src/main/java/com/example/worktimetracker/location/service/LocationProcessingGate.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/service/ForegroundLocationService.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/data/dao/WorkRecordDao.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/data/dao/UserSettingsDao.kt`
- Test: `app/src/test/java/com/example/worktimetracker/LocationProcessingGateTest.kt`

**Interfaces:**
- Consumes: provider/fix timestamp, settings revision, and learning-data revision.
- Produces: a conflated serial processing gate and cache validity decisions.

- [ ] **Step 1: Write failing gate tests**

```kotlin
@Test fun `newer pending fix replaces older unprocessed fix`() {
    val gate = LocationProcessingGate()
    gate.offer(Fix("gps", 100))
    gate.offer(Fix("gps", 200))
    assertEquals(200, gate.takePending()!!.time)
}

@Test fun `profile cache invalidates only when record revision changes`() {
    val cache = RevisionCache<String>()
    cache.put(7, "profile")
    assertEquals("profile", cache.get(7))
    assertNull(cache.get(8))
}
```

- [ ] **Step 2: Verify RED, implement pure gate/cache, verify GREEN**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.LocationProcessingGateTest`

Expected RED: missing types; expected GREEN after implementation: PASS.

- [ ] **Step 3: Route callbacks through one service coroutine**

`onLocationChanged` performs only the existing freshness check and offers the fix to the serial gate. One coroutine processes fixes in order; if callbacks arrive faster than processing, retain the newest unprocessed fix per provider rather than launching unlimited database coroutines.

- [ ] **Step 4: Cache settings and learned profile**

Observe settings as a Flow or cache the entity with `updatedAt`/content equality. Query the latest 14 learning rows only when the maximum eligible `updatedAt` changes. Invalidate the learning cache immediately after the service writes a completed valid session.

- [ ] **Step 5: Reduce logs without removing trajectory samples**

Keep accepted `location_logs`. Insert `app_logs` only when state changes, a provider/permission error occurs, a candidate boundary begins/cancels, a session confirms, or the sampling tier changes. Remove the unconditional `LOCATION currentState` app-log insert.

- [ ] **Step 6: Run focused tests and commit**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.LocationProcessingGateTest --tests com.example.worktimetracker.LocationFixGateTest --tests com.example.worktimetracker.LocationEventProcessorTest`

Expected: PASS.

```powershell
git add app/src/main/java/com/example/worktimetracker/location/service/LocationProcessingGate.kt app/src/main/java/com/example/worktimetracker/location/service/ForegroundLocationService.kt app/src/main/java/com/example/worktimetracker/data/dao/WorkRecordDao.kt app/src/main/java/com/example/worktimetracker/data/dao/UserSettingsDao.kt app/src/test/java/com/example/worktimetracker/LocationProcessingGateTest.kt
git commit -m "串行定位处理并缓存班次学习"
```

### Task 7: Integrate Company-Presence Fallback

**Files:**
- Modify: `app/src/main/java/com/example/worktimetracker/location/service/ForegroundLocationService.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/recovery/LocationHealthWorker.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/data/dao/WorkRecordDao.kt`
- Test: `app/src/test/java/com/example/worktimetracker/CompanyPresenceFallbackTest.kt`
- Create: `app/src/main/java/com/example/worktimetracker/location/service/CompanyPresenceFallback.kt`

**Interfaces:**
- Consumes: `ShiftWindowFallback` from Task 1, latest reliable company presence, current record, settings, and `now`.
- Produces: `CompanyPresenceFallback.evaluate(...): Action` where action is `None`, `Draft`, or `UpsertReview(record)`.

- [ ] **Step 1: Write failing integration-policy tests**

```kotlin
@Test fun `company presence during active shift creates draft without future end`() {
    val action = policy.evaluate(companyFixAt = instant("2026-08-01T12:00+08:00"), now = instant("2026-08-01T12:00+08:00"), existing = null, settings = settings)
    assertTrue(action is Action.Draft)
    assertNull((action as Action.Draft).record.endTime)
}

@Test fun `health check after shift end completes review record`() {
    val action = policy.evaluate(companyFixAt = instant("2026-08-01T12:00+08:00"), now = instant("2026-08-01T22:00+08:00"), existing = draft, settings = settings)
    val record = (action as Action.UpsertReview).record
    assertEquals(instant("2026-08-01T09:00+08:00"), record.startTime)
    assertEquals(instant("2026-08-01T21:00+08:00"), record.endTime)
    assertTrue(record.needsReview)
}

@Test fun `manual record is never changed`() {
    assertEquals(Action.None, policy.evaluate(companyFixAt, now, manualRecord, settings))
}
```

- [ ] **Step 2: Verify RED, implement policy, verify GREEN**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.CompanyPresenceFallbackTest`

Expected RED: missing policy; expected GREEN after implementation: PASS.

- [ ] **Step 3: Invoke fallback from reliable company fixes and health checks**

On a reliable `COMPANY` fix with no ordered commute session, evaluate and persist only `Draft`/`UpsertReview`. The 15-minute health worker re-evaluates unfinished fallback drafts after the window end even if no departure fix arrives. Add an exact DAO query for incomplete non-manual review drafts; never scan location logs.

- [ ] **Step 4: Verify interactions with ordered state machine**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.CompanyPresenceFallbackTest --tests com.example.worktimetracker.ConfirmedSessionTest --tests com.example.worktimetracker.LocationEventProcessorTest --tests com.example.worktimetracker.ShiftProfileLearnerTest`

Expected: PASS; ordered commute sessions retain reliable times and no manual record changes.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/example/worktimetracker/location/service/CompanyPresenceFallback.kt app/src/main/java/com/example/worktimetracker/location/service/ForegroundLocationService.kt app/src/main/java/com/example/worktimetracker/location/recovery/LocationHealthWorker.kt app/src/main/java/com/example/worktimetracker/data/dao/WorkRecordDao.kt app/src/test/java/com/example/worktimetracker/CompanyPresenceFallbackTest.kt
git commit -m "接入公司在场班次窗口兜底"
```

### Task 8: Full Verification, Safe Install, and Publish

**Files:**
- Modify only if verification exposes a tested defect.
- Update: `README.md` with confirmation, permission-routing, and fallback behavior after code is green.

**Interfaces:**
- Consumes all prior tasks.
- Produces tested APK, preserved device data, updated documentation, and pushed source.

- [ ] **Step 1: Update README behavior documentation**

Document that configured times define day and reverse night windows, fallback records require review, review records are editable in Statistics, and permissions require user confirmation in Android settings.

- [ ] **Step 2: Run all safe automated checks**

Run:

```powershell
$env:ANDROID_HOME='C:\Users\Administrator\Documents\Codex\2026-07-22\referenced-chatgpt-conversation-this-is-untrusted\work\android-env\android-sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Do not run connected instrumentation.

- [ ] **Step 3: Record pre-install device invariants**

Using the known ADB executable, verify authorized device, package path, first install time, database file sizes, foreground service, work-record count, manual count, salary count/sum, user settings, and representative July 28–31 records from a copied read-only database snapshot.

- [ ] **Step 4: Install without data loss**

Push the APK to `/data/local/tmp/worktime-review-performance.apk`, then use non-streaming `adb shell pm install -r -g /data/local/tmp/worktime-review-performance.apk`. Remove only that exact temporary APK after success. Never uninstall the app.

- [ ] **Step 5: Verify post-install invariants and UI**

Confirm first install time is unchanged; database counts, salary sum, settings, July records, and schema version match pre-install values; foreground service is running. Open the app and verify “待确认” is clickable, the editor contains shift/time/hours/confirm controls, the permission page contains “修复下一项”, and no crash appears in scoped app logs.

- [ ] **Step 6: Commit documentation and push**

```powershell
git add README.md
git commit -m "更新确认与班次兜底说明"
git push
```

Update the existing draft PR with exact test/build/device results. Report that FlClash was untouched.
