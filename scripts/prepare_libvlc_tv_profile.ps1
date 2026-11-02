param([Parameter(Mandatory = $true)][string]$SourceRoot)
$ErrorActionPreference = 'Stop'

# This prepares upstream sources only. It neither builds nor installs an AAR.
$expectedRevision = '0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6'
$sourceDirectory = (Get-Item -LiteralPath $SourceRoot).FullName
$revision = & git -C $sourceDirectory rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $revision -ne $expectedRevision) {
    throw "Expected official libvlcjni revision $expectedRevision"
}
$relativeScript = 'buildsystem/compile-libvlc.sh'
& git -C $sourceDirectory diff --quiet HEAD -- $relativeScript
if ($LASTEXITCODE -ne 0) { throw 'The upstream build script already has changes; use a clean checkout' }
$scriptPath = [IO.Path]::GetFullPath((Join-Path $sourceDirectory $relativeScript))
if (-not $scriptPath.StartsWith($sourceDirectory + [IO.Path]::DirectorySeparatorChar, [StringComparison]::Ordinal)) {
    throw 'Build script is outside the selected source directory'
}
$original = [IO.File]::ReadAllText($scriptPath)
$patched = $original
$features = @('lua', 'upnp', 'libdsm', 'smb2', 'nfs', 'microdns', 'libarchive', 'bluray', 'dvdread', 'live555', 'sout', 'mod', 'gme', 'fluidlite')
foreach ($feature in $features) {
    $pattern = '--enable-' + [regex]::Escape($feature) + '(?![A-Za-z0-9_-])'
    if (-not [regex]::IsMatch($patched, $pattern)) { throw "Missing expected upstream flag for $feature" }
    $patched = [regex]::Replace($patched, $pattern, "--disable-$feature")
}
foreach ($required in @('--enable-avcodec', '--enable-avformat', '--enable-swscale', '--enable-opus', '--enable-opensles', '--enable-gles2', '--enable-libass')) {
    if (-not $patched.Contains($required)) { throw "Required playback feature missing: $required" }
}
if (-not $patched.Contains('export USE_FFMPEG=1')) { throw 'FFmpeg decoding must remain enabled' }
[IO.File]::WriteAllText($scriptPath, $patched, [Text.UTF8Encoding]::new($false))
Write-Output "Prepared experimental TV profile at $sourceDirectory"
Write-Output "Disabled features: $($features -join ', ')"
Write-Output 'AV1/audio decoders, HTTP/TLS, adaptive demux, MP4, seeking and Android outputs are not deliberately disabled.'
Write-Output 'No AAR was built. Validate both ARM ABIs and playback before changing the app dependency.'
