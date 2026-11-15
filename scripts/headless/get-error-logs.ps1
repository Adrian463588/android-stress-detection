# get-error-logs.ps1: Extract concise, token-efficient Android error logs
[CmdletBinding()]
param (
    [string]$Serial = "emulator-5562",
    [int]$Lines = 40,
    [switch]$Clear
)

$sdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adbPath = Join-Path $sdkPath "platform-tools\adb.exe"

if ($Clear) {
    Write-Host "Clearing logcat buffer on $Serial..." -ForegroundColor Yellow
    & $adbPath -s $Serial logcat -c
    exit 0
}

Write-Host "Fetching recent error logs (*:E) from $Serial..." -ForegroundColor Cyan
$logs = & $adbPath -s $Serial logcat *:E -d | Select-Object -Last $Lines

if ($logs) {
    Write-Host "=================== LOGCAT ERROR DUMP ===================" -ForegroundColor Red
    $logs | ForEach-Object { Write-Host $_ }
    Write-Host "=========================================================" -ForegroundColor Red
} else {
    Write-Host "[OK] No recent error logs found in logcat buffer." -ForegroundColor Green
}
