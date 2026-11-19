REM Originally derived from:
REM Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
REM        and https://github.com/stevewongv/YesSteveModel (Apache-2.0)
REM
REM Licensed under the Apache License, Version 2.0.
REM You may obtain a copy of the License at:
REM https://www.apache.org/licenses/LICENSE-2.0
REM
REM Modified by MineRealms for RigWorks.
REM Changes: Cleanroom reimplementation / package adaptation / API changes for in-mod YSM decode.
@echo off
setlocal enabledelayedexpansion
pushd "%~dp0..\.." >nul
set "ROOT=%CD%"
popd >nul
set "ROOTFWD=%ROOT:\=/%"
set "SRC=%ROOT%\native\ysmprocessor\src\ysmprocessor.zig"
set "DIST=%ROOT%\native\ysmprocessor\dist"
set "RES=%ROOT%\src\main\resources\native\ysmprocessor"

set "ZIG="
if exist "%ROOT%\.reference\zig.exe" if exist "%ROOT%\.reference\lib\std\std.zig" set "ZIG=%ROOT%\.reference\zig.exe"
if "!ZIG!"=="" if exist "H:\MinecraftMods\FabriKNative\.tools\zig-windows-x86_64-0.13.0\zig.exe" set "ZIG=H:\MinecraftMods\FabriKNative\.tools\zig-windows-x86_64-0.13.0\zig.exe"
if "!ZIG!"=="" set "ZIG=zig"

if not exist "%DIST%" mkdir "%DIST%"
echo Using Zig: !ZIG!

call :build x86_64-windows-gnu       ysmprocessor-windows-x86_64.dll   win32-x86-64   ysmprocessor.dll         || exit /b 1
call :build x86_64-linux-gnu         ysmprocessor-linux-x86_64.so      linux-x86-64   libysmprocessor.so       || exit /b 1
call :build x86_64-macos.11.0.0-none ysmprocessor-macos-x86_64.dylib   darwin-x86-64  libysmprocessor.dylib    || exit /b 1
call :build aarch64-macos.11.0.0-none ysmprocessor-macos-aarch64.dylib darwin-aarch64 libysmprocessor.dylib    || exit /b 1

del /q "%DIST%\*.o" "%DIST%\*.obj" "%DIST%\*.pdb" "%DIST%\*.lib" 2>nul
echo YSM native build complete.
endlocal
exit /b 0

:build
set "TARGET=%~1"
set "OUT=%~2"
set "ARCH=%~3"
set "DESTMAME=%~4"
echo [ysmprocessor] %TARGET% -^> %OUT%
"!ZIG!" build-lib "%SRC%" -target %TARGET% -dynamic -lc -OReleaseFast "-femit-bin=%ROOTFWD%/native/ysmprocessor/dist/%OUT%"
if errorlevel 1 (
    echo [ysmprocessor] FAILED %TARGET%
    exit /b 1
)
set "DESTDIR=%RES%\%ARCH%"
if not exist "%DESTDIR%" mkdir "%DESTDIR%"
copy /Y "%DIST%\%OUT%" "%DESTDIR%\%DESTMAME%" >nul
if errorlevel 1 exit /b 1
exit /b 0
