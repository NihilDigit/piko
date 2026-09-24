# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Piko 是 PikPak 的第三方跨平台客户端。Android 与 Windows 共用一套 Material 3 Expressive 界面，
按窗口宽度自适应；业务逻辑与屏幕状态在 `shared`，界面在 `ui`，两端只剩入口与平台实现。

## 常用命令

```bash
./gradlew :app:compileDebugKotlin          # Android 编译（最快的语法与类型检查）
./gradlew :desktopApp:compileKotlinDesktop # Desktop 编译，注意不是 compileKotlinJvm
./gradlew :app:installDebug                # 装到已连接设备，包名 dev.piko.debug
./gradlew :desktopApp:run                  # 跑 Windows 桌面端
./gradlew :app:testDebugUnitTest           # 单元测试，目前只有 app/src/test
./gradlew :app:testDebugUnitTest --tests '*FileNameSanitizerTest*'   # 跑单个测试
```

改完务必两端都编译：`shared` 与 `ui` 的改动会同时波及 `app` 与 `desktopApp`，只编译一端看不出来。
`ui` 的桌面端与 Android 端用的 material3 版本不同（见「桌面端」一节），同一行代码可能只在一端报错。

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

四个模块：`shared`（状态与业务，commonMain + android/desktop 两个 target）、`ui`（共享界面，
同样两个 target）、`app`（Android 入口、平台实现与播放器）、`desktopApp`（Windows 入口、
平台实现与播放器窗口）。

### 界面写一次

`ui/src/commonMain` 是全部界面：主题、导航、网盘、传输、设置、回收站、登录与各组件，两端共用。
入口是 `PikoApp`：`MainActivity` 与桌面的 `Main.kt` 各自拼好 `PikoServices`（进程级的仓库与调度器）
与 `PikoPlatform`（平台能力），传进去即可。屏幕里经 `LocalPikoServices`、`LocalPikoPlatform` 取用，
不要再引用 `PikoApplication.instance`。

`shared/.../shared/state/DriveScreenState.kt` 仍是核心接缝：文件列表、加载态、排序、搜索、多选、
启发式折叠、防窥揭示、目录导航与增删改动作都在这里。约定：
- 状态用 Compose 的 `State` 而非 `StateFlow`；为此 `shared` 对 compose runtime 用的是 `api`。
- 派生值用 `derivedStateOf`，不要写成 getter——它们每帧会被读到多次。
- 面向用户的提示走 `messages: SharedFlow<String>` 事件流，界面用 Snackbar 呈现。用状态表达会在重组时重放。
- 长驻的错误态（如 `loadError`）才用状态，它描述的是「眼前这份数据是旧的」。

新增屏幕状态时照这个形状做。已下沉的 state holder 都在 `shared/.../shared/state/`：
`DriveScreenState`、`InstantSheetState`（秒传与磁力解析）、`OfflineTasksState`（云端离线任务，
轮询由调用方的协程控制启停）、`TrashScreenState`、`LoginState`、`FolderPickerState`（自带路径栈）。
播放器的准备策略是 `shared/.../shared/media/player/PlayerScreenState`，见「播放器」一节。

### 响应式布局

布局只看窗口宽度，不看设备：`ui/.../adaptive/WindowWidth.kt` 按 M3 断点给出 compact、medium、
expanded。桌面窗口缩放与平板分屏走同一套判断，桌面体验以 Android 平板为准。
- 导航：`NavigationSuiteScaffold` 在 compact 下是底部导航栏，更宽时换成侧边导航栏。
- 回收站：compact 下是盖住整窗的压栈页；medium 在导航栏右侧的内容区里；expanded 与「我的」并排成两栏。
- 行长：设置、传输、回收站的行内容收在 840dp 以内居中。列表本身仍铺满窗口（用 `readableSidePadding`
  算 contentPadding），两侧空白处滚轮也能滚。
- 对话框：目录选择器在 compact 下全屏，更宽时是居中的基本对话框。

鼠标与键盘：条目右键弹出与操作面板相同的菜单（`ContextMenuArea`，动作列表 `fileActions` 两处共用）；
图标按钮用 `TooltipIconButton`，快捷键写在提示里；Esc 经 `BackHandler` 触发返回；网盘页快捷键见
`DriveScreen` 的 `handleShortcut`。新加的界面同时照顾触屏与鼠标：下拉刷新之类只有触屏能用的操作，
宽窗口要另给按钮。

### 平台差异用接口，不用 expect/actual

`PikoUserPreferences`、`PikoSessionStore`、`PikoDownloadStorage`、`PikoSegmentDownloader`
都是 commonMain 的接口，Android 与 Desktop 各有实现。界面要的平台能力（剪贴板、系统取色、
下载位置选择、本地文件的打开与分享、片段预览的播放后端、全屏对话框、应用内更新）集中在
`ui/.../platform/PikoPlatform.kt`，实现是 `AndroidPikoPlatform` 与 `DesktopPikoPlatform`。
平台没有的能力返回 null 或 false，界面据此隐藏入口，例如桌面端没有应用内更新与系统分享。

**加一个偏好项要同时改三处**：接口、
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
- **回收站里的条目查详情会失败**：`getFileDetail` 返回 `error_code=9`、`file_in_recycle_bin`（2026-09-23 实测，
  文件与文件夹相同）。9 也是验证码的错误码，SDK 按 `error` 名区分。判断目录是否可用时失败与 `trashed` 同样视为不可用。
- **列回收站必须带 `parent_id=*`**，否则只返回从根目录删除的条目。SDK 0.6.8 起 `listTrash` 已带上。

## 播放器

两端解码都是 libmpv，Kotlin 绑定各走各的：

- **Android** 用预编译的 `dev.jdtech.mpv:libmpv`，适配层是 `MpvPlaybackBackend` 与 `MpvVideoSurface`。
  不用 MediaMP 的 Android mpv 后端：它的 Compose 表面是空实现，也不发布 .so。
- **Desktop** 用 MediaMP 的 mpv 后端（`MediampPlaybackBackend`），因为它提供了 D3D11 零拷贝进 Skia 的表面，
  这部分自己写的成本最高。播放器开独立窗口（`VideoPlayerWindow`），主界面经 `VideoPlayerHost.Detached`
  把播放请求交给它；Android 仍是应用内的压栈页（`VideoPlayerHost.InApp`）。两端只各自保留后端、画面表面
  与系统能力（Android 的亮度、媒体音量、方向与系统栏在 `PlayerSystemControls`），控件在 `ui`。

两者都实现 commonMain 的薄接口 `PlaybackBackend`，策略在 `PlayerScreenState`：取流顺序为本地副本 →
回环代理 → 新取的直链 → 转码流，只有首帧前失败才换下一个来源；播放中途失败按退避重连；续播位置
每 5 秒保存，末尾归零。控件是无状态的 `MobilePlayerControls`，
两端共用，数据全部来自 `PlayerScreenState`。横竖屏按窗口宽高比判断，所以桌面窗口得到横屏布局；
鼠标复用触屏手势层，另加悬停显示控件与键盘快捷键。垂直拖动调节的量经 `PlayerLevelControl` 由平台提供。

选集按文件名解析器分成作品与分区（`buildPlaylist`），上一集、下一集与自动连播不跨分区。
解析总开关关闭时退回按文件名自然排序（`buildRawPlaylist`）。

**所有网盘读取都经 `shared/.../media/proxy/` 的本机回环 HTTP 代理**，播放器只拿到一个 `http://127.0.0.1`
URL。直链过期重取、连接预算、预读与缓存都在 SDK 的 `PikPakFileHandle` / `PikPakStreamReader` 里；
代理负责把它们暴露成 HTTP Range。reader 只允许单个读者，而 mpv 拖动时新旧连接会短暂重叠，所以同一会话
只有一个 reader，新请求先取消并等待旧请求，再 seek。

**Android 分发包是 GPLv3**：jdtech 包里的 FFmpeg 以 `--enable-gpl --enable-version3` 构建，mpv 也是 GPL 构建。
piko 源码仍是 MIT，但发版时要附 GPLv3 与第三方声明，并指明对应源码的获取方式。

## 桌面端

- **版本**：界面库停在 CMP 1.12.0、material3 1.12.0-alpha03、MediaMP 0.5.0，与 Animeko 一致。CMP 1.13 的
  alpha 带的 skiko 0.152 把渲染后端包进 `OnScreenRedrawer`，MediaMP 的 D3D11 画面表面要直接拿
  `Direct3DRedrawer`，一开播放器就崩。打包插件单独用 1.13 的 alpha，因为 release 的 AOT 缓存 DSL 从这一版才有；
  所以 `desktopApp` 不用 `compose.desktop.currentOs`，而是按版本号写出运行库坐标。1.12 的 material3 里
  部分 API 仍是实验性，`ui` 模块已统一 opt-in；它也缺少无点击的 `SegmentedListItem`，设置页用
  `StaticSegmentedRow` 顶替。
- **release**：`./gradlew :desktopApp:packageReleaseMsi`（或 `createReleaseDistributable`）。ProGuard 只裁剪不混淆，
  规则在 `desktopApp/proguard-rules.pro`，JNA、MediaMP、ServiceLoader 实现、isoparser 必须保留。
  打包时会跑一遍 AOT 训练（进程带 `compose.aot.training-run`，由 `Main.kt` 在 12 秒后自行退出），
  得到 `app.aot`。AOT 缓存按 jar 的修改时间校验，MSI 与 zip 只存到偶数秒，训练前先把 jar 的时间取整，
  否则安装后缓存作废（`msiexec /a` 解出安装包即可验证）。
- **原生**：mpv 与 FFmpeg 的 DLL 解开放在应用资源目录的 `mpv/` 下，启动时经
  `MpvMediampPlayer.prepareLibraries` 指过去；MediaMP 默认每次运行都解压一份到 `%TEMP%` 且删不掉。
  Toast 经 FFM 直调 combase 与 COM 虚表（`WindowsToast`），不用 kotlin-winrt。未打包应用的 AUMID
  要在 `HKCU\Software\Classes\AppUserModelId` 登记才会显示通知，安装版首次启动时写入。
- **单实例**：`SingleInstance` 以 `~/.piko/instance.lock` 的文件锁决定主实例，后来者经同目录的
  Unix domain socket 转交启动参数（磁力链接）后退出。安装版与 `gradlew :desktopApp:run` 共用这把锁，
  装好的 Piko 开着时，开发构建一启动就把参数转交过去然后退出，调试前先关掉安装版。AOT 训练进程不参与。
- **关窗**：仍有下载进行时关主窗口不退出，藏进托盘，下完自动退出。窗口位置、大小与最大化状态存在
  settings.properties 的 `window.<名称>.*` 下。
- Compose 与 MediaMP 的桌面依赖带进了 ui-test、junit、truth 与 kotlinx-coroutines-test，
  在 `desktopRuntimeClasspath` 里排除，测试类路径不受影响。

## 冒烟测试

`.github/workflows/smoke.yml` 在每次推送时运行：Linux 上的 `:shared:desktopTest`，以及 x86_64 模拟器
（API 34）上的 `:app:connectedDebugAndroidTest`。这些是端到端行为冒烟，不是单元测试：走真实 libmpv、
真实代理，PikPak 服务端用 MockEngine 顶替，SDK 的请求、鉴权与解析仍走真实代码。本地不必跑，以 CI 结果为准。
老格式样片在 `testdata/media/`，直接提交，生成方式见 `generate.sh`；没有 WMV3/VC-1 样片，因为 ffmpeg 没有它的编码器。
