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

## 技术要求

| 项目 | 要求 |
|------|------|
| 手机系统 | Android 10 (API 29) 及以上 |
| 车机型号 | 特斯拉焕新 Model 3 (Highland) + 后排屏幕 |
| 网络 | 手机开启 Wi-Fi 热点，车机连接该热点 |
| 手机浏览器 | 任意现代浏览器（用于首次查看 IP） |
| 车机浏览器 | 特斯拉内置 Chromium（默认支持 WebRTC） |

## 安装到真实手机

### 方式一：下载 Release APK（推荐 ⭐）

1. 打开手机浏览器，访问 [Releases 页面](https://github.com/code-prometheus/teslaScreenFlow/releases)
2. 下载最新 `teslaScreenFlow-vX.X.X.apk`
3. 打开 APK 文件，允许「未知来源」安装
4. 安装完成 ✅

### 方式二：通过 ADB 安装（开发者）

```bash
# 1. 手机开启「开发者选项」→「USB 调试」
# 2. USB 连接电脑，手机弹出「允许 USB 调试」→ 点允许

adb devices                          # 确认设备已连接
adb install app-release.apk          # 安装 APK
```

### 方式三：自行编译安装

**环境准备**:
- JDK 17+（Android Studio 自带即可）
- Android SDK Platform 35 + Build-Tools 35.x
- 手机已开启「USB 调试」

```bash
# 1. 克隆仓库
git clone https://github.com/code-prometheus/teslaScreenFlow.git
cd teslaScreenFlow

# 2. 设置 Android SDK 路径（Windows 示例）
set ANDROID_HOME=C:\Users\%USERNAME%\AppData\Local\Android\Sdk
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr

# 3. 编译 Debug APK
gradlew.bat assembleDebug

# 4. 安装到手机
adb install app\build\outputs\apk\debug\app-debug.apk
```

> **macOS / Linux**: 将 `gradlew.bat` 替换为 `./gradlew`，路径使用正斜杠。

## 使用步骤

### 第 1 步：手机开启热点 📱

1. 打开手机「设置」→「热点与网络共享」→「便携式 WLAN 热点」
2. 设置一个简单的热点名称（如 `TeslaMirror`）和密码
3. 记下热点名称和密码

### 第 2 步：车机连接手机热点 🚗

1. 在特斯拉中控屏点击 Wi-Fi 图标
2. 连接到手机的热点
3. 确认车机浏览器可以正常上网（蓝牙连接走流量，Wi-Fi 走局域网）

### 第 3 步：启动投屏

1. 打开 **teslaScreenFlow** App
2. App 界面会显示手机 Wi-Fi IP 地址和服务端口
3. 点击 **「开始投屏」**
4. 系统弹出「屏幕录制」授权 → **点「立即开始」**
5. 如果提示需要无障碍服务 → 点「去设置」→ 找到 **teslaScreenFlow** → 开启

### 第 4 步：车机浏览器连接

1. 在车机 Chromium 浏览器地址栏输入:
   ```
   http://手机IP地址:8080
   ```
   > 例如: `http://192.168.43.1:8080`（手机热点默认 IP 通常是 `192.168.43.1`）

2. 车机屏幕将显示手机画面 🎉
3. 触控车机屏幕可反向操控手机

> **提示**: 如果车机 IP 不是 `192.168.43.1`，打开 App 主界面可以看到实际 IP。

## 连接拓扑

```
                    手机热点 Wi-Fi
          ┌─────────────────────────────────┐
          │                                 │
    ┌─────▼─────┐                    ┌──────┴──────┐
    │ Android   │   WebRTC 视频流     │  特斯拉车机   │
    │ 手机       │ ◄────────────────► │  Chromium   │
    │ :8080     │   WebSocket 触控   │  浏览器      │
    │ :8081     │ ◄────────────────► │             │
    └───────────┘                    └─────────────┘
```

## 权限说明

| 权限 | 用途 | 是否必须 |
|------|------|----------|
| 屏幕录制 (MediaProjection) | 捕获屏幕画面编码为 H.264 | ✅ 必须 |
| 无障碍服务 (AccessibilityService) | 接收车机触控事件并模拟点击 | ✅ 必须（触控回传） |
| 前台服务 (Foreground Service) | 投屏时保持后台运行 | ✅ 必须 |
| 网络 (Internet / Wi-Fi) | Web 服务器 + WebSocket | ✅ 必须 |

> 系统音频捕获 (AudioPlaybackCapture) 需要 Android 10+ 且 App 需使用系统签名或用户手动在 ADB 授权。详见 [常见问题](#常见问题)。

## 常见问题

### Q: 车机浏览器打开页面后一直显示 "Connecting..."

1. 确认手机和车机连接的是同一个 Wi-Fi 热点
2. 确认手机 IP 地址输入正确（App 主界面会显示）
3. 检查手机是否开启了 VPN 或防火墙 — 临时关闭

### Q: 视频延迟大 / 卡顿

1. 确保手机和车机之间没有障碍物
2. 尝试在 App 中将分辨率降至 720p（后续版本支持）
3. 关闭手机后台高耗电应用

### Q: 触控不生效

1. 确认已在系统设置中开启了 **teslaScreenFlow 无障碍服务**
2. 路径：设置 → 辅助功能 → 无障碍 → 已下载的应用 → teslaScreenFlow → 开启
3. 部分手机品牌（小米、OPPO）需要在「自启动」中允许该服务

### Q: 系统声音无法传输（只有画面没有声音）

Android 10+ 的 AudioPlaybackCapture 需要额外授权。在电脑上执行:

```bash
adb shell appops set com.tesla.screenflow PROJECT_MEDIA allow
```

或在开发者选项中开启「允许音频捕获」。

## 发布 Release

### 自动构建（GitHub Actions）

每次推送到 `main` 分支或创建 PR，CI 会自动编译 Debug APK。

### 手动构建 Release APK

```bash
# 1. 生成签名密钥（仅首次）
keytool -genkey -v -keystore tesla-screenflow.jks \
  -alias tesla-screenflow -keyalg RSA -keysize 2048 -validity 10000

# 2. 在项目根目录创建 keystore.properties
cat > keystore.properties << 'EOF'
storeFile=tesla-screenflow.jks
storePassword=你的密钥库密码
keyAlias=tesla-screenflow
keyPassword=你的密钥密码
EOF

# 3. 编译 Release APK
./gradlew assembleRelease

# 4. APK 输出路径
# app/build/outputs/apk/release/app-release.apk
```

### 创建 GitHub Release

1. 访问 [Releases 页面](https://github.com/code-prometheus/teslaScreenFlow/releases)
2. 点击 **「Draft a new release」**
3. Tag: `v1.0.0`（与 `versionName` 一致）
4. 标题: `teslaScreenFlow v1.0.0`
5. 上传 `app-release.apk` 作为附件
6. 点击 **「Publish release」**

或者用 `gh` CLI:

```bash
gh release create v1.0.0 \
  --title "teslaScreenFlow v1.0.0" \
  --notes "首次发布：支持 WebRTC 屏幕镜像到特斯拉焕新 Model 3" \
  app/build/outputs/apk/release/app-release.apk
```

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

## 项目结构

```
teslaScreenFlow/
├── app/
│   ├── src/main/
│   │   ├── java/com/tesla/screenflow/
│   │   │   ├── MainActivity.kt              — 主控制界面
│   │   │   ├── ScreenCaptureService.java     — 前台服务，MediaProjection 管理
│   │   │   ├── WebRTCManager.kt             — PeerConnection 生命周期
│   │   │   ├── WebServer.kt                 — NanoHTTPD 托管前端
│   │   │   ├── SignalingServer.kt            — WebSocket 信令服务
│   │   │   └── TouchSimulator.kt            — 无障碍触控模拟
│   │   ├── assets/
│   │   │   └── index.html                   — 车机端前端页面
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew.bat
├── CLAUDE.md
└── .github/workflows/android-ci.yml
```

## 目标车型

- **特斯拉焕新 Model 3** (Highland) — 15 英寸中控屏 + 8 英寸后排屏幕
- 车机系统: 内置 Chromium 浏览器，支持 WebRTC

## License

MIT License

---

🤖 Generated with [Claude Code](https://claude.ai/code)
