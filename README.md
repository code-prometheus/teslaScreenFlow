# teslaScreenFlow

Android 手机屏幕投屏至特斯拉焕新 Model 3 中控浏览器。

**纯 WebSocket + JPEG 流，无需 WebRTC，WiFi 和手机热点均可使用。**

[![Android CI](https://github.com/code-prometheus/teslaScreenFlow/actions/workflows/android-ci.yml/badge.svg)](https://github.com/code-prometheus/teslaScreenFlow/actions/workflows/android-ci.yml)

## 原理

```
手机端                          特斯拉车机中控
┌──────────────┐    HTTP :8080   ┌────────────────┐
│ ImageReader  │ ◄─────────────► │ <img> 渲染     │
│ VirtualDisplay│   WebSocket    │ 触控 → WS JSON │
│ → JPEG 帧    │   binary 帧    │                │
└──────────────┘                 └────────────────┘
```

- **手机端**：`MediaProjection` + `ImageReader` + `VirtualDisplay` 捕获屏幕 → JPEG → NanoWSD WebSocket binary 帧推送
- **车机端**：浏览器打开 `http://手机IP:8080`，WebSocket 接收 JPEG → `<img>` 双缓冲渲染
- **触控回传**：车机触控事件 → WebSocket JSON → `AccessibilityService.dispatchGesture()`
- **屏幕旋转**：`DisplayManager.DisplayListener` 自动重建 `VirtualDisplay`

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Kotlin |
| 构建 | Gradle 8.9 + Kotlin DSL |
| HTTP + WebSocket | NanoHTTPD + NanoWSD（单端口 8080） |
| 屏幕捕获 | MediaProjection + ImageReader + VirtualDisplay |
| 触控模拟 | AccessibilityService (dispatchGesture) |
| 前端 | 零依赖纯 HTML/CSS/JS |
| minSdk / targetSdk | 29 (Android 10) / 34 |

## 为什么是 JPEG over WebSocket 而不是 WebRTC？

华为手机热点（和其他部分 Android 热点实现）的 iptables/netfilter 会阻止 AP↔STA 方向的入站 UDP/TCP 连接（除已建立连接的端口外）。WebRTC ICE 协商需要随机端口双向通信，在热点模式下必然失败。而 WebSocket 走 8080 端口 TCP（HTTP 已验证可通），不存在此问题。

## 安装

```bash
# 编译 Debug APK
./gradlew assembleDebug

# 安装到手机
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

或从 [Releases](https://github.com/code-prometheus/teslaScreenFlow/releases) 下载最新 APK。

## 使用

1. 打开 App → 允许录屏权限
2. 手机开热点，车机连接
3. 车机浏览器打开 `http://192.168.43.1:8080`
4. 开启无障碍服务以支持触控回传

## 项目结构

```
app/src/main/java/com/tesla/screenflow/
├── MainActivity.kt            — 主界面，一键启动
├── ScreenCaptureService.kt    — 前台服务，协调捕获和服务器
├── ScreenJpegCapture.kt       — ImageReader 屏幕捕获 → JPEG
├── TeslaWebServer.kt          — NanoWSD HTTP + WebSocket
├── TouchSimulator.kt          — 无障碍触控模拟
app/src/main/assets/
├── index.html                 — 车机端前端页面
app/src/main/java/fi/iki/elonen/
├── NanoHTTPD.java             — HTTP 服务器
├── NanoWSD.java               — WebSocket 服务器
```

## 权限

| 权限 | 用途 |
|------|------|
| 屏幕录制 (MediaProjection) | 捕获屏幕画面 |
| 无障碍服务 (AccessibilityService) | 触控回传 |
| 前台服务 (Foreground Service) | 后台持续运行 |
| 网络 (Internet / Wi-Fi) | HTTP + WebSocket 服务 |

## 发布

### 自动构建

推送 `main` 分支或创建 PR 触发 CI 编译 Debug APK。推送 tag（`v*`）触发 Release APK 构建并自动创建 GitHub Release。

### 手动构建 Release APK

```bash
# 首次：生成签名密钥
keytool -genkey -v -keystore tesla-screenflow.jks \
  -alias tesla-screenflow -keyalg RSA -keysize 2048 -validity 10000

# 创建 keystore.properties
cat > keystore.properties << 'EOF'
storeFile=tesla-screenflow.jks
storePassword=你的密钥库密码
keyAlias=tesla-screenflow
keyPassword=你的密钥密码
EOF

# 编译
./gradlew assembleRelease
```

### GitHub Release

```bash
gh release create v1.0.0 \
  --title "teslaScreenFlow v1.0.0" \
  --notes "纯 WebSocket + JPEG 屏幕投屏方案" \
  app/build/outputs/apk/release/app-release.apk
```

## License

MIT
