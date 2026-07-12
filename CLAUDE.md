# CLAUDE.md — teslaScreenFlow 项目配置

## 项目概述
**teslaScreenFlow** — Android 端屏幕投屏 App，将手机屏幕与音频通过 WebRTC 实时串流至特斯拉焕新 Model 3 的中控 Chromium 浏览器。

## 核心架构
```
┌─────────────────────┐      WebRTC (H.264 + OPUS)     ┌─────────────────────┐
│   Android 手机端     │ ◄─────────────────────────────► │  特斯拉车机浏览器     │
│  (WebRTC Offer 方)  │                                  │  (WebRTC Answer 方) │
│                     │      WebSocket (信令 + 触控)      │                     │
│  - MediaProjection  │ ◄─────────────────────────────► │  - index.html       │
│  - AudioCapture     │                                  │  - 触控事件捕获      │
│  - NanoHTTPD :8080  │                                  │                     │
│  - WebSocket :8081  │                                  │                     │
└─────────────────────┘                                  └─────────────────────┘
```

## 技术栈
- **语言**: Java (Service 层) + Kotlin (Manager 层) + JavaScript (前端)
- **构建**: Gradle 8.9 (Kotlin DSL)
- **核心依赖**:
  - `org.webrtc:google-webrtc:1.0.32006`
  - `org.nanohttpd:nanohttpd:2.3.1`
  - `org.java-websocket:Java-WebSocket:1.5.3`
  - `androidx.core:core-ktx:1.13.1`
- **Android 配置**:
  - `minSdk: 29` (Android 10，AudioPlaybackCapture)
  - `targetSdk: 34` / `compileSdk: 35`
  - `namespace/applicationId: com.tesla.screenflow`

## 项目结构
```
teslaScreenFlow/
├── app/
│   ├── src/main/
│   │   ├── java/com/tesla/screenflow/
│   │   │   ├── ScreenCaptureService.java  — 前台服务，MediaProjection 管理
│   │   │   ├── WebRTCManager.kt           — PeerConnection 生命周期
│   │   │   ├── WebServer.kt               — NanoHTTPD 托管前端页面
│   │   │   ├── SignalingServer.kt         — WebSocket 信令服务
│   │   │   ├── TouchSimulator.kt          — AccessibilityService 触控模拟
│   │   │   └── MainActivity.kt            — 主控制界面
│   │   ├── assets/index.html              — 车机端前端页面
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/
├── gradlew.bat
├── README.md
└── .github/workflows/android-ci.yml
```

## 关键设计决策
1. **手机端 Offer 方**: 手机拥有完整媒体能力，主动生成 SDP Offer
2. **H.264 硬件编码**: 4Mbps 60fps 1920x1080，利用 Android 硬件编码器
3. **OPUS 音频**: 目标 < 150ms 端到端延迟
4. **无障碍服务触控**: `dispatchGesture()` 模拟触控
5. **前台服务**: 保证屏幕录制后台持续运行
6. **多客户端预留**: 信令层支持多客户端（后排屏幕扩展）

## 开发规范
- **命名**: Java/Kotlin PascalCase, 资源 snake_case
- **日志**: `android.util.Log`, TAG 格式 `TeslaScreenFlow::ClassName`
- **线程**: WebRTC 操作在专用线程，UI 回主线程
- **前端**: 极简设计，零外部依赖，适配 16:9/16:10

## 环境
- Java: Android Studio JBR (JDK 21)
- Android SDK: `C:\Users\Admin\AppData\Local\Android\Sdk`
- 代理: `http://localhost:60130` (http/https/git/npm/pip 均使用，跳过 SSL 验证)
