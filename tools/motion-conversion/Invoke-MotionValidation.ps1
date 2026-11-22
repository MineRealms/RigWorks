param(
    [string]$Manifest,
    [string]$Output,
    [ValidateSet('1.20.1-forge','1.21.1-neoforge')][string]$GameNode='1.21.1-neoforge',
    [string]$JavaHome=$env:JAVA_HOME,
    [string]$RigWorksDir=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [string]$AssimpLoaderDir
)

$ErrorActionPreference='Stop'
$rigRoot=(Resolve-Path -LiteralPath $RigWorksDir).Path
if([string]::IsNullOrWhiteSpace($AssimpLoaderDir)) {
    $AssimpLoaderDir=Join-Path $rigRoot 'AssimpLoader'
}
$engineRoot=(Resolve-Path -LiteralPath $AssimpLoaderDir).Path
if([string]::IsNullOrWhiteSpace($Manifest)) {
    $Manifest=Join-Path $rigRoot 'build\vmd-motion-preview\exports\manifest.json'
}
if([string]::IsNullOrWhiteSpace($Output)) {
    $Output=Join-Path $rigRoot "build\vmd-motion-preview\exports\assimp-validation-$GameNode.json"
}
$manifestPath=[IO.Path]::GetFullPath($Manifest)
$outputPath=[IO.Path]::GetFullPath($Output)
if(!(Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Export manifest not found; export the Blender preview first: $manifestPath"
}
if([string]::IsNullOrWhiteSpace($JavaHome)) { throw 'Set JAVA_HOME or pass -JavaHome' }
$javac=Join-Path $JavaHome 'bin\javac.exe'
$java=Join-Path $JavaHome 'bin\java.exe'
foreach($requiredPath in @($javac,$java)) {
    if(!(Test-Path -LiteralPath $requiredPath -PathType Leaf)) { throw "JDK executable not found: $requiredPath" }
}
$rigBuild=Join-Path $rigRoot "versions\$GameNode\build"
$engineBuild=Join-Path $engineRoot "versions\$GameNode\build"
$runtimeFile=Join-Path $rigBuild 'moddev\clientLegacyClasspath.txt'
foreach($requiredPath in @((Join-Path $rigBuild 'classes\java\main'),(Join-Path $engineBuild 'classes\java\main'),$runtimeFile)) {
    if(!(Test-Path -LiteralPath $requiredPath)) {
        throw "Compile the selected RigWorks/AssimpLoader node first; missing: $requiredPath"
    }
}
$runtime=@(Get-Content -LiteralPath $runtimeFile)
$classpath=@((Join-Path $rigBuild 'classes\java\main'),(Join-Path $rigBuild 'resources\main'),
             (Join-Path $engineBuild 'classes\java\main'),(Join-Path $engineBuild 'resources\main'))+$runtime
if($GameNode -eq '1.21.1-neoforge') {
    $nodeProperties=Get-Content -LiteralPath (Join-Path $rigRoot "versions\$GameNode\gradle.properties")
    $versionLine=$nodeProperties | Where-Object { $_ -match '^neo_version=' } | Select-Object -First 1
    if(!$versionLine) { throw "neo_version missing for $GameNode" }
    $neoVersion=$versionLine.Split('=',2)[1].Trim()
    $neoRoot=Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\net.neoforged\neoforge\$neoVersion"
    $classpath+=@(Get-ChildItem -LiteralPath $neoRoot -Filter '*universal.jar' -Recurse | Select-Object -First 1 -ExpandProperty FullName)
    $classpath+=@(Get-ChildItem -LiteralPath (Join-Path $rigBuild 'moddev\artifacts') -Filter '*merged.jar' | Select-Object -First 1 -ExpandProperty FullName)
}
$nativeClassifier=switch($env:PROCESSOR_ARCHITECTURE) {
    'ARM64' { 'windows-arm64' }
    'x86' { 'windows-x86' }
    default { 'windows' }
}
$classpath=@($classpath | Where-Object {
    $_ -and (([IO.Path]::GetFileName($_) -notmatch '-natives-') -or $_.EndsWith("-natives-$nativeClassifier.jar"))
} | Select-Object -Unique)
$cp=$classpath -join [IO.Path]::PathSeparator
$classes=Join-Path $rigRoot "build\motion-export-validation\$GameNode"
New-Item -ItemType Directory -Path $classes -Force | Out-Null
& $javac '-proc:none' '-encoding' 'UTF-8' '-cp' $cp '-d' $classes (Join-Path $PSScriptRoot 'ValidateMotionExports.java')
if($LASTEXITCODE -ne 0) { throw "Motion validation helper compile failed: $LASTEXITCODE" }
& $java '-Djava.awt.headless=true' '-cp' "$classes$([IO.Path]::PathSeparator)$cp" 'ValidateMotionExports' $manifestPath $outputPath
if($LASTEXITCODE -ne 0) { throw "Motion export validation failed: $LASTEXITCODE; report: $outputPath" }
