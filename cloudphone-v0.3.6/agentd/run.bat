@echo off
setlocal enabledelayedexpansion

if "%~1"=="-h" goto show_help
if "%~1"=="--help" goto show_help

goto init

:show_help
echo Usage: run.bat [adb-serial] [options]
echo Example: run.bat -id my-device -signaling wss://cloudphone.example.com:8443
echo          run.bat -id my-device -signaling ws://192.168.5.84:8443
echo          run.bat -id my-redroid -signaling ws://192.168.5.84:8443 -external-addr 192.168.5.85 -webrtc-port 50000
exit /b 0

:init
set "SERIAL="
set "FIRST_ARG=%~1"

if "%FIRST_ARG%"=="" goto start_process
:: If the first argument does not start with '-', it is considered the adb serial
if not "%FIRST_ARG:~0,1%"=="-" (
    set "SERIAL=-s %FIRST_ARG%"
    shift
)

:start_process
set "ENV_EXPORTS="
:args_parse_loop
if "%~1"=="" goto args_parse_done
if "%~1"=="-signaling" (
    set "ENV_EXPORTS=export CP_AGENT_SIGNALING=\"%~2\"; !ENV_EXPORTS!"
    shift
    shift
    goto args_parse_loop
)
if "%~1"=="-id" (
    set "ENV_EXPORTS=export CP_AGENT_ID=\"%~2\"; !ENV_EXPORTS!"
    shift
    shift
    goto args_parse_loop
)
if "%~1"=="-external-addr" (
    set "ENV_EXPORTS=export CP_AGENT_EXTERNAL_ADDR=\"%~2\"; !ENV_EXPORTS!"
    shift
    shift
    goto args_parse_loop
)
if "%~1"=="-webrtc-port" (
    set "ENV_EXPORTS=export CP_AGENT_WEBRTC_PORT=\"%~2\"; !ENV_EXPORTS!"
    shift
    shift
    goto args_parse_loop
)
if "%~1"=="-resolution" (
    set "ENV_EXPORTS=export CP_AGENT_RESOLUTION=\"%~2\"; !ENV_EXPORTS!"
    shift
    shift
    goto args_parse_loop
)
if "%~1"=="-bitrate" (
    set "ENV_EXPORTS=export CP_AGENT_BITRATE=\"%~2\"; !ENV_EXPORTS!"
    shift
    shift
    goto args_parse_loop
)
if "%~1"=="-max-size" (
    set "ENV_EXPORTS=export CP_AGENT_MAX_SIZE=\"%~2\"; !ENV_EXPORTS!"
    shift
    shift
    goto args_parse_loop
)
if "%~1"=="-max-fps" (
    set "ENV_EXPORTS=export CP_AGENT_MAX_FPS=\"%~2\"; !ENV_EXPORTS!"
    shift
    shift
    goto args_parse_loop
)
if "%~1"=="-video-codec-options" (
    set "ENV_EXPORTS=export CP_AGENT_VIDEO_CODEC_OPTIONS=\"%~2\"; !ENV_EXPORTS!"
    shift
    shift
    goto args_parse_loop
)
if "%~1"=="-snapshot-interval" (
    set "ENV_EXPORTS=export CP_AGENT_SNAPSHOT_INTERVAL=\"%~2\"; !ENV_EXPORTS!"
    shift
    shift
    goto args_parse_loop
)
if "%~1"=="-root" (
    set "ENV_EXPORTS=export CP_AGENT_ROOT=\"true\"; !ENV_EXPORTS!"
    shift
    goto args_parse_loop
)
if "%~1"=="-bwe" (
    set "ENV_EXPORTS=export CP_AGENT_BWE=\"%~2\"; !ENV_EXPORTS!"
    shift
    shift
    goto args_parse_loop
)
if "%~1"=="-audio" (
    set "ENV_EXPORTS=export CP_AGENT_AUDIO=\"true\"; !ENV_EXPORTS!"
    shift
    goto args_parse_loop
)
if "%~1"=="-ice-servers" (
    set "ENV_EXPORTS=export CP_AGENT_ICE_SERVERS=\"%~2\"; !ENV_EXPORTS!"
    shift
    shift
    goto args_parse_loop
)
if "%~1"=="-upnp" (
    set "ENV_EXPORTS=export CP_AGENT_UPNP=\"true\"; !ENV_EXPORTS!"
    shift
    goto args_parse_loop
)
if "%~1"=="-debug" (
    set "ENV_EXPORTS=export CP_AGENT_DEBUG=\"true\"; !ENV_EXPORTS!"
    shift
    goto args_parse_loop
)
if "%~1"=="-camera-addr" (
    set "ENV_EXPORTS=export CP_AGENT_CAMERA_ADDR=\"%~2\"; !ENV_EXPORTS!"
    shift
    shift
    goto args_parse_loop
)
if "%~1"=="-force-camera" (
    set "ENV_EXPORTS=export CP_AGENT_FORCE_CAMERA=\"true\"; !ENV_EXPORTS!"
    shift
    goto args_parse_loop
)
shift
goto args_parse_loop
:args_parse_done

:: Detect target device architecture
set "ARCH="
for /f "delims=" %%i in ('adb %SERIAL% shell uname -m 2^>nul') do (
    set "ARCH=%%i"
)

:: If adb command fails or ARCH is empty, report error and exit
if "%ARCH%"=="" (
    echo [ERROR] Failed to detect device architecture. Please check if device is connected via adb.
    exit /b 1
)

:: Remove possible spaces and other trailing characters
set "ARCH=%ARCH: =%"

set "AGENT_BIN=cloudphone-agent-armeabi-v7a"
echo !ARCH! | findstr /i "x86_64 amd64 i686 x86 i386" >nul
if !errorlevel! equ 0 (
    set "AGENT_BIN=cloudphone-agent-amd64"
) else (
    echo !ARCH! | findstr /i "aarch64 arm64" >nul
    if !errorlevel! equ 0 (
        set "AGENT_BIN=cloudphone-agent-arm64"
    )
)

echo === Deploying to device (!ARCH!) ===
echo Pushing !AGENT_BIN!...
adb %SERIAL% push "!AGENT_BIN!" /data/local/tmp/cloudphone-agent
if !errorlevel! neq 0 (
    echo [ERROR] Failed to push !AGENT_BIN! to device.
    exit /b 1
)

echo Pushing libsys_core.so...
adb %SERIAL% push libsys_core.so /data/local/tmp/libsys_core.so
if !errorlevel! neq 0 (
    echo [ERROR] Failed to push libsys_core.so to device.
    exit /b 1
)

adb %SERIAL% shell chmod +x /data/local/tmp/cloudphone-agent

echo === Starting Agent ===
:: 1. Force clean old processes (Agent & scrcpy-server)
adb %SERIAL% shell "pkill -f cloudphone-agent || killall cloudphone-agent || true"
adb %SERIAL% shell "pkill -f com.android.helper.CoreService || true"

:: 2. Start agent in background and save logs
set "START_CMD=export CP_AGENT_JAR=/data/local/tmp/libsys_core.so; !ENV_EXPORTS! setsid nohup env GODEBUG=asyncpreemptoff=1 /data/local/tmp/cloudphone-agent > /data/local/tmp/cloudphone-agent.log 2>&1 &"
echo Executing: !START_CMD!
adb %SERIAL% shell "sh -c '!START_CMD!'"

:: 3. Verify startup result
timeout /t 1 /nobreak >nul 2>&1
if !errorlevel! neq 0 (
    ping -n 2 127.0.0.1 >nul
)

adb %SERIAL% shell "ps -A | grep cloudphone-agent" >nul 2>&1
if !errorlevel! equ 0 (
    echo === [OK] Agent started successfully in background. ===
    echo Log file: /data/local/tmp/cloudphone-agent.log
) else (
    echo === [ERROR] Agent failed to start. Please check logs on device. ===
)

endlocal
exit /b 0
