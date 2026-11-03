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
$patchPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../third_party/libvlc_tv/profile.patch'))
& git -C $sourceDirectory apply --check $patchPath
if ($LASTEXITCODE -ne 0) { throw 'TV profile patch does not match upstream sources' }
& git -C $sourceDirectory apply $patchPath
if ($LASTEXITCODE -ne 0) { throw 'Could not apply TV profile patch' }
$patched = [IO.File]::ReadAllText($scriptPath)
foreach ($required in @('--enable-avcodec', '--enable-avformat', '--enable-swscale', '--enable-opus', '--enable-opensles', '--enable-gles2', '--enable-libass')) {
    if (-not $patched.Contains($required)) { throw "Required playback feature missing: $required" }
}
if (-not $patched.Contains('export USE_FFMPEG=1')) { throw 'FFmpeg decoding must remain enabled' }
Write-Output "Prepared experimental TV profile at $sourceDirectory"
Write-Output "Applied shared profile: $patchPath"
Write-Output 'AV1/audio decoders, HTTP/TLS, adaptive demux, MP4, seeking and Android outputs are not deliberately disabled.'
Write-Output 'No AAR was built. Validate both ARM ABIs and playback before changing the app dependency.'
