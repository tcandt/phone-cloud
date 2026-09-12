#!/bin/bash
set -e

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "======================================================="
echo "   ScrcpyOverWebRTC - Complete System Build Pipeline"
echo "======================================================="

echo "[1/6] Building WebRTC Signaling Backend..."
cd "$ROOT_DIR/recovered_source/webrtc-signaling"
go build -o webrtc-signaling .
GOOS=linux GOARCH=amd64 go build -o webrtc-signaling-linux-amd64 .
GOOS=linux GOARCH=arm64 go build -o webrtc-signaling-linux-arm64 .

echo "[2/6] Building CloudPhone Agent..."
cd "$ROOT_DIR/recovered_source/cloudphone-agent"
go build -o cloudphone-agent .
GOOS=linux GOARCH=arm64 go build -o cloudphone-agent-arm64 .
GOOS=linux GOARCH=arm GOARM=7 go build -o cloudphone-agent-armeabi-v7a .
GOOS=linux GOARCH=amd64 go build -o cloudphone-agent-amd64 .

echo "[3/6] Building Android Helper (libsys_core.so)..."
cd "$ROOT_DIR/recovered_source/android-helper"
chmod +x gradlew
./gradlew clean assembleHelper

echo "[4/6] Building Android App (Release APK with bundled binaries)..."
cd "$ROOT_DIR/recovered_source/android-app"
chmod +x gradlew
./gradlew clean assembleRelease

echo "[5/6] Building Production Web Frontend..."
cd "$ROOT_DIR/web-app"
npm install
npm run build

echo "[6/6] Running Conformance Verification Tests..."
cd "$ROOT_DIR/recovered_source/webrtc-signaling"
go test -v -timeout 60s -run TestProtocolConformance .
cd "$ROOT_DIR/recovered_source/cloudphone-agent"
go test -v -timeout 60s -run TestAgentConformance .

echo "======================================================="
echo "   BUILD SUCCESSFUL: All components built and verified!"
echo "======================================================="
