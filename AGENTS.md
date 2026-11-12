# AGENTS.md

Piko 是 PikPak 的第三方跨平台客户端。Android、Windows、macOS 与 Linux（后两者为实验性支持）共用一套 Material 3 Expressive 界面、业务能力与工作状态，按窗口可用净空间调整布局，按触摸与键鼠输入来源适配交互。业务逻辑与呈现状态位于 `shared`，共享界面位于 `ui`，`app` 与 `desktopApp` 仅保留平台入口与原生能力实现。

本文件记录跨模块的架构约束与工程约定。各模块与子领域的细则位于对应目录的 `AGENTS.md`：

| 文件 | 内容 |
| --- | --- |
| `ui/AGENTS.md` | 自适应工作区、状态归属、响应式布局、导航与侧边栏、外框与标题栏、对话框与面板、设置页规范、键鼠与触屏交互、动效与减少动画 |
| `ui/src/commonMain/kotlin/dev/piko/ui/screens/drive/AGENTS.md` | 网盘页：瀑布流、资料库、压缩包浏览、查重与清理、命令栏、地址栏、目录图、条目操作、下载画质、属性浮卡、双名称体系、键鼠与拖放 |
| `ui/src/commonMain/kotlin/dev/piko/ui/screens/clips/AGENTS.md` | 信息流：作用域、选段分散算法、挂起与恢复、TS 有界切片与 PTS 校准、取流预取调度、三播放器图层与交互 |
| `desktopApp/AGENTS.md` | 桌面端：依赖版本锁定、Release 打包与 AOT 缓存、原生库与 FFmpeg FFM 转封装、GPU 设备失效恢复、弹层销毁规避、数据目录、单实例与原生托盘、自绘标题栏、Windows 触摸桥、模态文件框隔离、macOS 与 Linux 打包及更新 |
| `shared/src/commonMain/kotlin/dev/piko/shared/update/AGENTS.md` | 应用内更新：多语言更新说明、版本检查与镜像降级、Windows 事务补丁与 `image.zip` 按需拉取、老客户端兼容契约、跨平台更新冒烟 |
| `shared/src/commonMain/kotlin/dev/piko/shared/download/AGENTS.md` | 下载：共享稀疏暂存池、转码档探测与画质匹配、FFmpeg / MediaMuxer 转封装与片段截取 |
| `shared/src/desktopMain/kotlin/dev/piko/shared/auth/AGENTS.md` | 桌面端机密存储：DPAPI、macOS 登录钥匙串、Linux Secret Service 与分层回退提升策略 |
| `cli/AGENTS.md` | 开发用命令行工具 `piko-cli` |
| `shots/AGENTS.md` | 无头截图与自适应交互回归工具 `:shots` |

## 常用命令

```bash
./gradlew :app:compileDebugKotlin                 # Android 编译（最快的语法与类型检查）
./gradlew :desktopApp:compileKotlinDesktop        # Desktop 编译（注意不是 compileKotlinJvm）
./gradlew :app:installDebug                       # 安装至已连接 Android 设备（包名 dev.piko.debug）
./gradlew :desktopApp:run                         # 启动桌面开发版
./gradlew :shared:desktopTest                     # shared 模块单元测试与冒烟测试
./gradlew :shared:desktopTest --tests '*ReleaseNotesTest*'  # 运行单个测试类
./gradlew :ui:desktopTest                         # ui 模块纯函数规则测试（命令栏显隐、设置分区高亮等）
./gradlew :desktopApp:desktopTest                 # 桌面端原生测试（含 WinRT、窗口过程与虚拟触摸，仅限 Windows）
./gradlew :app:testDebugUnitTest                  # Android 单元测试
./gradlew :desktopApp:createReleaseDistributable  # 构建桌面 Release 镜像（ProGuard + AOT 训练），暴露 keep 规则遗漏
gh workflow run test.yml -f desktop=true          # 手动触发全量 CI 测试（含 Windows、macOS、Linux 安装与更新冒烟）
gh workflow run release.yml -f version=9.9.9      # 发版演练：完整执行测试、构建与产物汇总，不创建 Release
```

`./gradlew :desktopApp:run` 直接从 `build/classes` 加载类文件；桌面开发版运行期间若重新编译其已加载的模块，运行中的进程会在下一次类加载时抛出 `NoClassDefFoundError`。重新编译前须先终止桌面开发版进程，编译完成后可在后台重新启动 `:desktopApp:run`；关闭用户系统中已安装的正式版 Piko 前仍须先征得用户确认。

修改 `shared` 或 `ui` 后必须同时执行 `:app:compileDebugKotlin` 与 `:desktopApp:compileKotlinDesktop` 验证双端编译：`ui` 在桌面端与 Android 端链接的 Material 3 版本不同（详见 `desktopApp/AGENTS.md`），同一处 API 调用可能仅在其中一端编译失败。

应用版本号读取环境变量 `PIKO_VERSION_NAME` 与 `PIKO_VERSION_CODE`，本地未设置时自动回退至默认值，无需额外配置。

## 与 pikpak-kotlin SDK 的协同

所有网盘协议与流式传输能力均来自 `io.github.nihildigit:pikpak-kotlin`（版本号记录于 `gradle/libs.versions.toml`），由同一作者维护，本机源码通常位于 `../pikpak-kotlin`。

- **本地复合构建联调**：在本仓库 `local.properties` 中配置 `pikpak.sdk.dir=../pikpak-kotlin`，`settings.gradle.kts` 即通过 Gradle Composite Build 将依赖替换为该目录源码，修改 SDK 后下次编译直接生效，无需发布 SNAPSHOT 或修改版本号。SDK 目录下的 `local.properties` 须包含 `sdk.dir`（供复合构建中的 Android 插件定位 SDK）。两仓库的 Kotlin、AGP 与 Gradle 版本必须保持一致，否则 AGP 无法匹配两侧 Android 变体而直接解析失败。
- **禁止使用 `mavenLocal`**：`publishToMavenLocal` 在未修改版本号时会覆盖本机缓存中的已发布正式版构件。
- **发布节奏与命名空间配额**：Maven Central 对命名空间按月限制发版次数（`io.github.nihildigit` 配额为每月 11 次发版、5,460 个文件；2026 年 9 月曾因发布 18 版超限）。日常 SDK 改动累积在 SDK 仓库的 `main` 分支，`test.yml` 默认检出 SDK 的 `main` 分支执行复合构建，使 Piko 主干可直接使用尚未发版的 SDK 能力；`release.yml` 则严格拉取 `libs.versions.toml` 中锁定的已发布正式版。发布 Piko 前须先发布 SDK，待 `repo1.maven.org` 可解析新版本后再更新 Piko 依赖版本并打 tag。

## 发版与多语言更新日志

两仓库均为自有仓库，直接在 `main` 分支提交，并由 `vMAJOR.MINOR.PATCH` 格式的 tag 触发 GitHub Actions 发布流水线。发版顺序不可颠倒：先向 SDK 推送 tag 发布至 Maven Central（`automaticRelease = true`，约数分钟同步至 `repo1.maven.org`），确认解析无误后再更新 Piko 的 SDK 版本号、提交并推送 tag。

### 多语言更新日志门控与幂等重跑

正式打 tag 发版前，须在 `.github/changelogs/<version>.md` 中编写五种语言的更新日志，依次包含 `# zh-Hans`、`# zh-Hant`、`# en`、`# ja`、`# ko` 五个一级标题段落：

- 每种语言段落遵循统一格式：首行一句话概述，随后按需列出 `## 修复` 与 `## 变化`（对应各语言标题），每条使用规范书面语描述用户可感知的行为变化，不出现文件名、类名、函数名、提交标题或内部重构细节。`## 修复` 仅记录已发布正式版中存在的问题；本次开发周期内引入并在发版前修复的问题不列入。编写前须对照上一版更新日志保持文风一致。
- **前置硬性门控**：`release.yml` 最开头的第一个前置 job（在全部测试与多平台编译启动之前）会对 `.github/changelogs/<version>.md` 执行硬性校验。正式 tag 发版时，若该文件缺失或任一语言段落为空，流水线会在开场数秒内直接报错终止，既防止漏写更新说明，又避免浪费后续近一小时的全平台测试与构建时间。手动 `workflow_dispatch` 触发的发版演练（`dry-run`）不强制要求该文件存在。
- **`origin/main` 优先读取与单任务重跑**：前置门控 job 与汇总发布的 `release` job 均优先从 `origin/main` 读取 `.github/changelogs/<version>.md`（不存在时回退至当前 tag 所在提交树）。草稿附件上传（`overwrite_files: true`）与 SLSA 证明附件挂载均已实现幂等覆盖：若推送 tag 时因漏写更新日志被前置门控拦截，或草稿生成后需修订文案，只需将修正后的 `.github/changelogs/<version>.md` 推送至 `main`，随后在 GitHub Actions 页面对失败的前置 job 或 `release` job 直接执行 Rerun，即可在不删除重建 tag、不重跑全平台打包的前提下完成草稿更新。

### 测试、构建来源证明与草稿公开

1. `release.yml` 通过前置校验后，同时并行启动 `test.yml` 全量测试（在真实系统环境运行全套单元测试与安装包冒烟测试，含 Windows、macOS、Linux 全新安装与应用内更新及 iOS 模拟器原生测试，固定使用已发布的 SDK）与各平台正式包构建。修改发版脚本或工作流后，须先通过 `gh workflow run release.yml -f version=9.9.9` 执行一次演练（完整运行测试、打包与汇总并保留 workflow 产物，跳过创建 Release 与构建来源证明）。
2. Android、Windows（x64、arm64）、macOS（arm64）、Linux（x64）与 iOS（arm64，调用可复用工作流 `ios-ipa.yml`）并行构建正式包并执行 JVM 字节码校验（`VerifyClasses.java`），`windows` 完成后紧接在 `windows-deltas` job 生成 Windows zstd 差分包；待 `test` 与全部平台构建均通过后汇总至 `release` job 计算 `SHA256SUMS.txt`、生成 `release.json`，并出具两份构建来源证明：GitHub 原生 `attest-build-provenance` 与隔离上下文 `slsa-github-generator` 生成的 SLSA Build L3 证明（`multiple.intoto.jsonl`）。
3. `release` job 将 `# zh-Hans` 段落拼接在 `.github/release-notes.md`（自 `## 下载` 起的设备附件表与校验说明）之前作为 GitHub Release 页面正文，同时将 `zh-Hans` 写入 `release.json` 的 `body` 字段，并将全部五种语言写入 `release.json` 的 `localized_notes` 字段，最终创建**草稿（Draft）** Release。草稿对 `releases/latest` 不可见，应用内更新在草稿公开前不会触发提示。应用内更新弹窗读取 GitHub Release 正文时在 `## 下载` 处截断（`GithubReleases.kt` 的 `updateNotesOf`，由 `ReleaseNotesTest` 守护），修改该标题须同步更新代码与模板；客户端同时按 `PikoLocale.effectiveLanguage` 优先展示匹配语言的更新说明（详见 `shared/src/commonMain/kotlin/dev/piko/shared/update/AGENTS.md`）。
4. 核对草稿附件与正文无误后，执行 `gh release edit <tag> --draft=false` 正式公开。按 tag 查询的 GitHub REST 接口不返回草稿；若 `gh` 按 tag 无法定位草稿，可通过 `gh api repos/NihilDigit/piko/releases` 列表接口按 `tag_name` 取得 Release ID。若需手动执行 `gh release edit --notes-file` 修改正文，必须先读回原正文再写入，因为 `--notes-file` 会整段替换正文，直接覆盖会导致附件表丢失。

## 模块与架构

仓库包含四个主要模块：

- `shared`：跨平台业务逻辑与呈现状态（`commonMain`、`jvmSharedMain`、`androidMain`、`desktopMain`）。
- `ui`：跨平台 Compose 界面（`commonMain`、`androidMain`、`desktopMain`）。
- `app`：Android 应用入口、平台能力实现与 `libmpv` 播放适配。
- `desktopApp`：Windows、macOS 与 Linux 入口、平台能力实现、原生系统集成与独立播放器窗口。

Windows 触摸与手写笔接入 Compose Desktop 的桥接库独立发布于 `dev.nihildigit:compose-windows-touch`（仓库 `NihilDigit/compose-windows-touch`，版本记录于 `gradle/libs.versions.toml`）；本地联调时在 `local.properties` 中写入 `windowstouch.dir=../compose-windows-touch` 即可启用复合构建。

### 自适应前端与状态归属

所有平台共用同一套自适应工作区模型（详见 `docs/development/adaptive-frontend-framework.md`、`docs/development/interaction-refactor.md` 与 `ui/AGENTS.md`）：

- **工作区与上下文集合**：`PikoDriveRepository` 不持有导航栈或剪贴板。所有平台共享 `shared/presentation/DriveWorkspace` 维护文件标签的位置、历史、固定状态（`DriveTab.pinId`）、最近访问文件夹（`recentFoldersFlow`）与文件剪贴板；`WorkspaceContexts` 在同一有序集合中按稳定 ID 管理文件标签、查重报告与独立导入草稿。已固定的文件标签始终排在上下文集合前部且禁止直接关闭（需先取消固定），侧边栏与外部定位跳转优先复用未固定标签或自动新建标签，不覆盖固定标签；固定标签目录按账号经 `PinnedTabs`（带 90 天取消固定墓碑的 LWW 账本）与 `PinnedTabSync` 跨设备同步至 `.piko/pinned-tabs-<时间戳>.json`。`WorkspaceViewModel` 由账号级 `AccountPresentationStores` 持有，不随主界面重组或宽窄布局切换而销毁。宽窗口（至少 `600×480dp`）在顶部常驻显示标签栏（`DriveTabBar`），窄窗口或低高度窗口通过顶栏数字入口打开双列页面缩略图总览切换同一组工作上下文。
- **作用域分离与保存目标**：查询展示订阅使用浏览会话子作用域，已接受的文件写操作使用账号级 `AccountExecutionScopes`。外部上传的默认目录经账号工作区的 `SaveTargetBinding` 注入 `SaveTargetContext`；新建导入草稿在打开那一刻从 `SaveTargetContext` 取得当前主文件目录并固定为该草稿的默认保存目标，此后浏览其他目录不会篡改该草稿的目标目录。资料库页（`Screen.Library`）、临时定位浏览页与目录选择器浮层（`FolderPickerState`）均维护各自独立的路径栈，严禁从仓库读取所谓「当前目录」或直接改动主工作区路径栈。
- **界面入口与状态约定**：`ui/src/commonMain` 入口为 `PikoApp`，由 Android `MainActivity` 与桌面 `Main.kt` 分别组装进程级 `PikoServices` 与平台接口 `PikoPlatform`，界面内统一经 `LocalPikoServices` 与 `LocalPikoPlatform` 获取，禁止引用 `PikoApplication.instance`。`DriveScreenState` 作为网盘列表核心接缝，负责文件列表投影、排序、搜索、多选、启发式折叠、防窥揭示与动作分发；普通文件编辑经 `FileCommands` 提交至 `FileMutations`，仅复制由 `FileWriteSession` 跟踪远端任务；历史与归档条目编辑分别使用 `HistoryMutations`、`VaultEntryMutations`。普通编辑不进入任务列表。遵循以下准则：
  - Compose 页面投影暴露只读 `State`，业务层与跨层订阅暴露只读 `StateFlow`，严禁将可变状态直接交给视图修改。
  - 高频读取的派生状态必须使用 `derivedStateOf`，避免写成每帧重复计算的普通属性 getter。
  - 即时反馈可通过 `messages: SharedFlow<String>` 驱动 Snackbar，但重要执行结局、待输入状态与恢复入口必须保留为可查询状态，不能仅依赖瞬态流订阅。
  - `ReceivingActivity` 仅投影在途接收工作的路径依赖供动作入口校验；整包离线裁剪的产出目录树在完成前需锁定路径，普通接收目录不锁定无关同级文件。网络异常结果未知时须先核对远端实际状态，不得将传输超时直接视为服务端未执行。
  - 常驻错误状态（如 `loadError`）仅用于表达当前屏幕上展示的数据可能已过期。
  - 其余主要状态持有者包括 `InstantSheetState`（磁力与链接解析，多链接由 `InstantBatchState` 管理）、`OfflineTasksState`（云端离线任务轮询）、`LoginState`、`DuplicateFinderState`、`ArchiveExtractSession`（进程级服务端解压）与 `ArchiveBrowser`（进程级压缩包内浏览）。

### 平台抽象、多语言与偏好同步

跨平台差异统一通过 `commonMain` 接口抽象，不使用 `expect`/`actual`；Android 与 Desktop 逻辑一致且仅依赖 JDK 标准库的实现放入中间源集 `shared/src/jvmSharedMain`（例如进程级代理选择器 `PikoProxySelector`、`FileClipCache` 等）。`PikoProxySelector` 必须在任何 `OkHttpClient`（含 SDK、图片加载器与更新检查客户端）实例化之前安装为 JVM 默认 `ProxySelector`（见 `PikoApplication.onCreate` 与桌面 `Main.kt`）。

平台能力集中在 `PikoUserPreferences`、`PikoSessionStore`、`PikoDownloadStorage`、`PikoSegmentDownloader` 与 `ui/.../platform/PikoPlatform.kt`（实现类为 `AndroidPikoPlatform` 与 `DesktopPikoPlatform`）。特定平台不支持的能力返回 `null` 或 `false`，界面据此隐藏入口（如桌面端无系统分享面板）。本机文件上传由 `PikoUploadCoordinator` 统一串行调度并持久化上传会话以支持跨进程续传，平台层仅提供文件源读取（`PikoUploadSources`）与文件选择器（`PikoPlatform.uploadPicker`）。应用公告不内置客户端拉取，统一发布于 Telegram 频道（`t.me/piko_dev`），在「关于」中提供入口。

**多语言本地化（`PikoLocale`）**：客户端支持跟随系统及五种显式语言（`System -> zh-Hans -> zh-Hant -> ko-KR -> ja-JP -> en`），共享层与界面层统一读取 `PikoLocale.effectiveLanguage` 切换文案、字幕回退字体及多语言更新说明。

**新增偏好设置项须同步修改三处**：`PikoUserPreferences` 接口、`SessionManager`（Android DataStore 实现）与 `DesktopPikoPreferences`（桌面 `DesktopSettingsStore` 实现）。若该设置需跨设备同步，再将其加入 `shared/.../shared/sync/PikoSettingsSync.kt` 的 `SyncedSettings`；窗口尺寸、下载目录、网络代理、减少动画、蜗牛模式等设备本地属性不得加入同步。设置同步文件位于网盘根目录 `.piko/settings-<时间戳>.json`（`DriveSettingsStore`，`.piko` 目录在网盘浏览列表中隐藏），按字段记录修改时间戳执行逐项合并，当前设备从未修改过的项视为最旧。机密数据严禁放入普通设置同步（1.1.0 曾明文同步压缩包密码，已停用并清理）；压缩包密码使用账号密码派生的密钥加密后独立存放于 `.piko/archive-passwords-<时间戳>.json`（`ArchivePasswordSync`），新增其他跨设备机密同步须沿用此加密方案。

MetaTube 地址与访问令牌经 `MetaTubeSync` 使用账号密码派生的密钥加密后同步至 `.piko/metatube-<时间戳>.json`，逐字段按修改时刻合并，本机同步账本只存 SHA-256 摘要。「以引用保存」勾选由 `saveAsReferenceFlow` 仅在当前设备记忆，不加入 `SyncedSettings`。

### 索引、目录树与变更收口

- **文件夹索引（`FolderIndex`）**：文件夹名称与父级关系在仓库的 `FolderIndex` 中维护唯一权威副本，列目录、查详情及客户端自身写操作均实时写入。路径栈、文件标签、浏览历史、最近访问与快速访问均自动跟随索引修正名称与父级（目录重命名时即时更新显示名，移动时更新上级，删除时自动回退至有效祖先）；持久化的 `PikoPathBreadcrumb.name` 仅在索引尚未收录该节点时作为回退值。按文件夹 ID 反查完整路径统一调用 `locateFolder`（优先查本地索引，缺口再向服务端逐级补齐）；资料库虚拟根与压缩包内部层级不进入索引（`isDriveFolderId`），压缩包本身按其文件 ID 记录。
- **本地全盘树索引（`DriveTreeIndex` 与 SQLite WAL）**：按账号独立维护带 SHA-256 后缀与 `owner_account` 双重校验的原生 SQLite WAL 库（Android 使用系统 `SQLiteDatabase`，Desktop 使用 `sqlite-jdbc`，原生解压定向至 `PikoHome/cache/natives`；无歧义旧版 `drive-tree-*.json` 首次启动流式迁入并清理）。Java 堆仅常驻文件夹骨架与物化子树聚合表（`SubtreeStats`，分别维护物理配额与归档虚拟体量/时长，驱动文件夹按递归大小与时长精确排序及 `Screen.SpaceMap` 空间分布图），不再将全盘数万至数十万文件对象常驻堆内；物理远端事实、文件实例原始存入时间（含归档取回后跨目录移动保留）与按 `GCID + size` 共享的内容媒体元数据三层分离，全盘拉取按分页候选代流式写库，全盘搜索经预归一化 `norm_name` 轻量游标完成 CJK 模糊匹配。重型流式落盘与内存水位统一受 `PikoMemoryGovernor` 协调。
- **变更统一收口**：一切修改网盘目录结构或文件状态的入口必须调用 `driveRepository.applyChange(DriveChange)`，统一完成 `FolderIndex` 与 `DriveTreeIndex` 原地更新、列表缓存失效及 `folderChanges` 广播；禁止各业务处自行清理缓存或单独通知视图。`DriveScreenState` 与目录图已订阅 `folderChanges` 并仅重列受影响层级，视图层无需重复订阅。
- **普通写操作的乐观投影**：`OptimisticWrites` 按账号保存待提交操作，界面、路径、搜索与资料库在已确认数据上叠加投影；`DriveScreenState` 保留基础列表供失败回滚。整批选择先投影，同一文件的请求按接受顺序执行，成功经 `applyChange` 写回基础数据，失败仅移除自身投影。撤销基线在前序请求结束后重取，撤销记录先于后序请求放行。成功响应直接确认结果；响应丢失时用绑定账号的原始接口核对，不能用乐观投影证明服务端已执行。复制仍跟随服务端任务。`VaultStore` 按目录排队保存纯函数编辑，完成或失败只移除自己的编辑，再将其余编辑应用于已确认清单。
- **持久树增量同步**：完整扫描和后续对账使用 SDK `consumeFileChanges`，在全部分页成功后保存末页服务端水位（2026-11-10 跨页实测：水位随 `db_modify_time` 扫描推进，分页期间已读条目的更新会在后续页重现；详见 `docs/development/backend-demand-consistency.md`）。增量页先暂存，全部读取成功后在同一 SQLite 事务中提交数据与游标；切号、在途乐观操作或扫描期间的本地变更使本轮暂存作废。移动先于删除落库，确保已移出的子树保留；清单胜出者变化时清除旧虚拟条目，尚未读取的清单在后续对账继续重试。没有游标或服务端报告游标过期时建立完整基线。
- **撤销状态与通知**：`DriveChangeJournal.state` 保存撤销记录和结果；提示事件使用有界丢弃队列，发送不挂起。界面用最新提示替换无限期 Snackbar，执行状态的收尾订阅持久状态，不依赖瞬态提示送达。

### 账号隔离与凭据管理

同一时刻仅有一个活动账号（`PikoClientManager.currentClient`），已登录未退出的账号保存在 `SavedAccounts`（仅含账号名、昵称、头像与上次空间用量，不含机密）供快速切换。所有按账号隔离的状态必须遵循账号切换生命周期，不得假定账号恒定：

- **仓库与工作区切换**：`PikoDriveRepository.enterAccount` 切换文件索引、列表缓存、本地树快照与撤销日志；`AccountPresentationStores` 与 `AccountExecutionScopes` 按账号隔离工作区 ViewModel、导入草稿、查重会话与执行作用域，旧工作区释放时不得覆盖新账号的 `SaveTargetBinding`。根目录清单缓存与在途写事务固定绑定发起时的 `PikPakClient` 实例。
- **进程级会话与窗口清理**：服务端解压、片段截取、归档等进程级会话以及桌面端独立播放器窗口、独立信息流窗口统一通过 `launchOnAccountLeave` 在主线程终止（`PikoServices` 与桌面 `Main.kt`）。后台下载任务携带 `account` 字段，切离账号时自动暂停该账号任务；任务页与任务入口徽标/速率仅统计当前账号的下载、上传与离线任务（`TransfersState`、`TransferActivity`），切回原账号后原任务仍可见并可继续。
- **主界面重建与缓存键隔离**：切换账号时 `PikoApp` 根状态整体重建，挂起的信息流、临时定位标签、浮窗位置等界面态随之重置；是否按设备开关自动重开信息流由 `ClipFeedSession.reopensFor` 判定，切换至另一账号时不自动打开。缓存目录中所有按账号落盘的文件键名必须包含账号标识：文件标签、固定标签账本（`pinned-tabs-`）、列表缓存、最近访问、来源账本（`sources-`）、归档目录表（`vault-trees-`）、归档星标表（`vault-stars-`）、本地文件树快照及信息流队列（`clip-feed-<账号>-<文件夹>`，因各账号根目录 ID 均为空串，仅凭文件夹 ID 会导致跨账号串数据）。待延迟写盘的数据在切号时立即向原账号落盘，不丢弃。记忆网盘文件夹 ID 的进程级对象（如 `PreviewTempFolder` 的 `Piko-Temp`）与偏好（`AccountScopedPreferences` 中的快速访问、最近移动目标，旧平台偏好值原样保留并记录接管账号以防同机旧版将空值同步回云端）均按账号隔离。
- **第一方 Google / 浏览器设备授权与 `sub` 槽位归并**：除邮箱/手机号密码登录外，客户端采用第一方 Android `YNxT9w7GMdWvEOKa` 的 RFC 8628 设备授权流（`POST /v1/auth/device/code` -> 系统默认浏览器打开 `https://mypikpak.com/auth?type=google&init=1&origin=...` -> `/drive/qr-login` 确认 -> `POST /v1/auth/token`）完成 Google 登录，保持 100% 完整传输额度与 `/drive/v1` 全量接口权限（严禁改走仅享 25% 传输额度的 Connected Apps / Public OAuth `/api/v1` 路线，严禁在内嵌 WebView / CEF 中打开 Google 授权页）。`PikoClientManager` 在换取令牌后先经 `/v1/user/me` 核对 `sub`，同一 PikPak 账号无论先后使用 Google 还是密码登录均按 `sub` 归并至同一本地存储槽位（未记录 `sub` 的旧版槽位绝不按显示邮箱盲目覆盖）；纯 Google 登录创建的内部槽位键 `pikpak:<sub>` 仅作存储与缓存键，严禁泄露到界面文案或用户名预填框（统一经 `SavedAccount.displayName`、`publicAccountHandle` 与 `resolvePrefillContext` 投影）。续期指定失效账号时强制校验 `sub` 一致，防止误选其他 Google 账号串槽；运行期无密码会话在 `refresh_token` 失效（如 `4126`）触发 `onInteractiveLoginRequired` 时自动标记失效并退回登录页。
- **`.piko/` 同步校验、独立同步口令与密文保护**：`DriveSettingsStore` 执行设置、固定标签账本、归档目录表、归档星标表、来源账本、压缩包密码与 MetaTube 同步时，每一步读写及旧文件清理前均核对当前活动账号仍为发起同步的账号，若已切号则立即中止本轮同步。压缩包密码（`ArchivePasswordSync`）与 MetaTube 配置（`MetaTubeSync`）优先使用按账号保存在系统机密库中的独立加密同步口令（`syncSecret`，写入 `Envelope version = 2` 以阻止旧版客户端误用账号密码覆盖，且一旦升为 `v2` 绝不回退为 `v1`），未配置时回退到已保存的登录密码（`version = 1`）；在已解锁状态下设置或更换口令会自动用旧密钥解密合并并无损重加密为 `v2`。无可用密钥记为 `LockedNoKey`，远端密文与当前密钥不匹配时记为 `LockedKeyMismatch` 并**严禁自动覆盖远端密文**，仅允许输入匹配口令解锁或由用户显式触发先写 `backup-<前缀><时间戳>.json` 备份再以单调递增时间戳重置云端。账号会话、密码、同步口令与压缩包密码合并后交由平台机密存储保管（桌面端细节见 `shared/src/desktopMain/kotlin/dev/piko/shared/auth/AGENTS.md`）；退出登录仅注销当前账号并切换至最近使用的另一账号，系统钥匙串暂时锁定时跳过加密同步轮次而不视为无密钥或已退出登录。

### 归档（Vault）、引用写入内核与来源账本

代码命名统一使用 `vault` 以区分压缩包（`ArchiveRepository` / 服务端解压），中文界面文案称为「归档」。并发吞吐与延迟确认的实测数据见 `docs/development/archive.md`。

- **单清单存储与可信合并**：归档条目是在网盘中不占配额空间、仅保留内容引用的虚拟文件。每个目录仅维护唯一一份清单文件 `.piko-vault-v<版本>-<随机串>.json`（`VaultStore`），记录文件名、大小、GCID、可选 CID、媒体宽高时长与完整来源链接；不再在同目录写入 `归档来源-<标识>.magnet` 或 `.txt` 旁路文件（历史遗留的同名旁路文件仍由 `VaultStore.isRecoveryFile` 在浏览列表中隐藏且不参与归档，云端原文件保持不动）。`listBrowsable` 将未取回的归档条目合并入目录列表，虚拟文件 ID 以 `piko-vault:` 开头。多端并发写同一目录清单采用无锁可信合并：版本号大者胜出，同版本号随机串字典序小者胜出，败方将自身纯函数变更重新应用至胜出清单后重写，仅存最终状态不存历史日志。取回网盘后条目通过 `restoredFileId` 保留来源与 CID 供再次归档复用，不再重复渲染虚拟文件。
- **统一引用写入内核（`VaultIngest`）**：网盘文件/文件夹归档（`FolderVaultSession`，作用域为 `VaultScope`）与磁力解析「以引用保存」（`InstantMagnetRepository.saveReferences`）共用 `VaultIngest` 内核，统一执行元数据补齐、新建空目录单次直写快速通道（`freshFolderIds`）、目录级并发清单写入与来源账本提取；二者唯一区别在于归档会在目录清单确认落盘后额外执行原文件移入回收站或永久删除，并登记 `DriveChangeJournal` 撤销记录。首次使用归档或以引用保存前会弹出一次性风险确认框（`VaultRiskWarningDialog`）。外层归档目录标记记录于 `VaultTrees`，跨设备同步至 `.piko/vault-trees-<时间戳>.json`（`VaultTreeSync`，合并取并集）。
- **归档星标账本（`VaultStars`）**：PikPak 服务端不支持对虚拟归档条目打星标，且真实文件归档删除后原服务端星标会丢失。`VaultStars` 按账号自管归档条目星标（带取消星标的 LWW 墓碑），本机缓存于 `vault-stars-<账号>.json`，跨设备经 `VaultStarSync` 同步至 `.piko/vault-stars-<时间戳>.json`；带星标的真实文件归档时自动迁入星标账本，取回为真实文件并在服务端打星确认成功后自动迁出，「星标」资料库直接合并展示。
- **按需租约借出（`LeaseBudget`）**：播放、下载或信息流打开归档条目（及压缩包内文件）时，通过 SDK 的 `leaseDetail` 与 `fileHandle(leased = true)` 按 GCID 秒传临时对象至 `Piko-Temp`，取得直链后立即删除云端临时对象；所有账号均由 `LeaseBudget` 按当前剩余配额空间限制同时借出的总字节数。
- **来源账本（`SourceLedger`）**：服务端仅对离线下载与分享转存自动填充 `params.url`，客户端秒传创建的文件 `params.url` 为空且无法通过 API 写入。`SourceLedger` 以 `大写 GCID + 文件字节数` 为键（不按文件 ID，确保复制文件后仍能命中），记录完整原始磁力链接、种子内相对路径与时间戳；在磁力秒传保存（`InstantSheetState.rawInstantSave`）、以引用保存及归档取回/撤销秒传（`PikoDriveRepository.instantCreate`）时写入，在仓库列目录（`fetchListing`）、全盘搜索、星标与事件资料库出口统一将缺失的 `sourceUrl` 注入 `params["url"]`。本机按账号存缓存目录，跨设备同步至 `.piko/sources-<时间戳>.json`（`SourceLedgerSync`，来源单独建表、条目按下标引用；合并取并集，同键取时间戳较晚者，时间戳相同时按来源与路径字典序取大者；明文存储，不记录分享提取码）。
- **免费账号与会员策略**：体验优先级排序为 `Piko + 会员 > 官方 + 会员 > Piko + 免费 > 官方 + 免费`。免费账号使用归档、播放与信息流不设人为次数上限、不限画质、不在客户端限速（服务端自身配额与限额照常生效），仅在预取激进度上区分会员（免费账号不执行悬停预借与额外并行预取）。账号等级由 `TransferAllowances.isPremium` 自动识别，不设手动开关。

### 番号识别与日志脱敏

- **番号识别**：识别与规范命名规则位于 `shared/.../shared/naming/av/`，设计与 MetaTube 对接规范见 `docs/development/av-naming.md`。代码库严禁内置任何成人站点域名或默认 MetaTube 实例地址，服务地址与令牌完全由用户在设置中自行配置。
- **全局日志与隐私红线**：`shared/.../shared/log/PikoLog` 将 `DEBUG` 及以上日志异步写入滚动文件（4 份各 `1 MiB`，保留 7 天；`WARN` 与 `ERROR` 另存 `piko-errors.log`，保留 30 天；连续重复行折叠计数），禁止在逐帧渲染或逐块 IO 热路径打印日志。导出日志供用户反馈排查，**严禁在日志中输出真实文件名、账号名、邮箱、访问令牌或媒体直链**：记录文件统一调用 `logFile(id, name)`（仅保留文件 ID 与扩展名）；吞掉异常仅向用户展示简短提示处须先调用 `.logFailure(tag, message)`。落盘前另有 `PikoLog.redact` 兜底脱敏（邮箱、手机号、本机路径、远程 URL），但正则无法识别不含路径的裸文件名，调用方必须自觉使用 `logFile`。未捕获崩溃由双端入口安装的默认异常处理器写入并等待落盘。

## PikPak API 实测约束

以下结论均经真实账号探测实测确认，编写业务代码时直接作为前置约束，无需重复推导。若需开展新的 API 探测，可在 `../pikpak-kotlin` 中编写 opt-in 的 `jvmTest`（环境变量 `PIKPAK_PROBE=1`，凭据置于该仓库 `.env`）：测试创建的总数据量不超过 `100 GB`，仅操作实验自身新建的任务与文件并在结束时移入回收站；若涉及修改网盘原有内容、总量超过 `100 GB` 或消耗免费账号每日配额次数，须先征得用户确认。

- **无服务端按文件名搜索**：`/drive/v1/files` 的 `filters` 仅识别 `phase`、`trashed`、`kind`、`starred`、`modified_time`；传入 `name` 直接返回 `404`，传入 `q` 或 `search_text` 会被静默忽略。官方 Web 端同样采用客户端本地过滤。全盘按名搜索只能通过客户端拉取目录与文件列表在本地匹配（SDK 的 `searchFilesRecursive` 及仓库 `DriveTreeIndex`）。
- **离线下载任务仅接受完整磁力链接**：`createUrlFile` 不支持按文件序号选择子集，`ResolvedFile` 亦不携带种子文件索引。因此对同一磁力链接「秒传一部分、离线下载另一部分」必然在云端产生完整包与已秒传文件的重复，客户端需借助整包离线裁剪（`OfflinePrune`）处理。
- **GCID 为纯内容哈希**：与文件名及存放路径无关；网盘内已有文件的 GCID 直接存放在 `FileStat.hash` 字段中。
- **回收站内条目查询详情必败**（2026-09-23 实测，文件与文件夹一致）：对回收站内条目调用 `getFileDetail` 会返回 `error_code=9`、`error="file_in_recycle_bin"`（`9` 同时也是验证码错误码，SDK 按 `error` 名称区分）。校验目标目录是否可用时，查询详情失败与 `trashed == true` 均须视为目录不可用。
- **列举回收站必须携带 `parent_id=*`**：不带该参数时服务端仅返回从根目录直接删除的顶层条目（SDK 0.6.8 起 `listTrash` 已默认携带）。
- **服务端解压不支持分卷压缩包**（2026-09-25 实测 7z、zip、RAR5 分卷）：服务端仅读取提交的单个文件，不会在同目录寻找其余分卷；绝大多数分卷直接返回 `INVALID_FILE_FORMAT`，zip 分卷的末尾卷虽能列出目录，但提交解压任务后会以 `E_INVALID_FORMAT` 失败。
- **文件名长度上限为 UTF-8 编码 1024 字节**（2026-09-29 实测，文件与文件夹一致）：对应 1024 个 ASCII 字符、341 个常用汉字或 256 个 Unicode 补充平面字符，超出 1 字节即返回 HTTP 400、`error_code=3`（`file_name_too_long`）。批量重命名预览据此实时标红拦截。
- **客户端无法写入或修改文件的 `params`**（2026-10-08 实测，SDK `SourceParamsProbeTest`）：秒传建文件时请求体携带的 `params` 被服务端静默丢弃；事后 `PATCH /drive/v1/files/{id}` 只要 `params` 非空（无论是 `url`、自定义键还是原样回传）一律返回 HTTP 400 `invalid_argument`，传 `{}` 则返回 `file_nothing_updated`。因此秒传文件的来源只能由客户端 `SourceLedger` 按 GCID 记录；服务端复制产生的新文件会重建 `params`（增加 `original_file_id`），任何需要跨复制保留的元数据均须按 GCID 而非文件 ID 关联。
- **上传中文件状态与会话有效期**：本机上传开始时目标目录内即出现 `phase == PENDING` 的文件条目，此时将其提交给服务端解压会返回 `file not complete`。分片上传会话凭据有效期为 12 小时；文件上传完成瞬间即进入服务端 CID 索引，再次上传同内容文件立即命中秒传。

## 播放器与自适应流式传输

双端视频解码均基于 `libmpv`，实现 `commonMain` 的统一薄接口 `PlaybackBackend`，播放控制界面共用无状态的 `MobilePlayerControls`（按窗口宽高比自动切换横竖屏布局，桌面窗口自然获得横屏布局，复用触屏手势并叠加鼠标悬停显隐与键盘快捷键，垂直手势调节能力由平台 `PlayerLevelControl` 提供）：

- **Android 端**：采用预编译的 `dev.jdtech.mpv:libmpv`，适配层为 `MpvPlaybackBackend` 与 `MpvVideoSurface`，以应用内压栈页 `VideoPlayerHost.InApp` 承载，系统亮度、媒体音量、屏幕方向与系统栏控制位于 `PlayerSystemControls`。（不使用 MediaMP 的 Android mpv 后端：其 Compose 渲染表面为空实现且未发布 `.so` 原生库。）
- **桌面端**：采用 MediaMP 0.5.0 的 mpv 后端（`MediampPlaybackBackend`），利用其 D3D11 零拷贝接入 Skia 的硬件渲染表面，以独立窗口 `VideoPlayerWindow`（`VideoPlayerHost.Detached`）承载，支持边播放边浏览网盘。
- **取流策略与选集**：`PlayerScreenState` 按 `本地已完成副本 → 本机回环 HTTP 代理 → 新取直链 → 转码流` 顺序尝试，仅在首帧渲染前失败才降级至下一来源，播放中途断流按退避策略重连；续播进度每 5 秒落盘，播至片尾归零。选集列表由文件名解析器划分为作品与分区（`buildPlaylist`），上一集、下一集与自动连播不跨分区；解析总开关关闭时退回按文件名自然排序（`buildRawPlaylist`）。
- **本机回环代理与自适应传输（`pikpak-kotlin 2.4.1+`）**：所有网盘媒体流（含外挂字幕）均经 `shared/.../media/proxy/` 暴露为 `http://127.0.0.1` 的 HTTP Range 地址，直链过期重取、连接预算与分块预读均由 SDK 的 `PikPakFileHandle` / `PikPakStreamReader` 管理。同一 handle 派生的多个 reader 共享块缓存并各自维护独立游标，mpv 拖动定位时新旧连接重叠或交错较差的 MP4 前后跳读均互不取消。SDK 2.4.1+ 将 HTTP Range 网络流跨度与常驻内存解耦（受 `transportMemoryBudgetBytes` 约束，桌面端按最大堆的 1/8 限制在 `128~512 MiB`，Android 端响应 `onTrimMemory` 动态收缩），支持子块前缀即刻交付、多工作协程公平份额跨度、尾部块拆分以及播放缓冲头部救援（`reportPlaybackBuffer`）。关闭或切换播放源前必须调用 `PlaybackBackend.stopAndAwaitIdle()` 排空 mpv 异步输入线程，随后再关闭回环代理会话，防止底层在流关闭后继续读取引发卡顿或异常。

## 开源许可与贡献者协议

Piko 源码以 `AGPL-3.0-or-later` 许可证发布（1.1.0 及更早版本为 MIT）。外部 Pull Request 须签署 `CLA.md`（基于 FSFE Contributor Agreements 生成的个人非独占贡献者协议，外向许可承诺贡献始终以 `AGPL-3.0-or-later` 提供，并允许附加其他许可证以便单独授权），由 `.github/workflows/cla.yml` 自动收签并记录于 `cla-signatures` 分支。Android 分发包中集成的 `jdtech` FFmpeg（`--enable-gpl --enable-version3`）与 `mpv` 均为 GPL 构建，依据 AGPL 第 13 条可合规合并分发，发版时须附带第三方开源声明及对应源码获取方式。引自其他开源项目的代码（如源自 PowerRename 的 MIT 许可代码）保留其原始版权与许可声明。

## 截图回归与冒烟测试

### 截图基础设施（`:shots`）

`:shots` 的 `standardSet` 覆盖 `docs/development/ui-states.md` 中的可达界面状态，用法详见 `shots/AGENTS.md`。由于全套渲染耗时较长且尚不支持按改动增量重绘（后续规划见 `docs/development/shots-roadmap.md`），**日常界面修改不要求同步补充截图步骤或假服务端接口，亦不要求收尾时跑图审查**；仅在明确要求或运行特定自适应交互验证（如 `verify-adaptive-workspace`、`verify-file-building-blocks`）时执行 `:shots`。常规界面改动在双端编译通过后直接重启桌面开发版或安装至 Android 真机供手测验收；无头截图不包含原生窗口外框与真实视频解码，无法替代真机交互与时序手测。

### 持续集成与冒烟测试（`test.yml`）

`.github/workflows/test.yml` 按变更路径智能筛选目标（`workflow_dispatch` 与发版门控固定全量执行）并分层验证跨平台行为：

- **JVM 单元测试**：Linux runner 执行 `:shared:desktopTest` 与 `:ui:desktopTest`；Windows runner 执行 `:desktopApp:desktopTest` 与 Android `:app:testDebugUnitTest`；macOS runner 执行钥匙串测试 `macos-keychain`。
- **移动端真机/模拟器冒烟**：Android x86_64 模拟器（API 34）执行 `:app:connectedDebugAndroidTest`，走真实 `libmpv` 与回环代理，以 Ktor `MockEngine` 模拟 PikPak 服务端；iOS 模拟器调用 `ios-native.yml` 执行 `:shared:iosSimulatorArm64Test`、`:ui:iosSimulatorArm64Test` 与 iOS 15 部署目标宿主启动验证。注意：`connectedDebugAndroidTest` 执行完毕后会自动卸载 `dev.piko.debug`（连同本地登录态与下载任务表一并清除），在日常联调使用的物理真机上运行前必须先征得用户确认，或改用模拟器。
- **桌面安装包与更新冒烟**：`windows-package`、`windows-legacy-upgrade`、`windows-2022-portable`、`macos-package` 与 `linux-package`（日常 push 不触发，手动触发 `test.yml -f desktop=true` 或正式发版时运行）并行打包真实安装包并验证全新安装、老版本升级、默认协议关联、在系统文件管理器中定位及内置硬件播放自检（`SelfTest.kt`，详见 `shared/src/commonMain/kotlin/dev/piko/shared/update/AGENTS.md`）。
- **Android ICU 正则兼容性防线**：HotSpot JVM 正则引擎与 Android ICU 正则引擎存在致命差异：
  1. ICU 不支持 `\p{IsHan}` 等 Java 专有字符类写法，在 Android 8 上编译正则即直接崩溃（1.0.0 曾发生），汉字脚本类必须统一写为 `\p{script=Han}`。
  2. ICU 的 `\d` 会匹配全部 Unicode 十进制数字字符（如数学粗体数字 `𝟐`、全角数字），提取后直接调用 `toInt()` 会抛出 `NumberFormatException`（1.1.0 信息流曾因此闪退），匹配 ASCII 数字必须统一写为 `[0-9]`。
  `AndroidRegexGuardTest` 在静态源码扫描层拦截上述两类写法；模拟器冒烟测试 `NamingUnicodeDigitsSmokeTest` 将文件名解析的所有入口（网盘列表、信息流、查重、播放列表）替换为数学粗体与全角数字在真实 ICU 引擎上逐一验证，新增文件名解析入口时必须同步补入该测试。老格式媒体测试样片提交于 `testdata/media/`（生成脚本见 `generate.sh`；因 FFmpeg 无对应编码器，不含 WMV3/VC-1 样片）。
