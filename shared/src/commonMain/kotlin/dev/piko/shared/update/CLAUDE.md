# 应用内更新

两端都有。检查与版本比较在这里，安装各走各的：Android 交给 PackageInstaller；Windows 按文件清单
决定只换补丁文件（exe、全部 jar、AOT 缓存、启动配置，及变了的运行时等，见下）、MSI 安装版整包重装，
还是便携版从 image.zip 只取不同的文件，
由 `apply-update.ps1`（`desktopApp/src/desktopMain/resources/update/`）在应用退出后执行，它要等 JVM 与启动器两个进程都退出
（jpackage 的启动器另起同名子进程跑 JVM）。
暂存在数据根目录的 `update/<版本>/` 下（`PikoHome.root`）：单实例锁按数据根目录加，同一个根下同时只有一次更新，
安装版与几份便携版各用各的。成功后脚本删掉装过的文件、安装包与清单，只留日志；`update-history.log` 超过 1 MiB 截掉前一半。
Windows 补丁换文件前先写事务记录（安装目录的 `.piko-update.journal`，落盘后才动第一个文件，脚本运行期间独占打开），
提交即删除。换的顺序是新增文件、替换文件、`Piko.cfg` 最后，中途断电或脚本被杀，镜像仍启动得起来；
下次启动 `DesktopAppUpdater.create` 见到无人占用的记录，就以 `-Mode recover` 交给脚本、自己退出，由脚本按记录回滚到旧版、
留下失败记号、再拉起。记录在时 `.old`、`.new` 不当残留删。复制、改名与删除遇到占用（杀毒、索引器）退避重试约 7 秒；
等应用退出超时同样写失败记号并拉起旧版。程序目录写不进（便携版放在 Program Files 下）时只给下载页。
脚本的这些行为由 `ApplyUpdateScriptTest` 真跑 PowerShell 验证；装好的包认出记录、交出、回滚后提示，由 `windows.ps1` 的场景 7 验证。
应用内更新从不解便携包（7z，只给人手动下载，理由见 `desktopApp/CLAUDE.md` 的 AOT 一条）。Windows 的更新附件有两路：
- **老客户端的路**：files.json、app.zip、.msi。app.zip 除了每次构建都变的那几类文件，还带上与仍在用的已发布版本
  （1.0.0 以外全部已公开、带清单的，`.github/scripts/update-bases.sh` 取来）不同或它们没有的运行时、mpv、原生库，清单里同样标
  `patch`（`UpdateArtifactsTask`）。1.1.0 及更早只认这三个附件，补丁对不上时便携版去找已不再发布的 .zip，只能手动更新。
- **image.zip**：整个应用目录，逐条目压缩。本版起的便携版补丁仍对不上时（本机文件被改过、比对照的旧版更早）按 HTTP Range
  先取中央目录、再只取不同的文件（`ImageZip.kt`），JDK 升级时约 34 MB，整个约 116 MB；镜像不认 Range 时整个下载。
  不把 app.zip 扩成全量：老客户端的 extractPatch 把清单 patch 之外的条目当成异常，补丁更新就坏了。
  MSI 安装版补丁对不上时照旧整包重装。

**发版契约**，老客户端靠它们更新，改之前先看 v1.0.0、v1.1.0 的 `DesktopAppUpdater.resolve` 与 `UpdateManifest.kt`：
- files.json 的字段只加不改，含义不变（老客户端 `ignoreUnknownKeys`，多出的字段无害）。
- app.zip 恰好是清单里 `patch=true` 的那些文件，路径与清单相同，多一个少一个都会被 extractPatch 拒掉。
- 附件名不变：`piko-windows-<架构>-<版本>-files.json`、`-app.zip`、`.msi`；三个缺一个，老客户端都当作还没有新版。
- 补丁包的对照是 1.0.0 以外全部已公开、带清单的版本，不限主版本（update-bases.sh）。不要缩小这个范围：被排除的版本
  本机缺新增的文件，补丁对不上，老便携版就只能手动更新。
- 1.0.0 是有意排除的例外，它不该走补丁：它自带的脚本按前缀长度截相对路径，`java.io.tmpdir` 是 8.3 短路径时把补丁写进
  错位的子目录、仍报成功，重启后还是旧版，下次开屏再提示，循环。排除后它的 canPatch 必然失败，安装版退回 msiexec 整包重装，
  便携版只给下载页。只排除还不够，它与对照的版本在 app.zip 之外全同时照样走补丁；`UpdateArtifactsTask` 拿它的清单
  （`-PpikoUpdateRetired`）核对补丁包之外至少有一个文件是它没有的，不成立就构建失败。目前挡住它的是 1.1.0 起才有的
  `app/resources/zstd/` 下的 libzstd，升级 zstd-jni 时留意。
`package-smoke/legacy.ps1`（test.yml 的 windows-package-legacy）拿真实的 1.0.0、1.1.0 便携 zip 与 MSI 升级到当次构建，
断言 1.1.0 走补丁（另有一轮走 `-from-1.1.0.zip` 差分）、1.0.0 的 MSI 版走 msiexec、1.0.0 的便携版补丁对不上，以及能启动、版本对、数据在，
MSI 版再修复一次仍能启动。1.0.0 没有自动安装，它的下载与暂存由冒烟照原样代劳，再跑它原样的更新脚本。
脚本里的相对路径逐级比对目录名得出，不按前缀截取：`%TEMP%` 可能是 8.3 短路径（`MARVIN~1`），与展开后的长路径
前缀对不上，CI 上出过换完文件又重启、无限循环。增量补丁（zstd 差分，`piko-windows-<架构>-<版本>-from-<旧版本>.zip`）
经 FFM 直调安装包资源目录 `zstd/` 里的 libzstd（`desktopApp/.../update/ZstdPatch.kt`），x64 与 arm64 同一条路：
zstd-jni 的 win_aarch64 库只导出 C 函数、没有 JNI 方法，它的 Java 类在 ARM64 上用不了，所以运行时不依赖 zstd-jni，
构建时只从它的按平台 jar 里取出原生库。载不了 libzstd 或还原对不上时退回换整个文件；1.1.0 及更早的 arm64 客户端
仍用 zstd-jni 的类，在那里一直是退回整个文件。`:desktopApp:desktopTest` 的差分用例载入的是与安装包同一份库，
release.yml 在 arm64 打包机上也跑它们。macOS 整个 .app 换成新 DMG 里的（`apply-update-mac.sh`），不逐个换文件，那会破坏签名封印。
Linux 只认 AppImage，按 .zsync 差分更新，见 `desktopApp/CLAUDE.md`。

检查更新依次取 GitHub API、`releases/latest/download/release.json`（API 匿名限流，走代理的用户常被 403）。
版本信息不经镜像取：附件摘要就在其中，镜像能连摘要一起伪造。下载附件在一个字节都没收到时退到 ghfast.top，
按取自 GitHub 的摘要校验。jsDelivr 不能用：它按 tag 取，tag 推上去时 release 还是草稿。
开屏自动检查可在设置里关掉（`autoCheckUpdatesFlow`）。

带 `-Dpiko.update.auto=true` 启动时查到新版即自动装上，`desktopApp/package/package-smoke/` 用它对着假 Release
（`fake_release.py`）端到端地测安装与更新，本机也能跑：测试包用 `pikoDesktopUpgradeUuid` 与 `pikoDesktopPackageName`
另起一个 MSI 产品，写进 HKCU 的名字（通知的 AUMID、打开方式的 ProgID、Capabilities、RegisteredApplications）也随包名另起一套
（`ShellIdentity`，卸载时的清理按同一规则），不改、不删已装的 Piko 的登记。magnet 协议与 .torrent 扩展名本身的键是共用的，
Piko 只在它们不存在时新建（`LinkRegistration`），已有 UserChoice 的机器上登记也不生效，所以 `windows.ps1` 在那里跳过打开方式一步。

MSI 安装版也就地打补丁，不改走整包重装：Windows Installer 修复（控制面板的「修复」、`msiexec /f` 默认的 omus）
保留创建时间早于修改时间的无版本文件，当它是用户改过的；exe 带版本号，新的不会被换回。所以修复只会保留补丁，
或（`/fa` 强制全部重装）退回一个能运行、会再提示更新的旧版，不会装坏（实测；`windows.ps1` 与 `legacy.ps1` 打完补丁后各修复一次）。`apply-update.ps1` 换上文件后显式沿用
原文件的创建时间；1.1.0 用的是它自带的旧脚本，那一跳靠的是 NTFS 文件名隧道（同名文件 15 秒内重建时
继承旧的创建时间），默认开着。补丁不改 Windows Installer 登记的版本，「应用」设置里显示的仍是装时的版本号。
MSI 装上的类路径 jar 按安装机时区偏了修改时间，由应用启动时按启动配置里的 `piko.classpath-mtime` 改回（见 `desktopApp/CLAUDE.md`
的 release 一条）；补丁换上的 jar 按清单还原时间，与那个属性是同一个值，二者在同一次构建里定下。

更新弹窗的说明读 Release 正文，读到 `## 下载` 就截断（`GithubReleases.kt` 的 `updateNotesOf`），
标题改动要与 `.github/release-notes.md` 一起改，`ReleaseNotesTest` 会报错。
