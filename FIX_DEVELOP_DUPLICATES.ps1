# Run from the root of the Dream Cast TV Git checkout after unpacking the updated source.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not (Test-Path -LiteralPath (Join-Path $root 'android/app/build.gradle.kts'))) {
    throw 'Run this script from the extracted project inside the Dream Cast TV Git repository.'
}
$relativeDebug = 'android/app/src/debug/kotlin/com/dreamcast/app'
$relativeMain  = 'android/app/src/main/kotlin/com/dreamcast/app'
$names = @('CosmosAv1PlayerActivity.kt', 'CosmosFmp4Resolver.kt', 'CosmosMedia3Probe.kt')
foreach ($name in $names) {
    $main = Join-Path (Join-Path $root $relativeMain) $name
    if (-not (Test-Path -LiteralPath $main)) { throw "Missing required main source: $main" }
}
foreach ($name in $names) {
    $old = Join-Path (Join-Path $root $relativeDebug) $name
    if (Test-Path -LiteralPath $old) {
        Remove-Item -LiteralPath $old -Force
        Write-Host "Deleted obsolete Debug duplicate: $name"
    } else {
        Write-Host "Already absent: $name"
    }
}
$activity = Get-Content -Raw -LiteralPath (Join-Path (Join-Path $root $relativeMain) 'CosmosAv1PlayerActivity.kt')
if ($activity -notmatch 'private val progressSaver\s*=') {
    throw 'progressSaver declaration missing from main activity; check project extraction.'
}
Write-Host 'OK: main player includes progressSaver; Debug duplicates have been removed.'
Write-Host 'Commit the three deletions in GitHub Desktop, then push develop.'
