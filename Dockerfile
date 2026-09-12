# ==============================================================================
# ScrcpyOverWebRTC - Complete Pure Source Multi-Stage Dockerfile
# Reconstructs: Frontend (Vue 3/Vite Production), Backend (Go Signaling), Agent (Go)
# ==============================================================================

# ------------------------------------------------------------------------------
# Stage 1: Build Vue 3 Web Frontend (Production)
# ------------------------------------------------------------------------------
FROM node:20-alpine AS web-builder
WORKDIR /build/web-app

# Install dependencies
COPY web-app/package*.json ./
RUN npm ci || npm install

# Build Vue SPA for production into dist/
COPY web-app/ ./
ENV VITE_OUT_DIR=dist
RUN npm run build

# ------------------------------------------------------------------------------
# Stage 2: Build Pure Go Binaries (Signaling Backend & Agent)
# ------------------------------------------------------------------------------
FROM golang:1.22-alpine AS go-builder
WORKDIR /build

# Build webrtc-signaling
COPY recovered_source/webrtc-signaling/ /build/webrtc-signaling/
WORKDIR /build/webrtc-signaling
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /out/webrtc-signaling .

# Build cloudphone-agent for Android and Linux targets
COPY recovered_source/cloudphone-agent/ /build/cloudphone-agent/
WORKDIR /build/cloudphone-agent
RUN CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -ldflags="-s -w" -o /out/cloudphone-agent-arm64 . && \
    CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 go build -ldflags="-s -w" -o /out/cloudphone-agent-armeabi-v7a . && \
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o /out/cloudphone-agent-amd64 .

# ------------------------------------------------------------------------------
# Stage 3: Minimal Production Runtime Container with TLS & TURN Ready
# ------------------------------------------------------------------------------
FROM alpine:3.20

LABEL maintainer="ScrcpyOverWebRTC Project"
LABEL version="0.3.6"
LABEL description="Complete ScrcpyOverWebRTC Platform running purely from reconstructed source"

RUN apk add --no-cache ca-certificates tzdata su-exec

WORKDIR /app

# Copy binaries
COPY --from=go-builder /out/webrtc-signaling /app/webrtc-signaling
COPY --from=go-builder /out/cloudphone-agent* /app/agent-binaries/

# Copy web frontend production assets
COPY --from=web-builder /build/web-app/dist /app/assets

# Copy certificates
COPY cloudphone-v0.3.6/certs/ /app/certs/

# Create persistence and runtime directories
RUN mkdir -p /app/data /app/downloads /app/certs && chmod -R 777 /app/data /app/downloads /app/certs

# Configure runtime environment
ENV DATA_DIR=/app/data
ENV DOWNLOADS_DIR=/app/downloads

# Expose HTTP (8000) and HTTPS/WSS (8443)
EXPOSE 8000 8443

# Healthcheck probe supporting both TLS and plain HTTP
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --quiet --tries=1 --no-check-certificate --spider https://127.0.0.1:8443/api/version || wget --quiet --tries=1 --spider http://127.0.0.1:8000/api/version || exit 1

ENTRYPOINT ["/app/webrtc-signaling"]
CMD ["-port", "8443", "-assets", "/app/assets", "-cert", "/app/certs/server.crt", "-key", "/app/certs/server.key"]
