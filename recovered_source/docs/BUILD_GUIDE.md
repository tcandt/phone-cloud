# Hướng Dẫn Biên Dịch & Triển Khai Từ Đầu (Build Guide)

Tài liệu này cung cấp hướng dẫn từng bước để biên dịch và đóng gói toàn bộ hệ thống ScrcpyOverWebRTC v0.3.6 trên Windows, Linux và Android.

---

## 1. Yêu Cầu Môi Trường (Prerequisites)

| Công cụ / SDK | Phiên bản tối thiểu | Mục đích sử dụng |
|---|---|---|
| **Go** | 1.22+ | Biên dịch `webrtc-signaling` và `cloudphone-agent` |
| **Java JDK** | 17+ | Biên dịch `android-helper` (`libsys_core.so`) |
| **Android SDK / Gradle** | AGP 8.x, SDK 34/36 | Đóng gói mã nguồn Android helper sang DEX |
| **Node.js & npm** | Node 18+, npm 9+ | Biên dịch giao diện bảng điều khiển `web-app` |
| **ADB (Android Debug Bridge)** | Android Platform Tools | Triển khai và gỡ lỗi trên thiết bị Android |

---

## 2. Biên Dịch Máy Chủ Tín Hiệu (`webrtc-signaling`)

Mã nguồn nằm tại: [`recovered_source/webrtc-signaling`](file:///d:/ScrcpyOverWebRTC/recovered_source/webrtc-signaling)

### 2.1 Biên dịch trên Windows
```bash
cd recovered_source/webrtc-signaling
go mod tidy
go build -ldflags="-s -w" -o webrtc-signaling.exe .
```

### 2.2 Biên dịch cho Linux (Host hoặc Docker Container)
```bash
# Linux AMD64 (x86_64)
env GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o webrtc-signaling-linux-amd64 .

# Linux ARM64 (aarch64)
env GOOS=linux GOARCH=arm64 go build -ldflags="-s -w" -o webrtc-signaling-linux-arm64 .
```

### 2.3 Khởi chạy máy chủ độc lập (Standalone)
```bash
# Khởi chạy HTTPS + WSS (cần tệp chứng chỉ server.crt/server.key)
./webrtc-signaling -port 8443 -assets ../../cloudphone-v0.3.6/assets -cert certs/server.crt -key certs/server.key

# Khởi chạy HTTP + WS (chế độ phát triển nội bộ không cần SSL)
./webrtc-signaling -port 8443 -assets ../../cloudphone-v0.3.6/assets
```

---

## 3. Biên Dịch Android Agent (`cloudphone-agent`)

Mã nguồn nằm tại: [`recovered_source/cloudphone-agent`](file:///d:/ScrcpyOverWebRTC/recovered_source/cloudphone-agent)

Agent được thiết kế để chạy trực tiếp trên hệ điều hành Android (chế độ Root, Shizuku, hoặc người dùng Shell UID 2000). Vì vậy cần cross-compile sang kiến trúc Linux/Android tương ứng.

### 3.1 Biên dịch cho các kiến trúc Android

```bash
cd recovered_source/cloudphone-agent
go mod tidy

# 1. Android ARM64 (Hầu hết điện thoại Android hiện nay)
env GOOS=linux GOARCH=arm64 go build -ldflags="-s -w" -o cloudphone-agent-arm64 .

# 2. Android ARMv7 32-bit (Điện thoại đời cũ)
env GOOS=linux GOARCH=arm GOARM=7 go build -ldflags="-s -w" -o cloudphone-agent-armeabi-v7a .

# 3. Android AMD64 (Máy ảo Android x86_64, Redroid Docker)
env GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o cloudphone-agent-amd64 .
```

---

## 4. Biên Dịch Android Helper (`libsys_core.so` / `scrcpy-server`)

Mã nguồn nằm tại: [`recovered_source/android-helper`](file:///d:/ScrcpyOverWebRTC/recovered_source/android-helper)

Lớp dịch vụ này chịu trách nhiệm chạy ngầm thông qua `app_process` của Android, sử dụng `SurfaceControl`, `MediaCodec` và `InputManager` của hệ điều hành.

### 4.1 Biên dịch bằng Gradle
```bash
cd recovered_source/android-helper

# Biên dịch ra DEX bytecode
./gradlew assembleHelper
```

产 vật được tạo ra chính là tệp `libsys_core.so` (bản chất là tệp ZIP chứa `classes.dex`).

---

## 5. Biên Dịch Giao Diện Web (`web-app`)

Mã nguồn nằm tại: [`web-app`](file:///d:/ScrcpyOverWebRTC/web-app)

```bash
cd web-app

# 1. Cài đặt các gói phụ thuộc
npm install

# 2. Chạy môi trường phát triển (Hot Reloading)
# Trỏ proxy tới backend signaling đang chạy
VITE_PROXY_TARGET=http://localhost:8443 npm run dev

# 3. Đóng gói phân phối ra thư mục dist
npm run build
```

Sau khi build, thư mục `web-app/dist` chứa toàn bộ HTML/CSS/JS có thể sao chép trực tiếp vào thư mục `assets/` của `webrtc-signaling`.

---

## 6. Triển Khai Thiết Bị Android (Đưa máy vào mạng)

### 6.1 Triển khai bằng máy tính qua ADB (Không cần Root)
1. Bật tính năng **Gỡ lỗi USB (USB Debugging)** trên điện thoại Android.
2. Cắm cáp USB vào máy tính.
3. Đẩy tệp Agent và Helper vào thư mục tạm của Android:
   ```bash
   adb push cloudphone-agent-arm64 /data/local/tmp/cloudphone-agent
   adb push libsys_core.so /data/local/tmp/libsys_core.so
   adb shell chmod +x /data/local/tmp/cloudphone-agent
   ```
4. Khởi chạy Agent:
   ```bash
   adb shell "export CP_AGENT_JAR=/data/local/tmp/libsys_core.so; export CP_AGENT_SIGNALING=wss://<IP-MAY-CHU>:8443; export CP_AGENT_ID=my-phone; setsid nohup env GODEBUG=asyncpreemptoff=1 /data/local/tmp/cloudphone-agent > /data/local/tmp/cloudphone-agent.log 2>&1 &"
   ```

### 6.2 Triển khai qua Magisk / KernelSU (Cần Root, Tự khởi động cùng máy)
1. Sử dụng gói mẫu tại [`cloudphone-agent-magisk-v0.3.6`](file:///d:/ScrcpyOverWebRTC/cloudphone-agent-magisk-v0.3.6).
2. Thay thế `binaries/cloudphone-agent-arm64` và `libsys_core.so` bằng tệp vừa biên dịch.
3. Nén lại thành file zip và cài đặt trong Magisk Manager.
4. Cấu hình địa chỉ máy chủ trong `/data/adb/modules/cloudphone-agent/config.conf`:
   ```bash
   CP_AGENT_SIGNALING="wss://<IP-MAY-CHU>:8443"
   CP_AGENT_ID="my-phone-root"
   ```
