[CmdletBinding()]
param(
    [string]$AssetDir = (Join-Path $PSScriptRoot '..\..\build\starpop'),
    [string]$Output,
    [string]$AssimpLoaderDir = (Join-Path $PSScriptRoot '..\..\..\AssimpLoader'),
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$rigWorksDir = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$assetPath = (Resolve-Path -LiteralPath $AssetDir).Path
if (!$Output) { $Output = Join-Path $assetPath 'starpop.rig.zip' }
$gameNode = '1.21.1-neoforge'
$rigBuild = Join-Path $rigWorksDir "versions\$gameNode\build"
$assimpBuild = Join-Path $AssimpLoaderDir "versions\$gameNode\build"
if (!$JavaHome) { throw 'Set JAVA_HOME or pass -JavaHome with the Java 21 JDK path.' }
$javac = Join-Path $JavaHome 'bin\javac.exe'
$java = Join-Path $JavaHome 'bin\java.exe'
if (!(Test-Path -LiteralPath $javac)) { throw "JDK javac not found: $javac" }

$runtimePath = Join-Path $rigBuild 'moddev\clientLegacyClasspath.txt'
$classpath = @(
    (Join-Path $rigBuild 'classes\java\main')
    (Join-Path $rigBuild 'resources\main')
    (Join-Path $assimpBuild 'classes\java\main')
    (Join-Path $assimpBuild 'resources\main')
)
foreach ($path in $classpath + $runtimePath) {
    if (!(Test-Path -LiteralPath $path)) { throw "Build the $gameNode node in both repositories first; missing $path" }
}
$classpath += @(Get-Content -LiteralPath $runtimePath)
$properties = Join-Path $rigWorksDir "versions\$gameNode\gradle.properties"
$neoSetting = Select-String -LiteralPath $properties -Pattern '^neo_version=(\S+)' | Select-Object -First 1
if (!$neoSetting) { throw "neo_version is missing from $properties" }
$neoVersion = $neoSetting.Matches[0].Groups[1].Value
$gradleHome = $env:GRADLE_USER_HOME
if (!$gradleHome) { $gradleHome = Join-Path $env:USERPROFILE '.gradle' }
$neoCache = Join-Path $gradleHome "caches\modules-2\files-2.1\net.neoforged\neoforge\$neoVersion"
$neoJar = Get-ChildItem -LiteralPath $neoCache -Filter '*-universal.jar' -File -Recurse | Select-Object -First 1
$minecraftJar = Get-ChildItem -LiteralPath (Join-Path $rigBuild 'moddev\artifacts') -Filter '*-merged.jar' -File | Select-Object -First 1
if (!$neoJar -or !$minecraftJar) { throw "NeoForge $neoVersion or merged Minecraft dependency missing; build $gameNode first." }
$classpath += @($neoJar.FullName, $minecraftJar.FullName)
$nativeClassifier = switch ($env:PROCESSOR_ARCHITECTURE) { ARM64 { 'windows-arm64' } x86 { 'windows-x86' } default { 'windows' } }
$classpath = @($classpath | Where-Object { $_ -and ($_ -notmatch '-natives-' -or $_.EndsWith("-natives-$nativeClassifier.jar")) } | Select-Object -Unique)
$separator = [IO.Path]::PathSeparator
$cp = $classpath -join $separator
$classes = Join-Path $rigWorksDir 'build\starpop-tools'
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sources = @(
    (Join-Path $PSScriptRoot 'PrepareStarpopAvatar.java')
    (Join-Path $PSScriptRoot 'PackageStarpopAvatar.java')
)
& $javac '-proc:none' '-encoding' 'UTF-8' '-cp' $cp '-d' $classes @sources
if ($LASTEXITCODE -ne 0) { throw "Starpop package tool failed to compile (exit $LASTEXITCODE)." }
& $java '-Djava.awt.headless=true' '-cp' "$classes$separator$cp" 'cn.minerealms.rigworks.character.client.PrepareStarpopAvatar' $assetPath $rigWorksDir
if ($LASTEXITCODE -ne 0) { throw "Starpop runtime calibration or validation failed (exit $LASTEXITCODE)." }
& $java '-Djava.awt.headless=true' '-cp' "$classes$separator$cp" 'cn.minerealms.rigworks.character.client.PackageStarpopAvatar' $assetPath ([IO.Path]::GetFullPath($Output))
if ($LASTEXITCODE -ne 0) { throw "Starpop package export or import validation failed (exit $LASTEXITCODE)." }
