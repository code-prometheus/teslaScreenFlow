# teslaScreenFlow

> 特斯拉焕新 Model 3 车机屏幕投屏 App — 将 Android 手机屏幕与音频通过 WebRTC 实时串流至车机 Chromium 浏览器。

[![Android CI](https://github.com/code-prometheus/teslaScreenFlow/actions/workflows/android-ci.yml/badge.svg)](https://github.com/code-prometheus/teslaScreenFlow/actions/workflows/android-ci.yml)

## 架构

```
┌──────────────────────┐      WebRTC (H.264 + OPUS)     ┌──────────────────────┐
│   Android 手机端       │ ◄───────────────────────────► │  特斯拉车机浏览器       │
│  (WebRTC Offer 方)    │                                │  (WebRTC Answer 方)   │
│                       │      WebSocket (信令 + 触控)     │                       │
│  - MediaProjection    │ ◄───────────────────────────► │  - index.html         │
│  - AudioCapture       │                                │  - 触控事件捕获        │
│  - NanoHTTPD :8080    │                                │                       │
│  - WebSocket  :8081   │                                │                       │
└──────────────────────┘                                └──────────────────────┘
```

## 特性

- **低延迟投屏**: H.264 硬件编码 4Mbps 60fps，OPUS 音频编码 < 150ms 延迟
- **触控回传**: 车机浏览器触控事件通过 WebSocket 回传，手机端通过无障碍服务模拟执行
- **系统音频捕获**: Android 10+ AudioPlaybackCapture API 捕获系统音频
- **多客户端预留**: 信令层支持多客户端，为后排屏幕扩展做准备
- **自适应屏幕**: 前端适配 16:9/16:10，全屏隐藏鼠标

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Java (Service) + Kotlin (Manager) + JavaScript (前端) |
| 构建 | Gradle 8.9 + Kotlin DSL |
| WebRTC | google-webrtc:1.0.32006 |
| HTTP | NanoHTTPD 2.3.1 |
| WebSocket | Java-WebSocket 1.5.3 |
| 最低 SDK | Android 10 (API 29) |
| 目标 SDK | Android 14 (API 34) |

## 快速开始

### 环境要求

- JDK 17+
- Android SDK (build-tools 35.x, platform android-35)
- Android Studio (推荐)

### 构建

```bash
# 克隆仓库
git clone https://github.com/code-prometheus/teslaScreenFlow.git
cd teslaScreenFlow

# 构建 Debug APK
./gradlew assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 使用

1. 打开 **teslaScreenFlow** App
2. 点击「开始投屏」，授权屏幕录制权限
3. 在手机设置中开启 **teslaScreenFlow 无障碍服务**（用于触控回传）
4. 在特斯拉车机浏览器中访问 `http://<手机IP>:8080`
5. 车机屏幕将显示手机画面，触控操作可回传至手机

## 项目结构

```
teslaScreenFlow/
├── app/
│   ├── src/main/
│   │   ├── java/com/tesla/screenflow/
│   │   │   ├── MainActivity.kt           — 主控制界面
│   │   │   ├── ScreenCaptureService.java — 前台服务，MediaProjection 管理
│   │   │   ├── WebRTCManager.kt          — PeerConnection 生命周期
│   │   │   ├── WebServer.kt              — NanoHTTPD 托管前端
│   │   │   ├── SignalingServer.kt        — WebSocket 信令服务
│   │   │   └── TouchSimulator.kt         — 无障碍触控模拟
│   │   ├── assets/
│   │   │   └── index.html                — 车机端前端页面
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── .github/workflows/android-ci.yml
```

## 目标车型

- **特斯拉焕新 Model 3** (Highland) — 15 英寸中控屏 + 8 英寸后排屏幕
- 车机系统: 内置 Chromium 浏览器，支持 WebRTC

## License

MIT License

---

🤖 Generated with [Claude Code](https://claude.ai/code)
