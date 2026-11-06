param(
    [ValidateSet('debug','release')][string]$Configuration = 'debug',
    [switch]$Test
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    if (-not $env:JAVA_HOME) {
        $localJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools/jdk17') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
    }
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    $buildType = if ($Configuration -eq 'release') { 'Release' } else { 'Debug' }
    $target = if ($Test) { ":app:test${buildType}UnitTest" } else { ":app:assemble${buildType}" }
    & ./native-android/gradlew.bat -p native-android $target --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)" }
} finally { Pop-Location }
