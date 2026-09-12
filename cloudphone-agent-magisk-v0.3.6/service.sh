#!/system/bin/sh

# 延迟等待系统完全启动，确保底层音频、视频编解码和 Display 服务均就绪
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 3
done

# 延迟额外 5 秒，保障 UI 和 adb 环境稳定
sleep 5

MODDIR="/data/adb/modules/cloudphone-agent"
CONF_FILE="$MODDIR/config.conf"
LOG_FILE="/data/local/tmp/cloudphone-agent.log"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Cloudphone Service watchdog started." >> "$LOG_FILE"

# 守护进程主循环
while true; do
    # 动态加载配置文件，以支持在不重启模块的前提下热更新参数与开关
    if [ -f "$CONF_FILE" ]; then
        # 为避免配置格式问题影响守护脚本，在一个独立的子 shell 中校验并读取
        # 这里直接 source 配置
        . "$CONF_FILE"
    else
        ENABLED="false"
    fi

    # 查询 agent 进程的 PID
    # 为了避免匹配到 pgrep 自身或者控制工具本身，指定完整路径
    PID=$(pgrep -f "^/system/bin/cloudphone-agent$")

    if [ "$ENABLED" = "true" ]; then
        if [ -z "$PID" ]; then
            echo "[$(date '+%Y-%m-%d %H:%M:%S')] Agent is enabled but not running. Launching agent..." >> "$LOG_FILE"
            
            # 清理以前残留的 Java 辅助进程以释放端口/UDS
            pkill -f "com.android.helper.CoreService" || true
            
            # 导出 Agent 参数所需要的环境变量
            export CP_AGENT_JAR="$MODDIR/libsys_core.so"
            export CP_AGENT_SIGNALING="$CP_AGENT_SIGNALING"
            export CP_AGENT_ID="$CP_AGENT_ID"
            export CP_AGENT_EXTERNAL_ADDR="$CP_AGENT_EXTERNAL_ADDR"
            export CP_AGENT_WEBRTC_PORT="$CP_AGENT_WEBRTC_PORT"
            export CP_AGENT_RESOLUTION="$CP_AGENT_RESOLUTION"
            export CP_AGENT_BITRATE="$CP_AGENT_BITRATE"
            export CP_AGENT_MAX_SIZE="$CP_AGENT_MAX_SIZE"
            export CP_AGENT_MAX_FPS="$CP_AGENT_MAX_FPS"
            export CP_AGENT_VIDEO_CODEC_OPTIONS="$CP_AGENT_VIDEO_CODEC_OPTIONS"
            export CP_AGENT_SNAPSHOT_INTERVAL="$CP_AGENT_SNAPSHOT_INTERVAL"
            export CP_AGENT_ROOT="true"
            export CP_AGENT_BWE="$CP_AGENT_BWE"
            export CP_AGENT_AUDIO="$CP_AGENT_AUDIO"
            export CP_AGENT_ICE_SERVERS="$CP_AGENT_ICE_SERVERS"
            export CP_AGENT_UPNP="$CP_AGENT_UPNP"
            export CP_AGENT_DEBUG="$CP_AGENT_DEBUG"
            export CP_AGENT_CAMERA_ADDR="$CP_AGENT_CAMERA_ADDR"
            export CP_AGENT_FORCE_CAMERA="$CP_AGENT_FORCE_CAMERA"

            # 启动二进制，配置 asyncpreemptoff 绕过特定 Go Runtime 兼容性问题
            # 将日志输出到统一路径 /data/local/tmp/cloudphone-agent.log
            setsid nohup env GODEBUG=asyncpreemptoff=1 /system/bin/cloudphone-agent >> "$LOG_FILE" 2>&1 &
            
            # 等待 1 秒确认是否成功运行
            sleep 1
            NEW_PID=$(pgrep -f "^/system/bin/cloudphone-agent$")
            if [ -n "$NEW_PID" ]; then
                echo "[$(date '+%Y-%m-%d %H:%M:%S')] Agent started successfully with PID: $NEW_PID" >> "$LOG_FILE"
            else
                echo "[$(date '+%Y-%m-%d %H:%M:%S')] [ERROR] Failed to start Agent." >> "$LOG_FILE"
            fi
        fi
    else
        # ENABLED 为 false 或未配置
        if [ -n "$PID" ]; then
            echo "[$(date '+%Y-%m-%d %H:%M:%S')] Agent is disabled in config. Terminating active process (PID: $PID)..." >> "$LOG_FILE"
            kill "$PID" || kill -9 "$PID"
            pkill -f "com.android.helper.CoreService" || true
        fi
    fi

    # 轮询间隔，5 秒可实现较快的崩溃恢复同时几乎不消耗 CPU
    sleep 5
done
