# 应用内更新

两端都有。检查与版本比较在这里，安装各走各的：Android 交给 PackageInstaller；Windows 按文件清单
决定只换补丁文件（exe、全部 jar、AOT 缓存、启动配置）、MSI 安装版整包重装，还是便携版从便携 zip 只换不同的文件，
由 `apply-update.ps1`（`desktopApp/src/desktopMain/resources/update/`）在应用退出后执行，它要等 JVM 与启动器两个进程都退出
（jpackage 的启动器另起同名子进程跑 JVM）。
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
另起一个产品，不碰已装的 Piko。

更新弹窗的说明读 Release 正文，读到 `## 下载` 就截断（`GithubReleases.kt` 的 `updateNotesOf`），
标题改动要与 `.github/release-notes.md` 一起改，`ReleaseNotesTest` 会报错。
