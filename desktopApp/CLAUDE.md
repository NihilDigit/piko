# desktopApp

Windows、macOS 与 Linux 的入口、平台实现与播放器窗口。应用内更新的检查与各平台安装见
`shared/src/commonMain/kotlin/dev/piko/shared/update/CLAUDE.md`，机密存储见 `shared/src/desktopMain/kotlin/dev/piko/shared/auth/CLAUDE.md`。

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
- **触摸与笔**：AWT 不处理 WM_POINTER，触摸被降级成单个鼠标指针，没有多指、压力与笔的类型。`compose-windows-touch` 在
  `WindowsCaption` 的两个窗口过程里（框架窗口与 Skiko 画布，WM_POINTER 发给鼠标下的画布）截下 WM_POINTER*，
  经反射注入 Compose 内部的 `ComposeScene.sendPointerEvent` 列表重载。要点：
  - 不碰 WM_NCPOINTER*：落在标题栏与边框的触摸由系统合成鼠标消息，窗口移动与标题栏按钮才照旧。
  - 对 Compose 的反射在第一条指针消息到来时才建立，取不到就一直走 AWT 的鼠标路径。它读 `composePanel`、
    `_composeContainer`、`mediator` 与 `sendPointerEvent-` 的 10 参重载，**升级 CMP 时先看这里**；
    release 的 ProGuard 要显式保留这些成员（`proguard-rules.pro`），否则只在 release 里悄悄退回鼠标。
  - 堆积的 MOVE 在事件分发线程前合并，较早的采样留作 `HistoricalChange`，并要自己设
    `originalEventPosition`，否则速度跟踪器从原点起算，fling 快得离谱。
  - 触摸阈值：Compose Desktop 写死 18dp，`ProvideTouchViewConfiguration` 换成 Android 的 8dp。鼠标阈值是它的固定
    比例（0.125dp / 18dp），换了之后仍不到一个物理像素，鼠标手感不变。
  冒烟用 `InjectTouchInput`（虚拟数字化仪，无需触摸屏），在 `:desktopApp:desktopTest` 里，只能在 Windows 上跑。
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
  应用内更新整个换掉 .app（见 `shared/.../shared/update/CLAUDE.md`）：新包先拷到旁边，过了 `codesign --verify` 再去掉隔离属性、换进去；
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
