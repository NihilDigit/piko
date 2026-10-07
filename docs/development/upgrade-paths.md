# 现有用户的升级路径调研（v1.0.0、v1.1.0 到下一版）

调研时点：2026-10-08，main 为 b8d7fba，距 v1.1.0 共 160 个提交。下一版按 main 的发版流水线产出。
范围：只读，未触发 workflow，未改仓库。引用旧版代码一律取自 `git show v1.0.0:路径`、`git show v1.1.0:路径`。

## 一、总表

| 人群 | 能否收到提示 | 下载什么 | 怎么安装 | 数据保留 | 风险 |
|---|---|---|---|---|---|
| Windows MSI 1.1.0 x64 | 能（API，失败改取 release.json） | 优先 `-from-1.1.0.zip` 差分，退回 `-app.zip` | 脚本就地换文件，不跑 msiexec | 保留，登录态自动迁移 | 中：差分与 release.json 路径从未被端到端验证 |
| Windows MSI 1.1.0 arm64 | 能 | `-app.zip`（旧客户端 zstd-jni 在 arm64 不可用，不走差分） | 同上 | 保留 | 中：legacy 冒烟只覆盖 x64 |
| Windows MSI 1.0.0（x64、arm64） | 能，仅 API | `-app.zip` | 1.0.0 自带脚本就地换文件 | 保留 | 高：临时目录为 8.3 短路径时更新必败且在安装目录留垃圾 |
| Windows 便携版 1.1.0 | 能 | 同 MSI 1.1.0 | 同上，在原目录就地换 | 保留（数据仍在 `~/.piko`） | 中，同 MSI 1.1.0；补丁对不上时只给下载页 |
| Windows 便携版 1.0.0 | 能，仅 API | `-app.zip` | 1.0.0 自带脚本就地换 | 保留 | 高，同 MSI 1.0.0 |
| Android 1.0.0、1.1.0 | 能（1.0.0 仅 API） | 按 ABI 选 `piko-<版本>-<abi>.apk`，无则 universal | PackageInstaller 会话，用户确认 | 保留，DataStore 就地迁移 | 低 |
| macOS 1.0.0 | 能 | 无，只开下载页 | 用户手动拖入 | 保留 | 低 |
| macOS 1.1.0 | 能 | `piko-macos-arm64-<版本>.dmg` | 脚本整包换 `.app` | 保留，明文文件迁入钥匙串 | 中：真实 1.1.0 到新版未测 |
| Linux | 无已发布包 | 不适用 | 不适用 | 不适用 | 不适用 |

## 二、共同机制

### 2.1 检查更新

- 1.0.0：`GithubReleaseClient` 只取 `https://api.github.com/repos/NihilDigit/piko/releases/latest`（`GithubReleases.kt:136`，v1.0.0）。匿名限流 403 时不提示，没有备用来源，也没有镜像。
- 1.1.0：依次取 API 与 `releases/latest/download/release.json`（`LATEST_RELEASE_SOURCES`，`GithubReleases.kt:184`，v1.1.0）；附件下载在一个字节都没收到时退到 `ghfast.top`，仍按 GitHub 公布的摘要校验。release.json 的正文为空，弹窗只给更新页链接。
- 时机：开屏检查一次，一次进程一次，没有后台轮询（`AppUpdateService.kt`，v1.0.0）；设置页可手动检查。只有带 `piko.release-build` 的发行构建才查。
- 版本比较：`isNewerVersion` 按 `.` 切段逐段比整数，忽略 `-` 后缀（`GithubReleases.kt:209`，v1.0.0，两版相同）。tag 形如 `v1.2.0` 即可。
- 附件摘要：只认 API 或 release.json 里的 `digest: sha256:...`，缺则拒绝下载（`ChecksumMismatchException`）。不读 `SHA256SUMS.txt`。
- 更新说明：取正文中 `## 下载` 之前的部分（`updateNotesOf`，两版相同）。当前 `.github/release-notes.md` 的标题未变，兼容。

### 2.2 当前流水线的附件，旧客户端认不认

旧客户端逐名查找，名字全部与旧版一致：

| 旧客户端查找的名字 | 当前流水线产出 | 结论 |
|---|---|---|
| `piko-windows-<arch>-<ver>-files.json`、`-app.zip`、`.msi` | `release.yml` 的 windows job 经 `packageReleaseUpdate` 与 `Copy-Item` 产出同名文件 | 兼容。三者缺一，旧客户端都当作没有新版 |
| `piko-windows-<arch>-<ver>-from-<旧版>.zip`（1.1.0 可选） | `delta-updates.sh` | 兼容；见 2.3 |
| `piko-windows-<arch>-<ver>.zip`（1.1.0 便携整包回退） | 已改为 `.7z`，不再产出 | 不兼容，但只影响补丁对不上时的便携版，退回下载页（`Manual(msi)`） |
| `piko-macos-arm64-<ver>.dmg` | 同名 | 兼容 |
| `piko-<ver>-<abi>.apk`、`-universal.apk` | 同名 | 兼容 |
| `release.json`（1.1.0 备用） | release job 的 python 步骤生成，字段为 `tag_name`、`html_url`、`body`、`draft`、`prerelease`、`assets[name,size,browser_download_url,digest]` | 兼容。已下载实物核对 v1.1.0 的 release.json，形状一致；当前流水线代码未改字段 |

新增而旧客户端忽略的附件：`-image.zip`、`.7z`、`.AppImage`、`.AppImage.zsync`、`.tar.gz`。旧客户端按完整文件名精确匹配，不会误取。

files.json 的字段：旧客户端用 `ignoreUnknownKeys`；当前清单仍是 `version`、`files[path,size,sha256,mtime,patch]`，未增字段。

### 2.3 补丁包的兼容性（Windows 老客户端唯一的路）

旧客户端的 `canPatch` 要求清单里 `patch=false` 的文件在本机逐字节相同；`extractPatch` 要求 app.zip 恰好是 `patch=true` 的那些条目（多一个少一个都抛 `ChecksumMismatchException`）（`UpdateManifest.kt`，两版相同）。

当前 `UpdateArtifactsTask`（`desktopApp/build.gradle.kts:461`）把「与已发布版本的清单不同或旧版没有的文件」也标为 `patch=true` 并放进 app.zip，对照来源是 `update-bases.sh` 取到的 1.0.0 起全部已公开版本。这正是为了避免 1.1.0 发版时的情形：v1.1.0 比 v1.0.0 新增了 `app/resources/zstd/libzstd-jni-1.5.7-20.dll`（已用两版 x64 清单比对确认：202 个文件对 203 个，仅此一个新增，5 个补丁文件有变化），结果 1.0.0 的 canPatch 全部失败，MSI 用户被迫走 msiexec 整包重装，便携版只给下载页。

按设计，只要本机未被改过，1.0.0 与 1.1.0 都能走补丁。实际是否成立取决于发版时 update-bases 取到的清单，必须用演练产物验证（见第七节第 1 条）。

差分包：`delta-updates.sh` 对已公开的同主版本 `m` 与 `m-1`，再并上最近三个已公开版本，各出一份 `-from-<旧版>.zip`；旧版清单里没有 `app/resources/zstd/` 的（即 1.0.0 及更早）跳过。所以 1.0.0 客户端永远没有差分，1.1.0 x64 客户端有。

## 三、Windows 安装版（MSI）

### 3.1 v1.1.0 x64

1. 开屏检查，取到新版后 `resolve`（`DesktopAppUpdater.kt:112`，v1.1.0）：拼 `piko-windows-x64-<ver>` 前缀，取 files.json 并校验摘要，`canPatch` 为真则走补丁；有 `-from-1.1.0.zip` 且 `zstdAvailable`（zstd-jni 可用）则改为 `Delta`。
2. `Delta` 下载差分包，用本机文件作字典还原；`ChecksumMismatchException` 或 `LinkageError` 时退回完整 app.zip。
3. 退出前把自带的 `apply-update.ps1` 写进暂存目录（`%TEMP%\piko-update\<ver>`，1.1.0 已规范成长路径）启动，`-Mode patch`。脚本等 JVM 与启动器两个进程退出，复核暂存文件摘要，逐个先拷成 `.new` 再改名换入，失败整体回滚，成功后按 `keep.txt` 清掉 `app`、`runtime` 下新版没有的文件，最后拉起 Piko.exe。
4. 不走 msiexec，Windows Installer 登记的版本仍是 1.1.0，「应用」设置里显示不变。
5. 补丁对不上（本机文件被改过）时走 `Installer`：`msiexec /i <新 msi> /passive`。同 UpgradeCode（`6d8d332e-f0f4-4ee0-bc2d-fb3ebf3d4267`，`build.gradle.kts` 默认值，main 未变），新 MSI 在安装事务里先卸旧再装，属于主要升级，可覆盖。
6. 数据：见第六节。

已知缺陷（旧脚本，新版修复不回流）：

- `Remove-StaleFiles`（v1.1.0 `apply-update.ps1:162`）拿 `Join-Path $InstallDir` 拼出的 keep 表与 `Get-ChildItem` 报出的长路径比对。`$InstallDir` 若是 8.3 短路径，没有一项对得上，换完文件后会把新版 `app`、`runtime` 整个删掉。main 的 70ea7d2 在脚本开头把 `$InstallDir` 展开成长路径，只对新版的脚本有效。1.1.0 的 `-InstallDir` 来自 `jpackage.app-path` 的 `absolutePath`（`DesktopAppUpdater.kt:309`），取决于启动器被怎样启动。MSI 默认装在 `%LOCALAPPDATA%\Piko`，通常是长路径，触发概率低，后果严重（安装损坏）。
- 脚本不保留文件创建时间，靠 NTFS 文件名隧道（15 秒内同名重建继承创建时间）让之后的 Windows Installer 修复保留补丁。main 的 `legacy.ps1` 对此做了修复后启动的断言，通过。
- 没有事务记录：换文件途中断电，下次启动无人收拾。回滚只在脚本进程存活时有效。

### 3.2 v1.1.0 arm64

同 3.1，区别：1.1.0 的 arm64 客户端用 zstd-jni，其 `win_aarch64` 库缺 JNI 方法，`zstdAvailable` 为假，永远走完整 app.zip（`DesktopAppUpdater.kt` 注释已说明）。下载量更大，但路径更少。legacy 冒烟只跑 x64（`legacy.ps1` 的 `-Arch` 默认 x64，test.yml 的 `windows-package-legacy` 在 windows-2022 上，没有 arm64）。

### 3.3 v1.0.0（x64、arm64）

1. 只查 API，无 release.json，无镜像。
2. `resolve`（`DesktopAppUpdater.kt:84`，v1.0.0）逻辑与 1.1.0 同：`canPatch` 为真走 `Patch`，否则 MSI 安装版走 `Installer`，便携版给下载页。
3. 实际历史：1.0.0 到 1.1.0 时 `canPatch` 因缺 zstd DLL 必败，所有 1.0.0 MSI 用户走的是 msiexec。这次有了 bases，1.0.0 会改走补丁，而这条路径上有下面这个缺陷。
4. **1.0.0 脚本的相对路径缺陷**（`apply-update.ps1:35`，v1.0.0）：`$relative = $file.FullName.Substring($sourceRoot.Length + 1)`，`$sourceRoot` 取 `Resolve-Path` 的结果。1.0.0 的暂存目录是 `System.getProperty("java.io.tmpdir")` 下的 `piko-update`（`DesktopAppUpdater.kt:77`），不展开成长路径。`java.io.tmpdir` 为 8.3 短路径时，`Resolve-Path` 保持短路径，而 `Get-ChildItem` 返回的 `FullName` 是长路径，前缀长度对不上，每个补丁文件都被写到安装目录下一个凭空出现的子目录。脚本报「update applied」，重启后仍是旧版，下次开屏再提示，形成「更新、重启、再提示」的循环（main 的 b4ac101 提交说明：CI 上表现为 endless relaunch，并指出用户名带空格或非 ASCII 时临时目录常为 8.3 路径）。
   - 已实测复现机制：在本机用 `powershell.exe`（5.1）对短路径调用同样的代码，`Resolve-Path` 返回短路径，`FullName` 为长路径，`Substring` 得到 `\scratchpad\...` 一类错位的相对路径。
   - 未实测：真实用户的 `%TEMP%` 在什么条件下是短路径。本机用户名 marvin 的 `%TEMP%` 是长路径，无法在本机复现。
   - 不会损坏已有安装（文件落在错位子目录，旧文件未动），但更新必败，且留下垃圾目录。
5. 1.0.0 的脚本只等 JVM 进程，不等启动器，换 exe 时会留下 `Piko.exe.old`；1.1.0 及之后的客户端在启动时清除（`removeUpdateLeftovers`），main 保留此逻辑。`legacy.ps1` 对此做了重现与断言。
6. 1.0.0 的 `legacy.ps1` 测的是「照 1.0.0 的 extractPatch 手工暂存，再跑 v1.0.0 原样脚本」，暂存目录在 `$Work\stage`（长路径），没有覆盖短路径临时目录。

## 四、Windows 便携版

### 4.1 v1.1.0 与 v1.0.0（`.zip`，解压运行 `Piko.exe`）

- 机制与 MSI 相同，区别在 `isMsiInstall` 为假：补丁对得上走 `Patch` 或 `Delta`；对不上时 1.1.0 找 `piko-windows-<arch>-<ver>.zip` 走 `Portable` 整包（`DesktopAppUpdater.kt` v1.1.0），当前流水线已不出 `.zip`，找不到则退为 `Manual(msi)`，只给下载页。1.0.0 对不上直接给下载页。
- 就地替换：脚本把文件换进原来解压的目录，用户数据不在该目录，不受影响。
- 因 bases 机制，补丁对不上的情形应当很少（只剩用户改过运行时文件）。
- 1.0.0 便携版同样受第三节 3.3 第 4 条缺陷影响，且 1.0.0 便携版没有 MSI 这条退路：历史上它们在 1.1.0 发版时只能手动下载。
- 便携版 `.zip` 解压出的 jar 修改时间可能偏移时区，AOT 缓存因此失效；补丁换入的 jar 按清单还原修改时间，更新后恢复。

### 4.2 新的便携版 `.7z` 与数据目录

当前 `pack-portable.ps1` 在包内加一个 `portable` 标记，`PikoHome` 见到它就把数据放在程序目录的 `data\`（`PikoHome.kt:62`）。标记刻意不进 files.json，所以应用内更新不会装上它，就地升级的老便携版继续读写 `~/.piko`。

后果：老便携版用户若不走应用内更新，而是手动下载新 `.7z` 解压，得到的是一份空的 `data\`：登录态、设置、下载任务都不在了，原数据留在 `~/.piko`，没有导入逻辑。若解压覆盖到旧目录，标记同样生效。

## 五、Android

### 5.1 机制（1.0.0 与 1.1.0 相同，仅 1.1.0 多了 release.json）

- `AppUpdater.pickApk`（`AppUpdater.kt:136`，v1.0.0）：按 `Build.SUPPORTED_ABIS` 顺序找 `piko-<ver>-<abi>.apk`，再退到 `piko-<ver>-universal.apk`。主流设备取 `arm64-v8a`。
- 下载时边写边算 SHA-256，与 digest 对不上则删除并报错。
- 安装：`PackageInstaller` 会话，`MODE_FULL_INSTALL`，`setAppPackageName`，`session.commit` 交给系统，由 `UpdateInstallReceiver` 接回调并转交用户确认页。未授予「安装未知应用」时先带去授权，授权后须再点一次。
- debug 包（包名 `dev.piko.debug`）只给下载页，不影响正式用户。

### 5.2 兼容性核对（已实测）

下载 v1.0.0 与 v1.1.0 的 arm64 APK，用 build-tools 37.0.0 的 apksigner 与 aapt2 核对：

- 包名均为 `dev.piko`。
- 签名：v2 单签名者，证书 SHA-256 均为 `faec4b73479583c878030b5bbe86f2ff995089a4a421579d0e71ee7ac3c8618f`，两版一致。
- versionCode：1.0.0 为 1000000，1.1.0 为 1001000，对应 `major*1000000 + minor*1000 + patch`。当前 `release.yml` 的 `version` job 公式未变，下一版（例如 1.2.0 为 1002000）单调递增。
- `app/build.gradle.kts` 的 applicationId、minSdk 26、targetSdk 37、ABI 拆分与签名配置自 1.0.0 起没有改动；AndroidManifest 与 `CredentialCipher` 自 1.1.0 起没有改动。
- 签名密钥来自仓库 Secret，与前两版同源，本次调研无法核对 Secret 本身是否仍是同一把。若换了密钥，所有用户都会得到「与已安装应用签名不一致」，无法升级。发版前应以 apksigner 对演练产物验证证书摘要。

### 5.3 渠道

仅 GitHub Releases，没有应用商店渠道的更新路径。ABI 拆分包与 universal 之间互相覆盖安装无问题（同包名、同签名、同 versionCode）。

## 六、用户数据与设置

约定「兼容与迁移只为已发版的数据格式写」：已发版的是 1.0.0 与 1.1.0，下表只列这两版写下的格式。

| 数据 | 1.0.0、1.1.0 的格式 | main | 结论 |
|---|---|---|---|
| 桌面登录态 | `~/.piko/pikpak-session.json`、`pikpak-account.txt`、`pikpak-password.txt` 明文，单账号 | `DesktopSessionStore.migrateLegacy`：写入 DPAPI（Windows）或钥匙串（macOS），读回一致才删旧文件，生成 `accounts.json` | 已迁移。失败时保留旧文件、本进程内用内存里的会话，下次启动重试 |
| 桌面设置 | `~/.piko/settings.properties` | 位置不变（默认根仍是 `~/.piko`）；`DesktopSettingsStore` 新增自愈：下载目录落在程序目录或工作目录内时删掉该键 | 兼容。1.0.0 的「恢复默认」把下载目录写成程序目录（见该类注释），升级后自动恢复默认 |
| 桌面设置键 | 1.1.0 的键 | 去掉了 `ui.inspectorPanel.open`、`ui.inspectorPanel.width`；`session.*` 五个键只在测试里写过，生产没有写 | 删除的键只是被忽略，无副作用 |
| 桌面解压密码、上传凭据 | 1.1.0 明文存于 settings.properties 的 `drive.archivePasswords`、`upload.tasks` | b557bb5：迁入机密存储，读回一致后删明文；上传凭据在首次保存时迁移 | 已迁移 |
| Android 登录态 | DataStore `piko_preferences`：`pikpak_session_<账号>` 明文 JSON，`pikpak_password_<账号>` 经 AndroidKeyStore 加密（旧版更早的明文也会就地换密文），`piko_last_account`，另有 `auth_token`、`refresh_token` 等明文镜像键 | `AndroidPikoSessionStore`：读到明文会话就地换成密文；`loadAccounts` 在没有 `piko_accounts` 时由 `piko_last_account` 与镜像键的昵称、头像、配额合成账号列表，并删去镜像键 | 已迁移 |
| Android 解压密码 | 1.1.0 明文存于主偏好 `archive_passwords` | `ArchivePasswordEncryption` 作为 DataStore 迁移就地加密，密钥库不可用时原样留着，下次再试 | 已迁移 |
| Android 其余偏好键 | 键名见 1.1.0 的 `SessionManager` | 只删了 `inspector_panel_*`，其余保留，新增键都有默认值 | 兼容 |
| 下载任务表 | `piko_downloads` 中的 JSON | `DownloadTask` 新字段全带默认值（`account=""` 对任何账号放行，`sparseCache=false` 表示旧的顺序下载，续传时导入前缀） | 兼容 |
| 设置同步文件 | 网盘 `.piko/settings-<时间戳>.json` | 键只增不改；`archivePasswords` 列入 `RetiredSettingKeys`，同步时抹去并删除旧文件 | 兼容；见第七节第 7 条 |
| 钥匙串命名空间 | 无（旧版不用钥匙串） | 默认根目录下命名空间为空串，沿用 `dev.piko.desktop` | 兼容 |

便携版数据位置的变化见 4.2。

## 七、发版前需要处理的问题

按严重程度排序。

### 1. 发版流水线自 1.1.0 以来整体改过，从未演练（高）

`gh run list --workflow release.yml` 显示最后一次运行在 2026-09-29（即 1.1.0）。之后加入了 Linux job（xvfb、appimagetool）、`pack-portable.ps1`（7z）、`update-bases.sh`、`delta-updates.sh` 的 libzstd 改动、AOT 训练目录隔离与启动配置改写、MSI 的 RemoveFolderEx 改写与 AUMID 注入。`test.yml` 在 9584abf 上的手动全量运行是绿的（含 `windows-package-legacy`），但它不覆盖 release.yml 本身。CLAUDE.md 要求「改了发版流程先手动触发 release.yml 演练」。

建议：先 `gh workflow run release.yml -f version=9.9.9` 演练，检查产物清单与文件名，再推 tag。

### 2. 1.1.0 x64 客户端的差分路径没有任何端到端验证（中高）

- 生产上 1.1.0 x64 用户会优先走 `Delta`：下载 `-from-1.1.0.zip`，在 1.1.0 的 zstd-jni 上还原。`grep -n "from-\|delta"` 对 `legacy.ps1`、`windows.ps1`、`fake_release.py`、`test.yml` 均无命中，CI 冒烟不含任何差分。
- v1.1.0 的发布附件里没有 `-from-` 包（当时没有可对照的旧版），所以这条路径在真实发布中从未运行过。
- 还原失败时只有 `ChecksumMismatchException` 与 `LinkageError` 会退回完整补丁（`DesktopAppUpdater.kt` v1.1.0 的 `stage`）；其他异常会让更新失败，用户可重试但会重复失败。`zstd --long=27` 生成的帧，文件超 128 MB 时由 zstd-jni 抛 `ZstdException`，被 `applyDelta` 转成 `ChecksumMismatchException`，这一支有退路；其他类型没有。
- 建议：用演练产物里的 `-from-1.1.0.zip` 与 `-files.json`，在真实 1.1.0 x64 安装上用 `-Dpiko.update.api` 指向本地假 Release，实测一次差分更新；或改 `legacy.ps1`，让假 Release 同时提供 `-from-` 包。

### 3. 1.0.0 客户端被 bases 机制引入有缺陷的补丁路径（高，影响面取决于短路径临时目录的占比）

见 3.3 第 4 条。1.0.0 的 Windows 下载量约：x64 MSI 273、x64 zip 148、arm64 MSI 8、arm64 zip 3，其中仍停在 1.0.0 的用户数未知。

后果：临时目录为 8.3 短路径的用户，更新必败并反复提示；不损坏安装。

可选改法（作者定夺）：
- A. 维持现状，在更新日志与 README 写明 1.0.0 用户请手动下载安装，并接受这部分用户更新失败。无法在服务端修复，因为旧客户端的脚本已经发出去了。
- B. 在 `update-bases.sh` 中排除 1.0.0 的清单，使 1.0.0 的 `canPatch` 因缺 `app/resources/zstd/libzstd-jni-1.5.7-20.dll` 而必败：MSI 用户退回 msiexec 整包重装（1.0.0 到 1.1.0 时已被事实证明可用，不依赖相对路径），便携版给下载页。代价：违反 `shared/.../update/CLAUDE.md` 的「不要缩小范围」，1.0.0 便携版只能手动更新；MSI 用户下载约 100 MB。要不要这样做取决于短路径用户占比，需先实测（见摘要）。
- 不要靠清单做「只对 1.0.0 失败」之外的区分：两版客户端的检查逻辑相同，无法按版本号分流。

已定为 B：`update-bases.sh` 把 1.0.0 的清单移出对照、另交 `-PpikoUpdateRetired`，`UpdateArtifactsTask` 核对补丁包之外至少有一个文件是 1.0.0 没有的，否则构建失败；`legacy.ps1` 按新路径断言。

### 4. 旧 MSI 的卸载会删除安装目录内的用户文件（中）

`transactional-upgrade.ps1` 的注释记载：jpackage 的 RemoveFolderEx 在卸载时递归删除整个安装目录，卸载 1.1.0 实测种子与压缩包都被删，整包升级卸旧版时同样触发；新 MSI 才改成只删 `app`、`runtime`，对已装的旧版不起作用。

触发条件：1.0.0、1.1.0 的 MSI 用户走 msiexec 路径（补丁对不上，或手动运行新 MSI）。1.0.0 曾把下载目录写成程序目录（`DesktopSettingsStore` 的注释），这部分用户的下载文件最容易丢。补丁路径不受影响。

建议：更新日志写一句，提醒把放在安装目录里的文件先移走。

### 5. 老便携版手动换 `.7z` 会丢数据（中）

见 4.2。建议改法二选一：更新日志明确写「便携版请用应用内更新；手动换包时把 `~/.piko` 复制到 `data\`」；或在 `PikoHome` 中，便携标记存在、`data\` 为空且 `~/.piko` 有数据时提示导入（涉及机密存储的绑定账户，需另行设计，不建议仓促加）。

### 6. Windows arm64 与 Windows 10（不确定）

README 与下载表现在写 arm64 需要 Windows 11，旧版徽章写的是 Windows 10 及以上。提交 93bf0b0 只说「Windows on ARM requires Windows 11」，没有给出原因。若是新版才引入的限制，Windows 10 ARM 上的老用户会被应用内更新升级到跑不起来的版本，且无提示。需向作者确认原因；若属实，应在更新日志里写明，或让 arm64 的更新对 Windows 10 不可见（旧客户端无此判断，做不到，只能写说明）。

### 7. 设置同步的跨版本影响（低）

仍在 1.1.0 的设备会把明文 `archivePasswords` 推回网盘，直到有新设备同步把它抹掉（b557bb5 提交说明已承认）。建议更新日志不必提，但内部知悉。

### 8. 其他小项（低）

- 远端有一个误建的 tag `untagged-a3dc9f03da502f0f35ed`（指向 92e126c，即 1.1.0 发版时的提交），本地也有。不匹配 `v*`，不触发流水线，建议删除以免混淆。
- 1.0.0 与 1.1.0 把更新暂存在 `%TEMP%\piko-update`，新版改到数据根目录的 `update` 下；旧暂存目录不会被新版清理，只是占用少量空间（未核实新版是否清理）。
- 1.0.0 没有 release.json 备用来源，也没有镜像：在 GitHub API 限流或被墙的网络里收不到提示，服务端无法补救。
- Android 与 macOS：发版前用演练产物核对 APK 证书摘要是否仍为 `faec4b73…8618f`（见 5.2）；macOS 真实 1.1.0 到新版的整包替换与钥匙串迁移没有 CI 覆盖（`macos.sh` 用的是从当前源码打的 9.9.0 与 9.9.1），建议在真机上用 1.1.0 的 DMG 实测一次。

## 八、未能确认、需要实测验证的点

1. 真实用户的 `%TEMP%` 何时是 8.3 短路径（用户名超过 8 个字符、含空格或非 ASCII）。本机无法复现，只复现了脚本一侧的机制。
2. 1.1.0 x64 的差分更新对真实的 `delta-updates.sh` 产物是否可用。
3. release.yml 新版整体能否跑通，产出的文件名、`release.json`、SHA256SUMS 是否如预期。
4. 签名密钥 Secret 是否仍与前两版相同。
5. macOS 1.1.0 到新版的整包替换、钥匙串迁移。
6. 1.1.0 脚本在 `InstallDir` 为 8.3 短路径时的破坏性（仅从代码与 main 的修复说明推断，没有在 1.1.0 上实测）。
7. 新 arm64 构建与 Windows 10 ARM 的关系。
