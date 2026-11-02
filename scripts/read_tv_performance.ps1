param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$OutputDirectory = '.tools/tv-performance',
    [string]$Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
)
$ErrorActionPreference = 'Stop'
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw 'Use a new output directory for each collection' }
# Read only: do not clear logcat, launch/stop the app or change device settings.
$lines = & $Adb -s $Serial logcat -d -t 10000 -v epoch 2>&1
if ($LASTEXITCODE -ne 0) { throw ($lines -join "`n") }
$records = @($lines | ForEach-Object {
    if ($_ -match 'DCTV_PERF\s*:?\s+(\{.*\})\s*$') {
        $payload = $Matches[1]
        $raw = [string]$_
        try {
            $entry = $payload | ConvertFrom-Json
            if ($null -ne $entry.elapsedUs -and $entry.event) {
                [pscustomobject]@{
                    event = $entry.event
                    source = $entry.source
                    outcome = $entry.outcome
                    elapsedMs = [math]::Round([double]$entry.elapsedUs / 1000, 3)
                    logLine = $raw
                }
            }
        } catch { Write-Warning 'Skipped an incomplete performance record' }
    }
})
if ($records.Count -eq 0) { throw 'No DCTV_PERF records. Install a debug build with performance instrumentation, run the scenarios, then collect again.' }
New-Item -ItemType Directory -Path $output | Out-Null
$records | Export-Csv -LiteralPath (Join-Path $output 'records.csv') -NoTypeInformation -Encoding UTF8
$summary = $records | Group-Object event,source,outcome | ForEach-Object {
    $values = @($_.Group.elapsedMs | Sort-Object)
    $count = $values.Count
    $median = if ($count % 2) { $values[[int][math]::Floor($count / 2)] } else {
        ($values[$count / 2 - 1] + $values[$count / 2]) / 2
    }
    [pscustomobject]@{
        event = $_.Group[0].event
        source = $_.Group[0].source
        outcome = $_.Group[0].outcome
        samples = $count
        medianMs = [math]::Round($median, 3)
        p90Ms = $values[[int][math]::Ceiling($count * 0.9) - 1]
        minMs = $values[0]
        maxMs = $values[-1]
    }
}
$summary | Export-Csv -LiteralPath (Join-Path $output 'summary.csv') -NoTypeInformation -Encoding UTF8
$summary | Format-Table -AutoSize
Write-Output 'Records include retained log history and possibly multiple processes/builds. Use records.csv timestamps/PIDs to select one device, build and scenario before comparing. Do not merge repeated collections.'
