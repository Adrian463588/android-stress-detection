# status-emulator.ps1: Inspect headless Android Emulator status and resource footprint
[CmdletBinding()]
param (
    [int]$Port = 5562
)

$sdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adbPath = Join-Path $sdkPath "platform-tools\adb.exe"
$targetSerial = "emulator-$Port"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " Headless Android Emulator Status Check ($targetSerial)" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Process info
$procs = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -match "qemu|emulator" }
if ($procs) {
    Write-Host "`n[Running Processes]:" -ForegroundColor Green
    foreach ($p in $procs) {
        $memMb = [math]::Round($p.WorkingSet64 / 1MB, 2)
        Write-Host " - PID: $($p.Id) | Process: $($p.ProcessName) | RAM WorkingSet: ${memMb} MB"
    }
} else {
    Write-Host "`n[Processes]: No active emulator processes detected." -ForegroundColor Yellow
}

# 2. ADB Device info
if (Test-Path $adbPath) {
    Write-Host "`n[ADB Devices Attached]:" -ForegroundColor Green
    & $adbPath devices
    
    $rawBoot = & $adbPath -s $targetSerial shell getprop sys.boot_completed 2>$null
    $bootCompleted = if ($rawBoot) { "$rawBoot".Trim() } else { "0" }
    
    if ($bootCompleted -eq "1") {
        $androidVer = (& $adbPath -s $targetSerial shell getprop ro.build.version.release 2>$null).Trim()
        $model = (& $adbPath -s $targetSerial shell getprop ro.product.model 2>$null).Trim()
        $apiLevel = (& $adbPath -s $targetSerial shell getprop ro.build.version.sdk 2>$null).Trim()
        
        Write-Host "`n[Device Properties]:" -ForegroundColor Green
        Write-Host " - Model        : $model"
        Write-Host " - Android OS   : $androidVer (API $apiLevel)"
        Write-Host " - Boot State   : READY (sys.boot_completed=1)"
    } else {
        Write-Host "`n[Device Properties]: Device is offline or still booting." -ForegroundColor Yellow
    }
} else {
    Write-Warning "ADB not found at $adbPath"
}
Write-Host "==========================================================" -ForegroundColor Cyan
