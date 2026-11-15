# stop-emulator.ps1: Gracefully terminate headless Android Emulator
[CmdletBinding()]
param (
    [int]$Port = 5562
)

$ErrorActionPreference = "SilentlyContinue"

$sdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adbPath = Join-Path $sdkPath "platform-tools\adb.exe"
$targetSerial = "emulator-$Port"

Write-Host "Stopping headless emulator $targetSerial..." -ForegroundColor Yellow

if (Test-Path $adbPath) {
    & $adbPath -s $targetSerial emu kill
}

Start-Sleep -Seconds 2

# Check if QEMU process still lingers
$qemuProcs = Get-CimInstance Win32_Process -Filter "name LIKE '%qemu%'" | Where-Object { $_.CommandLine -match "port $Port" -or $_.CommandLine -match "emulator-$Port" }
foreach ($p in $qemuProcs) {
    Write-Host "Terminating lingering process PID $($p.ProcessId)..." -ForegroundColor Yellow
    Stop-Process -Id $p.ProcessId -Force
}

Write-Host "[OK] Headless emulator $targetSerial stopped." -ForegroundColor Green
