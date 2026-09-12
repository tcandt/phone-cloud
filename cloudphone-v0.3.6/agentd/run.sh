#!/bin/bash

if [ "$1" == "-h" ] || [ "$1" == "--help" ]; then
    echo "Usage: ./run.sh [adb-serial] [options]"
    echo "Example: ./run.sh -id my-device -signaling wss://cloudphone.example.com:8443"
    echo "         ./run.sh -id my-device -signaling ws://192.168.5.84:8443"
    echo "         ./run.sh -id my-redroid -signaling ws://192.168.5.84:8443 -external-addr 192.168.5.85 -webrtc-port 50000"
    exit 0
fi

SERIAL=""
# 如果第一个参数不是以 '-' 开头，则认为它是 adb serial
if [ $# -gt 0 ] && [[ ! "$1" == -* ]]; then
    SERIAL="-s $1"
    shift
fi

# 探测目标设备架构
ARCH=$(adb $SERIAL shell uname -m | tr -d '\r')
if [[ "$ARCH" == *"x86_64"* ]] || [[ "$ARCH" == *"amd64"* ]] || [[ "$ARCH" == *"i686"* ]] || [[ "$ARCH" == *"x86"* ]] || [[ "$ARCH" == *"i386"* ]]; then
    AGENT_BIN="cloudphone-agent-amd64"
elif [[ "$ARCH" == *"aarch64"* ]] || [[ "$ARCH" == *"arm64"* ]]; then
    AGENT_BIN="cloudphone-agent-arm64"
else
    AGENT_BIN="cloudphone-agent-armeabi-v7a"
fi

echo "=== Deploying to device ($ARCH) ==="
echo "Pushing $AGENT_BIN..."
adb $SERIAL push "$AGENT_BIN" /data/local/tmp/cloudphone-agent
echo "Pushing libsys_core.so..."
adb $SERIAL push libsys_core.so /data/local/tmp/libsys_core.so
adb $SERIAL shell chmod +x /data/local/tmp/cloudphone-agent

echo "=== Starting Agent ==="
# 1. 强制清理旧进程 (Agent & scrcpy-server)
adb $SERIAL shell "pkill -f cloudphone-agent || killall cloudphone-agent || true"
adb $SERIAL shell "pkill -f com.android.helper.CoreService || true"

# 2. 解析参数并拼装为环境变量导出语句
ENV_EXPORTS=""
while [ $# -gt 0 ]; do
    case "$1" in
        -signaling)
            ENV_EXPORTS="export CP_AGENT_SIGNALING=\"$2\"; $ENV_EXPORTS"
            shift 2
            ;;
        -id)
            ENV_EXPORTS="export CP_AGENT_ID=\"$2\"; $ENV_EXPORTS"
            shift 2
            ;;
        -external-addr)
            ENV_EXPORTS="export CP_AGENT_EXTERNAL_ADDR=\"$2\"; $ENV_EXPORTS"
            shift 2
            ;;
        -webrtc-port)
            ENV_EXPORTS="export CP_AGENT_WEBRTC_PORT=\"$2\"; $ENV_EXPORTS"
            shift 2
            ;;
        -resolution)
            ENV_EXPORTS="export CP_AGENT_RESOLUTION=\"$2\"; $ENV_EXPORTS"
            shift 2
            ;;
        -bitrate)
            ENV_EXPORTS="export CP_AGENT_BITRATE=\"$2\"; $ENV_EXPORTS"
            shift 2
            ;;
        -max-size)
            ENV_EXPORTS="export CP_AGENT_MAX_SIZE=\"$2\"; $ENV_EXPORTS"
            shift 2
            ;;
        -max-fps)
            ENV_EXPORTS="export CP_AGENT_MAX_FPS=\"$2\"; $ENV_EXPORTS"
            shift 2
            ;;
        -video-codec-options)
            ENV_EXPORTS="export CP_AGENT_VIDEO_CODEC_OPTIONS=\"$2\"; $ENV_EXPORTS"
            shift 2
            ;;
        -snapshot-interval)
            ENV_EXPORTS="export CP_AGENT_SNAPSHOT_INTERVAL=\"$2\"; $ENV_EXPORTS"
            shift 2
            ;;
        -root)
            ENV_EXPORTS="export CP_AGENT_ROOT=\"true\"; $ENV_EXPORTS"
            shift
            ;;
        -bwe)
            ENV_EXPORTS="export CP_AGENT_BWE=\"$2\"; $ENV_EXPORTS"
            shift 2
            ;;
        -audio)
            ENV_EXPORTS="export CP_AGENT_AUDIO=\"true\"; $ENV_EXPORTS"
            shift
            ;;
        -ice-servers)
            ENV_EXPORTS="export CP_AGENT_ICE_SERVERS=\"$2\"; $ENV_EXPORTS"
            shift 2
            ;;
        -upnp)
            ENV_EXPORTS="export CP_AGENT_UPNP=\"true\"; $ENV_EXPORTS"
            shift
            ;;
        -debug)
            ENV_EXPORTS="export CP_AGENT_DEBUG=\"true\"; $ENV_EXPORTS"
            shift
            ;;
        -camera-addr)
            ENV_EXPORTS="export CP_AGENT_CAMERA_ADDR=\"$2\"; $ENV_EXPORTS"
            shift 2
            ;;
        -force-camera)
            ENV_EXPORTS="export CP_AGENT_FORCE_CAMERA=\"true\"; $ENV_EXPORTS"
            shift
            ;;
        *)
            shift
            ;;
    esac
done

# 3. 后台静默启动 (setsid + nohup) 并保存日志
START_CMD="export CP_AGENT_JAR=/data/local/tmp/libsys_core.so; $ENV_EXPORTS setsid nohup env GODEBUG=asyncpreemptoff=1 /data/local/tmp/cloudphone-agent > /data/local/tmp/cloudphone-agent.log 2>&1 &"
echo "Executing: $START_CMD"
adb $SERIAL shell "sh -c '$START_CMD'"

# 4. 验证启动结果
sleep 1
if adb $SERIAL shell "ps -A | grep cloudphone-agent" > /dev/null; then
    echo "=== [OK] Agent started successfully in background. ==="
    echo "Log file: /data/local/tmp/cloudphone-agent.log"
else
    echo "=== [ERROR] Agent failed to start. Please check logs on device. ==="
fi
