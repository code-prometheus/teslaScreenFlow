# CLAUDE.md — teslaScreenFlow 项目配置

## 项目概述
**teslaScreenFlow** — Android 端屏幕投屏 App，将手机屏幕通过 WebSocket + JPEG 实时串流至特斯拉焕新 Model 3 的中控浏览器。

## 核心架构
```
手机端                          特斯拉车机中控
┌──────────────┐    HTTP :8080   ┌────────────────┐
│ ImageReader  │ ◄─────────────► │ <img> 渲染     │
│ VirtualDisplay│   WebSocket    │ 触控 → WS JSON │
│ → JPEG 帧    │   binary 帧    │                │
└──────────────┘                 └────────────────┘
```

## 技术栈
- Kotlin
- **构建**: Gradle 8.9 (Kotlin DSL)
- **依赖**: `androidx.core:core-ktx`, `androidx.appcompat:appcompat`
- **内嵌**: NanoHTTPD.java + NanoWSD.java（单端口 HTTP + WebSocket）
- **Android 配置**: `minSdk: 29` / `targetSdk: 34` / `compileSdk: 35`

## 项目结构
```
app/src/main/java/com/tesla/screenflow/
├── MainActivity.kt            — 主界面，一键启动
├── ScreenCaptureService.kt    — 前台服务
├── ScreenJpegCapture.kt       — ImageReader 屏幕捕获 → JPEG
├── TeslaWebServer.kt          — NanoWSD HTTP + WebSocket
├── TouchSimulator.kt          — 无障碍触控模拟
app/src/main/assets/
├── index.html                 — 车机端前端
app/src/main/java/fi/iki/elonen/
├── NanoHTTPD.java
├── NanoWSD.java
```

## 关键设计决策
1. **JPEG over WebSocket**: 华为手机热点防火墙阻止 WebRTC ICE 入站连接，WebSocket 走 8080 TCP 已验证可通
2. **单端口 8080**: HTTP + WebSocket 共用，减少热点防火墙问题
3. **ImageReader + VirtualDisplay**: 直接捕获 Surface → RGBA → JPEG，不依赖硬件编码器
4. **DisplayListener**: 屏幕旋转自动重建捕获管线
5. **前台服务 + WakeLock**: 保证后台持续运行

## 开发规范
- **日志**: `android.util.Log`, TAG 格式 `TeslaScreenFlow::ClassName`
- **前端**: 极简设计，零外部依赖

## 环境
- Java: Android Studio JBR (JDK 21)
- Android SDK: `C:\Users\Admin\AppData\Local\Android\Sdk`
- 代理: `http://localhost:60130`（跳过 SSL 验证）
