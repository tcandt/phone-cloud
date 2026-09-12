@echo off
setlocal enabledelayedexpansion

echo =======================================================
echo    ScrcpyOverWebRTC - Complete System Build Pipeline
echo =======================================================
echo.

set ROOT_DIR=%~dp0
cd /d "%ROOT_DIR%"

echo [1/6] Building WebRTC Signaling Backend (Windows, Linux-amd64, Linux-arm64)...
cd "%ROOT_DIR%recovered_source\webrtc-signaling"
go build -o webrtc-signaling.exe .
if %ERRORLEVEL% neq 0 ( echo Failed to build signaling Windows binary && exit /b 1 )
set GOOS=linux
set GOARCH=amd64
go build -o webrtc-signaling-linux-amd64 .
set GOARCH=arm64
go build -o webrtc-signaling-linux-arm64 .
set GOOS=
set GOARCH=
echo   - webrtc-signaling built successfully!
echo.

echo [2/6] Building CloudPhone Agent (Windows, Linux-amd64, Android-arm64, Android-armv7)...
cd "%ROOT_DIR%recovered_source\cloudphone-agent"
go build -o cloudphone-agent.exe .
if %ERRORLEVEL% neq 0 ( echo Failed to build agent Windows binary && exit /b 1 )
set GOOS=linux
set GOARCH=arm64
go build -o cloudphone-agent-arm64 .
set GOARCH=arm
set GOARM=7
go build -o cloudphone-agent-armeabi-v7a .
set GOARCH=amd64
set GOARM=
go build -o cloudphone-agent-amd64 .
set GOOS=
set GOARCH=
echo   - cloudphone-agent built successfully!
echo.

echo [3/6] Building Android Helper (libsys_core.so)...
cd "%ROOT_DIR%recovered_source\android-helper"
call gradlew.bat clean assembleHelper
if %ERRORLEVEL% neq 0 ( echo Android Helper build failed && exit /b 1 )
echo   - Android Helper built successfully!
echo.

echo [4/6] Building Android App (Release APK with bundled binaries)...
cd "%ROOT_DIR%recovered_source\android-app"
call gradlew.bat clean assembleRelease
if %ERRORLEVEL% neq 0 ( echo Android App build failed && exit /b 1 )
echo   - Android App built successfully!
echo.

echo [5/6] Building Production Web Frontend...
cd "%ROOT_DIR%web-app"
call npm install
call npm run build
if %ERRORLEVEL% neq 0 ( echo Web Frontend build failed && exit /b 1 )
echo   - Web Frontend built successfully!
echo.

echo [6/6] Running Protocol Conformance Verification Tests...
cd "%ROOT_DIR%recovered_source\webrtc-signaling"
go test -v -timeout 60s -run TestProtocolConformance .
if %ERRORLEVEL% neq 0 ( echo Signaling protocol tests failed && exit /b 1 )

cd "%ROOT_DIR%recovered_source\cloudphone-agent"
go test -v -timeout 60s -run TestAgentConformance .
if %ERRORLEVEL% neq 0 ( echo Agent protocol tests failed && exit /b 1 )
echo   - All protocol conformance tests PASSED!
echo.

echo =======================================================
echo    BUILD SUCCESSFUL: All components built and verified!
echo =======================================================
