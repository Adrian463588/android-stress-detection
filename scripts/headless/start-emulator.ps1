# start-emulator.ps1: Launch lightweight headless Android AOSP Emulator
[CmdletBinding()]
param (
    [string]$AvdName = "codex_psikotes_api33",
    [int]$Port = 5562,
    [int]$MemoryMb = 1536,
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"

$sdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$emulatorPath = Join-Path $sdkPath "emulator\emulator.exe"
$adbPath = Join-Path $sdkPath "platform-tools\adb.exe"

if (-not (Test-Path $emulatorPath)) {
    Write-Error "Emulator not found at: $emulatorPath"
    exit 1
}

$targetSerial = "emulator-$Port"

# Check if already running
$runningDevices = & $adbPath devices
if ($runningDevices -match "$targetSerial\s+device") {
    Write-Host "[OK] $targetSerial is ALREADY running and online." -ForegroundColor Green
    exit 0
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "Starting Headless Android Emulator" -ForegroundColor Cyan
Write-Host "AVD     : $AvdName" -ForegroundColor Cyan
Write-Host "Serial  : $targetSerial" -ForegroundColor Cyan
Write-Host "Memory  : ${MemoryMb}MB (Ultra-Lightweight clamped)" -ForegroundColor Cyan
Write-Host "Mode    : -no-window -no-audio -no-boot-anim (SwiftShader)" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$arguments = @(
    "-avd", $AvdName,
    "-no-window",
    "-no-audio",
    "-no-boot-anim",
    "-gpu", "swiftshader_indirect",
    "-memory", "$MemoryMb",
    "-port", "$Port"
)

$process = Start-Process -FilePath $emulatorPath -ArgumentList $arguments -PassThru -NoNewWindow
Write-Host "Emulator process launched with PID $($process.Id)."

Write-Host "Waiting for device connection ($targetSerial)..."
$elapsed = 0
$booted = $false

while ($elapsed -lt $TimeoutSeconds) {
    Start-Sleep -Seconds 3
    $elapsed += 3
    
    $devices = & $adbPath devices
    if ($devices -match "$targetSerial\s+device") {
        $bootState = & $adbPath -s $targetSerial shell getprop sys.boot_completed 2>$null
        if ($bootState.Trim() -eq "1") {
            $booted = $true
            break
        } else {
            Write-Host "Device detected, waiting for boot completion... ($elapsed s)"
        }
    } else {
        Write-Host "Waiting for ADB attachment... ($elapsed s)"
    }
}

if ($booted) {
    Write-Host "[SUCCESS] $targetSerial is fully booted and ready for headless operations!" -ForegroundColor Green
} else {
    Write-Warning "Timeout waiting for $targetSerial boot completion ($TimeoutSeconds s)."
}
