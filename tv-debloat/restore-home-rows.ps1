$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$serial = '192.168.0.9:5555'
function Read-Ui {
    & $adb -s $serial shell uiautomator dump /sdcard/tv-debloat-restore.xml | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect TV screen' }
    $localUi = Join-Path $PSScriptRoot 'restore-ui.xml'
    & $adb -s $serial pull /sdcard/tv-debloat-restore.xml $localUi | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read TV screen' }
    [xml](Get-Content -LiteralPath $localUi -Raw -Encoding UTF8)
}
function Tap-Node($node) {
    if ($node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'Missing UI bounds' }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    & $adb -s $serial shell input tap $x $y | Out-Null
}
function Find-Label($ui, $pattern) {
    $ui.SelectNodes('//node') | Where-Object { $_.text -match $pattern } | Select-Object -First 1
}
function Enable-Row($pattern) {
    $ui = Read-Ui
    $label = Find-Label $ui $pattern
    if (-not $label) { throw "Cannot find row: $pattern" }
    $parent = $label.ParentNode
    $switch = $null
    while ($parent -and -not $switch) {
        $switch = $parent.SelectSingleNode('.//node[@checkable="true"]')
        if (-not $switch) { $parent = $parent.ParentNode }
    }
    if (-not $switch) { throw "Cannot find switch: $pattern" }
    if ($switch.checked -eq 'false') { & $adb -s $serial shell input keyevent 23 | Out-Null }
    $ui = Read-Ui
    $label = Find-Label $ui $pattern
    $parent = $label.ParentNode
    while ($parent -and -not $parent.SelectSingleNode('.//node[@checkable="true"]')) { $parent = $parent.ParentNode }
    if (-not $parent -or $parent.SelectSingleNode('.//node[@checkable="true"]').checked -ne 'true') { throw "Row did not enable: $pattern" }
}
& $adb -s $serial shell input keyevent 3 | Out-Null
& $adb -s $serial shell input keyevent 19 19 19 19 19 19 19 19 19 19 19 19 19 19 19 | Out-Null
& $adb -s $serial shell input keyevent 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 | Out-Null
& $adb -s $serial shell input keyevent 23 | Out-Null
$ui = Read-Ui
if (-not (Find-Label $ui '^Lampa TV$')) { throw 'Cannot find channel settings' }
& $adb -s $serial shell input keyevent 19 19 19 19 19 19 19 19 19 19 | Out-Null
& $adb -s $serial shell input keyevent 20 23 | Out-Null
& $adb -s $serial shell input keyevent 20 | Out-Null
Enable-Row '^Рекомендации$|^Recommendations$'
& $adb -s $serial shell input keyevent 4 | Out-Null
& $adb -s $serial shell input keyevent 19 19 19 19 19 19 19 19 19 19 | Out-Null
& $adb -s $serial shell input keyevent 20 20 20 | Out-Null
Enable-Row '^Spotify$'
& $adb -s $serial shell input keyevent 3 | Out-Null

