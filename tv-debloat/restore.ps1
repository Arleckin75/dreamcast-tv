$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$serial = '192.168.0.9:5555'
& $adb -s $serial shell pm enable --user 0 com.zzggsoft.bluetoothtvhider
& $adb -s $serial shell pm enable --user 0 com.waylonhuang.bluetoothpair
& $adb -s $serial shell pm enable --user 0 com.vestel.vestelreporter
& $adb -s $serial shell pm enable --user 0 fusion.android.tv.demo
& $adb -s $serial shell pm enable --user 0 com.google.android.tvrecommendations
& (Join-Path $PSScriptRoot 'restore-home-rows.ps1')
