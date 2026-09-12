#!/bin/bash

# ==============================================================================
# CloudPhone 云服务器 Docker & TURN 中转一键部署脚本
# ==============================================================================

set -e

PROJECT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
COTURN_DIR="${PROJECT_ROOT}/coturn"
mkdir -p "${COTURN_DIR}"

# ==============================================================================
# 配置文件与备份路径定义
# ==============================================================================
ENV_FILE="${PROJECT_ROOT}/.env"
ENV_BAK="${PROJECT_ROOT}/.env.bak"
TURN_CONF="${COTURN_DIR}/turnserver.conf"
TURN_CONF_BAK="${COTURN_DIR}/turnserver.conf.bak"
CONN_INFO="${PROJECT_ROOT}/connection_info.txt"

# ==============================================================================
# 参数解析
# ==============================================================================
ACTION="deploy"
if [ $# -gt 0 ]; then
    case "$1" in
        deploy)
            ACTION="deploy"
            ;;
        rollback)
            ACTION="rollback"
            ;;
        uninstall|down)
            ACTION="uninstall"
            ;;
        -h|--help)
            echo "用法: $0 [deploy | rollback | uninstall | down]"
            echo "  deploy:    (默认) 编译、备份并部署最新版本的服务"
            echo "  rollback:  回滚服务到上一个部署的版本（恢复配置与旧镜像）"
            echo "  uninstall: 停止服务并清理全部容器、构建镜像、本地配置及连接记录"
            exit 0
            ;;
        *)
            echo "未知参数: $1"
            echo "用法: $0 [deploy | rollback | uninstall | down]"
            exit 1
            ;;
    esac
fi

# ==============================================================================
# 辅助函数：检测 Docker 及 Compose 状态
# ==============================================================================
check_docker_environment() {
    # 1. 检测 Docker 引擎是否运行
    if ! docker info >/dev/null 2>&1; then
        echo "错误: 未检测到运行中的 Docker 引擎，请确认 Docker 服务已启动！"
        exit 1
    fi

    # 2. 检测并确定 Compose 命令
    if docker compose version >/dev/null 2>&1; then
        COMPOSE_CMD="docker compose"
    elif command -v docker-compose >/dev/null 2>&1 && docker-compose --version >/dev/null 2>&1; then
        COMPOSE_CMD="docker-compose"
    else
        echo "========================================================"
        echo "错误: 未检测到可用的 Docker Compose 服务！"
        echo "========================================================"
        if command -v docker-compose >/dev/null 2>&1; then
            echo "检测到系统上安装了旧版 'docker-compose'，但运行测试失败。"
            echo "这通常是因为 Python 3.12+ 移除了 'distutils' 模块导致其崩溃："
            echo "  ModuleNotFoundError: No module named 'distutils'"
            echo ""
            echo "建议解决方案: 安装 Docker 官方推荐的 Go 语言版本 Compose V2 插件："
            echo "  - Ubuntu/Debian: sudo apt-get update && sudo apt-get install docker-compose-plugin"
            echo "  - CentOS/Fedora: sudo yum install docker-compose-plugin"
            echo "  - macOS (Docker Desktop): 请更新 Docker Desktop"
            echo "  - 手动下载安装插件: 请访问 https://docs.docker.com/compose/install/"
        else
            echo "请安装 Docker Compose 插件或命令行工具后再重试。"
        fi
        echo "========================================================"
        exit 1
    fi
}

# ==============================================================================
# 辅助函数：备份当前版本
# ==============================================================================
do_backup() {
    echo "检查并备份当前版本资源..."
    # 1. 备份配置文件
    if [ -f "${ENV_FILE}" ]; then
        echo "备份环境变量配置 .env -> .env.bak"
        cp "${ENV_FILE}" "${ENV_BAK}"
    fi
    if [ -f "${TURN_CONF}" ]; then
        echo "备份中转配置文件 turnserver.conf -> turnserver.conf.bak"
        cp "${TURN_CONF}" "${TURN_CONF_BAK}"
    fi
    
    # 2. 备份 Docker 镜像
    if docker image inspect cloudphone-all-in-one:latest >/dev/null 2>&1; then
        echo "备份当前镜像标签 cloudphone-all-in-one:latest -> cloudphone-all-in-one:rollback"
        # 移除旧的 rollback 镜像以防冲突
        docker rmi cloudphone-all-in-one:rollback 2>/dev/null || true
        docker tag cloudphone-all-in-one:latest cloudphone-all-in-one:rollback
    fi
}

# ==============================================================================
# 辅助函数：写入本地连接记录文件
# ==============================================================================
write_connection_info() {
    local ip="$1"
    local user="$2"
    local pwd="$3"
    local port="$4"
    
    cat <<EOF > "${CONN_INFO}"
========================================================
             CloudPhone 部署连接配置记录
========================================================
部署/恢复时间: $(date '+%Y-%m-%d %H:%M:%S')

1. 网页管理后台连接 URL:
   https://${ip}:${port}

2. Android 设备/容器 Agent 接入启动指令:
   ./cloudphone-agent \\
     -signaling "wss://${ip}:${port}/register_agent" \\
     -id "<自定义设备ID>" \\
     -ice-servers "turn:${user}:${pwd}@${ip}:3478?transport=udp,stun:${ip}:3478"
========================================================
EOF
    echo "连接信息已成功记录到本地文件: docker/connection_info.txt"
}

# ==============================================================================
# 核心流程：部署 (Deploy)
# ==============================================================================
do_deploy() {
    echo "========================================================"
    echo "      开始部署 CloudPhone 云端服务架构 (含中转)"
    echo "========================================================"

    # 1. 智能检测运行环境
    RUN_IN_RELEASE="false"
    if [ -d "${PROJECT_ROOT}/../bin" ] && [ -d "${PROJECT_ROOT}/../assets" ]; then
        RUN_IN_RELEASE="true"
        SRC_BIN_DIR="${PROJECT_ROOT}/../bin"
        SRC_ASSETS_DIR="${PROJECT_ROOT}/../assets"
        echo "[环境检测] 运行于打包后的 release/docker 目录"
    elif [ -d "${PROJECT_ROOT}/../release/bin" ] && [ -d "${PROJECT_ROOT}/../release/assets" ]; then
        SRC_BIN_DIR="${PROJECT_ROOT}/../release/bin"
        SRC_ASSETS_DIR="${PROJECT_ROOT}/../release/assets"
        echo "[环境检测] 运行于开发环境"
    else
        echo "错误: 未找到 bin/ 和 assets/ 资源目录！请确保在 docker 目录下运行。"
        exit 1
    fi

    # 2. 探测 CPU 架构并校验二进制
    ARCH=$(uname -m)
    if [[ "$ARCH" == "x86_64" ]] || [[ "$ARCH" == "amd64" ]]; then
        OS_ARCH="linux_amd64"
    else
        OS_ARCH="linux_arm64"
    fi

    TARGET_BIN="${SRC_BIN_DIR}/${OS_ARCH}/webrtc-signaling"

    if [ "$RUN_IN_RELEASE" = "true" ]; then
        if [ ! -f "$TARGET_BIN" ]; then
            echo "错误: 未在 ${SRC_BIN_DIR}/${OS_ARCH}/ 目录中找到 webrtc-signaling 二进制文件！"
            exit 1
        fi
    else
        # 开发环境若无产物，自动构建
        if [ ! -f "$TARGET_BIN" ] || [ ! -d "${SRC_ASSETS_DIR}" ]; then
            echo "未检测到本地打包产物，开始自动调用 ../build_all.sh 编译源码..."
            chmod +x "${PROJECT_ROOT}/../build_all.sh"
            (cd "${PROJECT_ROOT}/.." && ./build_all.sh)
        fi
    fi

    # 3. 准备临时 Docker 构建上下文
    echo "正在为 Docker 引擎准备临时构建上下文..."
    rm -f "${PROJECT_ROOT}/webrtc-signaling"
    rm -rf "${PROJECT_ROOT}/assets"
    rm -rf "${PROJECT_ROOT}/certs"

    cp "${TARGET_BIN}" "${PROJECT_ROOT}/webrtc-signaling"
    cp -r "${SRC_ASSETS_DIR}" "${PROJECT_ROOT}/assets"
    
    # 默认创建空的 certs 目录以防 Docker 引擎在 COPY certs 时因目录不存在而报错
    mkdir -p "${PROJECT_ROOT}/certs"
    
    # 复制 certs 证书以支持容器内默认 HTTPS (TLS)
    if [ -d "${PROJECT_ROOT}/../certs" ] && [ "$(ls -A "${PROJECT_ROOT}/../certs" 2>/dev/null)" ]; then
        cp -r "${PROJECT_ROOT}/../certs"/* "${PROJECT_ROOT}/certs/"
    elif [ -d "${PROJECT_ROOT}/../release/certs" ] && [ "$(ls -A "${PROJECT_ROOT}/../release/certs" 2>/dev/null)" ]; then
        cp -r "${PROJECT_ROOT}/../release/certs"/* "${PROJECT_ROOT}/certs/"
    fi
    echo "上下文准备完毕。"

    # 确保脚本退出时清理临时构建文件
    cleanup() {
        echo "正在清理临时构建介质..."
        rm -f "${PROJECT_ROOT}/webrtc-signaling"
        rm -rf "${PROJECT_ROOT}/assets"
        rm -rf "${PROJECT_ROOT}/certs"
        echo "清理完毕。"
    }
    trap cleanup EXIT

    # 4. 自动探测或引导输入公网 IP
    echo "正在自动探测服务器公网 IP..."
    DETECTED_IP=""
    DETECTED_IP=$(curl -s -4 --connect-timeout 5 https://ifconfig.me || curl -s -4 --connect-timeout 5 https://api.ipify.org || echo "")

    if [ -n "$DETECTED_IP" ]; then
        read -p "探测到公网 IP 为 [ $DETECTED_IP ]，是否使用此 IP? (Y/n，或直接输入您要使用的自定义 IP): " confirm
        # 去除输入的首尾空格
        confirm=$(echo "$confirm" | xargs)
        
        if [ -z "$confirm" ] || [[ "$confirm" =~ ^[Yy]$ ]]; then
            PUBLIC_IP=$DETECTED_IP
        elif [[ "$confirm" =~ ^[Nn]$ ]]; then
            read -p "请输入您服务器的真实 IP: " PUBLIC_IP
        else
            # 用户直接输入了自定义的 IP 地址（既不是确认，也不是否定）
            PUBLIC_IP=$confirm
        fi
    else
        echo "警告: 无法自动获取公网 IP，请在下面手动输入。"
        read -p "请输入您服务器的真实 IP: " PUBLIC_IP
    fi

    if [ -z "$PUBLIC_IP" ]; then
        echo "错误: 公网 IP 不能为空！"
        exit 1
    fi

    # 5. 生成或复用中转凭证
    # 优先从环境变量读取，其次尝试从现有的 .env 文件中复用，最后才会随机生成
    EXISTING_USER=""
    EXISTING_PWD=""
    if [ -f "${ENV_FILE}" ]; then
        EXISTING_USER=$(grep "^TURN_USER=" "${ENV_FILE}" | cut -d'=' -f2-)
        EXISTING_PWD=$(grep "^TURN_PASSWORD=" "${ENV_FILE}" | cut -d'=' -f2-)
    fi

    TURN_USER=${TURN_USER:-${EXISTING_USER}}
    TURN_PASSWORD=${TURN_PASSWORD:-${EXISTING_PWD}}

    if [ -z "${TURN_USER}" ] || [ -z "${TURN_PASSWORD}" ]; then
        echo "未检测到已有凭证，生成随机且高强度中转连接安全凭证..."
        TURN_USER="cp_user_$(openssl rand -hex 3 2>/dev/null || echo "admin")"
        TURN_PASSWORD="$(openssl rand -hex 12 2>/dev/null || echo "cloudphone_pass_secret")"
    else
        echo "检测到已有的凭证配置，将直接复用旧的 TURN 凭证 (用户: ${TURN_USER})"
    fi

    # 在渲染新配置和构建新镜像前，先执行备份
    do_backup

    # 6. 渲染生成配置文件
    echo "开始渲染配置及环境参数..."

    # 6.1 生成 .env
    SIGNALING_PORT=${SIGNALING_PORT:-8443}
    DEFAULT_SETTINGS=${DEFAULT_SETTINGS:-'{"maxBitrate":4,"minBitrate":1,"fps":30,"size":1920,"bitrate":4}'}

    cat <<EOF > "${ENV_FILE}"
# 自动生成的环境变量配置文件
PUBLIC_IP=${PUBLIC_IP}
TURN_USER=${TURN_USER}
TURN_PASSWORD=${TURN_PASSWORD}
SIGNALING_PORT=${SIGNALING_PORT}
DEFAULT_SETTINGS=${DEFAULT_SETTINGS}
USE_TLS=true
EOF
    echo "已生成环境配置文件: .env"

    # 6.2 渲染生成 turnserver.conf
    if [ -f "${COTURN_DIR}/turnserver.conf.template" ]; then
        sed -e "s/{{TURN_USER}}/${TURN_USER}/g" \
            -e "s/{{TURN_PASSWORD}}/${TURN_PASSWORD}/g" \
            -e "s/{{PUBLIC_IP}}/${PUBLIC_IP}/g" \
            "${COTURN_DIR}/turnserver.conf.template" > "${TURN_CONF}"
        echo "已成功渲染并生成中转配置文件: coturn/turnserver.conf"
    else
        echo "错误: 未找到 coturn/turnserver.conf.template"
        exit 1
    fi

    # 7. 构建信令服务器 Docker 镜像
    echo "构建信令与前端一体化轻量 Docker 镜像..."
    docker build -t cloudphone-all-in-one "${PROJECT_ROOT}"

    # 8. 拉起 Docker Compose 服务
    echo "拉起 Docker Compose 容器集群..."
    (
        cd "${PROJECT_ROOT}"
        $COMPOSE_CMD down || true
        # 强制清理可能冲突的同名容器，防止因为非当前 compose 项目管理的残留容器导致启动失败
        echo "清理可能残留的冲突容器..."
        docker rm -f cloudphone-signaling cloudphone-coturn 2>/dev/null || true
        $COMPOSE_CMD up -d
    )

    # 9. 记录连接方式到本地文件
    write_connection_info "$PUBLIC_IP" "$TURN_USER" "$TURN_PASSWORD" "$SIGNALING_PORT"

    echo "========================================================"
    echo "                  🎉 部署服务已成功启动！"
    echo "========================================================"
    if [ -f "${CONN_INFO}" ]; then
        cat "${CONN_INFO}"
    fi
}

# ==============================================================================
# 核心流程：回滚 (Rollback)
# ==============================================================================
do_rollback() {
    echo "========================================================"
    echo "      开始回滚到 CloudPhone 上一次部署的版本"
    echo "========================================================"

    # 1. 校验备份文件是否存在
    if [ ! -f "${ENV_BAK}" ]; then
        echo "错误: 未找到配置文件备份 ${ENV_BAK}，无法回滚！"
        exit 1
    fi

    # 2. 校验备份镜像是否存在
    if ! docker image inspect cloudphone-all-in-one:rollback >/dev/null 2>&1; then
        echo "错误: 未找到上个版本的备份镜像 cloudphone-all-in-one:rollback，无法回滚！"
        exit 1
    fi

    # 3. 停止当前容器
    cd "${PROJECT_ROOT}"
    echo "停止当前运行的容器..."
    $COMPOSE_CMD down || true

    # 4. 恢复配置文件
    echo "恢复备份的配置文件..."
    cp "${ENV_BAK}" "${ENV_FILE}"
    if [ -f "${TURN_CONF_BAK}" ]; then
        cp "${TURN_CONF_BAK}" "${TURN_CONF}"
    fi

    # 5. 还原镜像 tag
    echo "恢复备份的 Docker 镜像..."
    docker tag cloudphone-all-in-one:rollback cloudphone-all-in-one:latest
    docker rmi cloudphone-all-in-one:rollback || true

    # 6. 重新启动服务
    echo "重新拉起旧版本容器服务..."
    $COMPOSE_CMD up -d

    # 7. 从恢复后的 .env 重新生成 connection_info.txt
    if [ -f "${ENV_FILE}" ]; then
        PUBLIC_IP=$(grep "^PUBLIC_IP=" "${ENV_FILE}" | cut -d'=' -f2-)
        TURN_USER=$(grep "^TURN_USER=" "${ENV_FILE}" | cut -d'=' -f2-)
        TURN_PASSWORD=$(grep "^TURN_PASSWORD=" "${ENV_FILE}" | cut -d'=' -f2-)
        SIGNALING_PORT=$(grep "^SIGNALING_PORT=" "${ENV_FILE}" | cut -d'=' -f2-)
        
        write_connection_info "$PUBLIC_IP" "$TURN_USER" "$TURN_PASSWORD" "$SIGNALING_PORT"
    fi

    echo "========================================================"
    echo "                  🎉 回滚已成功完成！"
    echo "========================================================"
    if [ -f "${CONN_INFO}" ]; then
        cat "${CONN_INFO}"
    fi
}

# ==============================================================================
# 核心流程：卸载 (Uninstall)
# ==============================================================================
do_uninstall() {
    echo "========================================================"
    echo "      开始卸载 CloudPhone 容器服务及清理相关资源"
    echo "========================================================"

    # 停止并删除容器
    if [ -f "${PROJECT_ROOT}/docker-compose.yml" ]; then
        echo "正在停止并删除 Docker 容器..."
        cd "${PROJECT_ROOT}"
        $COMPOSE_CMD down || true
        # 强制清理可能残留的同名容器
        echo "强制清理可能冲突的同名容器..."
        docker rm -f cloudphone-signaling cloudphone-coturn 2>/dev/null || true
    fi

    # 删除 Docker 镜像
    echo "正在删除本地构建的镜像..."
    docker rmi cloudphone-all-in-one:latest 2>/dev/null || true
    docker rmi cloudphone-all-in-one:rollback 2>/dev/null || true

    # 清理 dangling 镜像
    echo "清理未使用的 Docker 镜像缓存..."
    docker image prune -f

    # 删除临时配置文件和连接信息文件
    echo "正在清理本地配置文件及连接记录..."
    rm -f "${ENV_FILE}" "${ENV_BAK}"
    rm -f "${TURN_CONF}" "${TURN_CONF_BAK}"
    rm -f "${CONN_INFO}"

    echo "========================================================"
    echo "                  🎉 卸载与清理完成！"
    echo "========================================================"
}

# ==============================================================================
# 执行分流
# ==============================================================================
check_docker_environment

case "$ACTION" in
    deploy)
        do_deploy
        ;;
    rollback)
        do_rollback
        ;;
    uninstall)
        do_uninstall
        ;;
esac
