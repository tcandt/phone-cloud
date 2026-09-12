FROM alpine:latest
# 使用阿里镜像源以加速国内环境下的 apk 依赖安装，避免由于网络超时导致构建卡死
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories && \
    apk add --no-cache bash ca-certificates
WORKDIR /app

# 从当前构建上下文根目录拷贝（由 deploy_cloud.sh 临时复制至此）
COPY webrtc-signaling /app/webrtc-signaling
COPY assets /app/assets
COPY certs /app/certs

# 运行期默认环境变量
ENV PORT=8443
ENV HOST=0.0.0.0
ENV ASSETS=/app/assets
ENV ICE_SERVERS=""
ENV USE_TLS=true
ENV TLS_CERT=/app/certs/server.crt
ENV TLS_KEY=/app/certs/server.key
ENV DATA_DIR=/app/data

EXPOSE 8443

# 运行信令服务
ENTRYPOINT ["/app/webrtc-signaling"]
