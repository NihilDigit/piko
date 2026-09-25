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
  <img src="https://img.shields.io/badge/UI-Material%203%20Expressive-306EFF?style=flat-square&logo=materialdesign&logoColor=white" alt="Material 3 Expressive" />
</p>

---

## 平台支持与开发状态

两端共用同一套 Material 3 Expressive 界面，布局按窗口宽度自适应：手机上是底部导航，平板与桌面窗口换成侧边导航与多栏。

| 平台 | 状态 | 说明 |
| :--- | :--- | :--- |
| **Android** | ✅ **正式支持** | 完整功能，支持后台服务、段落抽取、手势播放与离线任务 |
| **Windows Desktop** | 🚧 **开发中 (In Development)** | 与 Android 平板同样的界面，补上右键菜单、键盘快捷键与悬停提示；独立播放器窗口、系统通知与防锁屏 |

---

## ✨ 特性亮点

- 🚀 **并发流式播放**：两端都以 libmpv 解码，经本机回环代理并发分段取流，拖动秒级响应。Android 端是沉浸式手势播放器，Desktop 端是可与主界面并排的独立窗口。
- ✂️ **视频指定段落无损抽取下载**（Android）：视频文件提供专属段落下载功能，支持开始/结束双时间点实时帧画面预览与微调；底层基于原生 `MediaExtractor` 与 `MediaMuxer` 进行无损流复制（免重编码），自动对齐前序关键帧生成标准合规 MP4 文件。
- ⚡ **可调并发多任务下载**：内置高性能分块下载引擎，支持 1~32 线程连接预算调节与自定义存储路径；Android 具备前台常驻通知服务，Windows 在下载完成或失败时发系统通知。
- 🧲 **极速磁力离线**：支持应用内一键秒存磁力链接，支持捕获系统外部 `magnet:` 链接与文本分享。
- 🎨 **一套界面，随窗口伸缩**：遵循 Material 3 Expressive 规范，Android 12 起支持系统取色，另有六套内置主题色。窄窗口单栏、宽窗口多栏，桌面窗口缩放与平板分屏效果一致；鼠标与键盘有右键菜单、快捷键与悬停提示。

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

核心依赖及在 Piko 中的用途：

- **语言与构建**：[Kotlin Multiplatform](https://github.com/JetBrains/kotlin)（`shared` 业务与屏幕状态 + `ui` 两端共用的界面）、[kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) / [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) / [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime)
- **界面**：[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform)（Material 3 Expressive + 自适应导航套件）+ [Navigation 3](https://developer.android.com/jetpack/compose/navigation)；Android 端另用 AndroidX（activity / lifecycle / DataStore / DocumentFile）
- **播放器**：两端都是 libmpv。Android 用 [libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android)，桌面用 [mediamp](https://github.com/open-ani/mediamp)（OpenAni，D3D11 画面直接进 Skia，x64/arm64 双架构）
- **Windows 原生**：JDK FFM 直调 WinRT 与 Win32（Toast 通知、防锁屏常醒、AUMID、magnet 协议关联）
- **网盘协议**：[pikpak-kotlin](https://github.com/nihildigit/pikpak-kotlin)（登录、文件、离线任务、回收站等全套 PikPak API）
- **网络与图片**：[Ktor Client](https://github.com/ktorio/ktor)（OkHttp 引擎）+ [Coil 3](https://github.com/coil-kt/coil)（跨平台图片加载）
- **视频片段**：[mp4parser](https://github.com/sannies/mp4parser)（纯 JVM 无损流复制切片，无需捆绑 ffmpeg）

---

## 🔨 本地构建

### 前置要求
- JDK 17 或以上（Android / shared 模块）
- JDK 25（desktopApp 模块：FFM 原生调用要求 JDK 22+，release 的 AOT 缓存要求 JDK 25；已钉死 toolchain 25，Gradle 自动供给）
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

# 打 Windows 安装包（需 WiX 3.x；ProGuard 裁剪并附 AOT 缓存，打包时会启动一次应用做训练）
./gradlew :desktopApp:packageReleaseMsi

# 打 Windows 绿色包（exe + 自带 JRE 25，解压即跑，CI 出 zip 用的就是它）
./gradlew :desktopApp:createReleaseDistributable
# 产物：desktopApp/build/compose/binaries/main-release/app/Piko/Piko.exe
```

---

## 🙏 致谢

- [52funny/pikpakcli](https://github.com/52funny/pikpakcli)：PikPak 接口的请求格式、验证码签名与上传协议均取自此项目。Piko 的网盘能力来自 [pikpak-kotlin](https://github.com/NihilDigit/pikpak-kotlin)，该 SDK 即以此为起点。
- [digbug82/PikPak_Enhancement_Master](https://github.com/digbug82/PikPak_Enhancement_Master)：PikPak 网页端增强脚本，Piko 的多项功能设计参考了它。

---

## 📄 开源许可

本项目基于 [MIT](LICENSE) 许可证开源。
