<p align="center">
  <img src="docs/icon.svg" width="96" height="96" alt="Piko Icon" />
</p>

<h1 align="center">Piko</h1>

<p align="center">
  <strong>轻量、极速、现代的第三方 PikPak 跨平台客户端</strong>
</p>

<p align="center">
  <a href="https://github.com/NihilDigit/piko/releases/latest"><img src="https://img.shields.io/github/v/release/NihilDigit/piko?style=flat-square&color=306EFF" alt="Latest Release" /></a>
  <a href="https://github.com/NihilDigit/piko/releases"><img src="https://img.shields.io/badge/Android-8.0%2B-306EFF?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" /></a>
  <a href="#"><img src="https://img.shields.io/badge/Windows%20Desktop-开发中-orange?style=flat-square&logo=windows&logoColor=white" alt="Windows Desktop (In Development)" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/NihilDigit/piko?style=flat-square&color=306EFF" alt="License" /></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.4%2B%20KMP-306EFF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin KMP" />
  <img src="https://img.shields.io/badge/Compose%20Multiplatform-Desktop%20%26%20Android-306EFF?style=flat-square&logo=jetpackcompose&logoColor=white" alt="Compose Multiplatform" />
  <img src="https://img.shields.io/badge/UI-Fluent%202%20%2F%20Material%203-306EFF?style=flat-square&logo=windows11&logoColor=white" alt="Fluent 2 & Material 3" />
</p>

---

## 平台支持与开发状态

| 平台 | UI 风格 | 状态 | 说明 |
| :--- | :--- | :--- | :--- |
| **Android** | Material 3 Expressive | ✅ **正式支持** | 完整功能，支持后台服务、段落抽取、手势播放与离线任务 |
| **Windows Desktop** | Microsoft Fluent 2 Design | 🚧 **开发中 (In Development)** | 基于 `compose-fluent` 与 `kotlin-winrt` 原生集成，支持 Mica/系统强调色/Toast/屏幕常亮、GridView网格、独立触控播放器窗口等 |

---

## ✨ 特性亮点

- 🚀 **并发流式播放**：底层统一支持基于 MediaMP 与 Seekable 媒体流实现并发分段取流与秒级拖拽，Android 端集成沉浸式手势播放器，Desktop 端提供独立弹出窗口与全套 Fluent 触控控件。
- ✂️ **视频指定段落无损抽取下载**（Android）：视频文件提供专属段落下载功能，支持开始/结束双时间点实时帧画面预览与微调；底层基于原生 `MediaExtractor` 与 `MediaMuxer` 进行无损流复制（免重编码），自动对齐前序关键帧生成标准合规 MP4 文件。
- ⚡ **可调并发多任务下载**：内置高性能分块下载引擎，支持 1~32 线程连接预算调节与自定义存储路径；Android 具备前台常驻通知服务，Windows 具备 WinRT Toast 原生消息通知。
- 🧲 **极速磁力离线**：支持应用内一键秒存磁力链接，支持捕获系统外部 `magnet:` 链接与文本分享。
- 🎨 **双端原生设计哲学**：
  - **Android 端**：严格遵循 Material 3 Expressive 设计规范，动态色彩与细腻转场。
  - **Windows Desktop 端**：严格遵循 Microsoft Fluent 2 设计规范，支持 Mica 云母效果材质、Windows 系统强调色与暗色模式自适应、全套 Fluent 按钮/输入框/弹窗及 GridView/ListView 布局。

---

## 📦 下载与安装

前往 [Releases 页面](https://github.com/NihilDigit/piko/releases/latest) 下载最新版本的 APK（Windows 桌面版本随测试进度发布）：

| 产物名称 | 架构说明 | 适用场景 |
| :--- | :--- | :--- |
| `piko-*-arm64-v8a.apk` | 64 位 ARM 架构 | **推荐**。绝大部分现代 Android 手机与平板 |
| `piko-*-universal.apk` | 全架构集成通用包 | 适用于任何设备，体积稍大 |
| `piko-*-armeabi-v7a.apk` | 32 位 ARM 架构 | 较老的 32 位安卓设备 |
| `piko-*-x86_64.apk` | 64 位 x86 架构 | PC Android 模拟器或 x86 设备 |

---

## 🛠 技术栈

Piko 站在这些开源项目肩上（排名不分先后，括号里是它们在 Piko 里的职责）：

- **语言与构建**：[Kotlin Multiplatform](https://github.com/JetBrains/kotlin)（`shared` 纯业务逻辑层 + 双端原生风格表现层）、[kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) / [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) / [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime)
- **Android 界面**：[Jetpack Compose](https://developer.android.com/jetpack/compose)（Material 3 Expressive）+ [Navigation 3](https://developer.android.com/jetpack/compose/navigation) + Material 3 自适应套件、AndroidX（activity / lifecycle / DataStore / DocumentFile）
- **桌面界面**：[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) + [compose-fluent](https://github.com/compose-fluent/compose-fluent-ui)（Microsoft Fluent 2 组件、图标与 Mica 背景）
- **播放器**：双端统一 [mediamp](https://github.com/open-ani/mediamp)（OpenAni，桌面走 MPV runtime，x64/arm64 双架构）
- **Windows 原生**：[kotlin-winrt](https://github.com/compose-fluent/kotlin-winrt)（WinRT 投影：Toast 通知、系统强调色/深浅色）+ JDK FFM 直调 Win32（防锁屏常醒、AUMID、文件关联）
- **网盘协议**：[`pikpak-kotlin`](https://github.com/nihildigit/pikpak-kotlin) by [@nihildigit](https://github.com/nihildigit)（登录、文件、离线任务、回收站等全套 PikPak API）
- **网络与图片**：[Ktor Client](https://github.com/ktorio/ktor)（OkHttp 引擎）+ [Coil 3](https://github.com/coil-kt/coil)（跨平台图片加载）
- **视频片段**：[mp4parser](https://github.com/sannies/mp4parser)（纯 JVM 无损流复制切片，无需捆绑 ffmpeg）

---

## 🔨 本地构建

### 前置要求
- JDK 17 或以上（Android / shared 模块）
- JDK 25（desktopApp 模块：WinRT FFM 桥要求 JDK 22+，已钉死 toolchain 25，Gradle 自动供给）
- Android SDK 34+
- Windows 打包另需 WiX Toolset 3.x（CI 自带，`packageMsi` 用）

### 本地编译
```bash
# 克隆仓库
git clone https://github.com/nihildigit/piko.git
cd piko

# 编译 Debug APK
./gradlew assembleDebug

# 安装到连接的 Android 设备
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 跑桌面端（Windows，需 JDK 25，原生冒烟测试一并执行）
./gradlew :desktopApp:run :desktopApp:desktopTest

# 打 Windows 安装包（需 WiX 3.x）
./gradlew :desktopApp:packageMsi

# 打 Windows 绿色包（exe + 自带 JRE 25，解压即跑，CI 出 zip 用的就是它）
./gradlew :desktopApp:createDistributable
# 产物：desktopApp/build/compose/binaries/main/app/Piko/Piko.exe
```

---

## 📄 开源许可

本项目基于 [MIT](LICENSE) 许可证开源。
