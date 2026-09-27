# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Piko 是 PikPak 的第三方跨平台客户端。Android、Windows 与 macOS（实验性）共用一套 Material 3 Expressive 界面，
按窗口宽度自适应；业务逻辑与屏幕状态在 `shared`，界面在 `ui`，两端只剩入口与平台实现。

## 常用命令

```bash
./gradlew :app:compileDebugKotlin          # Android 编译（最快的语法与类型检查）
./gradlew :desktopApp:compileKotlinDesktop # Desktop 编译，注意不是 compileKotlinJvm
./gradlew :app:installDebug                # 装到已连接设备，包名 dev.piko.debug
./gradlew :desktopApp:run                  # 跑桌面端
./gradlew :shared:desktopTest              # shared 的单测与冒烟（文件名解析、日志、代理、更新说明等）
./gradlew :shared:desktopTest --tests '*ReleaseNotesTest*'           # 跑单个测试
./gradlew :desktopApp:desktopTest          # 桌面端测试，含 WinRT 与窗口过程，只能在 Windows 上跑
./gradlew :app:testDebugUnitTest           # Android 单测
./gradlew :desktopApp:createReleaseDistributable  # release 包（ProGuard + AOT），keep 规则一类问题只在这里暴露
```

`gradlew :desktopApp:run` 直接读 `build/classes`，开发版运行期间重新编译它加载的模块，正在运行的进程会在
下一次加载类时报 `NoClassDefFoundError`。要编译先关掉开发版。

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

tag 只触发 `release.yml`：Android、Windows（x64、arm64）、macOS 并行构建，产物汇到 `release` job 统一算
`SHA256SUMS.txt`、做两份构建来源证明（`attest-build-provenance` 与 SLSA Build L3），建一个**草稿** release。
草稿对 `releases/latest` 不可见，应用内更新在公开前不会提示。push 与 PR 只跑 `ci.yml` 的单测与 `smoke.yml` 的冒烟，不打包。

Release 正文由 `release.yml` 按 `.github/release-notes.md` 生成：`## 下载` 起是按设备列出的附件表与校验说明。
**更新日志在草稿公开前手写，放在正文最前面、`## 下载` 之前**：应用内
更新弹窗读到这个标题就截断（`GithubReleases.kt` 的 `updateNotesOf`），标题改动要两边一起改，`ReleaseNotesTest` 会报错。
`gh release edit --notes-file` 替换整段正文而不是追加，改之前先读回原文，把更新日志拼在前面再写回，否则附件表就丢了。
写完后 `gh release edit <tag> --draft=false` 公开。按 tag 的 REST 接口不返回草稿，gh 按 tag 找不到时改用
`gh api repos/NihilDigit/piko/releases` 的列表按 `tag_name` 取 id。

更新日志写给下载的人看，照 Bilby 的格式：一行概述，然后 `## 修复` 与 `## 变化`，每条一句书面语，写读者能察觉的
现象或行为变化，不写文件名、类型名与提交标题，读者看不到的重构不写。`## 修复` 只列已发布版本里存在的问题：
本次新增的功能在发布前出过又修掉的问题，读者从未遇到，不列。写之前先读上一个版本的正文，保持一致。

## 架构

四个模块：`shared`（状态与业务，commonMain + android/desktop 两个 target）、`ui`（共享界面，
同样两个 target）、`app`（Android 入口、平台实现与播放器）、`desktopApp`（Windows 与 macOS 入口、
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
`DriveScreenState`、`InstantSheetState`（秒传与磁力解析，多条链接时由 `InstantBatchState` 为每条各建一个）、
`OfflineTasksState`（云端离线任务，轮询由调用方的协程控制启停）、`TrashScreenState`、`LoginState`、
`FolderPickerState`（自带路径栈）、`DuplicateFinderState`（查重）、`ArchiveExtractSession`（服务端解压，
进程级，挂在 `PikoServices` 上，离开网盘页照常进行）。
播放器的准备策略是 `shared/.../shared/media/player/PlayerScreenState`，见「播放器」一节。

### 响应式布局

布局只看窗口宽度，不看设备：`ui/.../adaptive/WindowWidth.kt` 按 M3 断点给出 compact、medium、
expanded。桌面窗口缩放与平板分屏走同一套判断，桌面体验以 Android 平板为准。
- 导航：`NavigationSuiteScaffold` 在 compact 下是底部导航栏，更宽时换成侧边导航栏。窗口到 1200dp（M3 large）
  换成一整条侧边栏（`MainSidebar`）：上面是三个去处，下面是网盘的快捷访问（`QuickAccessSections` /
  `QuickAccessState`：星标文件夹与最近去过的文件夹，最近不列眼前这个）。只亮一处：人在星标文件夹里时亮它，否则亮当前页。
  不要在导航栏旁边再并排一栏导航。侧边栏与状态栏在 `NavDisplay` 外面，打开「我的」里的各页时不被盖住（应用内播放器这类
  整窗的页照旧盖住，见 `sidebarMode`）。快捷访问的条目右键可以在新标签页打开、在信息流中刷、取消星标或从最近中移除。同样只在这一档，内容下面有状态栏（`ui/.../workbench/StatusBar`）：左边是进行中的传输
  （点开活动面板，看进度不必切到传输页）与最近一次能撤销的改动，右边是设置同步与空间用量。
- 返回栈：`PikoMainScaffold` 用 Navigation 3 的 `NavDisplay`，栈底 `Screen.Home` 是导航栏与三个根页面，
  其余页面压在上面、连同导航栏一起盖住。被盖住的 Home 离开组合，回来时重建，所以根页面的状态要经得起
  重建（网盘页的目录内容与滚动位置记在仓库里）。新页面加一个 `Screen` 子类、登记进 `NavKeyConfiguration`、
  在 `entryProvider` 里写一条 entry；切页与收起压栈页用 `resetToHome`，不要 `clear`，栈底必须留着 Home。
- 「我的」的详情页（星标、历史、分享、回收站、设置）：窄窗口是单页；expanded 由 `ListDetailSceneStrategy`
  与垫在下面的 `Screen.Profile` 拼成两栏，列表栏 360dp，两栏时详情页不给返回，退出在列表栏的顶栏上。
- 行长：设置、传输、回收站的行内容收在 840dp 以内居中。列表本身仍铺满窗口（用 `readableSidePadding`
  算 contentPadding），两侧空白处滚轮也能滚。
- 对话框：目录选择器在 compact 下全屏，更宽时是居中的基本对话框。
- 面板：一律经 `PikoSheet`，expanded 是从末端滑入的模态侧边面板，其余是只有展开一档的底部 sheet；
  不要直接用 `ModalBottomSheet`（播放器的面板另有横屏侧栏，除外）。
- 网盘页：compact 以上顶栏是整条路径加后退、前进（照资源管理器），compact 仍是目录名作标题、上级另成一行面包屑。
- 详情栏：expanded 的网盘页右侧，顶栏的「详情」或主修饰键+I 开关（`InspectorPane`）。看选中的几项，没选时看焦点所在的
  一项，都没有时是当前目录；操作与右键菜单同一份。与信息流侧栏占同一个位置，开一个就收起另一个。
  以后刮削到的作品信息放在预览与属性之间。
- 信息流：宽窗口是网盘页右侧的侧栏，放不下时全屏，桌面端还能弹出到独立窗口。它是**订阅**，不跟着网盘目录走：
  刷哪个文件夹只由范围菜单与文件夹操作里的「在信息流中刷」决定，上次订阅的即 `ClipFeedSession.lastFolder()`。

鼠标与键盘：条目右键弹出与操作面板相同的菜单（`ContextMenuArea`）。每页把一项的操作写成一个
`actionsFor`，面板与菜单都读它（网盘页是 `fileActions`）；新列表照做。网盘页按住主修饰键点选是加选，
Shift 点选是连选（`selectionClicks`，状态在 `DriveScreenState.toggleSelected` / `selectRange`）。
鼠标悬停时条目上出勾选框（列表盖在缩略图上，海报墙与图库在封面左上角），点它进入多选；在网格空白处拖动是框选
（`marqueeSelection`，`selectBoxed`），空白处单击退出多选。框选只从空白处开始，按在条目上拖动是拖放移动：
拖到侧边栏的文件夹、路径栏的上级或网格里的文件夹上，按着 Ctrl（mac 上 ⌥）是复制。拖放是应用内自己做的
（`FileDragState`，根上一份，落点经 `fileDropTarget` 登记范围），不走平台拖放；拖出去的一批自带落下后做什么，
落点只提供文件夹。
移动、移入回收站与重命名做完都记进 `DriveChangeJournal`（`driveRepository.changes`），提示带「撤销」，
Ctrl+Z 撤销最近一次；以后的批量改动（自动重命名、按刮削结果整理）也记一条，撤销即反向再做一次。
快捷键一览（F1 或主修饰键+/，`ShortcutsDialog`）是手写的一张表，加了快捷键要同时写进去。
命令面板（主修饰键+K，`CommandPalette`）：模糊搜索最近与星标文件夹、当前目录的子文件夹、去处与命令，方向键挑、回车执行。
全局的命令在 `PikoMainScaffold` 的 `paletteItems`；某一页自己的命令在页里经 `ContributePaletteItems` 登记，页面离开组合时撤掉
（网盘页登记了新建文件夹、上传、视图、详情栏等）。新页面有值得键盘直达的操作就照这样登记。
网盘页的键盘：方向键在条目间走（焦点所在的一项由 `keyboardFocusRing` 描边，只在键盘导航时画，
输入方式由根上的 `trackInputModality` 记），Enter 打开，菜单键或 Shift+F10 打开操作面板，
Delete 与 F2 作用于焦点所在项或选中的几项；鼠标点到哪一项，键盘就从哪一项接着走。Alt+←/→（mac 上 ⌘[ ⌘]）
与鼠标侧键是后退、前进，Backspace 与 Alt+↑ 是上一级。
横排的内容挂 `verticalWheelScrollsRow`，鼠标的竖滚轮才滚得动它；
图标按钮用 `TooltipIconButton`，快捷键写在提示里；Esc 经 `BackHandler` 触发返回；网盘页快捷键见
`DriveScreen` 的 `handleShortcut`。新加的界面同时照顾触屏与鼠标：下拉刷新之类只有触屏能用的操作，
宽窗口要另给按钮。快捷键的主修饰键取 `PikoPlatform.shortcutModifier`（mac 上是 ⌘），不要写死 Ctrl。
Compose 桌面端悬停移动事件的 `previousPosition` 恒等于 `position`，判断「鼠标动了」要自己记上一次的位置。

### 平台差异用接口，不用 expect/actual

两端相同、只是要用 JDK API 的代码放 `shared/src/jvmSharedMain`（Android 与 Desktop 共用的中间 source set），
不必拆成两份实现，例如网络代理的 `PikoProxySelector`：它装成进程默认的 ProxySelector，SDK、图片加载与更新检查
建 OkHttpClient 时都取走它，所以必须在任何客户端建出来之前装上（`PikoApplication.onCreate` 与桌面 `main`）。

`PikoUserPreferences`、`PikoSessionStore`、`PikoDownloadStorage`、`PikoSegmentDownloader`
都是 commonMain 的接口，Android 与 Desktop 各有实现。界面要的平台能力（剪贴板、系统取色、
下载位置选择、本地文件的打开与分享、片段预览的播放后端、全屏对话框、应用内更新）集中在
`ui/.../platform/PikoPlatform.kt`，实现是 `AndroidPikoPlatform` 与 `DesktopPikoPlatform`。
平台没有的能力返回 null 或 false，界面据此隐藏入口，例如桌面端没有系统分享。应用内更新两端都有，
检查与版本比较在 `shared/.../shared/update`，安装各走各的：Android 交给 PackageInstaller，桌面端按文件清单
决定只换 jar、AOT 缓存等五个文件还是整包 MSI，由 `apply-update.ps1` 在应用退出后执行。

本机文件上传的调度在 `shared/.../shared/upload/PikoUploadCoordinator`，一次传一个，会话随任务存盘以便跨进程续传；
平台只提供读文件（`PikoUploadSources`，桌面端是路径，Android 是 content: URI）与选择器（`PikoPlatform.uploadPicker`）。

**加一个偏好项要同时改三处**：接口、
`SessionManager`（Android，DataStore）、`DesktopPikoPreferences`（Desktop，`DesktopSettingsStore`）。
要跨设备同步的，再在 `shared/.../shared/sync/PikoSettingsSync.kt` 的 `SyncedSettings` 里加一行；窗口大小、下载目录、
代理这类每台设备各自的不要加。同步文件在网盘根目录的 `.piko/settings-<时间戳>.json`（`DriveSettingsStore`），
按项带修改时刻合并，这台设备从没同步过的项算最旧；`.piko` 不在网盘页里列出。

### 日志

`shared/.../shared/log/PikoLog` 是全局日志，从 debug 起全部写进滚动文件（四份各 1 MiB），用户在设置里导出后随
反馈交回。调用方只取时钟、投递一条，格式化与 IO 在单独的协程里做；即便如此也不要打在逐帧、逐块读写的热路径上，
几 MB 的上限会被刷掉。

导出的日志要发给别人，**不写文件名、账号、邮箱、令牌与直链**：文件用 `logFile(id, name)`，只留 ID 与扩展名；
给用户的提示只有一句话、异常被吞掉的地方，接一个 `.logFailure(tag, message)` 再 `onFailure`。写文件前还有
一层兜底脱敏（邮箱、手机号、本机路径、远程 URL），见 `PikoLog.redact`，但它认不出不带路径的文件名，
不能指望它。崩溃由两端入口装的默认异常处理写入并等落盘；桌面端界面线程上的异常经 `PikoWindow` 弹中文提示后同样抛出记下。

### 全局导航栈在仓库层

`PikoDriveRepository` 持有 `folderStackFlow`，是网盘主界面的全局位置，并持久化。
宽窗口可以开几个标签（`tabsFlow`、`openTab`、`switchTab`、`closeTab`），各有自己的路径栈与历史；`folderStackFlow` 与
`historyFlow` 始终是活动标签的那一份，切标签时仓库把它们换掉，所以别处照旧只认这两个，不必知道有标签。
标签栏（`DriveTabBar`）只在开了不止一个标签时出现；Ctrl+T、Ctrl+W、Ctrl+Tab，中键点文件夹在后台新标签打开，
拖到别的标签上即移进它停着的文件夹。标签按账号存进缓存目录（只存位置，不存历史），重启后由 `restoreTabs` 恢复。目录选择器
一类的浮层**必须维护自己的路径栈**，碰它会把主界面的位置一起改掉。
浏览历史（`historyFlow`，后退与前进）也在这里，每次换栈记一步；「上一级」与它无关。从别处跳进网盘（在网盘中显示、
快捷栏）用 `updateFolderStack`，会记进历史；只有启动时恢复位置用 `restoreFolderStack`，不记。
同处还记着侧边栏快捷访问的「最近」（`recentFoldersFlow`，按账号存进缓存目录）。
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
- **服务端解不了分卷压缩包**（2026-09-25 实测 7z、zip、RAR5 分卷）：它只读交给它的那一个文件，不去同目录找其余分卷。
  多数分卷当场回 `INVALID_FILE_FORMAT`；zip span 的最后一卷能列出目录，解压任务却以 `E_INVALID_FORMAT` 失败。
- **上传中的文件**（`phase` 为 PENDING）在开始上传时就出现在目录里，交给解压服务回 `file not complete`。
  上传会话的凭据 12 小时过期；刚传完的内容立刻进 CID 索引，再传同一文件即秒传。

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
代理负责把它们暴露成 HTTP Range。同一 handle 开出的 reader 共用一份块缓存、各有读位置，代理给每个 HTTP
请求开一个：mpv 拖动时新旧连接重叠、交错差的 MP4 在两处来回读，请求之间都互不取消。预取走
`handle.prefetch`，不占读位置；同一优先级按提出的先后取完，调用方不必自己限并发。

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
  规则在 `desktopApp/proguard-rules.pro`，JNA、MediaMP、ServiceLoader 实现、isoparser 必须保留；
  经 `MethodHandles` 按名字取出、交给 FFM 做 upcall 的方法（`WindowsCaption` 的窗口过程）代码里没有直接调用，
  同样要写 keep，否则 release 包里悄悄失效，debug 看不出来。
  打包时会跑一遍 AOT 训练（进程带 `compose.aot.training-run`，由 `Main.kt` 在 12 秒后自行退出），
  得到 `app.aot`。训练与运行都带 `-XX:-AOTAdapterCaching -XX:-AOTStubCaching`：JDK 25 会把训练机上生成的
  调用适配代码存进缓存且不核对 CPU 特性，CI runner 有 AVX-512，缓存装到没有它的 CPU 上随机崩在 AdapterBlob。AOT 缓存按 jar 的修改时间校验，MSI 与 zip 只存到偶数秒，训练前先把 jar 的时间取整，
  否则安装后缓存作废（`msiexec /a` 解出安装包即可验证）。
  jlink、jpackage 与 ProGuard 用 Azul 的 JDK 25 工具链，与运行 Gradle 的 JDK 无关；Temurin 25 不带 jmods，ProGuard 会失败。
  打出 MSI 后由 `package/windows/transactional-upgrade.ps1` 把卸载旧版挪进安装事务：新版装失败时旧版文件保留，
  但 Windows Installer 只把它记为「通告」状态，之后的应用内更新退回下载页。打包会弹出训练窗口约 12 秒。
- **原生**：mpv 与 FFmpeg 的 DLL 解开放在应用资源目录的 `mpv/` 下，启动时经
  `MpvMediampPlayer.prepareLibraries` 指过去；MediaMP 默认每次运行都解压一份到 `%TEMP%` 且删不掉。
  Toast 经 FFM 直调 combase 与 COM 虚表（`WindowsToast`），不用 kotlin-winrt。未打包应用的 AUMID
  要在 `HKCU\Software\Classes\AppUserModelId` 登记才会显示通知，安装版首次启动时写入。
- **单实例**：`SingleInstance` 以 `~/.piko/instance.lock` 的文件锁决定主实例，后来者经同目录的
  Unix domain socket 转交启动参数（磁力链接）后退出。安装版与 `gradlew :desktopApp:run` 共用这把锁，
  装好的 Piko 开着时，开发构建一启动就把参数转交过去然后退出，调试前先关掉安装版。AOT 训练进程不参与。
- **关窗**：仍有下载进行时关主窗口不退出，藏进托盘，下完自动退出。窗口位置、大小与最大化状态存在
  settings.properties 的 `window.<名称>.*` 下。
- **标题栏**：自绘，入口是 `WindowFrame`。Windows 上不用 undecorated，而是经 FFM 子类化窗口过程
  （`WindowsCaption`）：WM_NCCALCSIZE 只收回顶边，WM_NCHITTEST 答 HTCAPTION 与三个按钮的命中码，
  贴靠布局、边缘缩放、阴影与 Win+方向键因此仍由系统负责；按钮的悬停与按下来自非客户区消息，不是 Compose
  指针事件。macOS 用根面板属性把内容铺进标题栏，再由 Skiko 的 `disableTitleBar` 接管拖动，红绿灯保留。
- Compose 与 MediaMP 的桌面依赖带进了 ui-test、junit、truth 与 kotlinx-coroutines-test，
  在 `desktopRuntimeClasspath` 里排除，测试类路径不受影响。
- **版本号**：`-PpikoDesktopVersion` 只在 tag 构建时传（CI 经 `ORG_GRADLE_PROJECT_pikoDesktopVersion`），
  同时写入 `-Dpiko.release-build=true`，更新器只在带这个标记时启动即检查。不传时默认 1.0.0：macOS 的
  CFBundleVersion 首位必须大于 0，jpackage 拒绝 0.x；它可能与正式版同号，所以不能靠版本号认开发构建。
- **macOS（实验性，仅 Apple 芯片）**：同一个 `desktopApp`，原生库与 Compose 运行库按宿主系统取，jpackage
  不能交叉构建，DMG 只在 `release.yml` 的 macos-15 runner 上打。mpv 运行库照 Animeko 用 MediaMP 的
  `mediamp-mpv-runtime-macos-arm64`，画面走 Metal。与 Windows 的差别：不做 AOT 缓存（训练晚于 jpackage 签名，
  写进去会破坏签名封印）；播放器全屏用 `WindowPlacement.Fullscreen`；magnet 链接、Cmd+Q 与点 Dock 图标
  经 Apple 事件进来；通知经 osascript（署名为脚本编辑器，自己署名要签过名的 bundle），防休眠经 caffeinate；
  快捷键的主修饰键由 `PikoPlatform.shortcutModifier` 给出，mac 上是 ⌘。平台胶水集中在 `MacOs.kt`。
  更新器只给下载页。没有开发者证书，包未经签名与公证。

## 开发用 CLI

`:cli` 是开发工具，不随应用发布。`./gradlew :cli:installDist` 后执行 `cli/build/install/piko-cli/bin/piko-cli`：

- `snapshot -o <文件> [--root <路径>] [--depth <层数>] [--deep <名字,…>]`：只读列网盘目录，存成快照，
  只含文件名、类型、大小。会话取自 `~/.piko`，token 轮换后写回，与桌面端共用。
- `dryrun <快照> [--path <前缀>] [-o <文件>]`：离线对快照跑网盘页的解析流水线，逐行写出原名与界面上的样子。
  调的是 `DriveScreenState` 同一组函数（`analyzeDriveFolder`、`buildDriveItems`、`describeDriveFolder`）。
- `ls <路径>`：只读列一个目录，打印每项的 `params`。列目录接口在这里带回来源链接（离线下载的磁力、
  分享转存的 `mypikpak.com/s/` 链接）与视频的 `duration`、`width`、`height`，不必另查详情。
- `parse <文件名>…`：单独解析文件名。
- `share <分享链接> [--pass <提取码>] [--restore]`：只读列出分享的顶层。`--restore` 实测转存：把分享里
  最小的一个文件转存进根目录下新建的 `piko-probe-restore-*`，等任务结束后列出结果，再永久删除该文件夹。
  实测结论：文件直接落在目标目录下，不带分享里的上级目录；任务秒级完成；任务 params 里没有新旧 id 的映射。

Git Bash 会把以 `/` 开头的参数改写成 Windows 路径，传网盘路径时前面加 `MSYS_NO_PATHCONV=1`。

快照含真实文件名，放在仓库外，不要提交。改解析规则后重跑 `dryrun` 对比即可，不必重新请求网盘。

## 截图

`:shots` 也是开发工具，不随应用发布：无头运行整个应用（`PikoApp`，与桌面入口同一套界面、状态与平台实现），
数据来自假的 PikPak 服务端，按任意窗口尺寸与深浅主题出 PNG。改了布局就跑它看图，不必开真实账号，
也不用在 Windows 上：Linux 与没有显示器的机器同样能跑。

```bash
./gradlew :shots:run --args="all"                        # 一整套，写到 build/shots/，约一分钟
./gradlew :shots:run --args="shot starred --size 1100x800 --click 我的 --click 星标 --wait Dune"
./gradlew :shots:run --args="texts --click 传输"          # 打印界面上的文本，找 --click 的目标用
```

- 步骤有 `--click`、`--right-click`、`--hover`、`--key`、`--type`（往有焦点的输入框打字，中文也行）、`--drag`（按住左键拖，
  坐标按 dp）、`--release`、`--wait`、`--pump`，
  按写的顺序执行；点击按文本或内容描述找节点，
  弹层里的也算。`all` 的清单在 `shots/.../Main.kt` 的 `standardSet`，改了哪类界面就往里加一张。
- 数据在 `ShotEnv.kt` 的 `FakePikPak.seed()`：一部 12 集的番剧、一个子目录、电影与文档、回收站、星标、
  离线任务与四个本地下载。假服务端（`FakePikPak`）经 OkHttp 拦截器作答，SDK 的请求与解析仍走真实代码；
  只答界面读得到的接口，其余回 404，新页面要什么就补什么。与冒烟测试的 `FakePikPakServer` 是两份，那份要 MockEngine。
- 没有窗口外框：自绘标题栏与拖放层不在画面里。Linux 上没有微软雅黑，中文落到别的字体，字宽与 Windows 略有出入。
- 网络缩略图与海报不画，播放历史与我的分享是空的。
- 参数里有中文时，Linux 上要 UTF-8 的 locale（`LC_ALL=C.UTF-8`），否则 Gradle 传给进程时变成问号，按文本找不到节点。

## 冒烟测试

`.github/workflows/smoke.yml` 在推送到分支与 PR 时运行（tag 不跑）：Linux 上的 `:shared:desktopTest`，以及 x86_64 模拟器
（API 34）上的 `:app:connectedDebugAndroidTest`。这些是端到端行为冒烟，不是单元测试：走真实 libmpv、
真实代理，PikPak 服务端用 MockEngine 顶替，SDK 的请求、鉴权与解析仍走真实代码。本地不必跑，以 CI 结果为准。
老格式样片在 `testdata/media/`，直接提交，生成方式见 `generate.sh`；没有 WMV3/VC-1 样片，因为 ffmpeg 没有它的编码器。
