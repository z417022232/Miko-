# Today Navigation, Daily Details, and Vivo Autostart Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every foreground entry return to today, make all Statistics daily rows editable, and replace the permanently unknown Vivo autostart row with user-confirmed and boot-verified states while ensuring the background recording service recovers after reboot.

**Architecture:** Add small pure policies for foreground transitions, daily-row actions, and autostart state transitions, then connect them to Compose lifecycle/UI and the existing recovery pipeline. Persist Vivo status in private SharedPreferences, use the current `BootReceiver`/`ServiceRecovery`/geofence recovery work as the only source of boot verification, and reuse the existing record editor and manual-override audit path. Preserve all current uncommitted recovery/geofence work by reviewing and checkpointing it before feature edits.

**Tech Stack:** Kotlin, Android lifecycle, Jetpack Compose Material 3, SharedPreferences, Room, WorkManager, Android foreground location service, JUnit 4, Gradle 8.9, Android Gradle Plugin 8.7.3, minSdk 29, targetSdk 35.

## Global Constraints

- Do not delete, rebuild, or bulk-modify existing work records, salary records, location samples, settings, or manual overrides.
- Preserve package name, current signing key, foreground-service notification, and same-key update compatibility.
- Preserve and integrate the current uncommitted background recovery/geofence changes; do not overwrite or omit them.
- “Automatically start the app” means restore the background recording service, health worker, and geofences after boot/unlock/update; do not force-open the main UI.
- Android force-stop remains a system boundary: after force-stop, the user must open the app once.
- Do not stop, close, reconfigure, or otherwise modify FlClash.
- Do not run `connectedDebugAndroidTest`; use JVM tests plus copied-device database verification.
- Install a same-key update for user testing, then wait for explicit user confirmation before any GitHub push or branch merge.

---

## File Structure

- Create `ui/app/AppForegroundReset.kt`: pure foreground/start-stop transition state.
- Modify `MainActivity.kt`: observe Activity lifecycle and call `vm.today()` only for first entry or re-entry from background.
- Create `ui/DailyRecordAction.kt`: map review/normal rows to editor mode.
- Modify `StatisticsScreen.kt`: route every daily row to the shared editor with mode-specific copy.
- Modify `WorkTimeViewModel.kt`: save normal record edits through the same manual/audit path used by review confirmation.
- Create `location/permission/AutostartVerification.kt`: state enum, pure transitions, and SharedPreferences store.
- Modify `SettingsScreen.kt`: show “去设置 / 已开启 / 已验证”, prompt after returning from Vivo settings, and allow reopening settings.
- Modify `BootReceiver.kt` and recovery code: mark boot-verified only after a successful recovery request and geofence/health scheduling.
- Extend existing tests without adding a Room schema migration.

### Task 0: Review and Checkpoint Existing Recovery/Geofence Work

**Files:**
- Review modified files reported by `git status --short`, especially:
  - `app/src/main/java/com/example/worktimetracker/MainActivity.kt`
  - `app/src/main/java/com/example/worktimetracker/WorkTimeApplication.kt`
  - `app/src/main/java/com/example/worktimetracker/location/receiver/BootReceiver.kt`
  - `app/src/main/java/com/example/worktimetracker/location/recovery/LocationHealthWorker.kt`
  - `app/src/main/java/com/example/worktimetracker/location/recovery/ServiceRecovery.kt`
  - `app/src/main/java/com/example/worktimetracker/location/recovery/ServiceRecoveryPolicy.kt`
  - `app/src/main/java/com/example/worktimetracker/location/recovery/GeofenceRecovery.kt`
  - `app/src/main/java/com/example/worktimetracker/location/receiver/LocationTransitionReceiver.kt`

**Interfaces:**
- Consumes: current uncommitted recovery implementation and tests.
- Produces: a reviewed, buildable checkpoint commit containing only the already-present recovery/geofence work.

- [ ] **Step 1: Capture exact dirty scope and inspect every diff**

Run:

```powershell
git status --short
git diff -- app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/com/example/worktimetracker/MainActivity.kt app/src/main/java/com/example/worktimetracker/WorkTimeApplication.kt app/src/main/java/com/example/worktimetracker/data/HistoricalRecordRepair.kt app/src/main/java/com/example/worktimetracker/data/dao/LocationLogDao.kt app/src/main/java/com/example/worktimetracker/location/receiver/BootReceiver.kt app/src/main/java/com/example/worktimetracker/location/recovery/LocationHealthWorker.kt app/src/main/java/com/example/worktimetracker/location/recovery/ServiceRecovery.kt app/src/main/java/com/example/worktimetracker/location/recovery/ServiceRecoveryPolicy.kt app/src/test/java/com/example/worktimetracker/ServiceRecoveryPolicyTest.kt app/src/test/java/com/example/worktimetracker/WorkSessionEdgeCasesTest.kt
```

Read the complete contents of both untracked recovery files. Confirm no generated APK, database, coordinate, salary, token, or signing material is included.

- [ ] **Step 2: Run the focused recovery baseline**

Run:

```powershell
$env:ANDROID_HOME='C:\Users\Administrator\Documents\Codex\2026-07-22\referenced-chatgpt-conversation-this-is-untrusted\work\android-env\android-sdk'
.\gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.ServiceRecoveryPolicyTest --tests com.example.worktimetracker.WorkSessionEdgeCasesTest assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If a test fails, stop and diagnose that pre-existing recovery change before adding this feature.

- [ ] **Step 3: Commit the reviewed checkpoint explicitly**

Stage only the reviewed paths from Step 1, including the two recovery files if they are confirmed source files.

```powershell
git commit -m "完善后台恢复与围栏保活"
```

Do not use `git add -A`.

### Task 1: Foreground Re-entry Resets to Today

**Files:**
- Create: `app/src/main/java/com/example/worktimetracker/ui/app/AppForegroundReset.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/MainActivity.kt`
- Test: `app/src/test/java/com/example/worktimetracker/AppForegroundResetTest.kt`

**Interfaces:**
- Produces: `AppForegroundReset.onStart(): Boolean` and `AppForegroundReset.onStop()`.
- Consumes: `WorkTimeViewModel.today()`.

- [ ] **Step 1: Write the failing transition tests**

```kotlin
class AppForegroundResetTest {
    @Test fun `first start requests today`() {
        assertTrue(AppForegroundReset().onStart())
    }

    @Test fun `internal recomposition does not request today again`() {
        val reset = AppForegroundReset()
        reset.onStart()
        assertFalse(reset.onStart())
    }

    @Test fun `start after stop requests today`() {
        val reset = AppForegroundReset()
        reset.onStart()
        reset.onStop()
        assertTrue(reset.onStart())
    }
}
```

- [ ] **Step 2: Run RED**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.AppForegroundResetTest`

Expected: compilation fails because `AppForegroundReset` does not exist.

- [ ] **Step 3: Implement the minimal state machine**

```kotlin
class AppForegroundReset {
    private var foreground = false
    fun onStart(): Boolean = if (foreground) false else true.also { foreground = true }
    fun onStop() { foreground = false }
}
```

- [ ] **Step 4: Connect Activity lifecycle to the ViewModel**

In `AppRoot`, remember one `AppForegroundReset`, register a `LifecycleEventObserver` with `LocalLifecycleOwner`, call `vm.today()` when `ON_START` returns true, and call `onStop()` for `ON_STOP`. Dispose the observer with the composition. Do not tie this behavior to bottom-tab selection.

- [ ] **Step 5: Run GREEN and commit**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.AppForegroundResetTest assembleDebug`

Expected: PASS and successful Debug APK.

```powershell
git add app/src/main/java/com/example/worktimetracker/ui/app/AppForegroundReset.kt app/src/main/java/com/example/worktimetracker/MainActivity.kt app/src/test/java/com/example/worktimetracker/AppForegroundResetTest.kt
git commit -m "应用重新打开时回到当天"
```

### Task 2: Daily Detail Action and Normal Record Editing

**Files:**
- Create: `app/src/main/java/com/example/worktimetracker/ui/DailyRecordAction.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/ui/screens/StatisticsScreen.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/ui/app/WorkTimeViewModel.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/domain/engine/ReviewRecordEditor.kt`
- Test: `app/src/test/java/com/example/worktimetracker/DailyRecordActionTest.kt`
- Test: `app/src/test/java/com/example/worktimetracker/ReviewRecordEditorTest.kt`

**Interfaces:**
- Produces: `DailyRecordAction.forRecord(needsReview: Boolean): EditorMode`, where `EditorMode` is `CONFIRM_REVIEW` or `EDIT_CONFIRMED`.
- Produces: `WorkTimeViewModel.saveRecordEdit(...)` with the same field arguments as `confirmReview` but no `needsReview` precondition.
- Consumes: `ReviewRecordEditor.confirm(...)`, Room WorkRecord DAO, and ManualOverride DAO.

- [ ] **Step 1: Write failing action tests**

```kotlin
@Test fun `review row opens confirmation editor`() {
    assertEquals(EditorMode.CONFIRM_REVIEW, DailyRecordAction.forRecord(true))
}

@Test fun `normal row opens normal editor`() {
    assertEquals(EditorMode.EDIT_CONFIRMED, DailyRecordAction.forRecord(false))
}
```

- [ ] **Step 2: Write a failing normal-edit preservation test**

Add to `ReviewRecordEditorTest`:

```kotlin
@Test fun `editing confirmed record preserves home events and remains not review`() {
    val old = record(homeDepartureTime = 10L, homeArrivalTime = 40L).copy(needsReview = false)
    val edited = ReviewRecordEditor.confirm(old, "DAY_SHIFT", 20L, 30L, 540, "人工修改", 99L).getOrThrow()
    assertTrue(edited.isManual)
    assertFalse(edited.needsReview)
    assertEquals(10L, edited.homeDepartureTime)
    assertEquals(40L, edited.homeArrivalTime)
}
```

- [ ] **Step 3: Run RED**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.DailyRecordActionTest --tests com.example.worktimetracker.ReviewRecordEditorTest`

Expected: compilation failure for `DailyRecordAction`; existing editor test remains green.

- [ ] **Step 4: Implement action mapping and ViewModel save method**

`saveRecordEdit` must fetch the existing row, reject a missing record with “记录不存在”, call `ReviewRecordEditor.confirm`, upsert the result, and insert `ManualOverrideEntity` containing old/new shift, start, end, and minutes. It must not require `old.needsReview`.

- [ ] **Step 5: Make every daily row clickable**

In the Statistics daily list, pass an `onClick` to every `DailyStatRow`. Store both the selected record and `EditorMode`. Reuse `ReviewConfirmDialog` with mode-specific title/button:

- `CONFIRM_REVIEW`: title “确认 M月D日记录”, button “确认记录”, call `confirmReview`.
- `EDIT_CONFIRMED`: title “编辑 M月D日记录”, button “保存修改”, call `saveRecordEdit`.

Keep the existing review-count dialog and its row routing.

- [ ] **Step 6: Run GREEN and commit**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.DailyRecordActionTest --tests com.example.worktimetracker.ReviewRecordEditorTest assembleDebug`

Expected: PASS and successful build.

```powershell
git add app/src/main/java/com/example/worktimetracker/ui/DailyRecordAction.kt app/src/main/java/com/example/worktimetracker/ui/screens/StatisticsScreen.kt app/src/main/java/com/example/worktimetracker/ui/app/WorkTimeViewModel.kt app/src/main/java/com/example/worktimetracker/domain/engine/ReviewRecordEditor.kt app/src/test/java/com/example/worktimetracker/DailyRecordActionTest.kt app/src/test/java/com/example/worktimetracker/ReviewRecordEditorTest.kt
git commit -m "每日明细支持查看和人工修改"
```

### Task 3: Vivo Autostart State and Persistence

**Files:**
- Create: `app/src/main/java/com/example/worktimetracker/location/permission/AutostartVerification.kt`
- Test: `app/src/test/java/com/example/worktimetracker/AutostartVerificationTest.kt`

**Interfaces:**
- Produces: `enum class AutostartState { UNKNOWN, USER_CONFIRMED, BOOT_VERIFIED }`.
- Produces: `AutostartVerificationPolicy.userConfirmed(current, confirmed)` and `bootRecovery(current, succeeded)`.
- Produces: `AutostartVerificationStore.get()`, `set(state)`, `confirmByUser()`, and `verifyBootRecovery()`.

- [ ] **Step 1: Write failing transition tests**

```kotlin
@Test fun `user confirmation upgrades unknown to confirmed`() {
    assertEquals(USER_CONFIRMED, policy.userConfirmed(UNKNOWN, true))
}

@Test fun `cancel keeps current state`() {
    assertEquals(UNKNOWN, policy.userConfirmed(UNKNOWN, false))
}

@Test fun `successful boot recovery upgrades confirmed to verified`() {
    assertEquals(BOOT_VERIFIED, policy.bootRecovery(USER_CONFIRMED, true))
}

@Test fun `failed boot recovery does not claim verified`() {
    assertEquals(USER_CONFIRMED, policy.bootRecovery(USER_CONFIRMED, false))
}
```

- [ ] **Step 2: Run RED**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.AutostartVerificationTest`

Expected: compilation fails because autostart types do not exist.

- [ ] **Step 3: Implement pure transitions and SharedPreferences store**

Use preference file `worktime_autostart`, key `state`, and enum names as stored values. Invalid/missing values map to `UNKNOWN`. `confirmByUser()` writes `USER_CONFIRMED` unless already `BOOT_VERIFIED`; `verifyBootRecovery()` writes `BOOT_VERIFIED`.

- [ ] **Step 4: Run GREEN and commit**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.AutostartVerificationTest`

Expected: PASS.

```powershell
git add app/src/main/java/com/example/worktimetracker/location/permission/AutostartVerification.kt app/src/test/java/com/example/worktimetracker/AutostartVerificationTest.kt
git commit -m "记录Vivo自启动确认与验证状态"
```

### Task 4: Settings UI Confirmation and Boot Verification

**Files:**
- Modify: `app/src/main/java/com/example/worktimetracker/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/receiver/BootReceiver.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/recovery/ServiceRecovery.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/recovery/GeofenceRecovery.kt`
- Modify: `app/src/main/java/com/example/worktimetracker/location/recovery/LocationHealthWorker.kt`
- Test: `app/src/test/java/com/example/worktimetracker/ServiceRecoveryPolicyTest.kt`

**Interfaces:**
- Consumes: `AutostartVerificationStore` and `AutostartState` from Task 3.
- Produces: correct status labels and a boot recovery outcome that can be verified.

- [ ] **Step 1: Add a failing recovery-outcome test**

Extend the recovery policy test with an explicit outcome:

```kotlin
@Test fun `boot verification requires service and recovery scheduling success`() {
    assertTrue(ServiceRecoveryPolicy.bootVerified(serviceStarted = true, healthScheduled = true, geofenceRegistered = true))
    assertFalse(ServiceRecoveryPolicy.bootVerified(serviceStarted = true, healthScheduled = false, geofenceRegistered = true))
    assertFalse(ServiceRecoveryPolicy.bootVerified(serviceStarted = false, healthScheduled = true, geofenceRegistered = true))
}
```

- [ ] **Step 2: Run RED and implement the pure outcome function**

Run: `gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.ServiceRecoveryPolicyTest`

Expected RED: missing `bootVerified`; after minimal implementation, PASS.

- [ ] **Step 3: Return actual success from recovery helpers**

Make service start, health scheduling, and geofence registration report Boolean success without swallowing the outcome. `BootReceiver` must collect these results and call `AutostartVerificationStore.verifyBootRecovery()` only when `bootVerified(...)` returns true. On failure, keep the previous state and write one diagnostic app log.

- [ ] **Step 4: Implement the settings three-state UI**

Read `AutostartVerificationStore.get()` when the permission page opens and on resume. Map states:

- `UNKNOWN` → trailing text “去设置”, orange icon.
- `USER_CONFIRMED` → “已开启”, green check.
- `BOOT_VERIFIED` → “已验证”, green check.

When the Vivo row opens settings, set a local `awaitingAutostartConfirmation = true`. On lifecycle resume, if that flag is true, show an `AlertDialog` asking “是否已开启工时记录助手自启动？”. Confirm calls `confirmByUser()` and refreshes the row; cancel leaves state unchanged.

- [ ] **Step 5: Verify recovery and UI build, then commit**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.example.worktimetracker.AutostartVerificationTest --tests com.example.worktimetracker.ServiceRecoveryPolicyTest assembleDebug
```

Expected: PASS.

```powershell
git add app/src/main/java/com/example/worktimetracker/ui/screens/SettingsScreen.kt app/src/main/java/com/example/worktimetracker/location/receiver/BootReceiver.kt app/src/main/java/com/example/worktimetracker/location/recovery/ServiceRecovery.kt app/src/main/java/com/example/worktimetracker/location/recovery/GeofenceRecovery.kt app/src/main/java/com/example/worktimetracker/location/recovery/LocationHealthWorker.kt app/src/test/java/com/example/worktimetracker/ServiceRecoveryPolicyTest.kt
git commit -m "验证Vivo自启动与开机后台恢复"
```

### Task 5: Full Verification and Same-Key Device Update

**Files:**
- Update: `README.md` with foreground-to-today, clickable daily details, and autostart state semantics.
- Modify other source files only if a newly reproduced defect receives a failing test first.

**Interfaces:**
- Consumes all prior tasks.
- Produces a verified APK installed for user acceptance without remote publication.

- [ ] **Step 1: Update README**

Document that reopening returns to today, all Statistics daily rows are editable, and Vivo states mean user-confirmed versus boot-verified background service recovery.

- [ ] **Step 2: Run full safe checks**

```powershell
$env:ANDROID_HOME='C:\Users\Administrator\Documents\Codex\2026-07-22\referenced-chatgpt-conversation-this-is-untrusted\work\android-env\android-sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Do not run connected instrumentation.

- [ ] **Step 3: Record pre-install device invariants**

Using the known ADB executable, verify device authorization, package path, certificate SHA-256, first install time, database schema version, work/manual/review counts, salary count/sum, user time/radius settings, representative recent records, and foreground service state from a copied read-only database snapshot.

- [ ] **Step 4: Install the same-key update**

Verify the built APK certificate equals the installed certificate. Push to `/data/local/tmp/worktime-today-vivo.apk`, run non-streaming `adb shell pm install -r -g /data/local/tmp/worktime-today-vivo.apk`, and remove only that temporary APK. Never uninstall the package.

- [ ] **Step 5: Verify device behavior without changing records**

- Navigate to a historical month, background the app, reopen it, and confirm the UI returns to the current date.
- Open one normal daily row and one review daily row; verify mode-specific titles/buttons, then cancel both.
- Open Vivo settings, return, cancel once to prove no false status, repeat and confirm to prove “已开启”.
- Verify foreground service and recovery worker remain active and no scoped app crash appears in logcat.
- Re-copy the database and confirm all pre-install data invariants remain unchanged.

- [ ] **Step 6: Stop at the user acceptance gate**

Report exact automated/device results and ask the user to test. Do not push, merge, close the branch, or remove the working checkout until the user explicitly confirms completion.

### Task 6: Publish and Merge Only After User Confirmation

**Files:**
- No source changes unless the user reports a tested defect.

**Interfaces:**
- Consumes explicit user acceptance.
- Produces pushed commits, updated GitHub PR, and the user-selected merge result.

- [ ] **Step 1: Require explicit acceptance**

Proceed only after the user states that testing is complete and the fix is accepted. A request to continue testing is not acceptance.

- [ ] **Step 2: Run fresh final verification**

Run `gradlew.bat testDebugUnitTest lintDebug assembleDebug` and require exit code 0.

- [ ] **Step 3: Publish intentionally**

Inspect `git status -sb`, `git diff --check`, commit any accepted README-only finalization explicitly, and push the feature branch with tracking. Update or create the GitHub PR with exact root causes and verification evidence.

- [ ] **Step 4: Merge only with the confirmed branch strategy**

Use the finishing-development-branch workflow. If the user confirms merge into `main`, merge through the accepted PR or perform the exact locally approved merge, rerun tests on the merged tree, and report the final commit SHA. Do not force-push.
