[CmdletBinding()]
param(
    [string]$DownloadDirectory = 'G:/DOWNLOAD-EDGE',
    [string]$MarisaDirectory,
    [string]$MisakaDirectory = 'F:/MJ&CP',
    [string]$AssimpLoaderDir = (Join-Path $PSScriptRoot '../../../AssimpLoader'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../../build/default-model-pack/release'),
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$AvatarsOnly,
    [switch]$SourcesOnly
)

$ErrorActionPreference = 'Stop'
$rigRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$MarisaDirectory) { $MarisaDirectory = Join-Path 'F:/piack' ('"\u9727\u96e8\u9b54\u7406\u6c99"' | ConvertFrom-Json) }
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $output | Out-Null
$jobs = Join-Path $rigRoot 'build/default-model-pack/jobs'
New-Item -ItemType Directory -Force -Path $jobs | Out-Null
$readme = Join-Path $rigRoot 'docs/model-packs/README.md'
$releaseNotes = Join-Path $rigRoot 'docs/model-packs/RELEASE_NOTES.md'
$utf8 = New-Object Text.UTF8Encoding($false)

function AssetFile([string]$Source, [string]$Target) { return @{from=$Source; to=$Target} }
function AvatarJob([string]$Id, [string]$Model, [object[]]$Files) {
    return @{id=$Id; model=$Model; files=$Files; output=(Join-Path $output "$Id.rig.zip"); readme=$readme}
}

if (!$SourcesOnly) {
    $specs = @()
    $specs += @{archive=(Join-Path $rigRoot 'build/starpop-vmd/starpop-package-regression.rig.zip'); output=(Join-Path $output 'madoka-pixelized.rig.zip'); readme=$readme}
    $specs += @{archive=(Join-Path $rigRoot 'build/misaka/misaka.rig.zip'); output=(Join-Path $output 'misaka-pixelized.rig.zip'); readme=$readme}

    $exedra = Join-Path $rigRoot 'build/vmd-motion-preview/client-resourcepack/assets/rigworks/models/character/madoka_vmd'
    $job = AvatarJob 'madoka-exedra-hd' 'model.fbx' @(
        (AssetFile (Join-Path $exedra 'model.fbx') 'model.fbx'),
        (AssetFile (Join-Path $exedra 'profile.json') 'profile.json'),
        (AssetFile (Join-Path $exedra 'equipment.json') 'equipment.json'),
        (AssetFile (Join-Path $exedra 'rig.json') 'rig.json')
    )
    $job.blink = $true
    $specs += $job

    foreach ($costume in @('01','03')) {
        $folder = if ($costume -eq '01') { 'madoka_kaname' } else { 'madoka_kaname_03' }
        $vita = Join-Path $AssimpLoaderDir "run/resourcepacks/Madoka_Local/assets/madoka/models/$folder"
        $specs += (AvatarJob "madoka-vita-$costume" 'model.fbx' @(
            (AssetFile (Join-Path $vita 'model.fbx') 'model.fbx'),
            (AssetFile (Join-Path $vita 'profile.json') 'profile.json'),
            (AssetFile (Join-Path $vita 'equipment.json') 'equipment.json'),
            (AssetFile (Join-Path $vita 'model.instance.json') 'model.instance.json'),
            (AssetFile (Join-Path $vita 'textures') 'textures')
        ))
    }

    $lusia = Join-Path $DownloadDirectory 'lusia/assimploader_ready/resourcepack/assets/lusia/models/character/lusia'
    $job = AvatarJob 'lusia-hd-pbr' 'lusia.fbx' @(
        (AssetFile (Join-Path $lusia 'lusia.fbx') 'lusia.fbx'),
        (AssetFile (Join-Path $lusia 'profile.json') 'profile.json'),
        (AssetFile (Join-Path $lusia 'equipment.json') 'equipment.json'),
        (AssetFile (Join-Path $lusia 'lusia.instance.json') 'lusia.instance.json'),
        (AssetFile (Join-Path $lusia 'textures') 'textures')
    )
    $job.profileOverrides = @{animations=@{idle='n_idle'; walk='n_walk'; run='n_run'}}
    $specs += $job
    $jobPaths = @()
    for ($i=0; $i -lt $specs.Count; $i++) {
        $file = Join-Path $jobs "avatar-$i.json"
        [IO.File]::WriteAllText($file, ($specs[$i] | ConvertTo-Json -Depth 30), $utf8)
        $jobPaths += $file
    }
    & (Join-Path $PSScriptRoot 'Invoke-ReleaseAvatarPackage.ps1') -JobFile $jobPaths -AssimpLoaderDir $AssimpLoaderDir -JavaHome $JavaHome
}

if (!$AvatarsOnly) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $sources = @(
        @{id='marisa'; root=$MarisaDirectory; model=('"\u9727\u96e8\u9b54\u7406\u6c99R.pmx"' | ConvertFrom-Json)},
        @{id='misaka-mikoto'; root=$MisakaDirectory; localOnly=$true; model=('"\u5fa1\u5742\u7f8e\u7434Ver1.0 \u5168\u3066\u306e\u89aa.pmd"' | ConvertFrom-Json)}
    )
    $sourceJobs = @()
    foreach ($spec in $sources) {
        $root = (Resolve-Path -LiteralPath $spec.root).Path
        $sourceOutput = if ($spec.localOnly) { Join-Path $output 'local-only' } else { $output }
        New-Item -ItemType Directory -Force -Path $sourceOutput | Out-Null
        $destination = Join-Path $sourceOutput ($spec.id + '.mmd.zip')
        $model = Join-Path $root $spec.model
        if (!(Test-Path -LiteralPath $model)) { throw "Missing source model: $model" }
        $payloads = [ordered]@{}
        foreach ($file in Get-ChildItem -LiteralPath $root -File | Sort-Object FullName) {
            if ($file.FullName -ne $model -and $file.Extension.ToLowerInvariant() -notin @('.png','.jpg','.jpeg','.bmp','.tga','.dds','.spa','.sph','.txt')) { continue }
            $relative = $file.FullName.Substring($root.Length + 1).Replace('\','/')
            $payloads[('assets/' + $relative)] = $file.FullName
        }
        $payloads['README.md'] = $readme
        $checks = [ordered]@{}
        foreach ($name in $payloads.Keys) {
            $file = Get-Item -LiteralPath $payloads[$name]
            $checks[$name] = [ordered]@{bytes=$file.Length; sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
        }
        $manifest = [ordered]@{format='rigworks.mmd-source'; version=1; model=('assets/' + $spec.model); files=$checks}
        $stream = [IO.File]::Open($destination, [IO.FileMode]::Create)
        try {
            $zip = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
            try {
                $entry = $zip.CreateEntry('manifest.json')
                $entry.LastWriteTime = [DateTimeOffset]::new(1980,1,1,0,0,0,[TimeSpan]::Zero)
                $writer = New-Object IO.StreamWriter($entry.Open(), $utf8)
                try { $writer.Write(($manifest | ConvertTo-Json -Depth 10)) } finally { $writer.Dispose() }
                foreach ($name in $payloads.Keys) {
                    $entry = $zip.CreateEntry($name, [IO.Compression.CompressionLevel]::Optimal)
                    $entry.LastWriteTime = [DateTimeOffset]::new(1980,1,1,0,0,0,[TimeSpan]::Zero)
                    $input = [IO.File]::OpenRead($payloads[$name])
                    $entryStream = $entry.Open()
                    try { $input.CopyTo($entryStream) } finally { $entryStream.Dispose(); $input.Dispose() }
                }
            } finally { $zip.Dispose() }
        } finally { $stream.Dispose() }
        Write-Output "Created MMD source ZIP: $destination"
        $sourceJob = Join-Path $jobs ($spec.id + '-source.json')
        [IO.File]::WriteAllText($sourceJob, (@{mmdSource=$destination} | ConvertTo-Json), $utf8)
        $sourceJobs += $sourceJob
    }
    & (Join-Path $PSScriptRoot 'Invoke-ReleaseAvatarPackage.ps1') -JobFile $sourceJobs -AssimpLoaderDir $AssimpLoaderDir -JavaHome $JavaHome
}

Copy-Item -LiteralPath $readme -Destination (Join-Path $output 'README.md') -Force
Copy-Item -LiteralPath $releaseNotes -Destination (Join-Path $output 'RELEASE_NOTES.md') -Force

$publicPackages = @(
    'madoka-exedra-hd.rig.zip', 'madoka-vita-01.rig.zip', 'madoka-vita-03.rig.zip',
    'lusia-hd-pbr.rig.zip', 'madoka-pixelized.rig.zip', 'misaka-pixelized.rig.zip',
    'marisa.mmd.zip'
)
$missing = @($publicPackages | Where-Object { !(Test-Path -LiteralPath (Join-Path $output $_)) })
if ($missing.Count -eq 0) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $checksumFile = Join-Path $output 'SHA256SUMS.txt'
    $checksums = @($publicPackages | Sort-Object | ForEach-Object {
        (Get-FileHash -LiteralPath (Join-Path $output $_) -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $_
    })
    [IO.File]::WriteAllText($checksumFile, (($checksums -join "`n") + "`n"), $utf8)
    $collection = Join-Path $output 'Default-Model-Pack.zip'
    $stream = [IO.File]::Open($collection, [IO.FileMode]::Create)
    try {
        $zip = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
        try {
            foreach ($name in $publicPackages + @('README.md','SHA256SUMS.txt')) {
                $entryName = if ($publicPackages -contains $name) { 'characters/' + $name } else { $name }
                $entry = $zip.CreateEntry($entryName, [IO.Compression.CompressionLevel]::NoCompression)
                $entry.LastWriteTime = [DateTimeOffset]::new(1980,1,1,0,0,0,[TimeSpan]::Zero)
                $input = [IO.File]::OpenRead((Join-Path $output $name))
                $entryStream = $entry.Open()
                try { $input.CopyTo($entryStream) } finally { $entryStream.Dispose(); $input.Dispose() }
            }
        } finally { $zip.Dispose() }
    } finally { $stream.Dispose() }
    Write-Output "Created public collection (7 character ZIPs): $collection"
}
