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

新增屏幕状态时照这个形状做。下沉是分刀进行的，`DriveScreenState` 是第一刀，尚未下沉的见下节。

### 还没下沉的状态

按投入产出排序，前两条是 Desktop 功能缺失的根因，后两条是整洁性收益：

- **秒传与磁力解析**。Android 的 `InstantSheetContent` 是完整状态机：解析、勾选、启发式默认
  选择、目标目录解析与失效校验、多文件建目录、秒传与离线分流；其中没有一行平台相关。Desktop
  的 `TasksView` 只有「粘 URL 提交」。下沉后 Desktop 直接获得整个秒传能力，这是它当前最缺的。
- **播放器的准备策略**。Android 的取流顺序是本地已下载优先 → `prepareMedia` + `createMediaData`
  → 失败回退直链，外加续播位置的读写与播放到末尾归零。Desktop 的 `VideoPlayerWindow` 只调了
  一次 `createMediaData`，没有续播、没有本地优先、没有回退。缺的是策略不是控件，控件仍各写各的。
- **云端离线任务**。两端各有一份轮询与任务状态（Android 在 `TransfersScreen`，Desktop 在
  `TasksView`），做的是同一件事。SDK 还缺任务删除与重试接口，补上后这块只会更大。
- **回收站**。Android 是独立页面 `TrashScreen`，Desktop 是 `DriveView` 里的 `showTrash` 分支，
  各自持有列表与加载态。
- **登录**。两端都是 account / password / error / isLoggingIn 四个字段加一次 `manager.login`，
  逻辑相同但很小，收益主要是一致性。

**不要下沉这两处**：下载列表的状态本就在 `PikoDownloadCoordinator`（已在 shared），视图只做渲染；
设置页看着重复实则不然，Desktop 是主题模式、WinRT 通知测试与 `File` 形式的下载目录，Android 是
DataStore 开关加配额与资料卡片，真正共用的部分已经在仓库层。

### 平台差异用接口，不用 expect/actual

`PikoUserPreferences`、`PikoSessionStore`、`PikoDownloadStorage`、`PikoSegmentDownloader`
都是 commonMain 的接口，Android 与 Desktop 各有实现。**加一个偏好项要同时改三处**：接口、
`SessionManager`（Android，DataStore）、`DesktopPikoPreferences`（Desktop，内存）。

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

Android 与 Desktop 都基于 MediaMP。`player.features` 里的能力**按后端注册，取不到要优雅降级**：
ExoPlayer 后端只注册了 Buffering、FramePreview、MediaMetadata、PlaybackSpeed、VideoAspectRatio，
`AudioLevelController` 与 `Screenshots` 没有实现，所以音量走 `AudioManager`。

Android 播放器的控件在 `app/.../ui/screens/player/` 下拆成四个文件，手势的平台胶水在
`PlayerGestures.kt`。
