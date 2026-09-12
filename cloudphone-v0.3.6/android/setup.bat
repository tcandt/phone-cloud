@echo off
setlocal enabledelayedexpansion

SET "SCRIPT_DIR=%~dp0"
IF "%SCRIPT_DIR:~-1%"=="\" SET "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

set "SERIAL="
if not "%~1" == "" (
    set "SERIAL=-s %~1"
)

echo ========================================================
echo      Deploying Android Standalone Package (Windows)
echo ========================================================

echo 1. Pushing standalone binaries to device...
adb %SERIAL% push "%SCRIPT_DIR%" /data/local/tmp/
if !errorlevel! neq 0 (
    echo [ERROR] Failed to push binaries. Please check adb connection.
    pause
    exit /b 1
)

echo 2. Pushing static assets to device...
if exist "%SCRIPT_DIR%\..\assets" (
    adb %SERIAL% push "%SCRIPT_DIR%\..\assets" /data/local/tmp/android/assets
) else if exist "%SCRIPT_DIR%\assets" (
    adb %SERIAL% push "%SCRIPT_DIR%\assets" /data/local/tmp/android/assets
) else (
    echo [WARNING] assets directory not found, skipping assets push.
)

echo 2.5 Pushing TLS certificates to device...
if exist "%SCRIPT_DIR%\..\certs" (
    adb %SERIAL% push "%SCRIPT_DIR%\..\certs" /data/local/tmp/android/certs
) else if exist "%SCRIPT_DIR%\certs" (
    adb %SERIAL% push "%SCRIPT_DIR%\certs" /data/local/tmp/android/certs
) else (
    echo [WARNING] certs directory not found, skipping certificates push.
)

if !errorlevel! neq 0 (
    echo [ERROR] Failed to push assets or certificates.
    pause
    exit /b 1
)

echo 3. Starting services on Android...
adb %SERIAL% shell "sh /data/local/tmp/android/setup.sh"

echo ========================================================
echo                ^^[OK^^] Deployment Finished!
echo ========================================================
pause
