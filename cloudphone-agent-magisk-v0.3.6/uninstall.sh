#!/system/bin/sh

# 清理可能正在运行的 Agent 进程及其 Java 核心进程
pkill -f "^/system/bin/cloudphone-agent$" || true
pkill -f "com.android.helper.CoreService" || true

# 清理在系统上生成的临时日志
rm -f /data/local/tmp/cloudphone-agent.log
