# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Piko 是 PikPak 的第三方跨平台客户端。Android、Windows、macOS 与 Linux（后两者实验性）共用一套 Material 3 Expressive 界面，
桌面与移动各一套交互、按窗口宽度调整密度；业务逻辑与屏幕状态在 `shared`，界面在 `ui`，两端只剩入口与平台实现。

本文件只写跨模块的约定。各模块的细节在所在目录的 CLAUDE.md，读到那里的文件时自动加载；只搜索、不读文件时要自己打开：

| 文件 | 内容 |
| --- | --- |
| `ui/CLAUDE.md` | 响应式布局、导航与侧边栏、外框与标题栏、面板、图标、设置页的分类与行组件、鼠标与键盘、命令面板、动效与减少动画 |
| `ui/.../screens/drive/CLAUDE.md` | 网盘页：库、命令栏、地址栏、详情栏、选中与框选、拖放、键位 |
| `ui/.../screens/clips/CLAUDE.md` | 信息流：范围、挂起与继续、挑段先后、取流调度 |
| `desktopApp/CLAUDE.md` | 桌面端：界面库版本、release 与 AOT、原生库、显卡失效、弹层崩溃、标题栏、触摸、文件框、macOS、Linux |
| `shared/.../shared/update/CLAUDE.md` | 应用内更新：检查、镜像、各平台安装、安装与更新冒烟 |
| `shared/.../shared/download/CLAUDE.md` | 下载：稀疏暂存、画质与转封装、片段截取 |
| `shared/src/desktopMain/.../auth/CLAUDE.md` | 桌面端机密存储 |
| `cli/CLAUDE.md`、`shots/CLAUDE.md` | 开发用 CLI、截图工具 |

## 常用命令

```bash
./gradlew :app:compileDebugKotlin          # Android 编译（最快的语法与类型检查）
./gradlew :desktopApp:compileKotlinDesktop # Desktop 编译，注意不是 compileKotlinJvm
./gradlew :app:installDebug                # 装到已连接设备，包名 dev.piko.debug
./gradlew :desktopApp:run                  # 跑桌面端
./gradlew :shared:desktopTest              # shared 的单测与冒烟（文件名解析、日志、代理、更新说明等）
./gradlew :shared:desktopTest --tests '*ReleaseNotesTest*'           # 跑单个测试
./gradlew :ui:desktopTest                  # ui 里 internal 的纯函数规则（命令栏显隐等）
./gradlew :desktopApp:desktopTest          # 桌面端测试，含 WinRT 与窗口过程，只能在 Windows 上跑
./gradlew :app:testDebugUnitTest           # Android 单测
./gradlew :desktopApp:createReleaseDistributable  # release 包（ProGuard + AOT），keep 规则一类问题只在这里暴露
gh workflow run test.yml -f desktop=true   # 手动跑全部测试，含 Windows、macOS 的安装与更新冒烟
gh workflow run release.yml -f version=9.9.9  # 发版演练：测试、构建、汇总照常，不建 release
```

`gradlew :desktopApp:run` 直接读 `build/classes`，开发版运行期间重新编译它加载的模块，正在运行的进程会在
下一次加载类时报 `NoClassDefFoundError`。要编译先关掉开发版。改完代码直接结束开发版进程、编译、在后台重新
`:desktopApp:run`，不必先问或等我点完界面；安装版的 Piko 要关仍先问。

改完务必两端都编译：`shared` 与 `ui` 的改动会同时波及 `app` 与 `desktopApp`，只编译一端看不出来。
`ui` 的桌面端与 Android 端用的 material3 版本不同（见 `desktopApp/CLAUDE.md`），同一行代码可能只在一端报错。

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
平台实现与播放器窗口）。Windows 触摸与笔输入接入 Compose Desktop 的桥不在本仓库，是单独发布的
`dev.nihildigit:compose-windows-touch`（仓库 NihilDigit/compose-windows-touch，版本在 `libs.versions.toml`）。
联调时在 `local.properties` 写 `windowstouch.dir=../compose-windows-touch`，与 SDK 同一套复合构建。

### 界面写一次

`ui/src/commonMain` 是全部界面：主题、导航、网盘、传输、设置、登录与各组件，两端共用。
入口是 `PikoApp`：`MainActivity` 与桌面的 `Main.kt` 各自拼好 `PikoServices`（进程级的仓库与调度器）
与 `PikoPlatform`（平台能力），传进去即可。屏幕里经 `LocalPikoServices`、`LocalPikoPlatform` 取用，
不要再引用 `PikoApplication.instance`。交互模型按平台（`PikoPlatform.formFactor`：桌面或移动），密度按窗口宽度，细节见 `ui/CLAUDE.md`。

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
进程级，挂在 `PikoServices` 上，离开网盘页照常进行）、`ArchiveBrowser`（把压缩包当文件夹看，同样进程级）。
播放器的准备策略是 `shared/.../shared/media/player/PlayerScreenState`，见「播放器」一节。

### 平台差异用接口，不用 expect/actual

两端相同、只是要用 JDK API 的代码放 `shared/src/jvmSharedMain`（Android 与 Desktop 共用的中间 source set），
不必拆成两份实现，例如网络代理的 `PikoProxySelector`：它装成进程默认的 ProxySelector，SDK、图片加载与更新检查
建 OkHttpClient 时都取走它，所以必须在任何客户端建出来之前装上（`PikoApplication.onCreate` 与桌面 `main`）。

`PikoUserPreferences`、`PikoSessionStore`、`PikoDownloadStorage`、`PikoSegmentDownloader`
都是 commonMain 的接口，Android 与 Desktop 各有实现。界面要的平台能力（剪贴板、系统取色、
下载位置选择、本地文件的打开与分享、片段预览的播放后端、全屏对话框、应用内更新）集中在
`ui/.../platform/PikoPlatform.kt`，实现是 `AndroidPikoPlatform` 与 `DesktopPikoPlatform`。
平台没有的能力返回 null 或 false，界面据此隐藏入口，例如桌面端没有系统分享。应用内更新两端都有，
见 `shared/.../shared/update/CLAUDE.md`。公告不做进应用：发在 Telegram 频道（`t.me/piko_dev`），「关于」里有入口。

本机文件上传的调度在 `shared/.../shared/upload/PikoUploadCoordinator`，一次传一个，会话随任务存盘以便跨进程续传；
平台只提供读文件（`PikoUploadSources`，桌面端是路径，Android 是 content: URI）与选择器（`PikoPlatform.uploadPicker`）。

**加一个偏好项要同时改三处**：接口、
`SessionManager`（Android，DataStore）、`DesktopPikoPreferences`（Desktop，`DesktopSettingsStore`）。
要跨设备同步的，再在 `shared/.../shared/sync/PikoSettingsSync.kt` 的 `SyncedSettings` 里加一行；窗口大小、下载目录、
代理这类每台设备各自的不要加。同步文件在网盘根目录的 `.piko/settings-<时间戳>.json`（`DriveSettingsStore`），
按项带修改时刻合并，这台设备从没同步过的项算最旧；`.piko` 不在网盘页里列出。
机密不放进设置同步（1.1.0 明文同步过压缩包密码，已停用并清理）。压缩包密码以账号密码派生的密钥加密，单独存一份
`.piko/archive-passwords-<时间戳>.json`（`ArchivePasswordSync`），要同步别的机密照它做。

### 番号

番号的识别规则在 `shared/.../shared/naming/av/`，规范命名、MetaTube 对接与待办见 `docs/development/av-naming.md`。
不内置任何成人站点网址与 MetaTube 实例，地址与令牌只由用户在设置里填。

### 日志

`shared/.../shared/log/PikoLog` 是全局日志，从 debug 起全部写进滚动文件（四份各 1 MiB，只留 7 天；警告与错误另存一份
`piko-errors.log`，留 30 天；连续重复的行折成一行计数），用户在设置里导出后随
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
标签栏（`DriveTabBar`）在宽窗口里一直在，只开一个标签时也是；Ctrl+T、Ctrl+W、Ctrl+Tab，中键点文件夹在后台新标签打开，
拖到别的标签上即移进它停着的文件夹。标签按账号存进缓存目录（只存位置，不存历史），重启后由 `restoreTabs` 恢复。目录选择器
一类的浮层**必须维护自己的路径栈**，碰它会把主界面的位置一起改掉。
浏览历史（`historyFlow`，后退与前进）也在这里，每次换栈记一步；「上一级」与它无关。从别处跳进网盘（在网盘中显示、
快捷栏）用 `updateFolderStack`，会记进历史；只有启动时恢复位置用 `restoreFolderStack`，不记。
同处还记着最近去过的文件夹（`recentFoldersFlow`，按账号存进缓存目录，命令面板用）与快速访问（`pinnedFoldersFlow`）。

文件夹的名字与上级只有一份，在仓库的 `FolderIndex`：列目录、查详情与 Piko 自己的改动写进去，路径栈、标签、历史、
最近去过随它改正（改名换名字，移走换上级，删掉的退到它之外），快速访问读出时换名字；存着的 `PikoPathBreadcrumb.name`
只在索引里还没有那一项时回落，存盘格式没变。所以显示路径的地方直接读这几个流，不要自己拼或另存名字；按 ID 找路径用
`locateFolder`（先查索引，缺口才问服务端）。库与压缩包里的一层不进索引（`isDriveFolderId`），压缩包那一级按它的文件 ID 记。
目录的内容也只有一份：仓库的列表缓存，网盘页、目录图、地址栏的 › 与补全、命令面板都读它。网盘里的改动一律经
`applyChange(DriveChange)` 收口（索引、缓存过期、广播 `folderChanges`），新加改动网盘的入口照此调一次，不要自己清缓存或通知界面；
`DriveScreenState` 与目录图已订阅，只重列受影响的那几层，视图不要再订阅一遍。
有标签栏时查重标签由查重会话占着（`duplicatesTabHeld`）：往别处走另开标签，不改写它；查重结束后照常改写。

### 账号与凭据

同一时刻一个账号在用（`PikoClientManager.currentClient`），登录过、没退出的记在 `accounts`（`SavedAccounts`，
只有账号名、昵称、头像与上次的用量，不含机密）里手动切换。切换就是换一个 client，别处照旧只认 `currentClient`；
按账号的东西在账号变了时各自换一份，新写的按账号的状态照这个做，不要假定账号不变：
- 网盘页的位置、标签、历史、剪贴板、撤销记录与列表缓存：`PikoDriveRepository.enterAccount`，收集者与网盘页都调，谁先到谁做。
- 进程级会话（添加链接、查重、解压、片段下载、信息流、归档）与桌面端的播放器、信息流窗口，一律经
  `launchOnAccountLeave` 在主线程上当场结束（`PikoServices` 与桌面 `Main.kt` 各一处）；新加的进程级会话登记到这里，不另收集账号。
  下载任务带 `account`，别的账号的暂停。传输页与「传输」按钮上的项数、速度只算当前账号的下载、上传与秒传
  （`TransfersState`、`TransferActivity`），别的账号的任务原样留着，切回去照旧可见、可继续。
- 压缩包密码按账号存，与会话、密码同在账号的机密里（`ArchivePasswordStore`，桌面并进 `DesktopSessionStore` 那一份，
  Android 是 DataStore 里加密的一键），退出登录不清。读写只经进程里唯一的 `ArchivePasswordVault`（`PikoServices.archivePasswords`），
  它只给当前账号看、只记进当前账号；同步只推当前账号的，拉下来的只并进它。1.1.0 全机一份的明文表由登录后头一个运行的
  账号并进自己的那份，随即删掉：1.1.0 只能登录一个账号，升级时登着的就是它。只在开发期存在过的设备级密文不迁移。
- 主界面按账号重建（`PikoApp` 的根状态），挂起的信息流、临时标签、目录图、属性卡片这类组合里的状态随之丢弃。
  只有设备级的开关会带过去：重建时要不要照开关打开信息流由 `ClipFeedSession.reopensFor` 定，换了号不开。
- 缓存目录里按账号的存盘，键里都带账号：标签、列表缓存、最近去过、来源账本（`sources-`）、归档目录表（`vault-trees-`）、
  信息流队列（`clip-feed-<账号>-<文件夹>`）。只靠文件夹 ID 不够，各账号的根目录都是空串，信息流队列曾因此串到别的账号。
  等着延迟写盘的，换号时当场写给原账号，不取消。
- 记着网盘里文件夹 ID 的进程级对象按账号记（`PreviewTempFolder` 的 Piko-Temp），不要只记一个。
- `.piko/` 的同步（设置、归档目录表、来源账本、压缩包密码）经 `DriveSettingsStore`，网盘读写走的是仓库眼前的账号，
  所以每一步前核对账号仍是发起同步的那个，换了就放弃这一轮。
- 记着文件夹 ID 的偏好（快速访问、最近移动目标）经 `AccountScopedPreferences` 按账号存进缓存目录。它们的旧值在平台偏好里
  原样留着、另记「已被哪个账号接走」：清空的话，同机先后打开的旧版本会把「本机改成了空」同步进网盘。
- 退出只退当前账号，随后切到最近用过的另一个；钥匙串一时读不出密码时按没有密码处理，不当成已退出。

机密（会话与密码）按账号合成一份交给平台保管，桌面端的实现见 `shared/src/desktopMain/.../auth/CLAUDE.md`。

### 归档（vault）

归档条目是网盘里不占空间、只留引用的文件：每个文件夹一份清单 `.piko-vault-v<版本>-<随机串>.json`（`VaultStore`），
记着 gcid、大小与来源。列表经 `listBrowsable` 把条目并进真实文件，ID 以 `piko-vault:` 开头；播放、下载、信息流打开它们时
按 gcid 秒传一个对象到 Piko-Temp，取到直链就删（SDK 的 `leaseDetail` 与 `fileHandle(leased = true)`，所有账号均以
`LeaseBudget` 按剩余空间限制同时借出的字节数）。清单是可信写入：版本大的赢，同版本随机串小的赢，输的一方把自己的纯函数改动套到
赢家上重写，只存状态不存历史。归档文件夹与归档单个、多个文件是同一个会话（`FolderVaultSession`，范围为 `VaultScope`）：
文件夹各取整棵树，单独的文件按所在文件夹分组写清单，确认、处置、撤销与进度不另写一套。取回后条目以 `restoredFileId` 保留来源和 CID，不再显示虚拟文件；真实文件按 ID 与内容标识补回元数据，供再次归档使用。
文件夹上的归档标记除了直接放着条目的（单独归档的文件所在的那一层即属此类），还推到归档时选的那一层：清单只在放着条目的那一层，外层看不出来，
所以整棵归档过的树另记一张表（`VaultTrees`），跨设备存在网盘 `.piko/vault-trees-<时间戳>.json`（`VaultTreeSync`，跟着设置同步的开关，
与设置文件分开，合并取并集）。同目录另存完整来源：磁力为 `归档来源-<标识>.magnet`，分享链接为 `.txt`，其他客户端可直接读取；这些文件不参与归档。代码里叫 vault，与压缩包（ArchiveRepository、服务端解压）区分；界面上叫「归档」。
并发与写入的实测数据在 `docs/development/archive.md`。

来源账本（`SourceLedger`）：服务端只给离线下载与分享转存的文件填 `params.url`，Piko 按 gcid 秒传的文件为空、也写不进，
由 Piko 记下。键是 gcid（大写）加大小，不用文件 ID：复制换 ID，内容不变。值是来源（完整磁力，原样）、种子内路径与记下的时刻。
写入点是磁力秒传保存（`InstantSheetState.rawInstantSave`，批量逐行同经此处）与归档取回、撤销归档时的秒传
（`PikoDriveRepository.instantCreate` 带上条目的来源）；分享转存服务端已填 url，不记。补全只在仓库：列目录（`fetchListing`，
列表缓存、目录图、网盘页同读这一份）、全盘搜索、星标与事件库出来的文件，`sourceUrl` 为空而账本里有的，以 `params + ("url" to 来源)`
并进，与 `VaultEntry.enrich` 同一形式，下游照常读 `sourceUrl`，不分辨文件怎么来的。账本载入或合并进新内容时，已缓存的相关几层重列。
本机按账号存缓存目录，跨设备存网盘 `.piko/sources-<时间戳>.json`（`SourceLedgerSync`，跟着设置同步的开关），来源单列一张表、文件按下标引用。
合并取并集，同键取记下得晚的，时刻相同按来源、路径的字典序取大者，只留一条。不加密：归档本就把来源明文存在网盘里；
提取码不进账本。官方客户端秒传的、账本之前秒传的文件不回填。

免费账号与会员的取舍：目标排序是 Piko+会员 > 官方+会员 > Piko+免费 > 官方+免费。免费账号的归档、播放、信息流不设上限，
画质与速度不由 Piko 限制（服务端的限额照旧生效），只在结构上低于会员：不做激进优化（不悬停预借、不额外并行预取）。
已否决：按日限额、限画质、Piko 侧限速、刻意降级、伪装流量。账号等级自动识别（`TransferAllowances.isPremium`），不给开关。

## PikPak API 的既有约束

这些是实测结论，不要重新推导。新的实测可以直接在我的真实账号上做，不必先问：在 `../pikpak-kotlin` 写 opt-in 的
jvmTest（`PIKPAK_PROBE=1`，凭据在它的 `.env`），总量不超过 100 GB，只创建、改动、删除实验自己建的任务与文件，结束后移入回收站。
碰到网盘原有内容、超过 100 GB 或会消耗免费账号每日次数的，先问。

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
- **文件名上限按 UTF-8 算，1024 字节**（2026-09-29 实测，文件与文件夹相同）：ASCII 1024 个、汉字 341 个、
  补充平面字符 256 个，再多一个字符回 `file_name_too_long`（error_code=3，HTTP 400）。批量重命名据此当场标出。
- **文件的 `params` 客户端写不进**（2026-10-08 实测，SDK 的 `SourceParamsProbeTest`）：秒传建文件时请求体带 `params` 被静默丢弃；
  事后 `PATCH /drive/v1/files/{id}` 只要 `params` 非空一律 400 `invalid_argument`（`url`、自定义键、原样回传都一样），`{}` 回
  `file_nothing_updated`。`params.url`（来源）只有离线下载与分享转存由服务端填；秒传的来源只能 Piko 自己记（见「归档」一节的来源账本）。
  复制出的新文件 params 由服务端重建（多 `original_file_id`），按 ID 记的东西复制后对不上，要按 gcid 记。
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

**许可**：piko 源码是 AGPL-3.0-or-later（1.1.0 及更早是 MIT），外部 PR 须签 `CLA.md`（FSFE Contributor Agreements 生成的个人非独占协议，
外向许可承诺贡献始终以 AGPL-3.0-or-later 提供，另可附加其他许可证，以便单独授权），由 `.github/workflows/cla.yml` 收签，签名存于 `cla-signatures` 分支。
Android 分发包里 jdtech 的 FFmpeg 以 `--enable-gpl --enable-version3` 构建，mpv 也是 GPL 构建，与 AGPL 按其第 13 条可合并分发；
发版时附第三方声明，并指明对应源码的获取方式。取自其他项目的代码（如 PowerRename 的 MIT 代码）保留原有的版权与许可说明。

## 截图与界面审查

`:shots` 的 `standardSet` 覆盖 `docs/development/ui-states.md` 里的每个可达状态，桌面、手机、平板各出一张，用法见 `shots/CLAUDE.md`。
它的目标是界面的回归基础设施，但还没成熟：整套要跑几十分钟，也还不能按改动只重出受影响的图。后续工作见
`docs/development/shots-roadmap.md`。

**在那之前，界面改动不要求同时补截图步骤与假服务端，也不要求收尾时出图审查。** 只在我明确要求时才跑 `:shots`；
平常改完界面直接编译、重启桌面开发版（或装到 Android 真机）交给我手测。基础设施成熟后，再改回「新界面与新状态必须进截图清单、
收尾时只看变了的图按 M3、Fluent、Apple HIG 与 GNOME HIG 的本地镜像审一轮」（上一轮审查的做法与结论见 `docs/development/ux-review/`）。
截图不替代手测：假数据放不了视频、没有窗口外框，时机与手感只能在真机上看。

## 冒烟测试

都在 `.github/workflows/test.yml`，业务逻辑放 JVM 上测，原生行为在真机器上冒烟：Linux 上的 `:shared:desktopTest` 与 `:ui:desktopTest`，
Windows 上的 `:desktopApp:desktopTest`，Android 单测，x86_64 模拟器（API 34）上的 `:app:connectedDebugAndroidTest`，
以及 Windows、macOS、Linux 上对安装包的冒烟（`windows-package`、`macos-package`、`linux-package`，推送时不跑）：安装、应用内更新，
再验默认打开方式、在资源管理器中显示这类依赖系统真实行为的，包里的入口是 `SelfTest.kt`。冒烟走真实 libmpv、
真实代理，PikPak 服务端用 MockEngine 顶替，SDK 的请求、鉴权与解析仍走真实代码。本地不必跑，以 CI 结果为准；
安装与更新冒烟的脚本本机也能跑，见 `shared/.../shared/update/CLAUDE.md`。
`connectedDebugAndroidTest` 跑完会卸载 `dev.piko.debug`，连同登录与下载任务表；在日常联调的真机上跑之前先问，或改用模拟器。

JVM 测试看不出 Android 与 HotSpot 的差异：Android 的正则是 ICU，不认 `\p{IsHan}` 这类 Java 专有写法，
Android 8 上一编译就崩（1.0.0 出过）。`AndroidRegexGuardTest` 扫源码拦着，写脚本类用 `\p{script=Han}`。
ICU 的 `\d` 还是全部 Unicode 数字，文件名里的 `𝟐` 被抓出来后 `toInt()` 就抛（1.1.0 信息流闪退），数字一律写 `[0-9]`，同一个测试拦着。
模拟器上的 `NamingUnicodeDigitsSmokeTest` 把文件名解析的各个入口（网盘页、信息流、查重、播放列表）在 ICU 上跑一遍，
文件名里的数字逐位换成数学粗体、全角等别的数字，不许抛异常；解析入口有新增时往里补一行。
老格式样片在 `testdata/media/`，直接提交，生成方式见 `generate.sh`；没有 WMV3/VC-1 样片，因为 ffmpeg 没有它的编码器。
