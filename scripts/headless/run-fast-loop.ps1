# run-fast-loop.ps1: Fast-Fail Execution Loop for Autonomous Android Engineering
[CmdletBinding()]
param (
    [string]$Serial = "emulator-5562",
    [switch]$SkipInstall,
    [switch]$LaunchApp
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$androidDir = Join-Path $projectRoot "android"
$sdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adbPath = Join-Path $sdkPath "platform-tools\adb.exe"

$timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
$progressFile = Join-Path $androidDir "PROGRESS.md"

function Log-Progress([string]$stage, [string]$status, [string]$details) {
    $entry = "| $timestamp | $stage | $status | $details |`n"
    if (Test-Path $progressFile) {
        Add-Content -Path $progressFile -Value $entry
    }
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " Running Autonomous Fast-Fail Verification Loop" -ForegroundColor Cyan
Write-Host " Project : $androidDir" -ForegroundColor Cyan
Write-Host " Time    : $timestamp" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# -------------------------------------------------------------
# Tier 1: Kotlin Compile Check (Syntax & Type Errors in seconds)
# -------------------------------------------------------------
Write-Host "`n[Tier 1/3] Running Kotlin Compilation (:app:compileProductionDebugKotlin)..." -ForegroundColor Yellow
Push-Location $androidDir
try {
    & .\gradlew :app:compileProductionDebugKotlin --daemon
    if ($LASTEXITCODE -ne 0) { throw "Compilation failed with exit code $LASTEXITCODE" }
    Write-Host "[OK] Tier 1 Passed: Kotlin compilation succeeded." -ForegroundColor Green
    Log-Progress "Tier 1: Compile" "PASS" "Kotlin compiled cleanly"
} catch {
    Write-Host "[FAIL] Tier 1 Failed: Kotlin compilation errors found." -ForegroundColor Red
    Log-Progress "Tier 1: Compile" "FAIL" "$_"
    Pop-Location
    exit 1
}

# -------------------------------------------------------------
# Tier 2: JVM Unit Tests (Instant Logic Verification)
# -------------------------------------------------------------
Write-Host "`n[Tier 2/3] Running JVM Unit Tests (:app:testProductionDebugUnitTest)..." -ForegroundColor Yellow
try {
    & .\gradlew :app:testProductionDebugUnitTest --daemon
    if ($LASTEXITCODE -ne 0) { throw "Unit tests failed with exit code $LASTEXITCODE" }
    Write-Host "[OK] Tier 2 Passed: JVM unit tests succeeded." -ForegroundColor Green
    Log-Progress "Tier 2: Unit Test" "PASS" "All unit tests green"
} catch {
    Write-Host "[FAIL] Tier 2 Failed: Unit test assertions failed." -ForegroundColor Red
    Log-Progress "Tier 2: Unit Test" "FAIL" "$_"
    Pop-Location
    exit 1
}

# -------------------------------------------------------------
# Tier 3: Package & Install on Headless Emulator
# -------------------------------------------------------------
if (-not $SkipInstall) {
    Write-Host "`n[Tier 3/3] Packaging APK (:app:assembleProductionDebug)..." -ForegroundColor Yellow
    try {
        & .\gradlew :app:assembleProductionDebug --daemon
        if ($LASTEXITCODE -ne 0) { throw "Packaging failed with exit code $LASTEXITCODE" }
        Write-Host "[OK] APK build succeeded." -ForegroundColor Green
    } catch {
        Write-Host "[FAIL] APK packaging failed." -ForegroundColor Red
        Log-Progress "Tier 3: Packaging" "FAIL" "$_"
        Pop-Location
        exit 1
    }

    $apkPath = Join-Path $androidDir "app\build\outputs\apk\production\debug\app-production-debug.apk"
    if (Test-Path $apkPath) {
        Write-Host "Installing APK to headless device $Serial..." -ForegroundColor Yellow
        $installResult = & $adbPath -s $Serial install -r $apkPath
        if ($installResult -match "Success") {
            Write-Host "[OK] Tier 3 Passed: APK successfully installed on $Serial." -ForegroundColor Green
            Log-Progress "Tier 3: Install" "PASS" "APK installed on $Serial"
            
            if ($LaunchApp) {
                Write-Host "Launching com.stressdetector on $Serial..." -ForegroundColor Cyan
                & $adbPath -s $Serial shell am start -n "com.stressdetector/.presentation.MainActivity"
                Start-Sleep -Seconds 2
                
                # Check UI dump
                & "$PSScriptRoot\capture-ui-tree.ps1" -Serial $Serial
            }
        } else {
            Write-Host "[FAIL] APK install failed on $Serial: $installResult" -ForegroundColor Red
            Log-Progress "Tier 3: Install" "FAIL" "$installResult"
            Pop-Location
            exit 1
        }
    } else {
        Write-Warning "APK not found at $apkPath"
    }
} else {
    Write-Host "`n[Tier 3/3] Install step skipped by parameter." -ForegroundColor Cyan
}

Pop-Location
Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host " FAST-FAIL LOOP COMPLETED SUCCESSFULLY!" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
