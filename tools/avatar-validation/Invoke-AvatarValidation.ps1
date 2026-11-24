param(
    [Parameter(Mandatory=$true)][string]$Model,
    [Parameter(Mandatory=$true)][Alias('Node')][ValidateSet('1.20.1-forge','1.21.1-neoforge')][string]$GameNode,
    [Parameter(Mandatory=$true)][string]$Output,
    [Parameter(Mandatory=$false)][string]$Joint='Head',
    [string]$Profile='',
    [string]$JavaHome=$env:JAVA_HOME,
    [string]$RigWorksDir=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [string]$AssimpLoaderDir=(Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path 'AssimpLoader')
)
$ErrorActionPreference='Stop'
$rigBuild=Join-Path $RigWorksDir "versions\$GameNode\build"
$engineBuild=Join-Path $AssimpLoaderDir "versions\$GameNode\build"
if([string]::IsNullOrWhiteSpace($JavaHome)){throw 'Set JAVA_HOME or pass -JavaHome'}
if(!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))){throw "JDK javac not found: $JavaHome"}
if(!(Test-Path -LiteralPath $rigBuild)){throw "RigWorks node build not found: $rigBuild"}
if(!(Test-Path -LiteralPath $engineBuild)){throw "AssimpLoader node build not found: $engineBuild"}
$runtime=@(Get-Content -LiteralPath (Join-Path $rigBuild 'moddev\clientLegacyClasspath.txt'))
$classpath=@((Join-Path $rigBuild 'classes\java\main'),(Join-Path $rigBuild 'resources\main'),(Join-Path $engineBuild 'classes\java\main'),(Join-Path $engineBuild 'resources\main'))+$runtime
if($GameNode -eq '1.21.1-neoforge') {
    $nodeProperties=Get-Content -LiteralPath (Join-Path $RigWorksDir "versions\$GameNode\gradle.properties")
    $neoVersion=($nodeProperties | Where-Object {$_ -match '^neo_version='} | Select-Object -First 1).Split('=',2)[1].Trim()
    $neoRoot=Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\net.neoforged\neoforge\$neoVersion"
    $classpath+=@(Get-ChildItem -LiteralPath $neoRoot -Filter '*universal.jar' -Recurse | Select-Object -First 1 -ExpandProperty FullName)
    $classpath+=@(Get-ChildItem -LiteralPath (Join-Path $rigBuild 'moddev\artifacts') -Filter '*merged.jar' | Select-Object -First 1 -ExpandProperty FullName)
}
$nativeClassifier=switch($env:PROCESSOR_ARCHITECTURE){'ARM64'{'windows-arm64'} 'x86'{'windows-x86'} default{'windows'}}
$classpath=@($classpath | Where-Object {$_ -and (([IO.Path]::GetFileName($_) -notmatch '-natives-') -or $_.EndsWith("-natives-$nativeClassifier.jar"))} | Select-Object -Unique)
$cp=$classpath -join [IO.Path]::PathSeparator
$classes=Join-Path $RigWorksDir "build\avatar-validation\$GameNode"
New-Item -ItemType Directory -Path $classes -Force | Out-Null
& (Join-Path $JavaHome 'bin\javac.exe') '-proc:none' '-encoding' 'UTF-8' '-cp' $cp '-d' $classes (Join-Path $PSScriptRoot 'InspectAvatar.java')
if($LASTEXITCODE -ne 0){throw "Inspection compile failed: $LASTEXITCODE"}
$inspectionArguments=@([IO.Path]::GetFullPath($Model),$Joint,[IO.Path]::GetFullPath($Output))
if(![string]::IsNullOrWhiteSpace($Profile)){$inspectionArguments+=([IO.Path]::GetFullPath($Profile))}
& (Join-Path $JavaHome 'bin\java.exe') '-Djava.awt.headless=true' '-cp' "$classes$([IO.Path]::PathSeparator)$cp" 'InspectAvatar' @inspectionArguments
if($LASTEXITCODE -ne 0){throw "Inspection failed: $LASTEXITCODE"}
