<#
.SYNOPSIS
Regenerates native-android/app/src/main/baseline-prof.txt on a connected TV.

.DESCRIPTION
Builds the "profiling" variant (release code without R8, application ID
com.dreamcast.app.profiling, so it installs next to a published release), lets
:baselineprofile walk the app with remote keys, and copies the result into the
app sources. Commit the updated baseline-prof.txt.

Needs one TV with Android 13+ over adb (the Streamer works; Android 11 does not),
network access to the site, and the dav1d sources prepared for a normal build.
A run takes 10-20 minutes; the app opens and moves by itself on the TV.
Regenerate after larger UI changes, not on every build.

.EXAMPLE
./scripts/baseline_profile.ps1 -Device <tv-ip>:5555
#>
param(
    [Parameter(Mandatory = $true)][string]$Device
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$android = Join-Path $root 'native-android'
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem (Join-Path $root '.tools/jdk17') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'

& $adb connect $Device | Out-Null
$sdk = [int]((& $adb -s $Device shell getprop ro.build.version.sdk) -join '').Trim()
if ($sdk -lt 33) { throw "Device $Device runs API $sdk; Baseline Profile generation needs Android 13 (API 33) or newer." }

$output = Join-Path $android 'baselineprofile/build/outputs/connected_android_test_additional_output'
Remove-Item -Recurse -Force $output -ErrorAction SilentlyContinue

$env:ANDROID_SERIAL = $Device
Push-Location $android
try {
    & .\gradlew.bat :baselineprofile:connectedProfilingAndroidTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Profile generation failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

$profile = Get-ChildItem -Recurse $output -Filter '*-startup-prof.txt' |
    Where-Object { $_.Name -notmatch '\d{4}-\d{2}-\d{2}' } | Select-Object -First 1
if (-not $profile) { throw "No profile found under $output" }
$target = Join-Path $android 'app/src/main/baseline-prof.txt'
Copy-Item $profile.FullName $target -Force
$rules = (Get-Content $target | Measure-Object -Line).Lines
$own = (Select-String -Path $target -Pattern 'com/dreamcast' | Measure-Object).Count
Write-Host "Updated $target ($rules rules, $own from Dream Cast). Commit it."
