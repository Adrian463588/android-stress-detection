# capture-ui-tree.ps1: Dump Android UI Accessibility Tree without heavy screenshots
[CmdletBinding()]
param (
    [string]$Serial = "emulator-5562",
    [string]$OutputPath = "$PSScriptRoot\..\..\build\reports\ui_tree.xml"
)

$ErrorActionPreference = "Stop"

$sdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adbPath = Join-Path $sdkPath "platform-tools\adb.exe"

$parentDir = Split-Path $OutputPath -Parent
if (-not (Test-Path $parentDir)) {
    New-Item -ItemType Directory -Path $parentDir -Force | Out-Null
}

Write-Host "Dumping UI tree from $Serial..." -ForegroundColor Cyan
& $adbPath -s $Serial shell uiautomator dump /sdcard/ui_dump.xml | Out-Null
& $adbPath -s $Serial pull /sdcard/ui_dump.xml $OutputPath | Out-Null

if (-not (Test-Path $OutputPath)) {
    Write-Error "Failed to pull UI dump."
    exit 1
}

Write-Host "[OK] UI tree saved to $OutputPath" -ForegroundColor Green
Write-Host "`n--- Interactive UI Elements Summary ---" -ForegroundColor Yellow

[xml]$xml = Get-Content $OutputPath
$nodes = $xml.SelectNodes("//node[@text!='' or @content-desc!='' or @clickable='true']")

foreach ($n in $nodes) {
    $text = if ($n.text) { "Text: '$($n.text)'" } else { "" }
    $desc = if ($n.'content-desc') { "Desc: '$($n.'content-desc')'" } else { "" }
    $clickable = if ($n.clickable -eq "true") { "[CLICKABLE]" } else { "" }
    $class = ($n.class -split '\.')[-1]
    $bounds = $n.bounds
    
    $label = "$class $clickable $text $desc (Bounds: $bounds)".Trim()
    if ($label) {
        Write-Host " -> $label"
    }
}
Write-Host "---------------------------------------" -ForegroundColor Yellow
