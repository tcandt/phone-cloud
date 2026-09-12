#!/system/bin/sh
cd /data/local/tmp/android
export CLASSPATH=/data/local/tmp/android/libsys_core.so

echo "Stopping existing services..."
pkill -f webrtc-signaling
pkill -f cloudphone-agent
pkill -f com.android.helper.CoreService

echo "Starting Signaling Server (HTTPS)..."
chmod +x webrtc-signaling
nohup ./webrtc-signaling -port 8443 -assets ./assets -cert ./certs/server.crt -key ./certs/server.key > signaling.log 2>&1 &
sleep 2

echo "Starting Agent..."
chmod +x cloudphone-agent
export CP_AGENT_ID="local-android"
export CP_AGENT_SIGNALING="wss://127.0.0.1:8443"
export CP_AGENT_JAR="./libsys_core.so"
setsid nohup env GODEBUG=asyncpreemptoff=1 ./cloudphone-agent > agent.log 2>&1 &

IP=$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}')
if [ -z "$IP" ]; then
    IP=$(ifconfig wlan0 2>/dev/null | grep 'inet ' | awk '{print $2}' | sed 's/addr://')
fi
if [ -z "$IP" ]; then
    IP="<Android-IP>"
fi
echo "Services started. Connect via https://$IP:8443"
