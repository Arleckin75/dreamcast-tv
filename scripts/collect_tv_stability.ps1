param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Package = 'com.dreamcast.app',
    [ValidateRange(15, 7200)][int]$Seconds = 600,
    [ValidateRange(5, 60)][int]$IntervalSeconds = 15,
    [string]$OutputDirectory = '.tools/tv-stability',
    [string]$Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
)
$ErrorActionPreference = 'Stop'
if ($Package -notmatch '^[a-zA-Z0-9_.]+$') { throw 'Invalid package name' }
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw 'Use a new output directory for each measurement' }
New-Item -ItemType Directory -Path $output -Force | Out-Null
function Invoke-AdbRead([string[]]$Arguments) {
    $result = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return $result -join "`n"
}
function Read-Number($Text, $Pattern) {
    $match = [regex]::Match($Text, $Pattern)
    if ($match.Success) { return [long]$match.Groups[1].Value }
    return $null
}
$metadata = [ordered]@{
    serial = $Serial
    package = $Package
    startedAt = [DateTime]::UtcNow.ToString('o')
    packageVersion = (Invoke-AdbRead @('shell', 'dumpsys', 'package', $Package)) -split "`n" |
        Where-Object { $_ -match 'versionName=|versionCode=' }
    note = 'Read-only observation of the installed build. No APK installation, force-stop, input events or log clearing.'
}
$metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'metadata.json') -Encoding UTF8
$samples = [Collections.Generic.List[object]]::new()
$clock = [Diagnostics.Stopwatch]::StartNew()
do {
    $index = $samples.Count
    $memory = Invoke-AdbRead @('shell', 'dumpsys', 'meminfo', $Package)
    $activity = Invoke-AdbRead @('shell', 'dumpsys', 'activity', 'activities')
    $resumed = ($activity -split "`n" | Where-Object { $_ -match 'mResumedActivity:|topResumedActivity=' }) -join ' '
    $sample = [pscustomobject]@{
        utc = [DateTime]::UtcNow.ToString('o')
        elapsedSeconds = [math]::Round($clock.Elapsed.TotalSeconds, 1)
        pid = Read-Number $memory '\*\* MEMINFO in pid (\d+)'
        totalPssKb = Read-Number $memory 'TOTAL PSS:\s*(\d+)'
        nativeHeapPssKb = Read-Number $memory '(?m)^\s*Native Heap\s+(\d+)'
        totalSwapPssKb = Read-Number $memory 'TOTAL SWAP PSS:\s*(\d+)'
        activities = Read-Number $memory 'Activities:\s*(\d+)'
        resumedActivity = $resumed.Trim()
    }
    $samples.Add($sample)
    $memory | Set-Content -LiteralPath (Join-Path $output ("meminfo-{0:D3}.txt" -f $index)) -Encoding UTF8
    $samples | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output 'samples.json') -Encoding UTF8
    Write-Output ("{0}s pid={1} PSS={2}KB swap={3}KB {4}" -f $sample.elapsedSeconds,$sample.pid,$sample.totalPssKb,$sample.totalSwapPssKb,$sample.resumedActivity)
    $left = $Seconds - $clock.Elapsed.TotalSeconds
    if ($left -le 0) { break }
    Start-Sleep -Seconds ([math]::Min($IntervalSeconds, [math]::Ceiling($left)))
} while ($true)
$graphics = Invoke-AdbRead @('shell', 'dumpsys', 'gfxinfo', $Package)
$graphics | Set-Content -LiteralPath (Join-Path $output 'gfxinfo.txt') -Encoding UTF8
Write-Output "Saved $($samples.Count) samples to $output. Compare only intervals with the same PID and playback/activity state."
