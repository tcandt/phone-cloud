@echo off
echo Starting CloudPhone...
webrtc-signaling.exe -port 8443 -assets ../../assets -cert ../../certs/server.crt -key ../../certs/server.key -data ../../data
pause
