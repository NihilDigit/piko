# Piko (皮可)

<p align="center">
  <strong>一款轻量、极速、现代的第三方 PikPak Android 客户端</strong>
</p>

---

## ✨ 特性亮点

- 🚀 **并发流式播放**：参考 Animeko 播放架构，结合 Media3 (ExoPlayer) 与 `PikPakStreamReader` 实现多连接并行取流、LRU 分块预取与高命中缓存，支持精确 Seek 与秒开拖拽不卡顿。
- ✂️ **视频指定段落无损抽取下载**：视频文件提供专属段落下载功能，支持开始/结束双时间点实时帧画面预览与微步微调芯片；底层基于原生 `MediaExtractor` 与 `MediaMuxer` 进行无损流复制（免重编码），自动对齐前序关键帧生成标准合规 MP4 文件。
- ⚡ **可调并发多任务下载 & 前台服务**：内置高性能下载引擎，支持 1~32 线程连接预算调节与自定义存储路径；配备真正的 Android 前台服务 (`PikoDownloadService`)，通知栏实时展示传输速率与进度，支持任务断点持久化与崩溃状态恢复。
- 🧲 **极速磁力离线**：支持应用内一键秒存磁力链接，支持捕获系统外部 `magnet:` 链接与文本分享。
- 🎬 **沉浸式手势播放器**：双击快进快退、横滑 Seek 定位、左侧滑调节亮度、右侧滑调节媒体音量、长按 2.0x 极速冲刺、0.5x~3.5x 无级倍速、记忆播放进度与恢复提示。
- 🎨 **Material 3 现代界面**：对齐现代 Android 规范，全手势与流畅转场动效，支持防剧透缩略图高斯遮蔽、网格与列表视图灵活切换。

---

## 🛠 技术栈

- **Language**: Kotlin 2.0+
- **UI Framework**: Jetpack Compose (Material 3)
- **Architecture**: Single Activity, Flow / Coroutines, Repository Pattern
- **Media Engine**: AndroidX Media3 (ExoPlayer 1.5+)
- **SDK**: [`pikpak-kotlin`](https://github.com/nihildigit/pikpak-kotlin) by [@nihildigit](https://github.com/nihildigit)
- **Image Loading**: Coil 3
- **Local Storage**: AndroidX DataStore Preferences

---

## 📦 构建与安装

### 前置要求
- JDK 17 或以上
- Android SDK 34+

### 本地编译
```bash
# 克隆仓库
git clone https://github.com/nihildigit/piko.git
cd piko

# 编译 Debug APK
./gradlew :app:assembleDebug

# 安装到连接的 Android 设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 开源许可

本项目遵循 MIT 许可证开源码。详见 [LICENSE](LICENSE)。
