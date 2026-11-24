[CmdletBinding()]
param(
    [string]$AssetDir = (Join-Path $PSScriptRoot '..\..\build\misaka'),
    [string]$Output,
    [string]$AssimpLoaderDir = (Join-Path $PSScriptRoot '..\..\..\AssimpLoader'),
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Invoke-StarpopPackage.ps1') -Avatar misaka -AssetDir $AssetDir `
    -Output $Output -AssimpLoaderDir $AssimpLoaderDir -JavaHome $JavaHome
