# Run from the root of the develop working tree (GitHub Desktop local repository).
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$main = Join-Path $root 'android\app\src\main\kotlin\com\dreamcast\app'
$debug = Join-Path $root 'android\app\src\debug\kotlin\com\dreamcast\app'
$names = @('MainActivity.kt','CosmosAv1PlayerActivity.kt','CosmosFmp4Resolver.kt','CosmosMedia3Probe.kt')
foreach ($directory in @($main,$debug)) {
  foreach ($name in $names) {
    $target = Join-Path $directory $name
    if (Test-Path -LiteralPath $target -PathType Leaf) {
      Remove-Item -LiteralPath $target -Force
      Write-Host "Removed legacy Kotlin source: $target"
    }
  }
}
$flavors = @{
  'softwareAv1'=@('MainActivity.kt','CosmosAv1PlayerActivity.kt','CosmosFmp4Resolver.kt')
  'hardwareAv1'=@('MainActivity.kt','CosmosMedia3Probe.kt')
}
foreach ($flavor in $flavors.Keys) {
  foreach ($name in $flavors[$flavor]) {
    $path = Join-Path $root "android\app\src\$flavor\kotlin\com\dreamcast\app\$name"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing required $flavor source: $path" }
  }
}
Write-Host 'OK: legacy Kotlin sources removed; flavor-specific Kotlin sources present.'
Write-Host 'Review deletions in GitHub Desktop, commit them to develop, then rebuild both flavors.'
