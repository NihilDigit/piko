# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Piko 是 PikPak 的第三方跨平台客户端。Android、Windows、macOS 与 Linux（后两者实验性）共用一套 Material 3 Expressive 界面，
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
gh workflow run test.yml -f desktop=true   # 手动跑全部测试，含 Windows、macOS 的安装与更新冒烟
gh workflow run release.yml -f version=9.9.9  # 发版演练：测试、构建、汇总照常，不建 release
```

`gradlew :desktopApp:run` 直接读 `build/classes`，开发版运行期间重新编译它加载的模块，正在运行的进程会在
下一次加载类时报 `NoClassDefFoundError`。要编译先关掉开发版。

改完务必两端都编译：`shared` 与 `ui` 的改动会同时波及 `app` 与 `desktopApp`，只编译一端看不出来。
`ui` 的桌面端与 Android 端用的 material3 版本不同（见「桌面端」一节），同一行代码可能只在一端报错。

版本号来自环境变量 `PIKO_VERSION_NAME` / `PIKO_VERSION_CODE`，本地不设则用默认值，无需配置。

## 与 pikpak-kotlin SDK 的关系

网盘能力全部来自 `io.github.nihildigit:pikpak-kotlin`（版本在 `gradle/libs.versions.toml`），
作者同一人，源码通常在本机 `../pikpak-kotlin`。

**需要改 SDK 时**：在本仓库的 `local.properties` 里写 `pikpak.sdk.dir=../pikpak-kotlin`，`settings.gradle.kts`
就以复合构建把依赖里的 `pikpak-kotlin` 换成那个目录的源码，改了 SDK 下次编译即生效，不必发 SNAPSHOT、不必改版本号。
SDK 那边的 `local.properties` 要有 `sdk.dir`（Android 插件在复合构建里读它）。两个仓库的 Kotlin、AGP 与 Gradle
版本要一致：AGP 不同时 Gradle 判断不了两边的 Android 变体是否匹配，直接解析失败。
SDK 在 Piko 发版之前才发，平时的改动攒在 SDK 的 main 上。所以 `test.yml` 平时也检出 SDK 的 main 做复合构建，
Piko 的 main 可以先用上 SDK 还没发布的改动；`release.yml` 不这样做，照样取 `libs.versions.toml` 里的已发布版本。
发 Piko 之前先发 SDK、再把版本号改过去，否则 Piko 的发版构建过不去。
**不要再用 mavenLocal**：`publishToMavenLocal` 版本号没改对时会覆盖本机缓存里的正式版。

Maven Central 按月给命名空间限额（`io.github.nihildigit` 是 11 次发版、5,460 个文件，见 Sonatype 的 Usage Center），
2026 年 9 月发了 18 版、超了。联调走复合构建，SDK 的改动攒到 Piko 发版前一起发。

## 发版

两个仓库都由 `v*` tag 触发 GitHub Actions。piko 依赖已发布的 SDK，所以顺序不能颠倒：
先发 SDK（tag → Maven Central，`automaticRelease = true` 无需手动确认，同步到 repo1 约数分钟），
确认 `repo1.maven.org` 能解析到新版本后，再改 piko 的依赖版本、提交、打 tag。

自有仓库不走 PR，直接在 `main` 上提交。

测试与发版是两个 workflow，先测后发。tag 只触发 `release.yml`，它的第一个 job 调 `test.yml`（全部单测与冒烟，
含 Windows、macOS 的全新安装与应用内更新，用已发布的 SDK），全绿后 Android、Windows（x64、arm64）、macOS
才并行构建，产物汇到 `release` job 统一算 `SHA256SUMS.txt`、生成 `release.json`（检查更新的备用来源）、
做两份构建来源证明（`attest-build-provenance` 与 SLSA Build L3），建一个**草稿** release。
草稿对 `releases/latest` 不可见，应用内更新在公开前不会提示。push 与 PR 只跑 `test.yml` 里不打包的部分；
桌面端的安装与更新冒烟要打两遍安装包，手动触发 `test.yml` 或发版时才跑。改了发版流程先手动触发 `release.yml`
演练一遍：填一个版本号，测试、构建、汇总照常，不建 release、不做证明，产物留作 workflow 产物。

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
同样两个 target）、`app`（Android 入口、平台实现与播放器）、`desktopApp`（Windows、macOS 与 Linux 入口、
平台实现与播放器窗口）。

### 界面写一次

`ui/src/commonMain` 是全部界面：主题、导航、网盘、传输、设置、登录与各组件，两端共用。
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
`OfflineTasksState`（云端离线任务，轮询由调用方的协程控制启停）、`LoginState`、
`FolderPickerState`（自带路径栈）、`DuplicateFinderState`（查重）、`ArchiveExtractSession`（服务端解压，
进程级，挂在 `PikoServices` 上，离开网盘页照常进行）。
播放器的准备策略是 `shared/.../shared/media/player/PlayerScreenState`，见「播放器」一节。

### 响应式布局

布局只看窗口宽度，不看设备：`ui/.../adaptive/WindowWidth.kt` 按 M3 断点给出 compact、medium、
expanded。桌面窗口缩放与平板分屏走同一套判断，桌面体验以 Android 平板为准。
- 导航只有两套：compact 下是 `NavigationSuiteScaffold` 的底部导航栏（写死 `ShortNavigationBarCompact`，不交给库按窗口挑：
  库还看高度，横握的手机会得到一条横向底栏），比 compact 宽（`SidebarMinWindowWidth`，600dp）一律是一整条侧边栏
  （`MainSidebar`），连同外框与并进内容的标题栏。窗口不到 `SidebarPushMinWindowWidth`（1000dp）时侧边栏只占窄轨，
  展开的那一份带遮罩浮在内容上（照模态抽屉，点遮罩、返回或去了别处就收回，不改存下的收起状态）；更宽时展开是推开内容。
  侧边栏上面是去处，下面是快速访问（`QuickAccessSection` / `QuickAccessState`），
  照资源管理器只列用户固定的文件夹，文件夹右键「固定到快速访问」。PikPak 没有这项，存在偏好 `pinnedFolders` 里
  经设置同步带走（`PinnedFolders`，只存 ID 与名字，打开时按 ID 查上级）。只亮一处：人在固定的文件夹里时亮它，否则亮当前页。
  不要在导航栏旁边再并排一栏导航。侧边栏在 `NavDisplay` 外面，打开「我的」里的各页时不被盖住（应用内播放器这类
  整窗的页照旧盖住，见 `sidebarMode`）。快速访问的条目右键可以在新标签页打开或取消固定。侧边栏不能拖宽，只能收起成只剩图标的窄轨
  （顶上的开关或主修饰键+B，`sidebarCollapsedFlow`，每台设备各自的；各行读 `LocalSidebarCollapsed` 自己换成窄轨的样子）。
  蜗牛模式开着时「传输」按钮整个用强调色，有没有传输都是。「文件」与「传输」是一组连体按钮；有传输在跑时「传输」按钮上写速度（上下行取快的一边）或项数
  （`workbench/TransferActivity`），蜗牛模式（限速，偏好 `snailModeFlow`，每台设备各自的，不同步）开着时用强调色。
  原来窗口底部的状态栏已去掉，不要再加回。
  这一档画外框（`theme/Frame.kt`）：侧边栏、网盘页页眉与右侧面板同为外框色，各页内容是一张卡片；标题栏在 Windows 上
  可并进内容（设置里的「紧凑标题栏」，`WindowCaption`）：贴着窗口右上角的那一行自己画窗口按钮，空白处经 `windowDragArea` 登记为拖动区。
  并进与否不看宽度：主界面任何宽度都接管（手机宽度时是各页顶栏画按钮），登录页这类主界面之外的页仍是系统标题栏。
  新写的顶栏要能画按钮：挂 `rememberCaptionSlot()` 的 modifier，按钮接在动作后面，标题后面的空白登记为拖动区。
  各页一律用 `PikoScaffold`，不直接用 `Scaffold`：有外框时它把顶栏、底栏放在外框色上，内容裁成卡片；顶栏与底栏的高度
  取 `Frame.kt` 的 `FrameTopRowHeight`、`FrameBottomRowHeight`，与侧边栏的图标行、账号行对齐。
- 图标：平时一律描边（Outlined），选中、打开、正在生效时换实心（Filled），侧边栏、导航项、视图切换、开关按钮都照此。
  挑图标时选实心与描边长得不一样的：History、Share、SyncAlt 两种写法同形，切换了看不出。播放器叠在画面上的操作按钮例外，用实心。
- 顶栏滚动换色：列表页用 `PikoTopBar.kt` 的 `rememberListScrollTint`，按列表是否在顶端设顶栏状态，不要挂
  `pinnedScrollBehavior` 的 nestedScroll。后者累加滚动量，列表换了内容（进子文件夹、换筛选）或根本滚不动时
  仍停在换过色的状态。
- 搜索框右端一直有取消按钮：有字时清空，没字时关掉搜索。触屏上没有 Esc，没有这个按钮就退不出去。
- 返回栈：`PikoMainScaffold` 用 Navigation 3 的 `NavDisplay`，栈底 `Screen.Home` 是导航栏与三个根页面，
  其余页面压在上面、连同导航栏一起盖住。被盖住的 Home 离开组合，回来时重建，所以根页面的状态要经得起
  重建（网盘页的目录内容与滚动位置记在仓库里）。新页面加一个 `Screen` 子类、登记进 `NavKeyConfiguration`、
  在 `entryProvider` 里写一条 entry；切页与收起压栈页用 `resetToHome`，不要 `clear`，栈底必须留着 Home。
- 库：最近添加、星标、播放历史与回收站不是单独的页，是网盘页里的位置（`DriveLibrary`），列表、视图、详情栏、
  多选与右键菜单全用网盘页的。它占路径栈的第一级、取代根目录（`[星标]`、`[星标, 某文件夹]`），ID 带 `piko:` 前缀，
  后退、标签、恢复上次位置因此照常工作。`DriveScreenState.libraryView` 为眼前列的是哪个库，`load` 据此改从星标、
  回收站或事件接口取；库里平铺不解析、不折叠、不给排序，不能新建、上传、粘贴，也不接拖放。回收站只有恢复与彻底删除，
  最近添加与播放历史多一个「移除记录」，各库都多「在网盘中显示」。侧边栏里它们是开关：停在这个库时再点一下回到打开之前的位置。
  我的分享列的是链接不是文件，仍是单独的页。
- 「我的」只在手机上是一页（底部导航栏的第三项）；有侧边栏时库与设置直接列在侧边栏上，账号、退出登录与关于并进设置，
  详情页（我的分享、设置）占满内容区、不给返回。原来 600–1200dp 的两栏（`ListDetailSceneStrategy`）已去掉。
- 对话框：目录选择器在 compact 下全屏，更宽时是居中的基本对话框。
- 面板：一律经 `PikoSheet`。有外框时（大窗口）停进外框右侧那一栏（`SidePanelHost`），不带遮罩、不挡列表，与详情栏共用宽度；
  expanded 而没有外框时是从末端滑入的模态侧边面板，其余是只有展开一档的底部 sheet；
  不要直接用 `ModalBottomSheet`（播放器的面板另有横屏侧栏，除外）。
- 右侧那一栏同一时刻只放一样东西：详情、信息流或停进来的面板，谁进来原来的让出去。详情与面板让出去是关掉，
  信息流让出去是挂起（见下）。
- 宽窗口网盘页的命令栏：每一样显不显示由 `DriveCommands.kt` 的 `driveCommands` 按规则算出，输入是在哪、作用于哪几项、
  右侧那一栏里是什么、剪贴板与眼前列表的情形。做不了的不摆，别处已经摆着的不重复（详情栏开着时条目操作只在详情栏里）。
  加按钮先在那里加规则，不在命令栏里零散判断。放不下时由 `CommandBarLayout` 按优先级把低的收进「更多」
  （M3 toolbars 的 overflow），收哪几项只看宽度，拖动窗口时不跳；显示什么仍只由规则决定。
- 网盘页：compact 以上顶栏照资源管理器：后退、前进、上一级，加一条地址栏（`DrivePathTitle`，每段能点、能接住拖来的条目）；
  compact 仍是目录名作标题、上级另成一行面包屑。
- 详情栏：expanded 的网盘页右侧（`InspectorPane`），条目上悬停出现的详情按钮（`ItemDetailsButton`，取代原来的三点；
  触屏与窄窗口一直显示，打开操作面板）、空白处右键或主修饰键+I 打开，关闭在它自己的顶上。看选中的几项，没选时看焦点所在的
  一项，都没有时是当前目录；操作与右键菜单同一份。与信息流侧栏占同一个位置，开详情时信息流挂起。
  以后刮削到的作品信息放在预览与属性之间。
- 信息流：刷**网盘页当前文件夹**里的视频，子文件夹里的也算，其余一切都为刷得顺服务。宽窗口是网盘页右侧的侧栏，
  放不下时全屏，桌面端还能弹出到独立窗口。范围在打开的那一刻取定；进子文件夹不换。离开这个文件夹（路径栈里不再有它）、
  右侧那一栏被详情或面板占去，都是挂起，与「在网盘中显示」同一种状态（`suspendFeed`），不收起；独立窗口不挂起。挑段的先后在 `ClipFeedSession.ranked`：有 720P 转码的先于只有原画的，当前层先于子文件夹；
  没有转码的照样能放（原画 seek，起播慢），有转码的挑完了才轮到。不要再加范围菜单或「订阅」一类的入口。
  在信息流里「在网盘中显示」是「刷到有趣的，去研究一下」：信息流**挂起**（队列与看到哪一段都留着，应用内不画），
  出发点记成 `DriveLocation`，之后左边的浏览是临时的，离开文件夹也不收起；网盘页底部的 `FeedResumeBar` 给「继续刷」
  （`returnTo` 连历史一起回到出发点，临时浏览整段丢掉）与关闭。只有关闭才清空队列（`ClipFeedSession.close`）。
  取流的调度：每段只预取切片开头 5 秒（`PreparedClip.sliceRanges`）；放过 3 秒才升档，播放器的缓冲（`setBufferAhead`，
  mpv 的 cache-secs）与代理的预读一起放开到 10 秒，此前两者都压着。播放器的缓冲读在 SDK 里是最高档，不压就越过所有预取。
  冷开时头一段画面走起来之前只备前三段（`COLD_START_CLIPS`）。各段的会话与预取（`ClipStreams`）挂在 `ClipFeedSession` 上，
  不随页面走：Android 上「看完整」压栈时信息流离开组合，回来不必重取。取不到的一段先挪到队尾重取一次，第二次才拉黑。
  原画开头比转码开头低一档（7 对 8），不要改成独占通道：一段直链坏了会把其余原画全堵住。
  「当前页」一松手就取 `targetPage`，不等 `settledPage`：手机上吸附动画收尾要几百毫秒，等它就是每段起步顿一下。
  SDK 的阻塞读分两档：`PikPakStreamReader.urgent`（拖动后、卡顿、未出首帧）是 100，播放器平时往后缓冲是 50；
  「有人在等」由界面判断后设上，桌面端后端拖动时不报缓冲，拖动要单独记。

鼠标与键盘：条目右键弹出与操作面板相同的菜单（`ContextMenuArea`）；右键点在几项选中里的一项上时菜单作用于全部选中的，
照资源管理器。网盘网格的空白处另有一层右键菜单（查看、刷新、粘贴、新建、全选、详情），条目的菜单在里层先接住。
列表一律用按行对齐的 `LazyVerticalGrid`（`PikoItemGrid`），不用瀑布流：瀑布流按最矮的一栏放，顺序会在各栏间跳。每页把一项的操作写成一个
`actionsFor`，面板与菜单都读它（网盘页是 `fileActions`）；新列表照做。
网盘页的点击与键位照各自系统的文件管理器（Windows 照资源管理器，mac 照 Finder），不自创：
鼠标单击是选中（条目取得焦点，`focusIndication` 盖一层底色，详情栏跟着它），双击才打开；触屏轻点照旧打开。
多选时条目上画着勾选框，鼠标单击照旧是勾选。按住主修饰键点选是加选，
Shift 点选是连选（`selectionClicks`，状态在 `DriveScreenState.toggleSelected` / `selectRange`）。
在网格空白处拖动是框选
（`marqueeSelection`，`selectBoxed`），空白处单击退出多选。按在已选中或刚点过（焦点所在）的条目上拖动是拖放移动，按在空白或别的条目上拖动是框选，照相册的做法：
海报墙与图库几乎没有空白可按。拖放移动：
拖到侧边栏的文件夹、路径栏的上级或网格里的文件夹上，按着 Ctrl（mac 上 ⌥）是复制。拖放是应用内自己做的
（`FileDragState`，根上一份，落点经 `fileDropTarget` 登记范围），不走平台拖放；拖出去的一批自带落下后做什么，
落点只提供文件夹。
移动、移入回收站与重命名做完都记进 `DriveChangeJournal`（`driveRepository.changes`），提示带「撤销」，
Ctrl+Z 撤销最近一次；以后的批量改动（自动重命名、按刮削结果整理）也记一条，撤销即反向再做一次。
快捷键一览（F1 或主修饰键+/，`ShortcutsDialog`）是手写的一张表，加了快捷键要同时写进去。
命令面板（主修饰键+K，`CommandPalette`）：模糊搜索最近去过的与快速访问里的文件夹、当前目录的子文件夹、去处与命令，方向键挑、回车执行。
全局的命令在 `PikoMainScaffold` 的 `paletteItems`；某一页自己的命令在页里经 `ContributePaletteItems` 登记，页面离开组合时撤掉
（网盘页登记了新建文件夹、上传、视图、详情栏等）。新页面有值得键盘直达的操作就照这样登记。
网盘页的键盘：方向键在条目间走（焦点所在的一项由 `focusIndication` 描边，键盘导航时描边、鼠标点的盖底色，
输入方式由根上的 `trackInputModality` 记），菜单键或 Shift+F10 打开操作面板，
Delete 与 F2 作用于焦点所在项或选中的几项；鼠标点到哪一项，键盘就从哪一项接着走。鼠标侧键是后退、前进。
Windows：Enter 打开，Backspace 与 Alt+←/→ 后退、前进，Alt+↑ 上一级。
mac：⌘↓ 打开，回车改名（条目自己在 onPreviewKeyEvent 里接住，否则条目的单击先把它当打开），⌘[ ⌘] 后退、前进，⌘↑ 上一级。
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
检查与版本比较在 `shared/.../shared/update`，安装各走各的：Android 交给 PackageInstaller；Windows 按文件清单
决定只换补丁文件（exe、全部 jar、AOT 缓存、启动配置）、MSI 安装版整包重装，还是便携版从便携 zip 只换不同的文件，
由 `apply-update.ps1` 在应用退出后执行，它要等 JVM 与启动器两个进程都退出（jpackage 的启动器另起同名子进程跑 JVM）。
脚本里的相对路径逐级比对目录名得出，不按前缀截取：`%TEMP%` 可能是 8.3 短路径（`MARVIN~1`），与展开后的长路径
前缀对不上，CI 上出过换完文件又重启、无限循环。增量补丁（zstd）在解码器加载不了的机器上（Windows ARM64）跳过，
退回换整个文件；
macOS 整个 .app 换成新 DMG 里的（`apply-update-mac.sh`），不逐个换文件，那会破坏签名封印。
检查更新依次取 GitHub API、`releases/latest/download/release.json`（API 匿名限流，走代理的用户常被 403）。
版本信息不经镜像取：附件摘要就在其中，镜像能连摘要一起伪造。下载附件在一个字节都没收到时退到 ghfast.top，
按取自 GitHub 的摘要校验。jsDelivr 不能用：
它按 tag 取，tag 推上去时 release 还是草稿。开屏自动检查可在设置里关掉（`autoCheckUpdatesFlow`）。
带 `-Dpiko.update.auto=true` 启动时查到新版即自动装上，`desktopApp/package/package-smoke/` 用它对着假 Release
（`fake_release.py`）端到端地测安装与更新，本机也能跑：测试包用 `pikoDesktopUpgradeUuid` 与 `pikoDesktopPackageName`
另起一个产品，不碰已装的 Piko。
公告不做进应用：发在 Telegram 频道（`t.me/piko_dev`），「关于」里有入口。

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
同处还记着最近去过的文件夹（`recentFoldersFlow`，按账号存进缓存目录，命令面板用）与快速访问（`pinnedFoldersFlow`）。
仓库层还有 `refreshEvents`，供界面外的改动（如解压完成）通知列表刷新，`DriveScreenState`
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
  但 Windows Installer 只把它记为「通告」状态，之后的应用内更新退回下载页。它还给 app 目录登记 `*.jar`、`*.xml`
  的 RemoveFile 规则：增量更新换进来的新名字 jar 不在 MSI 的文件表里，没有这条卸载时会留下。打包会弹出训练窗口约 12 秒。
- **原生**：mpv 与 FFmpeg 的 DLL 解开放在应用资源目录的 `mpv/` 下，启动时经
  `MpvMediampPlayer.prepareLibraries` 指过去；MediaMP 默认每次运行都解压一份到 `%TEMP%` 且删不掉。
  Toast 经 FFM 直调 combase 与 COM 虚表（`WindowsToast`），不用 kotlin-winrt。未打包应用的 AUMID
  要在 `HKCU\Software\Classes\AppUserModelId` 登记才会显示通知，安装版首次启动时写入。
- **显卡设备失效**：驱动复位 GPU（NVIDIA 事件 153、TDR）时 D3D 设备一律失效，skiko 0.150 不检查 HRESULT，
  下一次改窗口尺寸就在 `makeDirectXSurface` 里解引用空指针，整个 JVM 崩溃。`GpuDeviceWatch` 在改尺寸前与每秒一次
  问 `GetDeviceRemovedReason`，失效了就让 `PikoWindow` 以 `generation` 为 key 重建所有窗口。它读的是 skiko 的内部布局
  （`Direct3DRedrawer.device` 与 `DirectXDevice` 结构体的槽位），升级 skiko 要对照源码核对。复现：管理员执行
  `dxcap -forcetdr` 后拉一下窗口。mediamp 的日志经 `MpvLogBridge` 进应用日志，tag 是 mpv。
- **测量途中销毁弹层**：Compose 桌面场景每帧先拷一份「主层加各弹层」的列表再逐个测量，测主层时若有弹层
  （菜单、提示、对话框）离开组合，它当场被销毁却仍在列表里，轮到它时整个窗口抛 `RootNodeOwner is already disposed`，
  出帧协程随之结束（Compose 的 bug，jb-main 仍在）。在测量时才组合的地方最容易撞上：`SubcomposeLayout`、
  Scaffold 的槽位、`BoxWithConstraints`、懒加载列表。规避：
  - 放弹层的自定义布局用普通 `Layout`，不用 `SubcomposeLayout`（命令栏的 `CommandBarLayout` 即因此改过）。
  - 拖着窗口边框改尺寸时，Compose 不等下一帧、当场测量，最易触发。Windows 上 `WindowFrame` 在拖动期间把
    `LocalWindowInfo` 的尺寸停在拖动前，并提供 `LocalWindowResizing`，按宽度换形态的判断都等松手；自己量尺寸
    做判断的地方照 `PikoMainScaffold` 的 `panelFits` 那样，拖动中先存着、松手再算。
  - 兜底：`PikoWindow` 的异常处理认出这一个异常，重建出事的窗口，不弹错误框。认的是 require 的文案，升级 Compose 时核对。
- **单实例**：`SingleInstance` 以 `~/.piko/instance.lock` 的文件锁决定主实例，后来者经同目录的
  Unix domain socket 转交启动参数（磁力链接）后退出。安装版与 `gradlew :desktopApp:run` 共用这把锁，
  装好的 Piko 开着时，开发构建一启动就把参数转交过去然后退出，调试前先关掉安装版。AOT 训练进程不参与。
- **关窗**：仍有下载进行时关主窗口不退出，藏进托盘，下完自动退出。窗口位置、大小与最大化状态存在
  settings.properties 的 `window.<名称>.*` 下。
- **标题栏**：自绘，入口是 `WindowFrame`。Windows 上不用 undecorated，而是经 FFM 子类化窗口过程
  （`WindowsCaption`）：WM_NCCALCSIZE 只收回顶边，WM_NCHITTEST 答 HTCAPTION 与三个按钮的命中码，
  贴靠布局、边缘缩放、阴影与 Win+方向键因此仍由系统负责；按钮的悬停与按下来自非客户区消息，不是 Compose
  指针事件。macOS 用根面板属性把内容铺进标题栏，再由 Skiko 的 `disableTitleBar` 接管拖动，红绿灯保留。
- **模态文件框一律经 `AwtDialogs`**（FileDialog、JFileChooser）：它只有挂起函数，里面换到专用线程上弹。
  在界面线程上同步弹，模态框就地嵌套一层 AWT 事件循环，里面又渲染一帧、又 flush 一次 Compose 不可重入的
  FlushCoroutineDispatcher，同一个续体被恢复两次，窗口整个崩掉（issue #7，macOS 上边放视频边改下载位置复现）。
  `AwtDialogsGuardTest` 扫 import，别处出现就不过。Windows 的原生框（`FolderPicker`、`SaveFilePicker`）本来就在自己的
  STA 线程上。属主窗口要在点击的当下取，再传进去。
- Compose 与 MediaMP 的桌面依赖带进了 ui-test、junit、truth 与 kotlinx-coroutines-test，
  在 `desktopRuntimeClasspath` 里排除，测试类路径不受影响。
- **版本号**：`-PpikoDesktopVersion` 只在 tag 构建时传（CI 经 `ORG_GRADLE_PROJECT_pikoDesktopVersion`），
  同时写入 `-Dpiko.release-build=true`，更新器只在带这个标记时启动即检查。不传时默认 1.0.0：macOS 的
  CFBundleVersion 首位必须大于 0，jpackage 拒绝 0.x；它可能与正式版同号，所以不能靠版本号认开发构建。
- **macOS（实验性，仅 Apple 芯片）**：同一个 `desktopApp`，原生库与 Compose 运行库按宿主系统取，jpackage
  不能交叉构建，DMG 只在 `release.yml` 的 macos-15 runner 上打。mpv 运行库照 Animeko 用 MediaMP 的
  `mediamp-mpv-runtime-macos-arm64`，画面走 Metal。与 Windows 的差别：不做 AOT 缓存（训练晚于 jpackage 签名，
  写进去会破坏签名封印）；播放器全屏用 `WindowPlacement.Fullscreen`；magnet 链接、.torrent、Cmd+Q 与点 Dock 图标
  经 Apple 事件进来（回调在界面线程上，读种子挪到后台）；设为 magnet 与种子的默认打开方式经 LaunchServices 直接改
  （`MacLinkAssociation`，Windows 则是登记后跳系统设置，见 `WindowsLinkAssociation`），首次启动问一次，答过不再问；通知经 osascript（署名为脚本编辑器，自己署名要签过名的 bundle），防休眠经 caffeinate；
  快捷键的主修饰键由 `PikoPlatform.shortcutModifier` 给出，mac 上是 ⌘。平台胶水集中在 `MacOs.kt`。
  应用内更新整个换掉 .app（见「平台差异」一节）：新包先拷到旁边，过了 `codesign --verify` 再去掉隔离属性、换进去；
  任何一步失败都留着旧包、重新打开它，下次启动时提示更新未完成（脚本写的 `failed` 标记）。
  没有开发者证书，包未经签名与公证。

## Linux（实验性）

同一个 `desktopApp`，只出 x64：MediaMP 0.5.0 的 mpv 运行库只有 `linux-x64`。打包照 Animeko 的路子，差别在下面逐条写明。

- **应用 ID `dev.nihildigit.Piko`，不再改**：.desktop、AppStream（`package/linux/`）、图标名、WM_CLASS 都用它，Flathub 以它为包名，
  按 `nihildigit.dev` 域名验证。Flathub 要求域名部分小写，末段可以大写。Android 与 macOS 已发版的 `dev.piko`、`dev.piko.desktop` 不动。
  WM_CLASS 默认取主类名（`dev-piko-desktop-MainKt`），桌面环境对不上 .desktop，Dock 里是一个没有图标的 java；
  `LinuxDesktop.setWmClass` 经反射改 XToolkit 的字段，要 `--add-opens java.desktop/sun.awt.X11`，只在 Linux 宿主上加。
- **产物**：`./gradlew :desktopApp:packageReleaseAppImage`（只在 Linux 宿主上注册）先出 jpackage 的 app-image，
  再由 `package/linux/build-appimage.sh` 出三个附件：app-image 原样的 `.tar.gz`（日后 Flathub 取它）、`.AppImage`、`.AppImage.zsync`。
  appimagetool 与 AppImage 头部的运行时按版本与摘要钉死，不用 continuous。AppImage 内嵌
  `gh-releases-zsync|NihilDigit|piko|latest|piko-linux-x64-*.AppImage.zsync`，AppImageUpdate 一类外部工具也能更新；
  .zsync 的 URL 写相对名，经镜像取时照样对得上。打包前的 AOT 训练要开窗，CI 上套 `xvfb-run`；本机 Gradle 的 foojay 0.9
  在 Gradle 9.6 上自动下载工具链会失败，要自己装一份 Zulu 25 写进 `org.gradle.java.installations.paths`。
- **AOT 缓存照做**：AppImage 每次挂载在不同的 `/tmp/.mount_*` 下，JDK 只比对类路径各项的相对位置，缓存照样认（`-Xlog:aot` 可见
  Opened AOT cache）。squashfs 的修改时间只到秒，与 Windows 取整到偶数秒同一个处理。**不能设 `SOURCE_DATE_EPOCH`**：
  mksquashfs 见到它会把所有文件的修改时间改成同一个值，缓存整份作废。
- **原生库**：mpv 运行库的 jar 存不了符号链接，同一个库以真实文件名、SONAME、无版本名各存一份（libavcodec 三份各 11 MB），
  `bundledAppResources` 只取 SONAME 那一份，放在资源目录的 `mpv/` 下，各库自带 `$ORIGIN` 的 RUNPATH，不上 `java.library.path`，
  也就不必照 Animeko 那样挪目录、patchelf。运行库要 glibc 2.38（Ubuntu 24.04、Debian 13 起），更老的系统开不了播放器。
- **画面要硬件 OpenGL**：MediaMP 在 Linux 上经 GLX 与 Skiko 共享纹理，只认 Skiko 的 `LinuxOpenGLRedrawer`。Skiko 把 llvmpipe 列为
  不支持，没有硬件驱动（虚拟机、xvfb、WSLg 默认）时退到软件渲染，画面一直是黑的，打开也不返回；播放器窗口据此提示一句。
  WSLg 里设 `GALLIUM_DRIVER=d3d12` 才走显卡。JVM 单测测不到这一段，装好的包里有自检：`-Dpiko.selftest=play`，
  `PIKO_SELFTEST_PATH` 指一个本机视频（`SelfTest.kt`、`PlaybackSelfTest.kt`）。
- **平台胶水**（`LinuxDesktop.kt`）：打开链接与文件交给 `xdg-open`，通知与「在文件管理器中显示」经 gdbus 调
  `org.freedesktop.Notifications`、`org.freedesktop.FileManager1.ShowItems`（后者失败退回打开所在目录），防休眠经 `systemd-inhibit`。
  一律不走 AWT 的 Desktop：它在 Linux 上靠 GTK，会把系统的 glib 载入进程，与 mpv 运行库自带的那份撞 SONAME。
  中文字体挑一个装了的简体字体（Noto Sans CJK SC 等），理由同 Windows 指定雅黑。标题栏用系统的，不自绘；
  播放器全屏用 `WindowPlacement.Fullscreen`；主修饰键是 Ctrl。GNOME 默认没有托盘，关窗后台传输时提示「再次打开 Piko」，
  单实例把后来者的启动转成叫回窗口。
- **默认打开方式**（`LinuxLinkAssociation`）：在 `~/.local/share/applications` 写一个 NoDisplay 的 .desktop（Exec 指 `$APPIMAGE`），
  再 `xdg-mime default` 写进 mimeapps.list，当场生效，首次启动问一次。只在以 AppImage 运行时可用，每次启动若 AppImage 挪了位置就改写 Exec。
  取消关联删掉这个文件与 mimeapps.list 里指向它的项。WSL 里 xdg-utils 认出 WSL 就把 xdg-open 转给 Windows，本机验证要换 `gio open`。
- **应用内更新**：只认 AppImage（`$APPIMAGE`），Flatpak（`FLATPAK_ID` 或 `/.flatpak-info`）里整个关掉，`updater` 为 null。
  查到新版时先取 .zsync（按 GitHub 的摘要校验），拿本机 AppImage 滚动对照，只按 Range 下缺的块（`Zsync.kt` 是 zsync 0.6.2 客户端的
  Kotlin 实现，含 MD4），拼好后按 GitHub 公布的 SHA-256 核对，拼不出来或对不上就整包下载。不照 Animeko 捆 appimageupdatetool：
  它自己去 GitHub 查、只信 .zsync 里的 SHA-1，退不到 ghfast.top，也校验不了 GitHub 的摘要。
  新文件写在旧文件旁边（`.<名字>.piko-update`），校验过即改名换上，**不必等退出**：运行中的 AppImage 由 FUSE 挂载进程开着旧 inode。
  重新打开要等退出，否则新进程撞上单实例锁、转交完就走：由 `setsid sh` 等本进程的 pid 消失再 exec 新的 AppImage。
  它的环境要去掉 `_JPACKAGE_LAUNCHER`：jpackage 的启动器在本进程里设了它，带着它起的新启动器不读 Piko.cfg，只打出 java 的用法。
  应用自己起的子进程再拉起 Piko（例如经 xdg-open）都有这个问题。
  所在目录不可写或不是 AppImage 运行（解开的 app-image）时只给下载页。
- **冒烟**：`package-smoke/linux.sh`，CI 的 `linux-package`（推送时不跑），`fake_release.py` 认单段 Range 并记下每次送出的字节数，
  断言差分确实只下了一部分。停应用只杀 JVM（挂载目录里的 `usr/bin/Piko`），先杀 AppImage 的运行时会把挂载从 JVM 底下拆掉，
  它下次读类文件时 SIGBUS。

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

**只在全自动工作流里，或我明确要求时才跑 `:shots`。** 平常改完界面直接编译、重启桌面开发版（或装到 Android 真机），
交给我手测。截图环境的假数据放不了视频、没有窗口外框，看不出的问题比看得出的多，反复出图只是拖慢来回。

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

都在 `.github/workflows/test.yml`，业务逻辑放 JVM 上测，原生行为在真机器上冒烟：Linux 上的 `:shared:desktopTest`，
Windows 上的 `:desktopApp:desktopTest`，Android 单测，x86_64 模拟器（API 34）上的 `:app:connectedDebugAndroidTest`，
以及 Windows、macOS、Linux 上对安装包的冒烟（`windows-package`、`macos-package`、`linux-package`，推送时不跑）：安装、应用内更新，
再验默认打开方式、在资源管理器中显示这类依赖系统真实行为的，包里的入口是 `SelfTest.kt`。冒烟走真实 libmpv、
真实代理，PikPak 服务端用 MockEngine 顶替，SDK 的请求、鉴权与解析仍走真实代码。本地不必跑，以 CI 结果为准；
安装与更新冒烟的脚本本机也能跑，见上面「平台差异」一节末尾。

JVM 测试看不出 Android 与 HotSpot 的差异：Android 的正则是 ICU，不认 `\p{IsHan}` 这类 Java 专有写法，
Android 8 上一编译就崩（1.0.0 出过）。`AndroidRegexGuardTest` 扫源码拦着，写脚本类用 `\p{script=Han}`。
ICU 的 `\d` 还是全部 Unicode 数字，文件名里的 `𝟐` 被抓出来后 `toInt()` 就抛（1.1.0 信息流闪退），数字一律写 `[0-9]`，同一个测试拦着。
模拟器上的 `NamingUnicodeDigitsSmokeTest` 把文件名解析的各个入口（网盘页、信息流、查重、播放列表）在 ICU 上跑一遍，
文件名里的数字逐位换成数学粗体、全角等别的数字，不许抛异常；解析入口有新增时往里补一行。
对话框在 Android 上是按内容定高、居中的独立窗口，内容高度一变整个对话框就跳：对话框里不做尺寸动画，
提示行常驻、出错只变色（issue #9）。
老格式样片在 `testdata/media/`，直接提交，生成方式见 `generate.sh`；没有 WMV3/VC-1 样片，因为 ffmpeg 没有它的编码器。
