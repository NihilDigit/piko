# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Piko 是 PikPak 的第三方跨平台客户端。Android 走 Material 3 Expressive，Windows 走 Fluent 2，
业务逻辑与屏幕状态在 `shared` 模块共用。

## 常用命令

```bash
./gradlew :app:compileDebugKotlin          # Android 编译（最快的语法与类型检查）
./gradlew :desktopApp:compileKotlinDesktop # Desktop 编译，注意不是 compileKotlinJvm
./gradlew :app:installDebug                # 装到已连接设备，包名 dev.piko.debug
./gradlew :desktopApp:run                  # 跑 Windows 桌面端
./gradlew :app:testDebugUnitTest           # 单元测试，目前只有 app/src/test
./gradlew :app:testDebugUnitTest --tests '*FileNameSanitizerTest*'   # 跑单个测试
```

改完务必两端都编译：`shared` 的改动会同时波及 `app` 与 `desktopApp`，只编译一端看不出来。

版本号来自环境变量 `PIKO_VERSION_NAME` / `PIKO_VERSION_CODE`，本地不设则用默认值，无需配置。

## 与 pikpak-kotlin SDK 的关系

网盘能力全部来自 `io.github.nihildigit:pikpak-kotlin`（版本在 `gradle/libs.versions.toml`），
作者同一人，源码通常在本机 `../pikpak-kotlin`。

**需要改 SDK 时**：在 SDK 仓库 `./gradlew publishToMavenLocal`（那边需要 `ANDROID_HOME`，
仓库里没有 `local.properties`），把版本指向对应的 SNAPSHOT，并在 `settings.gradle.kts` 的
`dependencyResolutionManagement` 里临时加 `mavenLocal`。**提交前必须移除 mavenLocal 并指向
已发布版本**——发版在干净 runner 上构建，本机 `~/.m2` 在那里不存在，否则 release 必挂。

## 发版

两个仓库都由 `v*` tag 触发 GitHub Actions。piko 依赖已发布的 SDK，所以顺序不能颠倒：
先发 SDK（tag → Maven Central，`automaticRelease = true` 无需手动确认，同步到 repo1 约数分钟），
确认 `repo1.maven.org` 能解析到新版本后，再改 piko 的依赖版本、提交、打 tag。

自有仓库不走 PR，直接在 `main` 上提交。

## 架构

三个模块：`shared`（commonMain + android/desktop 两个 target）、`app`（Android）、
`desktopApp`（Windows）。

### 屏幕状态在 commonMain，UI 各自实现

`shared/.../shared/state/DriveScreenState.kt` 是核心接缝：文件列表、加载态、排序、搜索、
多选、启发式折叠、防窥揭示、目录导航与增删改动作都在这里，两端共用。Android 与 Windows
只负责布局与外观——**两套 UI 是刻意的产品选择（README 把「双端原生设计哲学」写为卖点），
不是该消除的重复**。真正该消除的是状态逻辑写两遍。

约定：
- 状态用 Compose 的 `State` 而非 `StateFlow`，因为两端视图层都是 Compose；为此 `shared`
  对 `compose.runtime` 用的是 `api` 而不是 `implementation`。
- 派生值用 `derivedStateOf`，不要写成 getter——它们每帧会被读到多次。
- 面向用户的提示走 `messages: SharedFlow<String>` 事件流，各端自行呈现（Android 用 Snackbar，
  Windows 用 Fluent 的 InfoBar）。用状态表达会在重组时重放。
- 长驻的错误态（如 `loadError`）才用状态，它描述的是「眼前这份数据是旧的」。

新增屏幕状态时照这个形状做。已下沉的 state holder 都在 `shared/.../shared/state/`：
`DriveScreenState`、`InstantSheetState`（秒传与磁力解析）、`OfflineTasksState`（云端离线任务，
轮询由调用方的协程控制启停）、`TrashScreenState`、`LoginState`、`FolderPickerState`（自带路径栈）。
播放器的准备策略是 `shared/.../shared/media/player/PlayerScreenState`，见「播放器」一节。

**不要下沉这两处**：下载列表的状态本就在 `PikoDownloadCoordinator`（已在 shared），视图只做渲染；
设置页看着重复实则不然，Desktop 是主题模式、WinRT 通知测试与 `File` 形式的下载目录，Android 是
DataStore 开关加配额与资料卡片，真正共用的部分已经在仓库层。

### 平台差异用接口，不用 expect/actual

`PikoUserPreferences`、`PikoSessionStore`、`PikoDownloadStorage`、`PikoSegmentDownloader`
都是 commonMain 的接口，Android 与 Desktop 各有实现。**加一个偏好项要同时改三处**：接口、
`SessionManager`（Android，DataStore）、`DesktopPikoPreferences`（Desktop，`DesktopSettingsStore`）。

### 全局导航栈在仓库层

`PikoDriveRepository` 持有 `folderStackFlow`，是网盘主界面的全局位置，并持久化。目录选择器
一类的浮层**必须维护自己的路径栈**，碰它会把主界面的位置一起改掉。
仓库层还有 `refreshEvents`，供界面外的改动（如回收站恢复）通知列表刷新，`DriveScreenState`
已在 `init` 里订阅，视图不要再订阅一遍。

## PikPak API 的既有约束

这些是实测结论，不要重新推导：

- **没有服务端按名搜索**。`/drive/v1/files` 的 `filters` 只认 phase / trashed / kind /
  starred / modified_time，`name` 一律 404，`q` 与 `search_text` 被接受后忽略。官方 Web 端
  自己也是本地过滤。全盘搜索只能是客户端递归遍历（SDK 的 `searchFilesRecursive`）。
- **离线任务只吃整条磁力 URL**，`createUrlFile` 没有按文件选择的参数，`ResolvedFile` 也不带
  文件索引。所以「秒传一部分、离线另一部分」必然产生重复文件。
- **gcid 是内容哈希**，与文件名无关。已在网盘的文件其 gcid 就在 `FileStat.hash` 里。
- **`getFileDetail` 对已移进回收站的条目仍返回成功**，判断目录是否可用要带 `!trashed`。

## 播放器

两端解码都是 libmpv，Kotlin 绑定各走各的：

- **Android** 用预编译的 `dev.jdtech.mpv:libmpv`，适配层是 `MpvPlaybackBackend` 与 `MpvVideoSurface`。
  不用 MediaMP 的 Android mpv 后端：它的 Compose 表面是空实现，也不发布 .so。
- **Desktop** 用 MediaMP 的 mpv 后端（`MediampPlaybackBackend`），因为它提供了 D3D11 零拷贝进 Skia 的表面，
  这部分自己写的成本最高。

两者都实现 commonMain 的薄接口 `PlaybackBackend`，策略在 `PlayerScreenState`：取流顺序为本地副本 →
回环代理 → 新取的直链 → 转码流，只有首帧前失败才换下一个来源；播放中途失败按退避重连；续播位置
每 5 秒保存，末尾归零。控件是无状态的（Android `MobilePlayerControls`，Desktop `FluentPlayerControls`），
两端参数同名，数据全部来自 `PlayerScreenState`。

**所有网盘读取都经 `shared/.../media/proxy/` 的本机回环 HTTP 代理**，播放器只拿到一个 `http://127.0.0.1`
URL。直链过期重取、连接预算、预读与缓存都在 SDK 的 `PikPakFileHandle` / `PikPakStreamReader` 里；
代理负责把它们暴露成 HTTP Range。reader 只允许单个读者，而 mpv 拖动时新旧连接会短暂重叠，所以同一会话
只有一个 reader，新请求先取消并等待旧请求，再 seek。

**Android 分发包是 GPLv3**：jdtech 包里的 FFmpeg 以 `--enable-gpl --enable-version3` 构建，mpv 也是 GPL 构建。
piko 源码仍是 MIT，但发版时要附 GPLv3 与第三方声明，并指明对应源码的获取方式。

## 冒烟测试

`.github/workflows/smoke.yml` 在每次推送时运行：Linux 上的 `:shared:desktopTest`，以及 x86_64 模拟器
（API 26 与 34）上的 `:app:connectedDebugAndroidTest`。这些是端到端行为冒烟，不是单元测试：走真实 libmpv、
真实代理，PikPak 服务端用 MockEngine 顶替，SDK 的请求、鉴权与解析仍走真实代码。本地不必跑，以 CI 结果为准。
老格式样片在 `testdata/media/`，直接提交，生成方式见 `generate.sh`；没有 WMV3/VC-1 样片，因为 ffmpeg 没有它的编码器。
