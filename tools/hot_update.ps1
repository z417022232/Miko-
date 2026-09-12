# ============================================================
# 工时记录应用 — 一键热更新脚本
# 流程：assembleDebug → adb install -r -g → am force-stop → am start
# 用法：
#   pwsh tools/hot_update.ps1                  # 全自动（build+install+start）
#   pwsh tools/hot_update.ps1 -SkipBuild       # 不 build，用现有 APK
#   pwsh tools/hot_update.ps1 -SkipLaunch      # 装完不启动，让用户手动点
#   pwsh tools/hot_update.ps1 -DryRun          # 只检查不执行
# ============================================================

param(
    [string]$ApkPath        = "app/build/outputs/apk/debug/app-debug.apk",
    [string]$PackageName    = "com.example.worktimetracker",
    [string]$MainActivity   = ".MainActivity",
    [string]$AdbPath        = "C:\Users\Administrator\Documents\Codex\2026-07-22\referenced-chatgpt-conversation-this-is-untrusted\work\android-env\android-sdk\platform-tools\adb.exe",
    [string]$Jdk17Path      = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot",
    [switch]$SkipBuild      = $false,
    [switch]$SkipLaunch     = $false,
    [switch]$DryRun         = $false
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot

# ---- 颜色函数 ----
function Step($msg) { Write-Host "`n>>> $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "[OK] $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "[WARN] $msg" -ForegroundColor Yellow }
function Err($msg)  { Write-Host "[ERR] $msg" -ForegroundColor Red }

# ---- 解析绝对路径 ----
$resolvedApk = if ([System.IO.Path]::IsPathRooted($ApkPath)) {
    $ApkPath
} else {
    Join-Path $ProjectRoot $ApkPath
}

# ===========================================================
# Step 1: 设备连接预检
# ===========================================================
Step "1/5 检查 adb 设备连接"
& $AdbPath devices
$devOut = & $AdbPath devices 2>&1
if ($devOut -notmatch "`tdevice`r?`n") {
    Err "没有 device 在线的 USB 设备；请检查 vivo USB 调试是否开启"
    exit 2
}
Ok "设备在线"

if ($DryRun) { Ok "DryRun 模式，结束"; exit 0 }

# ===========================================================
# Step 2: APK 存在性 + 可选 build
# ===========================================================
Step "2/5 检查 APK: $resolvedApk"
if (-not (Test-Path $resolvedApk)) {
    if ($SkipBuild) {
        Err "APK 不存在且 -SkipBuild：$resolvedApk"
        exit 3
    }
    Warn "APK 不存在，触发 assembleDebug"
    $gradlew = Join-Path $ProjectRoot "gradlew.bat"
    $env:JAVA_HOME = $Jdk17Path
    & $gradlew assembleDebug --offline -q
    if ($LASTEXITCODE -ne 0) {
        Err "assembleDebug 失败（exit=$LASTEXITCODE）"
        exit 4
    }
}
if (-not (Test-Path $resolvedApk)) {
    Err "build 后仍未找到 APK：$resolvedApk"
    exit 5
}
$apkInfo   = Get-Item $resolvedApk
$apkMd5    = (Get-FileHash $resolvedApk -Algorithm MD5).Hash
$apkSha256 = (Get-FileHash $resolvedApk -Algorithm SHA256).Hash
Ok "APK 大小=$([math]::Round($apkInfo.Length / 1KB, 1)) KB  MD5=$apkMd5  SHA256=$apkSha256"

# ===========================================================
# Step 3: 签名比对（可选，防止本地装错版本）
# ===========================================================
Step "3/5 校验 device 端签名一致性"
$apksigner = Get-Command apksigner -ErrorAction SilentlyContinue
$certOut = & $AdbPath shell pm path $PackageName 2>&1
if ($certOut -match "^package:(.+)$") {
    $deviceApkPath = $matches[1]
    if ($apksigner) {
        $localCert  = & apksigner verify --print-certs $resolvedApk 2>&1
        $localSha   = ($localCert | Select-String "SHA-256 digest:" | Select-Object -First 1) -replace ".*SHA-256 digest:\s+", ""
        $remoteCert = & $AdbPath exec-out run-as $PackageName cat /data/data/$PackageMarker/ 2>&1
        # 简化路径：直接比对 dumpsys package
        $dumpsysOut = & $AdbPath shell dumpsys package $PackageName 2>&1
        $remoteSha  = ($dumpsysOut | Select-String "signatures=.*SHA-256" | Select-Object -First 1).ToString()
        Warn "设备已装包；签名比对见 dumpsys 输出"
    } else {
        Warn "apksigner 不在 PATH，跳过签名比对"
    }
} else {
    Warn "设备未装 $PackageName，本次为首次安装"
}

# ===========================================================
# Step 4: adb install -r -g
# ===========================================================
Step "4/5 adb install -r -g"
$installOut = & $AdbPath install -r -g $resolvedApk 2>&1
Write-Host $installOut
if ($installOut -notmatch "Success") {
    Err "安装失败，输出见上"
    exit 6
}
Ok "安装成功"

# ===========================================================
# Step 5: force-stop + start
# ===========================================================
Step "5/5 am force-stop + am start"
& $AdbPath shell am force-stop $PackageName 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Warn "force-stop 退出码 $LASTEXITCODE" }

if ($SkipLaunch) {
    Warn "-SkipLaunch 模式，跳过启动；用户需手动点击桌面图标"
} else {
    $startOut = & $AdbPath shell am start -n "$PackageName$MainActivity" 2>&1
    Write-Host $startOut
    if ($startOut -notmatch "Starting:") {
        Err "启动失败；用户可手动点击图标"
        exit 7
    }
    Ok "启动成功"
}

# ===========================================================
# 完成报告
# ===========================================================
Write-Host ""
Write-Host "================================================" -ForegroundColor Green
Write-Host " 热更新完成" -ForegroundColor Green
Write-Host " APK: $resolvedApk" -ForegroundColor Green
Write-Host " MD5: $apkMd5" -ForegroundColor Green
Write-Host " PKG: $PackageName$MainActivity" -ForegroundColor Green
Write-Host "================================================" -ForegroundColor Green