[CmdletBinding()]
param(
    [Parameter(Mandatory)][string[]]$JobFile,
    [string]$AssimpLoaderDir = (Join-Path $PSScriptRoot '../../../AssimpLoader'),
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$rigRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$node = '1.21.1-neoforge'
$rigBuild = Join-Path $rigRoot "versions/$node/build"
$engineBuild = Join-Path $AssimpLoaderDir "versions/$node/build"
if (!$JavaHome) { throw 'Pass -JavaHome pointing to a Java 21 JDK.' }
$classpath = @(
    (Join-Path $rigBuild 'classes/java/main'),
    (Join-Path $rigBuild 'resources/main'),
    (Join-Path $engineBuild 'classes/java/main'),
    (Join-Path $engineBuild 'resources/main')
)
$runtimePath = Join-Path $rigBuild 'moddev/clientLegacyClasspath.txt'
foreach ($path in $classpath + $runtimePath) {
    if (!(Test-Path -LiteralPath $path)) { throw "Build both repositories first; missing $path" }
}
$classpath += @(Get-Content -LiteralPath $runtimePath)
$gradleRoot = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$properties = Join-Path $rigRoot "versions/$node/gradle.properties"
$neoVersion = (Select-String -LiteralPath $properties -Pattern '^neo_version=(\S+)').Matches[0].Groups[1].Value
$rhinoVersion = (Select-String -LiteralPath $properties -Pattern '^rhino_version=(\S+)').Matches[0].Groups[1].Value
$neoJar = Get-ChildItem -LiteralPath (Join-Path $gradleRoot "caches/modules-2/files-2.1/net.neoforged/neoforge/$neoVersion") -Filter '*-universal.jar' -File -Recurse | Select-Object -First 1
$rhinoJar = Get-ChildItem -LiteralPath (Join-Path $gradleRoot "caches/modules-2/files-2.1/dev.latvian.mods/rhino/$rhinoVersion") -Filter '*.jar' -File -Recurse | Select-Object -First 1
$minecraftJar = Get-ChildItem -LiteralPath (Join-Path $rigBuild 'moddev/artifacts') -Filter '*-merged.jar' -File | Select-Object -First 1
if (!$neoJar -or !$rhinoJar -or !$minecraftJar) { throw 'Minecraft/NeoForge/Rhino runtime dependencies are missing.' }
$classpath += @($neoJar.FullName, $rhinoJar.FullName, $minecraftJar.FullName)
$nativeClassifier = switch ($env:PROCESSOR_ARCHITECTURE) { ARM64 { 'windows-arm64' } x86 { 'windows-x86' } default { 'windows' } }
$classpath = @($classpath | Where-Object { $_ -and ($_ -notmatch '-natives-' -or $_.EndsWith("-natives-$nativeClassifier.jar")) } | Select-Object -Unique)
$classes = Join-Path $rigRoot 'build/default-model-pack/package-tools/classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$separator = [IO.Path]::PathSeparator
$cp = $classpath -join $separator
& (Join-Path $JavaHome 'bin/javac.exe') '-proc:none' '-encoding' 'UTF-8' '-cp' $cp '-d' $classes (Join-Path $PSScriptRoot 'PackageReleaseAvatar.java')
if ($LASTEXITCODE -ne 0) { throw "Release helper compilation failed: $LASTEXITCODE" }
foreach ($job in $JobFile) {
    & (Join-Path $JavaHome 'bin/java.exe') '-Djava.awt.headless=true' '-Xmx1536m' '-cp' "$classes$separator$cp" 'cn.minerealms.rigworks.character.client.PackageReleaseAvatar' ([IO.Path]::GetFullPath($job))
    if ($LASTEXITCODE -ne 0) { throw "Release package failed: $job (exit $LASTEXITCODE)" }
}
