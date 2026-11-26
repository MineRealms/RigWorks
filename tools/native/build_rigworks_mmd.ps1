param(
    [ValidateSet('x64', 'ARM64')][string]$Architecture = 'x64',
    [string]$BuildDirectory = '',
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $BuildDirectory) { $BuildDirectory = Join-Path $taskRoot "build/native/rigworks_mmd/$Architecture" }
$cmakeCommand = Get-Command cmake -ErrorAction SilentlyContinue
if ($cmakeCommand) {
    $cmake = $cmakeCommand.Source
} else {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
    if (-not (Test-Path -LiteralPath $vswhere)) { throw 'CMake not found. Install CMake 3.21+ or Visual Studio CMake tools.' }
    $vsRoot = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.CMake.Project -property installationPath
    $cmake = Join-Path $vsRoot 'Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe'
    if (-not (Test-Path -LiteralPath $cmake)) { throw 'Visual Studio CMake tools not found.' }
}
$configure = @('-S', (Join-Path $taskRoot 'native/rigworks_mmd'), '-B', $BuildDirectory,
    '-G', 'Visual Studio 17 2022', '-A', $Architecture)
if ($SkipTests) { $configure += '-DBUILD_TESTING=OFF' } else { $configure += '-DBUILD_TESTING=ON' }
& $cmake @configure
if ($LASTEXITCODE -ne 0) { throw 'MMD CMake configure failed.' }
& $cmake --build $BuildDirectory --config Release --parallel
if ($LASTEXITCODE -ne 0) { throw 'MMD native build failed.' }
if (-not $SkipTests) {
    $ctest = Join-Path (Split-Path $cmake) 'ctest.exe'
    & $ctest --test-dir $BuildDirectory -C Release --output-on-failure
    if ($LASTEXITCODE -ne 0) { throw 'MMD native tests failed.' }
}
& $cmake --install $BuildDirectory --config Release --component RigWorksMmd --prefix (Join-Path $taskRoot 'native/rigworks_mmd/dist')
if ($LASTEXITCODE -ne 0) { throw 'MMD native staging failed.' }
