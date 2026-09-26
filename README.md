<p align="center"><img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/icon.png" alt="Piko" width="96"></p>

<h1 align="center">Piko</h1>

<p align="center"><b>简体中文</b> | <a href="README.en.md">English</a></p>

<p align="center">
<a href="#安装"><img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-306EFF?style=flat-square&logo=android&logoColor=white"></a>
<a href="#安装"><img alt="Windows 10+ x64 | arm64" src="https://img.shields.io/badge/Windows-10%2B%20x64%20%7C%20arm64-306EFF?style=flat-square&logo=data:image/svg%2bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0id2hpdGUiIGQ9Ik0yIDJoOS41djkuNUgyek0xMi41IDJIMjJ2OS41aC05LjV6TTIgMTIuNWg5LjVWMjJIMnpNMTIuNSAxMi41SDIyVjIyaC05LjV6Ii8+PC9zdmc+"></a>
<a href="https://github.com/NihilDigit/piko/releases/latest"><img alt="Latest Release" src="https://img.shields.io/github/v/release/NihilDigit/piko?style=flat-square&color=306EFF"></a>
<a href="LICENSE"><img alt="MIT" src="https://img.shields.io/github/license/NihilDigit/piko?style=flat-square&color=306EFF"></a>
<br>
<img alt="Kotlin Multiplatform" src="https://img.shields.io/badge/Kotlin%20Multiplatform-306EFF?style=flat-square&logo=kotlin&logoColor=white">
<img alt="Compose Multiplatform" src="https://img.shields.io/badge/Compose%20Multiplatform-306EFF?style=flat-square&logo=jetpackcompose&logoColor=white">
<img alt="Material 3 Expressive" src="https://img.shields.io/badge/Material%203%20Expressive-306EFF?style=flat-square&logo=materialdesign&logoColor=white">
</p>

<p align="center"><b>高性能、多平台的 PikPak 客户端</b></p>

## 双端支持

基于 Kotlin Multiplatform 构建，两端共用界面与业务代码，界面遵循 Material 3 Expressive 设计规范：
- **Android**：原生实现，界面基于 Jetpack Compose，播放基于 libmpv。
- **Windows**：基于 Compose Multiplatform 的 GPU 加速界面，视频画面经 D3D11 直通 Skia，零拷贝合成。

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/desktop.jpg" height="380" alt="Windows：海报墙">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/phone.jpg" height="380" alt="Android：海报墙">
</p>

## 离线下载

磁力链接按作品、分区与集数解析，字幕随视频归组。已收录的视频可在保存前完整预览，保存时只保留勾选的文件。

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/magnet.jpg" height="528" alt="磁力解析">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/magnet-sections.jpg" height="528" alt="多部作品与分区">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/preview.jpg" height="528" alt="保存前预览">
</p>

## 按作品浏览网盘

网盘目录同样按作品、分区与集数分组，支持海报墙视图与全盘搜索。

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/drive.jpg" height="528" alt="列表视图">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/search.jpg" height="528" alt="全盘搜索">
</p>

## 播放

选集按作品与分区划分，播放进度与 PikPak 官方客户端同步。Android 支持手势调节亮度、音量与进度，Windows 支持鼠标与键盘操作。

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/player.jpg" width="410" alt="横屏播放">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/episodes.jpg" width="410" alt="选集">
</p>

## 传输与整理

下载与在线播放以 8 条连接并发读取，弱网下也能用满带宽。另支持无损截取视频片段、上传、服务端解压与查找重复。

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/segment.jpg" height="528" alt="片段下载">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/duplicates.jpg" height="528" alt="查找重复">
<img src="https://raw.githubusercontent.com/NihilDigit/piko/main/docs/screenshots/account.jpg" height="528" alt="网盘与流量">
</p>

## 安装

前往 [Releases](https://github.com/NihilDigit/piko/releases/latest) 下载。所有安装包均由 GitHub Actions 从仓库源码构建，校验值见 `SHA256SUMS.txt`。

- **Android**：需要 Android 8.0 或更高版本。按设备架构选择 APK，无法确定时选 `universal`。
- **Windows**：需要 Windows 10 或更高版本，提供 x64 与 arm64 两种架构。
  - `.msi`：安装到当前用户目录，无需管理员权限，支持应用内更新。
  - `.zip`：便携版，解压后运行 `Piko.exe`。

## 贡献

欢迎提交 Issue 与 PR。小的 Bug 修复、崩溃排查与文档补充可以直接提交。

计划新增功能或调整架构时，请先提交 Issue，说明使用场景与拟定方案，确认方向后再实现。

使用 LLM 辅助编写代码时，请理解新增代码的逻辑，并在真机上验证。

## 许可与致谢

- 源码以 [MIT](LICENSE) 许可证开源。Android 安装包内置以 GPL 构建的 mpv 与 FFmpeg，整体按 GPLv3 分发。
- PikPak 接口实现参考了 [52funny/pikpakcli](https://github.com/52funny/pikpakcli)。
- 多项功能设计参考了 PikPak 网页端增强脚本 [digbug82/PikPak_Enhancement_Master](https://github.com/digbug82/PikPak_Enhancement_Master)。
- Windows 端 mpv 的零拷贝渲染由 [MediaMP](https://github.com/open-ani/mediamp) 实现。
