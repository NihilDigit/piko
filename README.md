<p align="center">
  <img src="docs/icon.svg" width="96" height="96" alt="Piko Icon" />
</p>

<h1 align="center">Piko</h1>

<p align="center">
  <strong>轻量、极速、现代的第三方 PikPak Android 客户端</strong>
</p>

<p align="center">
  <a href="https://github.com/NihilDigit/piko/releases/latest"><img src="https://img.shields.io/github/v/release/NihilDigit/piko?style=flat-square&color=306EFF" alt="Latest Release" /></a>
  <a href="https://github.com/NihilDigit/piko/releases"><img src="https://img.shields.io/badge/Android-8.0%2B-306EFF?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/NihilDigit/piko?style=flat-square&color=306EFF" alt="License" /></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.0%2B-306EFF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-306EFF?style=flat-square&logo=jetpackcompose&logoColor=white" alt="Compose" />
  <img src="https://img.shields.io/badge/Media3-ExoPlayer-306EFF?style=flat-square&logo=youtube&logoColor=white" alt="Media3" />
</p>

---

## ✨ 特性亮点

- 🚀 **并发流式播放**：基于 AndroidX Media3 (ExoPlayer) 与 `PikoStreamDataSource` 实现并发分段取流、预取缓存，支持微秒级精准 Seek 与即拖即放。
- ✂️ **视频指定段落无损抽取下载**：视频文件提供专属段落下载功能，支持开始/结束双时间点实时帧画面预览与微调；底层基于原生 `MediaExtractor` 与 `MediaMuxer` 进行无损流复制（免重编码），自动对齐前序关键帧生成标准合规 MP4 文件。
- ⚡ **可调并发多任务下载 & 前台服务**：内置高性能分块下载引擎，支持 1~32 线程连接预算调节与自定义存储路径；配备真正的 Android 前台服务 (`PikoDownloadService`)，通知栏实时展示传输速率与进度，支持任务断点持久化与崩溃状态恢复。
- 🧲 **极速磁力离线**：支持应用内一键秒存磁力链接，支持捕获系统外部 `magnet:` 链接与文本分享。
- 🎬 **沉浸式手势播放器**：双击快进快退、横滑 Seek 定位、左侧滑调节亮度、右侧滑调节媒体音量、长按 2.0x 极速冲刺、0.5x~3.5x 无级倍速、记忆播放进度与恢复提示。
- 🎨 **Material 3 现代界面**：遵循 Material 3 Expressive 设计规范，原生动态色彩与流畅转场动效，支持防剧透缩略图高斯遮蔽、网格与列表视图灵活切换。

---

## 📦 下载与安装

前往 [Releases 页面](https://github.com/NihilDigit/piko/releases/latest) 下载最新版本的 APK：

| 产物名称 | 架构说明 | 适用场景 |
| :--- | :--- | :--- |
| `piko-*-arm64-v8a.apk` | 64 位 ARM 架构 | **推荐**。绝大部分现代 Android 手机与平板 |
| `piko-*-universal.apk` | 全架构集成通用包 | 适用于任何设备，体积稍大 |
| `piko-*-armeabi-v7a.apk` | 32 位 ARM 架构 | 较老的 32 位安卓设备 |
| `piko-*-x86_64.apk` | 64 位 x86 架构 | PC Android 模拟器或 x86 设备 |

---

## 🛠 技术栈

- **Language**: Kotlin 2.0+
- **UI Framework**: Jetpack Compose (Material 3 Expressive)
- **Architecture**: Single Activity, MVVM / Flow / Coroutines, Repository Pattern
- **Media Engine**: AndroidX Media3 (ExoPlayer)
- **SDK**: [`pikpak-kotlin`](https://github.com/nihildigit/pikpak-kotlin) by [@nihildigit](https://github.com/nihildigit)
- **Image Loading**: Coil 3
- **Local Storage**: AndroidX DataStore Preferences

---

## 🔨 本地构建

### 前置要求
- JDK 17 或以上
- Android SDK 34+

### 本地编译
```bash
# 克隆仓库
git clone https://github.com/nihildigit/piko.git
cd piko

# 编译 Debug APK
./gradlew assembleDebug

# 安装到连接的 Android 设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 开源许可

本项目基于 [MIT](LICENSE) 许可证开源。
