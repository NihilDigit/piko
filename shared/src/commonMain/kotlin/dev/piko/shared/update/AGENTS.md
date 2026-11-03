# 应用内更新

Android 与桌面端均支持应用内检查与安装更新。跨平台版本检查、多语言更新说明解析与分段下载位于本目录（`GithubReleaseClient`、`GithubReleases.kt`），具体安装落地由各平台分别实现：Android 移交系统 `PackageInstaller`；macOS 经 `apply-update-mac.sh` 整包替换 `Piko.app`；Linux 仅针对 AppImage 执行纯 Kotlin `.zsync` 块级差分替换（详见 `../../../../../../../../desktopApp/AGENTS.md`）；Windows 由 `DesktopAppUpdater` 与 `apply-update.ps1` 执行带事务回滚的补丁替换、便携版 `image.zip` 按需 Range 提取或 MSI 整包重装。

## 版本检查、安全边界与多语言更新说明

### 检查来源与镜像校验边界

- **双来源顺序回退（`GithubReleaseClient.LATEST_RELEASE_SOURCES`）**：
  1. 首选 GitHub REST API（`api.github.com/repos/NihilDigit/piko/releases/latest`）：返回各附件的官方 `sha256` 摘要及默认正文，但匿名请求按出口 IP 限制每小时 60 次，共用代理出口的用户易遭遇 `403` 限流。
  2. 回退读取最新已公开 Release 挂载的清单附件 `releases/latest/download/release.json`（由 `release.yml` 自动生成，JSON 结构与 REST API 完全同形，同样内含各附件 SHA-256 摘要，走 `github.com` 下载链路不受 API 速率限制，且草稿 Release 对 `releases/latest` 不可见）。
- **严禁通过第三方镜像拉取版本元数据**：附件的权威 SHA-256 摘要记录在版本元数据中，若经镜像拉取元数据，恶意或被劫持的镜像可同时伪造新版本号与伪造安装包的摘要从而绕过完整性校验。镜像（`ghfast.top`，见 `ReleaseAsset.urls`）**仅**用于下载具体附件，且仅在直连 `github.com` 尚未收到任何字节即失败时切换；下载完成后必须严格核对从 GitHub 官方来源取得的 SHA-256 摘要，不符立即丢弃并抛出 `ChecksumMismatchException`。禁止使用 `jsDelivr`（其按 git tag 拉取仓库文件，tag 推送时尚处于构建与草稿阶段，会提前暴露未发布版本且无法获取 Release 二进制附件）。启动自动检查可通过偏好 `autoCheckUpdatesFlow` 关闭。

### 多语言更新说明链路

- 发版前在 `.github/changelogs/<version>.md` 中编写 `# zh-Hans`、`# zh-Hant`、`# en`、`# ja`、`# ko` 五段更新说明。`release.yml` 最开头的第一个前置 job 对正式 tag 发版执行硬性门控校验（缺失或任一语言为空即在数秒内失败阻断，不浪费后续测试与编译时间；`workflow_dispatch` 演练不强制要求），且前置校验与 `release` job 均优先从 `origin/main` 读取该文件（回退至 tag 提交树），支持漏写或修订文案后推送到 `main` 直接单任务 Rerun `release` job 幂等覆盖草稿附件与 SLSA 证明。
- `release` job 将 `zh-Hans` 拼接在 `.github/release-notes.md` 的 `## 下载` 附件表之前作为 GitHub Release 页面正文（保持中文默认展示并兼容旧版客户端：`GithubReleases.kt` 的 `updateNotesOf` 读到 `## 下载` 即截断，修改该标题须同步修改 `.github/release-notes.md`，由 `ReleaseNotesTest` 守护），同时将 `zh-Hans` 写入 `release.json` 的 `body` 字段，并将全部五种语言写入 `release.json` 的 `localized_notes` 字段。
- 客户端请求 `api.github.com` 成功但响应体本身不含 `localized_notes` 时，`GithubReleaseClient` 会按 API 公布的 `release.json` 权威 SHA-256 摘要经 `ReleaseAsset.urls` 下载并校验 `release.json`，提取 `localized_notes` 供 `UpdateDialog` 按 `PikoLocale.effectiveLanguage` 展示对应语言更新说明（缺失时依次回退至 `zh-Hans` 与截断后的默认 `body`）。

## Windows 更新架构与老客户端兼容契约

更新暂存目录统一位于当前实例的数据根目录 `update/<版本>/`（`PikoHome.root`），单实例锁同样按数据根目录隔离，使正式安装版与多份便携版互不干扰。更新成功后脚本自动清理暂存补丁、安装包与清单，仅保留日志（`update-history.log` 超过 `1 MiB` 自动截去前半部分）。应用内更新**绝不下载或解压 `.7z` 便携包**（`.7z` 仅供用户手动下载全新便携版，原因见 `desktopApp/AGENTS.md` 的 AOT 时间戳说明）。

### 两路 Windows 更新附件与增量差分

1. **传统补丁与 MSI 通道（`files.json`、`app.zip`、`.msi` 及 zstd 差分包）**：
   - `app.zip` 除包含每次编译必变的启动器、应用 jar、AOT 缓存与 `Piko.cfg` 外，还包含相对于所有仍在服役的已公开旧版（`1.0.0` 以外全部带清单的已发布版本，由 `.github/scripts/update-bases.sh` 拉取比对）新增或发生变化的 JRE 运行时、mpv 与原生库文件，并在 `files.json` 中统一标记 `patch = true`（`UpdateArtifactsTask`）。
   - 针对近期版本的 zstd 二进制差分包（`piko-windows-<架构>-<版本>-from-<旧版本>.zip`）在客户端通过 FFM 直接调用随包 `app/resources/zstd/` 目录下的原生 `libzstd`（`desktopApp/.../update/ZstdPatch.kt`），x64 与 arm64 完全同路：由于 `zstd-jni` 的 `win_aarch64` 动态库仅导出标准 C 函数而未编译 JNI 桥接符号，其 Java 包装类在 Windows ARM64 上不可用，因此运行时彻底剥离 `zstd-jni` Java 依赖，仅在构建期从各平台 jar 提取原生 DLL（`1.1.0` 及更早的 ARM64 客户端仍调用 `zstd-jni` 类，会自动降级回退至整文件替换；`release.yml` 在 `windows-11-arm` 打包机上直接运行 `:desktopApp:desktopTest` 验证真实 DLL 加载与差分还原）。
2. **便携版全量按需提取通道（`image.zip`）**：包含整个应用目录的逐条目压缩包。当新版客户端的便携版因本机文件被改动或版本早于补丁基准而无法通过 `app.zip` 补丁校验时，`ImageZip.kt` 通过 HTTP Range 先拉取 `image.zip` 末尾的 ZIP 中央目录，计算差异后仅按 Range 下载本机缺失或哈希不一致的条目（例如跨 JDK 升级仅需下载约 `34 MB` 而非全量 `116 MB`；若镜像不支持 Range 则回退整包下载）。MSI 安装版在补丁失配时照常回退至 `.msi` 整包重装。

### 不可破坏的老客户端发版契约

`v1.0.0` 与 `v1.1.0` 等已发布老客户端依赖以下静态契约完成自我升级，修改 `UpdateManifest.kt` 或打包脚本前必须核对旧版 `DesktopAppUpdater.resolve` 逻辑：

- **`files.json` 字段只增不删、语义不变**：旧客户端配置了 `ignoreUnknownKeys = true`，新增字段可安全忽略，但既有字段重命名或语义变更会直接破坏解析。
- **`app.zip` 条目集合严格等于 `patch == true` 集合**：不可将 `app.zip` 扩展为全量包——旧版 `extractPatch` 会将任何未在清单中标记 `patch = true` 的压缩包条目判定为异常并中止更新，多一个或少一个文件均会被拒绝。
- **三大基础附件命名不可变更**：`piko-windows-<架构>-<版本>-files.json`、`-app.zip`、`.msi` 三者缺一不可，缺少任意一个时 `1.1.0` 及更早客户端均会判定当前尚无可用新版。
- **补丁基准覆盖范围不可收缩**：`update-bases.sh` 必须拉取除 `1.0.0` 以外的全部历史已发布版本清单（不限主版本号）；一旦剔除某个旧版本，停留在那个版本且缺少后续新增运行时文件的老便携版将因 `app.zip` 补丁失配、又无旧版全量 `.zip` 可下而退化为只能手动更新。
- **`1.0.0` 的强制补丁阻断机制**：`1.0.0` 是唯一被刻意排除在补丁基准之外的例外版本。`1.0.0` 内置的旧更新脚本按字符串前缀长度截取相对路径，当 Windows `%TEMP%` 为 8.3 短路径（如 `MARVIN~1`）时会将补丁文件写入错位的子目录却仍返回成功，导致重启后仍是旧版、开屏再次提示更新的死循环（新版脚本已改为按目录层级逐级比对推导相对路径）。为阻止 `1.0.0` 走补丁通道，除了在 `update-bases.sh` 中排除它之外，`UpdateArtifactsTask` 还通过 `-PpikoUpdateRetired` 强制校验当前构建在 `patch = true` 之外至少存在一个 `1.0.0` 所没有的文件（目前由 `1.1.0` 引入的 `app/resources/zstd/` 原生库承担该阻断锚点，升级或调整 zstd 目录时必须留意，校验不满足时构建直接报错失败），从而确保 `1.0.0` 的 `canPatch` 必然返回 `false`：MSI 用户安全退回 `msiexec` 整包升级，便携版引导至下载页。

### Windows 补丁事务日志、回滚与 MSI 修复兼容

- **崩溃一致性事务（`apply-update.ps1`）**：脚本等待启动器与 JVM 子进程双双退出（超时未退则写入失败标记并重新拉起旧版；安装目录无写权限如便携版置于 `Program Files` 下时客户端直接仅提供下载页），在改动任何文件前先在安装目录落盘并独占锁定 `.piko-update.journal` 事务日志。文件操作严格遵循 `新增文件 -> 替换既有文件 -> 最后更新 Piko.cfg` 的顺序（遇杀毒软件或系统索引器短暂占用文件句柄时退避重试约 `7 秒`），提交完成后删除日志；若中途断电或脚本被强杀，下次启动时 `DesktopAppUpdater.create` 检测到未被独占锁定的残留 `.piko-update.journal`，立即以 `-Mode recover` 移交脚本并自行退出，由脚本依据日志完整回滚至旧版、记录失败提示后重启旧版（日志存在期间 `.old` 与 `.new` 备份绝不当作普通残留清理）。该机制由真跑 PowerShell 的 `ApplyUpdateScriptTest` 与 `package-smoke/windows.ps1` 场景 7 端到端验证。
- **MSI 就地补丁与 Windows Installer 修复（Repair）兼容**：MSI 安装版同样优先采用就地打补丁以节省流量与时间。Windows Installer 执行修复（控制面板「修复」或默认 `msiexec /f` 即 `omus` 模式）时，对于无 PE 版本号的文件（如 `.jar`、`app.aot`），只要其**创建时间早于修改时间**即视为用户修改过的文件而予以保留；对于带 PE 版本号的 `Piko.exe`，高版本不会被旧版覆盖。因此 `apply-update.ps1` 在替换补丁文件后显式继承原文件的创建时间（`1.1.0` 旧脚本升级那一跳则依赖 Windows 默认开启的 NTFS 15 秒文件名隧道机制自动继承创建时间），并按清单将类路径 jar 的修改时间还原至与 `-Dpiko.classpath-mtime` 一致的构建时刻，确保修复操作要么完好保留补丁，要么在强制全量重装（`/fa`）时干净退回可正常启动并再次提示更新的旧版，绝不损坏安装镜像（注意：就地补丁不修改 Windows「已安装应用」列表中登记的初始 MSI 版本号）。

## 端到端安装与历史升级冒烟测试

桌面安装包冒烟脚本位于 `desktopApp/package/package-smoke/`，本机与 CI 均可直接运行：

- **沙箱隔离身份（`ShellIdentity`）**：携带 `-Dpiko.update.auto=true` 启动的被测包在查到假服务端（`fake_release.py`）的新版后自动下载安装。测试构建通过 `pikoDesktopUpgradeUuid` 与 `pikoDesktopPackageName` 生成独立的 MSI 产品 UUID 与包名，写入 `HKCU` 的通知 AUMID、协议关联 `ProgID`、`Capabilities` 与 `RegisteredApplications` 均随测试包名独立隔离并在测试卸载时干净清除，绝不污染或误删宿主机已安装的正式版 Piko（仅 `magnet` 与 `.torrent` 顶层键为系统共用，`LinkRegistration` 仅在键不存在时创建，已有 `UserChoice` 的机器上 `windows.ps1` 自动跳过默认打开方式断言）。
- **历史真包升级回归（`package-smoke/legacy.ps1`）**：CI 的 `windows-package-legacy` 拉取真实的 `v1.0.0` 与 `v1.1.0` 已发布便携包及 MSI 安装包直接升级至当次构建，逐一断言：`1.1.0` 成功走常规补丁与 `-from-1.1.0.zip` zstd 差分升级、`1.0.0` MSI 成功退回 `msiexec` 整包升级、`1.0.0` 便携版补丁判定失配，且升级及执行一次 MSI 修复后应用均能正常启动、版本号匹配、用户数据完整保留。
