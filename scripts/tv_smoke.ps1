<#
.SYNOPSIS
Walks Dream Cast TV on a connected TV with remote keys and saves a screenshot after each step.

.DESCRIPTION
A quick look after a build: home and banner, a release page and its episode list, the
catalog, every section of the top menu and the settings screens. Open the PNGs in the
output folder in order and check that every screen opened and nothing is clipped.

Before every key press the script checks that Dream Cast is the focused app. If anything
else is in front (a Back that left the app, a system dialog), it stops instead of pressing
keys in another app.

-Player also opens episode 1 and tries seeking with the preview; this saves a watch
position in that build's history, so use it with a test build rather than the one you
watch with. Screenshots stay on this computer.

.EXAMPLE
./scripts/tv_smoke.ps1 -Device 192.168.0.10:5555 -Package com.dreamcast.app.kotlin -Player
#>
param(
    [Parameter(Mandatory = $true)][string]$Device,
    [string]$Package = 'com.dreamcast.app',
    [string]$OutDir = (Join-Path (Split-Path -Parent $PSScriptRoot) "dist/tv-smoke-$(Get-Date -Format 'yyyyMMdd-HHmmss')"),
    [switch]$Player,
    [double]$LoadSeconds = 5
)
$ErrorActionPreference = 'Stop'
# Menu labels are Russian: read adb output as UTF-8. (This file is saved with a BOM so
# Windows PowerShell 5.1 reads the labels below correctly.)
[Console]::OutputEncoding = [Text.Encoding]::UTF8
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'

# adb and monkey write progress to stderr; Windows PowerShell would treat that as an error.
function Adb { $ErrorActionPreference = 'Continue'; & $adb -s $Device @args 2>$null }
function Focused { (Adb shell dumpsys window) -match 'mCurrentFocus' | Select-Object -First 1 }
function Assert-InApp([string]$step) {
    $focus = Focused
    if ($focus -notmatch [regex]::Escape("$Package/")) {
        throw "Stopped before '$step': Dream Cast is not in front ($focus). No keys were sent to other apps."
    }
}

$script:step = 0
function Shot([string]$name, [double]$wait = 1.2) {
    Start-Sleep -Milliseconds ([int]($wait * 1000))
    $script:step++
    $file = Join-Path $OutDir ('{0:D2}_{1}.png' -f $script:step, $name)
    $tmp = '/data/local/tmp/dreamcast-smoke.png'
    Adb shell screencap -p $tmp | Out-Null
    Adb pull $tmp $file | Out-Null
    Adb shell rm $tmp | Out-Null
    Write-Host "  $([IO.Path]::GetFileName($file))"
}
$codes = @{ UP = 19; DOWN = 20; LEFT = 21; RIGHT = 22; OK = 23; BACK = 4 }
function Keys([string]$step, [string[]]$names) {
    foreach ($name in $names) {
        Assert-InApp $step
        Adb shell input keyevent $codes[$name] | Out-Null
        Start-Sleep -Milliseconds 450
    }
}

# The screen as uiautomator sees it: the top-menu icons (by label, left to right) and the
# bounds of the focused element.
$menuLabels = @('Каталог', 'Поиск', 'Уведомления', 'Библиотека', 'Расписание', 'Настройки')
function Read-Screen {
    $dump = '/data/local/tmp/dreamcast-smoke.xml'
    Adb shell uiautomator dump $dump | Out-Null
    $xml = (Adb shell cat $dump) -join ''
    Adb shell rm $dump | Out-Null
    $nodes = [regex]::Matches($xml, 'content-desc="([^"]*)"[^>]*?focused="(true|false)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') | ForEach-Object {
        [pscustomobject]@{
            Label = $_.Groups[1].Value; Focused = $_.Groups[2].Value -eq 'true'
            Left = [int]$_.Groups[3].Value; Top = [int]$_.Groups[4].Value
            Right = [int]$_.Groups[5].Value; Bottom = [int]$_.Groups[6].Value
        }
    }
    $menu = foreach ($label in $menuLabels) {
        $nodes | Where-Object { $_.Label -like "$label*" } | Select-Object -First 1
    }
    [pscustomobject]@{ Menu = @($menu); Focus = $nodes | Where-Object Focused | Select-Object -Last 1 }
}
# Index of the menu icon that contains the focused element, or -1.
function Menu-Focus($screen) {
    if (-not $screen.Focus) { return -1 }
    $x = ($screen.Focus.Left + $screen.Focus.Right) / 2; $y = ($screen.Focus.Top + $screen.Focus.Bottom) / 2
    for ($i = 0; $i -lt $screen.Menu.Count; $i++) {
        $m = $screen.Menu[$i]
        if ($m -and $x -ge $m.Left - 8 -and $x -le $m.Right + 8 -and $y -ge $m.Top - 8 -and $y -le $m.Bottom + 8) { return $i }
    }
    return -1
}
# Moves focus up into the top menu, then along it to $label, and presses OK.
function Open-Tab([string]$label) {
    $target = [array]::IndexOf($menuLabels, $label)
    for ($attempt = 0; $attempt -lt 2; $attempt++) {
        for ($i = 0; $i -lt 8; $i++) {
            $at = Menu-Focus (Read-Screen)
            if ($at -ge 0) { break }
            Keys $label @('UP')
        }
        if ($at -ge 0) { break }
        # Up did not reach the menu from this screen: try again from the home screen.
        Write-Warning "Up does not reach the top menu here; retrying '$label' from home"
        Keys $label @('BACK'); Start-Sleep -Seconds 1
    }
    if ($at -lt 0) { throw "Could not move focus to the top menu for '$label'" }
    for ($i = 0; $i -lt 12 -and $at -ne $target; $i++) {
        Keys $label @($(if ($at -lt $target) { 'RIGHT' } else { 'LEFT' }))
        $at = Menu-Focus (Read-Screen)
    }
    if ($at -ne $target) { throw "Could not reach '$label' in the top menu" }
    Keys $label @('OK')
}

New-Item -ItemType Directory -Force $OutDir | Out-Null
& $adb connect $Device | Out-Null
if (-not ((Adb shell pm list packages $Package) -match "package:$([regex]::Escape($Package))$")) {
    throw "$Package is not installed on $Device"
}
Adb logcat -c | Out-Null
Adb shell am force-stop $Package | Out-Null
Adb shell monkey -p $Package -c android.intent.category.LEANBACK_LAUNCHER 1 | Out-Null
Start-Sleep -Seconds ([int][Math]::Ceiling($LoadSeconds + 2))
Write-Host "Screenshots -> $OutDir"

Assert-InApp 'launch'; Shot 'home' 0
Keys 'banner' @('RIGHT'); Shot 'banner_next'
Keys 'banner' @('LEFT')

Keys 'release' @('OK'); Shot 'release' $LoadSeconds
Keys 'episodes' @('OK'); Shot 'episodes' $LoadSeconds
Keys 'episodes' @('DOWN', 'DOWN', 'DOWN', 'DOWN', 'DOWN', 'DOWN', 'DOWN', 'DOWN'); Shot 'episodes_scrolled'
Keys 'episodes' @('UP', 'UP', 'UP', 'UP', 'UP', 'UP', 'UP', 'UP'); Shot 'episodes_top'

if ($Player) {
    Keys 'player' @('OK'); Shot 'player' ($LoadSeconds + 5)
    Keys 'seek' @('RIGHT', 'RIGHT', 'RIGHT'); Shot 'seek_preview' 1.5
    Keys 'seek' @('BACK'); Shot 'seek_cancelled'
    Keys 'player' @('BACK'); Keys 'player' @('BACK'); Shot 'player_closed' 2
}

Keys 'back' @('BACK'); Shot 'release_again'
Keys 'back' @('BACK'); Shot 'home_again'

Keys 'catalog' @('DOWN', 'DOWN', 'DOWN', 'OK'); Shot 'catalog' $LoadSeconds
Keys 'catalog' @('DOWN', 'DOWN', 'RIGHT', 'RIGHT'); Shot 'catalog_scrolled'
Keys 'catalog' @('OK'); Shot 'catalog_release' $LoadSeconds
Keys 'catalog' @('BACK'); Shot 'catalog_again'

# Top menu, left to right: catalog, search, notifications, library, schedule, settings.
# Top menu sections are opened by their label (the icons' content descriptions), not by
# arrow keys: from some screens Up does not reach the menu, and the walk would drift.
# Search goes last: its text field opens the on-screen keyboard whenever it gets focus.
foreach ($tab in 'Уведомления', 'Библиотека', 'Расписание', 'Настройки', 'Поиск') {
    Open-Tab $tab
    Shot $tab $LoadSeconds
    if ($tab -eq 'Настройки') {
        Keys 'settings' @('DOWN', 'DOWN', 'DOWN', 'DOWN', 'DOWN', 'DOWN'); Shot 'settings_scrolled'
    }
}
Keys 'search' @('BACK'); Shot 'search_keyboard_closed'

$crashes = Adb logcat -d -s 'AndroidRuntime:E' | Select-String -Pattern $Package
if ($crashes) { Write-Warning "Crash in logcat:"; $crashes | Select-Object -First 20 | ForEach-Object { Write-Host $_ } }
else { Write-Host "No crashes in logcat. $script:step screenshots in $OutDir" }
