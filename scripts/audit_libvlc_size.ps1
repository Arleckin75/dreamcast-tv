param(
    [Parameter(Mandatory = $true)][string]$Archive,
    [Parameter(Mandatory = $true)][string]$Report
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-ElfSections([byte[]]$Payload) {
    if ($Payload.Length -lt 64 -or $Payload[0] -ne 127 -or
        [Text.Encoding]::ASCII.GetString($Payload, 1, 3) -ne 'ELF' -or $Payload[5] -ne 1) {
        throw 'Expected a little-endian ELF library'
    }
    $is64 = $Payload[4] -eq 2
    if (-not $is64 -and $Payload[4] -ne 1) { throw 'Unsupported ELF class' }
    if ($is64) {
        $table = [BitConverter]::ToUInt64($Payload, 40)
        $stride = [BitConverter]::ToUInt16($Payload, 58)
        $count = [BitConverter]::ToUInt16($Payload, 60)
        $namesIndex = [BitConverter]::ToUInt16($Payload, 62)
    } else {
        $table = [BitConverter]::ToUInt32($Payload, 32)
        $stride = [BitConverter]::ToUInt16($Payload, 46)
        $count = [BitConverter]::ToUInt16($Payload, 48)
        $namesIndex = [BitConverter]::ToUInt16($Payload, 50)
    }
    $minimumStride = if ($is64) { 64 } else { 40 }
    if ($stride -lt $minimumStride -or $count -eq 0 -or $namesIndex -ge $count -or
        $table + [long]$count * $stride -gt $Payload.Length) { throw 'Invalid ELF section table' }
    $headers = for ($index = 0; $index -lt $count; $index++) {
        $offset = [int]($table + $index * $stride)
        $fileOffset = if ($is64) { [BitConverter]::ToUInt64($Payload, $offset + 24) } else { [BitConverter]::ToUInt32($Payload, $offset + 16) }
        $size = if ($is64) { [BitConverter]::ToUInt64($Payload, $offset + 32) } else { [BitConverter]::ToUInt32($Payload, $offset + 20) }
        [pscustomobject]@{ NameIndex = [BitConverter]::ToUInt32($Payload, $offset); Offset = $fileOffset; Size = $size }
    }
    $names = $headers[$namesIndex]
    if ($names.Offset + $names.Size -gt $Payload.Length) { throw 'Invalid ELF string table' }
    foreach ($header in $headers) {
        if ($header.NameIndex -ge $names.Size) { throw 'Invalid ELF section name' }
        $start = [int]($names.Offset + $header.NameIndex)
        $end = $start
        while ($end -lt $names.Offset + $names.Size -and $Payload[$end] -ne 0) { $end++ }
        [pscustomobject]@{ Name = [Text.Encoding]::ASCII.GetString($Payload, $start, $end - $start); Size = [long]$header.Size }
    }
}

$archiveFile = Get-Item -LiteralPath $Archive
$zip = [IO.Compression.ZipFile]::OpenRead($archiveFile.FullName)
try {
    $native = @(foreach ($entry in $zip.Entries) {
        if ($entry.FullName -notmatch '^(?:jni|lib)/([^/]+)/(libvlc\.so|libvlcjni\.so|libc\+\+_shared\.so)$') { continue }
        $abi = $Matches[1]
        $library = $Matches[2]
        $buffer = [IO.MemoryStream]::new()
        $stream = $entry.Open()
        try {
            $stream.CopyTo($buffer)
            $sections = @(Get-ElfSections $buffer.ToArray())
        } finally { $stream.Dispose(); $buffer.Dispose() }
        [pscustomobject]@{
            abi = $abi
            library = $library
            bytes = $entry.Length
            compressedBytes = $entry.CompressedLength
            debugSectionBytes = [long](($sections | Where-Object { $_.Name -match '^\.(?:debug_|zdebug_)' } | Measure-Object Size -Sum).Sum)
            staticSymbolBytes = [long](($sections | Where-Object { $_.Name -in @('.symtab', '.strtab') } | Measure-Object Size -Sum).Sum)
        }
    })
    if (-not @($native | Where-Object { $_.library -eq 'libvlc.so' }).Count) { throw 'LibVLC was not found in the archive' }
    $assets = @($zip.Entries | Where-Object { $_.FullName -match '^assets/(lua|hrtfs)/' -and $_.Length -gt 0 })
    $result = [ordered]@{
        archive = $archiveFile.Name
        archiveBytes = $archiveFile.Length
        archiveSha256 = (Get-FileHash -LiteralPath $archiveFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        nativeLibraries = $native
        libvlcAssetFiles = $assets.Count
        libvlcAssetBytes = [long](($assets | Measure-Object Length -Sum).Sum)
        libvlcAssetCompressedBytes = [long](($assets | Measure-Object CompressedLength -Sum).Sum)
        note = 'AAR sizes are not APK savings. Inspect the packaged APK to see what Gradle already strips and compresses.'
    }
} finally { $zip.Dispose() }
$reportFile = [IO.Path]::GetFullPath($Report)
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($reportFile)) | Out-Null
[IO.File]::WriteAllText($reportFile, ($result | ConvertTo-Json -Depth 6) + "`n", [Text.UTF8Encoding]::new($false))
$native | Format-Table abi,library,bytes,compressedBytes,debugSectionBytes,staticSymbolBytes -AutoSize
Write-Output "LibVLC size report: $reportFile"
