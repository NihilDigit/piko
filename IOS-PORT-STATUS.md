# iOS 移植状态（Piko v1.4.0）

- 上游基线：[NihilDigit/piko v1.4.0](https://github.com/NihilDigit/piko/releases/tag/v1.4.0)；iOS 移植分支：`codex/ios-v1.4.0`。
- 下载：[iOS v1.4.0 Release](https://github.com/bluedvst/piko/releases/tag/ios-v1.4.0)。附件为 Debug 构建的未签名 IPA，需使用 LCSign 等工具签名后安装；最低系统版本 iOS 16。
- 已修复应用图标与 Kotlin/Native 文件名规则初始化闪退；此前修复版已获用户真机运行反馈。
- iOS 内置播放目前使用应用内系统 AVPlayer 界面，尚未移植桌面的 libmpv 播放器界面；外部播放入口支持 SenPlayer、Rex、VLC、Outplayer 的跳转，ddPlayer 可复制直链。直链会过期，失效后需重新打开。外部播放器的实际跳转和播放需在安装了对应应用的设备上验证。
- 本次构建：`1fa17e4598167be3513eb21813b3178af8d24606`，包含移植分支代码及仅用于 Debug 构建的工作流调整。[IPA 工作流](https://github.com/bluedvst/piko/actions/runs/41590585703) 和 [iOS 模拟器测试](https://github.com/bluedvst/piko/actions/runs/41590515323) 已通过；[Android 与桌面验证](https://github.com/bluedvst/piko/actions/runs/41590515424) 已通过。
- IPA SHA-256：`50659cc9f3bd7ee267c03aad6785490c44152242b36779e1e633e66d090ec5c2`。

以下内容为早期移植过程的历史记录，其中“尚未生成 IPA”“尚未连接 host”等状态仅适用于记录时点，不代表当前状态。

---

# iOS IPA 移植进度

## 交付状态

- 上游：https://github.com/NihilDigit/piko
- 基线：`c0ebd15121f0facd0a317bda8cb3a14047b35026`
- 已建立本地分支：`feat/ios-ipa`；尚未提交、推送或创建远程分支。
- **尚未生成 IPA。** 当前是共享层 Native 移植和 macOS 验证配置，不是可安装应用。

## 已落地的修改

1. shared、ui 增加 `iosArm64` / `iosSimulatorArm64` targets，ui 配置静态 `PikoKit.framework`。
2. RenameRun 移除 JVM AtomicInteger，使用协程 Mutex 串行化进度更新。
3. UI 日期、时长和速度格式化迁移到 commonMain 跨平台实现，加入 commonTest 与 desktopTest 回归测试。
4. 增加 iOS 稀疏缓存 actual 与 iosTest，包含 SHA-256 缓存名、分块持有记录、CRC 校验、同步与原子替换等逻辑；Native 编译与运行仍待 macOS 验证。
5. Foojay toolchain resolver 更新到 `1.0.0` 适配 Gradle 9 API。
6. 新增手动触发的 `.github/workflows/ios-native.yml`：在 macOS 上编译 shared，链接设备和模拟器 UI frameworks，Apple Silicon runner 上执行 Native 测试并上传报告。
7. 将 `SecretVault` / `VaultUnavailableException` 提取到 commonMain，桌面文件后端继续实现同一接口。新增 `IosKeychainVault`，以 service + key 隔离 generic-password 项，禁用 iCloud 同步，使用 `AfterFirstUnlockThisDeviceOnly`，无明文兜底；写入先 update，缺项才 add，处理并发插入重复项；只有查无此项返回 null，Keychain 暂时不可访问时抛异常。
8. 新增 9 个 Keychain Native 测试，使用真实 CoreFoundation 字典及注入的 SecItem 假边界，覆盖空/二进制数据、访问失败、更新/新增/并发冲突、删除幂等、非法身份及无值响应。它们不调用系统 Keychain，不依赖测试进程的 Keychain entitlement；仍需 macOS 才能编译和运行，也不替代真机读写测试。
9. 新增 commonMain `VaultSessionStore`，实现完整 `PikoSessionStore`：账号列表和逐账号会话/密码/压缩包密码均通过 SecretVault 持久化；键名带版本和账号 SHA-256，受保护内容内再校验账号身份。协程 Mutex 串行读改写，IO dispatcher 执行阻塞存储，不使用明文兜底或乐观内存缓存；损坏、未知版本或存储访问失败抛错，不当作已退出账号；解析错误不包含机密输入。退出登录保留压缩包密码。
10. 新增 `createIosSessionStore(service)` 将上述实现组合到 `IosKeychainVault`，要求 host 启动时创建单例并使用稳定的 app 自有 service；尚无 app host 实际调用。iOS 格式与桌面旧文件格式独立，没有声明跨平台账号文件迁移。新增 14 项 commonTest，覆盖重开、令牌刷新、账号隔离、账号列表、注销保留、并发更新、写入/删除失败、损坏/错误身份/未来版本/非法 UTF-8，以及未知字段兼容；测试同时归入桌面与 Native 源集。
11. 修正 iOS 稀疏缓存代码中 `Dispatchers.IO` 所需的 Native 扩展导入；仍需 macOS 编译确认。

12. 将桌面完整偏好实现提取为 commonMain `StoredPikoPreferences` + `PreferenceSettings`，桌面原入口改为委托适配，保留键名、默认值、迁移读取和现有下载目录重置语义。动态图块大小 flow 的注册表由 JVM synchronized 改为 StateFlow 不可变 Map + CAS。新增 14 项公共测试代码，包含重开、默认值、并发 flow、播放记录裁剪、机密分离和失败写入。
13. 新增 iOS `IosPreferenceSettings`（带命名空间的 NSUserDefaults）、`createIosPreferences`（上传凭据与 MetaTube token 单独走 Keychain），以及 `createIosAppDirectories`（Application Support/Piko、Caches/Piko、Documents/Piko）。NSUserDefaults 的磁盘刷新由 Foundation 异步完成，不将其声明为同步落盘事务；host 尚未调用这些工厂。
14. iOS 下载目录仅支持应用自有 Documents/Piko 及其子目录；存相对路径，每次启动按当前容器解析，不持久化容器绝对 URL。外部文件夹原位长期访问仍需文件选择器与 security-scoped bookmark 集成；下述单文件导入采用复制快照而非长期持有原始 URL。本轮增加 9 项公共路径测试、7 项真实 Foundation 偏好 Native 测试代码；Native 测试使用隔离 suite 和临时目录，不访问系统 Keychain，也不代表磁盘刷新或真机验证通过。

15. 新增 commonMain `AppDirectoryDownloadStorage`：使用 kotlinx-io 文件系统处理真实下载目录与独立暂存目录，查询不建目录；整文件沿用现有 iOS 稀疏缓存的原子导出，不另外引入阶段文件。`commit` 验证并保留已捕获的交付路径，目录偏好在导出途中变化时不将文件重新指向新位置。支持批量核对、空路径查询所选根、受限非递归删除和自底向上清理空目录。
16. 新增 `createIosDownloadStorage(directories, defaults)`，复用偏好中的相对下载位置，每次操作按当前容器解析，稀疏暂存在 Caches/Piko/downloads，文件在 Documents/Piko 或其子目录。App host 尚未调用，调度与 Native 缓存尚未在 iOS 实际联通；这不是后台 URLSession 或片段转封装实现。
17. 存储边界验证下载/缓存根彼此独立、相对名称、路径前缀和物理父路径，拒绝根目录删除及根下符号链接/路径重定向，保留非空目录与内容。公共文件系统 API 缺少 no-follow/openat，检查与使用之间并非原子操作；这些是应用私有目录，不宣称消除恶意并发替换、硬链接或挂载点等宿主文件系统问题。新增 21 项桌面真实文件系统测试、5 项 Foundation 与下载存储组合 Native 测试代码；Native 用例涵盖目录切换、容器重定位、暂存清理后重建与查询无副作用，仍待 macOS 执行。补齐下载、上传及文件缓存池公共代码的 Native `Dispatchers.IO` 扩展导入。

18. 新增 commonMain `AppDirectoryUploadSources`，以 `piko-app-upload:<相对路径>` 标识应用私有目录内的上传文件，不存 iOS 容器绝对路径；支持描述、CID 三段随机读取、按 offset 续传、保留相对层级的文件夹枚举。构造与查询不建目录，释放任务不删除源文件。路径名称采用可移植约束（排除冒号、反斜杠、空路径段及点路径段）；文件夹枚举跳过这些名称及链接，目录枚举 IO 失败时不返回截断的选择。
19. 新增 `createIosUploadSources(directories)` 与 POSIX 文件读取后端：应用自有 Documents/Piko 内普通文件使用独立 fd / `pread`，64 位偏移、最多 64 KiB 的读取缓冲，检查实际打开的文件类型，处理 EINTR、EOF、关闭和范围错误。它不导入外部文档、不持有 security-scoped bookmark、不实现文件选择器，也尚未接入 app host 或真实网络上传。
20. 新增 20 项桌面真实文件系统上传测试代码，包含容器迁移、3 GiB 以上稀疏偏移、独立续传源、文件变化/缩短、范围溢出、UTF-8/百分号名称、目录层级、链接与循环，以及释放后保留原文件。新增 8 项真实 Foundation/POSIX Native 测试代码，覆盖 iOS 工厂、元数据、随机读取、续传、EOF/关闭、链接与容器迁移；这些 Native 用例仍待 macOS 执行，不把桌面测试结果算作 iOS 后端验证。

21. 新增 commonMain `AppDirectoryUploadImporter`：外部来源以最多 64 KiB 的请求流式复制，调用方显式指定单文件大小上限，未知大小也按实际读取累计检查；每次导入分配独立 UUID 目录，同名文件不覆盖。源和暂存写入句柄全部关闭、取消检查通过后才原子发布，返回现有上传后端可读取的相对 URI；导入成功后由 host 管理保留期限，不随上传任务释放而删除。
22. 导入暂存目录必须与上传目录物理独立且位于同一文件系统；iOS 使用 Caches/Piko/upload-imports，最终文件在 Documents/Piko/imports/<UUID>/<文件名>。暂存不进入上传树，因此并发枚举不会选择未完成文件。失败或取消时尽力移除本次暂存文件与空目标目录，不递归删除其他内容；清理失败不掩盖原始异常。进程中断后的残留清理、磁盘配额和保留策略仍需 host 实现。
23. 新增 `importIosUploadDocument(directories, url, maxBytes)`：IO dispatcher 上获取并成对释放 security-scoped access，通过 NSFileCoordinator 协调普通文件读取，复制成应用私有快照后才返回。普通容器文件的 scope 获取可返回 false，实际读取/协调失败仍抛错；Kotlin 异常先在 Obj-C 回调内捕获，再回到 Kotlin 抛出。新增 14 项桌面真实文件系统测试、7 项 Foundation/POSIX Native 测试代码；尚未实现文档选择器 UI、外部文件夹/书签或真实上传集成。Native 用例和第三方文件提供器行为仍待 macOS/设备验证。

24. 新增 commonMain `createPikoAuxiliaryHttpClient(engineFactory, configure)`：MetaTube 与取图共用的**无鉴权**前台传输，`expectSuccess = false`（错误状态码交回调用方判断）、跟随重定向、请求/连接/读写 30s/15s/30s 超时，不安装日志、鉴权、Cookie 或证书覆盖；客户端由宿主持有并在其消费者之后关闭。`createIosAuxiliaryHttpClient()`（iosMain）用 Ktor Darwin 引擎（URLSession）实现，新增 `ktor-client-darwin` 与 `coil-network-ktor3` 依赖别名。新增 3 项桌面测试：404 不抛异常、重定向跟随两次请求、请求头不含 Authorization；这些验证的是共用配置，不验证 Darwin 引擎或真实 TLS/ATS 行为。
25. 新增 ui iosMain `installIosImageLoader(httpClient)`：Coil 在 iOS 没有 OkHttp/ServiceLoader，改为用上述客户端显式装配 `KtorNetworkFetcherFactory`，磁盘缓存沿用 Coil 的 iOS 默认位置（应用 Caches 下的 image_cache），不写入 Documents 或全局临时目录；客户端只借用不关闭。ui 的 iosMain 新增 `ktor-client-core` 与 `coil-network-ktor3` 依赖。该文件与 shared 的 Darwin 客户端都**尚未 Native 编译**：Windows 不能编译 iOS target，需 macOS 工作流链接 framework 时才能暴露签名或 API 不匹配。

26. 新增 commonMain 可移植加密：`PortableDigest`（SHA-256 / HMAC-SHA256 / PBKDF2-HMAC-SHA256）、`PortableAes`（AES-128/192/256 加密方向 + GCM，S-box 由 GF(2^8) 现算不手抄）、`PortableSyncCipher`（实现 `SyncCipher`，密文布局与 `JvmSyncCipher` 相同：IV(12)+密文+标签(16)）。随机数按平台各给一份 `secureRandomBytes`：jvmShared 用 `SecureRandom`，iosMain 用 `SecRandomCopyBytes`。`createIosServices` 的 `syncCipher` 由 null 改为 `PortableSyncCipher()`，压缩包密码同步在 iOS 上不再是缺失项。
27. 新增 `shared/src/desktopTest/kotlin/dev/piko/shared/sync/PortableSyncCipherTest.kt` 9 项对照测试：SHA-256 已知向量与 14 种长度对拍 `MessageDigest`，HMAC 7 种密钥长度对拍 `Mac`，PBKDF2 5 种密钥长度对拍 `SecretKeyFactory`（含非 ASCII 口令），AES-256 单块 NIST 向量（`8ea2b7ca516745bfeafc49904b496089`），GCM 7 种明文长度（0/1/15/16/17/1000/65537）对拍 `AES/GCM/NoPadding`，`PortableSyncCipher` 与 `JvmSyncCipher` 双向互解，标签/密钥/长度错误一律 null，盐与 IV 每次都换。首次运行抓到两个真问题：PBKDF2 的 U1 输入缓冲按 `DIGEST_BYTES+4` 分配，多出 16 个零字节（已改为 `salt.size+4`）；测试里 1M 个 `a` 的期望值抄错（实现输出 `cdc76e5c…12cd0` 才是 FIPS 向量）。修正后 9/9 通过。
28. 重写 `.github/workflows/ios-ipa.yml`：原文件里 xcodebuild 的续行是 `\\`（两个反斜杠），bash 会把转义后的 `\\` 当作实参传给 xcodebuild，命令必然失败；改为 bash 数组拼参数，不再用续行。同时补上 Android SDK 安装、Xcode 选择、证书/描述文件导入（可选）、`local.properties` 指向 SDK 源码（可选）、Gradle + `~/.konan` 缓存、`:ui:compileKotlinIosArm64` 预热、产物 SHA-256 汇总进 step summary、无产物时明确失败。

## 本机验证边界

- Windows 环境能配置 Gradle iOS targets；这不代表 Native 编译或测试通过。模拟器任务被宿主平台限制禁用。
- 同时请求 `:shared:desktopTest :ui:desktopTest :app:compileDebugKotlin :desktopApp:compileKotlinDesktop` 的验证在任务依赖解析阶段退出：`SDK location not found`。Android SDK 路径尚未配置，未据此判定源码编译失败。
- 独立桌面验证成功：`:shared:desktopTest :ui:desktopTest :desktopApp:compileKotlinDesktop`，退出码 0，耗时 6 分 48 秒，25 个任务执行。shared 报告 696 项、0 失败、6 跳过；UI 报告 99 项、0 失败、0 跳过，其中新增格式化测试 12 项通过。
- Keychain 接口提取后再次执行 `:shared:desktopTest :desktopApp:compileKotlinDesktop`：退出码 0，耗时 3 分 59 秒，20 个任务中 13 个执行、7 个保持最新；shared 仍为 696 项、0 失败、6 跳过。该结果验证共享接口迁移没有破坏桌面编译与 shared 回归，不验证 iosMain 后端。
- 会话实现加入后的验证：`:shared:desktopTest :desktopApp:compileKotlinDesktop` 退出码 0，耗时 3 分 19 秒，20 个任务中 9 个执行、11 个保持最新；shared 报告 710 项、0 失败、6 跳过，新增 VaultSessionStoreTest 的 14 项全部通过。桌面编译在首次运行已成功，复验为 UP-TO-DATE。该结果不验证 iOS Keychain 工厂或 Native 后端。
- 首次运行发现一处测试断言错误：JVM 协程栈恢复会将已脱敏异常复制到 cause，故外层 cause 不一定为 null；测试调整为逐层检查整条异常链均为不含输入的脱敏异常，然后全量复验通过。未放宽机密日志检查。
- 新增 commonTest 后 AGP 提示 shared 的 Android host tests 未启用；本轮公共测试通过桌面运行，Android host 与 Native 的执行结果仍未声明。
- 验证使用本地 `../ios-google-mirror.init.gradle` 解决当前主机访问 Google Maven 的问题；该临时网络配置未加入源码仓库。
- 仍有 Compose 插件 1.13.0-alpha01 与部分运行时依赖 1.12.0 的兼容性警告、Gradle 弃用警告；本轮桌面编译和测试通过不代表 Native 兼容性已确认。
- 偏好提取与沙盒路径公共层验证：`./gradlew -I ../ios-google-mirror.init.gradle :shared:desktopTest :desktopApp:desktopTest --tests dev.piko.desktop.DesktopPikoPreferencesTest --no-daemon --stacktrace` 退出码 0，耗时 4 分 21 秒，29 个任务中 22 个执行、7 个保持最新；shared 报告 733 项、0 失败、6 跳过，其中新增 StoredPikoPreferencesTest 14 项及 SandboxPathsTest 9 项全部通过；桌面原偏好测试 3 项全部通过。构建同时成功编译 shared、ui 和 desktopApp 的桌面代码。新增 7 项 Foundation Native 测试尚未执行，iOS 目录与 NSUserDefaults/Keychain 工厂尚未经 Native 编译验证。
- 下载存储最终复验：`./gradlew -I ../ios-google-mirror.init.gradle :shared:desktopTest :ui:compileKotlinDesktop :desktopApp:compileKotlinDesktop :desktopApp:desktopTest --no-daemon --no-configuration-cache --max-workers=2` 退出码 0，耗时 4 分 21 秒，29 项任务中 13 项执行、16 项保持最新。shared 共 754 项、744 通过、10 跳过、0 失败/错误；其中新增存储测试 21 项、17 通过、4 项符号链接测试因 Windows 创建链接权限被 JUnit 明确标记跳过。desktopApp 全量 103 项、99 通过、4 跳过、0 失败/错误；ui 与桌面应用编译成功。首次链接测试以提前返回记录为通过，已修正为真实的条件跳过并全量复验，未将未执行的断言计为通过。
- 新增 5 项下载工厂 Foundation Native 测试尚未执行；公共层和桌面验证不证明 iOS 文件系统实际行为、后台传输或容器恢复集成已经通过。
- 上传来源最终全量复验：`./gradlew -I ../ios-google-mirror.init.gradle :shared:desktopTest :ui:desktopTest :desktopApp:desktopTest :desktopApp:compileKotlinDesktop --no-daemon --no-configuration-cache --max-workers=2` 退出码 0，耗时 3 分 13 秒，34 项任务中 17 项执行、17 项保持最新。shared 共 774 项、761 通过、13 跳过、0 失败/错误，其中新增上传测试 20 项、17 通过、3 项符号链接测试因宿主权限被 JUnit 明确跳过；UI 99 项全部通过；desktopApp 共 103 项、99 通过、4 跳过、0 失败/错误。shared、ui 与 desktopApp 桌面编译成功；`git diff --check` 退出码 0。
- 新增 8 项上传来源 Foundation/POSIX Native 测试尚未执行；iOS 工厂与 `pread` 后端仍需 macOS 编译、模拟器运行及真实文件导入/网络上传集成验证。
- 外部文档导入最终全量复验：`./gradlew -I ../ios-google-mirror.init.gradle :shared:desktopTest :ui:desktopTest :desktopApp:desktopTest :desktopApp:compileKotlinDesktop --no-daemon --no-configuration-cache --max-workers=2` 退出码 0，耗时 4 分 23 秒，34 项任务中 19 项执行、15 项保持最新。shared 共 788 项、775 通过、13 跳过、0 失败/错误，其中导入新增 14 项全部通过、无跳过；UI 99 项全部通过；desktopApp 共 103 项、99 通过、4 跳过、0 失败/错误。shared、ui、desktopApp 桌面编译成功，JUnit XML 计数断言及 `git diff --check` 均退出码 0。此前定向导入测试也退出码 0；最终全量结果包含其后的全部修改与新增用例。
- 新增 7 项文档导入 Native 测试尚未执行；桌面测试验证的是公共复制实现，不验证 NSFileCoordinator、security-scoped access、Foundation API 签名或真实提供器授权。iOS 入口尚无 Native 编译证据，也没有文档选择器/host 接线。
- 辅助传输与取图装配复验：`./gradlew -I ../ios-google-mirror.init.gradle :shared:desktopTest :ui:desktopTest :desktopApp:desktopTest :desktopApp:compileKotlinDesktop --no-configuration-cache --max-workers=2` 退出码 0，耗时 3 分 4 秒，34 项任务中 17 项执行、17 项保持最新。shared 共 791 项、778 通过、13 跳过、0 失败/错误（新增 PikoAuxiliaryHttpClientTest 3 项全部通过）；UI 99 项、0 失败；desktopApp 共 103 项、99 通过、4 跳过、0 失败/错误。shared、ui、desktopApp 桌面编译成功。首次尝试把 `MockEngine` 实例当作引擎工厂传入，Kotlin 编译报类型不匹配；改为向工厂传入 `MockEngine` 对象并在 `configure` 中 `engine { addHandler { … } }` 后通过，未放宽断言。
- 本机 Gradle 仍无法直连 `dl.google.com`（TLS 超时），本轮所有构建都通过 `-I ../ios-google-mirror.init.gradle` 走镜像；该临时网络配置没有加入源码仓库。
- 新增的 Darwin 客户端、Coil 取图装配与 `ktor-client-darwin` / `coil-network-ktor3` 依赖均未在本机 Native 编译；macOS 工作流链接 ui framework 时才会暴露真实 API 与链接问题。
- 当前没有 macOS/Xcode 执行结果，也没有设备安装验证结果。

- 可移植加密验证：`./gradlew :shared:desktopTest --tests dev.piko.shared.sync.PortableSyncCipherTest` 9 项全部通过（0 失败）；随后全量 `:shared:desktopTest :ui:desktopTest :desktopApp:desktopTest` 共 1018 项、0 失败、17 跳过。这一轮只证明 JVM 上的算法正确性与两端密文互解，iOS 的 `SecRandomCopyBytes` 调用和 K/N 编译没有本机证据。
- `ios-ipa.yml` 的本地验证仅限于静态检查：PyYAML 解析通过（20 个步骤），每个 `run` 块单独 `bash -n` 语法通过，工作流里不再有 `\\` 续行或制表符。没有在 GitHub 上跑过，也没有 macOS 本机可跑。

## 出 IPA：`.github/workflows/ios-ipa.yml`

1. 推分支到有 Actions 的仓库后，**Actions → ios-ipa → Run workflow**。不填任何密钥就是无签名包：会走无签名 archive，再手工打 `Payload/` 成 `.ipa`，产物名 `Piko-ipa-<run>`，解压后先看 step summary 里的 SHA-256 与大小。
2. 想要签过名的包，先在仓库 Secrets 里配 `APPLE_TEAM_ID`、`APPLE_CERT_P12_BASE64`（导出 .p12 后 base64）、`APPLE_CERT_PASSWORD`，可选 `APPLE_PROVISION_PROFILE_BASE64` 与 `APPLE_BUNDLE_ID`。工作流会建临时 keychain 导入证书、按 UUID 安装描述文件，然后 `xcodebuild archive` + `exportArchive`。缺团队或证书时自动退回无签名路径，不会卡住。
3. 输入项：`configuration`（Release/Debug）、`export_method`（ad-hoc/development/app-store）、`pikpak_ref`（填 commit/分支则用 SDK 源码联调，留空用 Maven 正式版本）、`xcode_version`（默认 `latest-stable`）。
4. 无签名的 `.ipa` 不能直接装：用 AltStore/Sideloadly 之类重新签名，或 TrollStore 这类工具侧载。这一步是设备侧的事，工作流只负责产出结构正确的包。
5. 失败时先看 `ios-build-logs-<run>`：`archive-signed.log`/`archive-unsigned.log`/`export.log` 与 `shared/build/reports`、`ui/build/reports` 都在里面。最可能先炸的三处是 Android SDK 平台（`compileSdk 37` 在 runner 上不一定能装到）、Maven 上 `io.github.nihildigit:pikpak-kotlin` 的版本是否存在、以及 iosMain 里尚未 Native 编译过的 K/N 签名。

## macOS 验证工作流使用方式

1. 将本地移植分支推送到有 Actions 权限的仓库后，手动运行 **iOS Native validation**。
2. 默认 runner 为 `macos-15`；执行 arm64 模拟器测试时确认 runner 为 Apple Silicon。工作流会明确记录跳过测试的情况。
3. 工作流检出 `NihilDigit/pikpak-kotlin`，默认 revision 为 `6e1e54869acc284a6f253cdcaf7963c082d4f490`，通过 `pikpak.sdk.dir` composite build 使用其源码；支持输入其他明确的 revision。
4. 工作流安装 JDK 21 / 25，并将两个安装路径显式传入 Gradle；配置 Android SDK 以满足项目配置依赖。
5. 下载 framework 和测试报告，检查每个任务结果；工作流产物 **不是 IPA**。

## iOS 文档导入接入步骤（尚无 host 调用）

1. 启动时解析 `createIosAppDirectories()`，用同一个目录集合创建 `createIosUploadSources(directories)`。将后者注入真实服务容器。
2. host 的文件选择器回调保留选中的 NSURL，在生命周期拥有的协程中等待 `importIosUploadDocument`；`maxBytes` 必须采用产品明确设定的单文件上限，不根据未验证的外部元数据跳过实际读取限制。
3. 仅将成功返回的 URI 组装为 `UploadSelection(files = listOf(uri))`，然后交给既有上传入口；原始 provider URL 不放入任务表。多个文档逐一处理成功/失败，批量回滚和进度交互由 host 决定。
4. 在导入期间维持协程和所选 URL；读取间及发布前检查取消，协调器等待和单次阻塞读取本身并非可中断操作。新进程重新解析目录后即可使用持久 URI，不依赖旧容器绝对路径或原始文档仍然存在。
5. 需要真机验证 iCloud/第三方文件提供器、系统锁定、低磁盘空间、前后台切换、协调器失败、源文件变化和任务恢复；本地普通文件测试不替代这些验证。文件夹选择、原位长期访问仍需额外书签/授权生命周期实现。协调读取 API 参见 [Apple NSFileCoordinator 文档](https://developer.apple.com/documentation/foundation/nsfilecoordinator/coordinate%28readingitemat%3Aoptions%3Aerror%3Abyaccessor%3A%29)。

## iOS 取图与辅助请求接入步骤（尚无 host 调用）

1. host 启动时创建**一个** `createIosAuxiliaryHttpClient()`，长期持有；先调用 `installIosImageLoader(client)`，再把它传给 MetaTube 服务，避免各自新建客户端。
2. 该客户端不是 SDK 的登录客户端：不上鉴权头、不共享 Cookie、不替代下载/上传传输。MetaTube 与缩略图属于无鉴权的普通 GET。
3. 退出时按“图片请求 → MetaTube → 客户端”的顺序关闭；Coil 的 `SingletonImageLoader` 不要重复注册。
4. 若后续需要 ATS 例外、自签证书或代理，属于产品决定，必须在 host 侧显式配置并单独验证，当前实现沿用系统默认 TLS 行为。
5. 需真机验证大图内存占用、Caches 清理后的重新取图、切前后台与锁定状态下的请求行为；本地桌面测试只覆盖共用配置。

## iOS 平台适配层（`IosPikoPlatform`，尚无 host 调用）

`ui/src/iosMain/kotlin/dev/piko/ui/platform/IosPikoPlatform.kt` 实现了共享界面要求的全部 `PikoPlatform` 成员，只声明系统真有的能力：

1. 取色、更新、外部播放器、片段预览、磁力链接登记一律为「没有」：iOS 没有 Monet 取色与 App 内自更新，回环代理只活在本进程里，也不能由应用登记 `magnet:` 打开方式。
2. 下载位置不可选（固定在文稿目录 Piko 下），`displayName` 固定返回「文件 App / Piko」，`diskSpace` 用 `attributesOfFileSystemForPath` 读 `NSFileSystemFreeSize`/`NSFileSystemSize`。
3. 剪贴板、外部打开、分享接 `UIPasteboard` / `UIApplication.openURL` / `UIActivityViewController`（iPad 上先设 `popoverPresentationController.sourceView`）；日志导出写到 Caches/exports 再交给分享面板，没有可展示控制器时返回 false。
4. 文件上传走 `UIDocumentPickerViewController(documentTypes = ["public.item"], inMode = Import)` 多选，回调里对每个 URL 调 `importIosUploadDocument`，只把成功返回的 `piko-app-upload:` 标识交给界面。
5. 系统「减少动态效果」用 `UIAccessibilityIsReduceMotionEnabled()` 推到 `PikoMotionScale.systemScale`（0 或 1）；系统设置变更需要在宿主 scenePhase 回到前台时再调 `refreshSystemMotion()`。
6. `FullscreenDialog` 用 `DialogProperties(usePlatformDefaultWidth = false)` 铺满；`ListScrollbar` 与 Android 一样不画。

未完成/不实之处（不当作已实现）：

- 软键盘可见性恒为 false，需要宿主接 `UIKeyboardWillShow/DidHide` 通知后才有真实值。
- 沉浸式全屏的状态栏浅色与收放属于宿主 `UIViewController` 的 `preferredStatusBarStyle`/`prefersStatusBarHidden`，适配层没有越权修改。
- 文件夹上传的空实现：共享层还没有应用私有导入根的入口，`rememberFolderLauncher` 不回调，界面入口需要在宿主接入前隐藏。
- 该文件尚未经 Kotlin/Native 编译（Windows 无 macOS 工具链），`UIDocumentPickerMode` 常量、委托签名与 `NSURL(string:)` 构造需在 macOS 上核对。

## iOS 切片缓存（`IosClipCache`）与共用记录格式

1. 新增 `shared/src/commonMain/kotlin/dev/piko/shared/media/ClipRecordCodec.kt`：`ClipRecord` 的文本编解码（版本行 `v2` + 5 个字符串字段 + 5 个数字字段，字段内转义 `\`、`
`、`
`），以及两端共用的缓存文件名字符白名单 `clipCacheSafeName`。坏记录一律解码为 null（当作没缓存），不读出半条错的去建 handle。
2. 新增 `shared/src/iosMain/kotlin/dev/piko/shared/media/IosClipCache.kt`：一块一个文件放 `blocks/<内容哈希>/<偏移>`，记录走上面的文本格式放 `records/<键>`；总量超过 300 MB 时按 `NSFileModificationDate` 淘汰到 80%，记录最多 4096 条；读成功即刷新修改时间；写用 `NSData.writeToFile(atomically = true)` 落临时名再 `moveItemAtPath` 替到位。
3. 桌面端的 `FileClipCache` 仍用它自己的二进制记录格式（`FORMAT = 2`），本轮没有改动，避免动到已通过验证的桌面行为；两种格式互不读取，各自的目录也分开。
4. 本地可验证的部分已验证：`:shared:desktopTest --tests dev.piko.shared.media.ClipRecordCodecTest` 8 条测试全过（往返、转义、极端数值、版本不符、字段缺失、非数字、未知转义、文件名白名单）。
5. 未验证：`IosClipCache` 依赖的 `NSFileManager` 目录列表与属性读取、`NSData` 与 `ByteArray` 互转的指针写法，需在 macOS 上 Native 编译与模拟器跑一遍；NSFileType 判定目录、`setAttributes` 刷新时间也没有设备侧证据。

## iOS 入口、Xcode 工程与出包流水线

1. `ui/src/iosMain/kotlin/dev/piko/ui/ios/IosServices.kt`：`createIosServices(directories, appScope, keychainService)` 拼 `PikoServices`——Keychain 会话（`createIosSessionStore`）、`IosClipCache`、`createIosDownloadStorage`、`createIosUploadSources`、`FilePikoCacheStore`、`MetaTubeService` + `createIosAuxiliaryHttpClient`。`syncCipher` 传 `PortableSyncCipher()`（commonMain 可移植实现，与桌面 `JvmSyncCipher` 双向互解），`segmentDownloader` 传 `IosPikoSegmentDownloader()`。
2. `ui/src/iosMain/kotlin/dev/piko/ui/ios/IosVideoPlayerHost.kt`：`VideoPlayerHost.Detached` 工厂——本机文件直接 `fileURLWithPath`，否则 `preparePlayback` 取 `proxyUrl` 交给 `AVPlayerViewController` 全屏播放；`Detached` 是 final 类，所以用工厂函数不继承。
3. `ui/src/iosMain/kotlin/dev/piko/ui/ios/MainViewController.kt`：`MainViewController()` 装日志（support/logs）、建进程级 `PikoServices`，返回 `ComposeUIViewController { IosRoot(...) }`；首帧前 `runBlocking` 同步读外观，避免首帧跳主题。
4. `iosApp/project.yml`（XcodeGen）：app target `Piko`，iOS 16 起，`FRAMEWORK_SEARCH_PATHS` 指向 `ui/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`，`-framework PikoKit`；preBuild 脚本跑 `./gradlew :ui:embedAndSignAppleFrameworkForXcode`；`Configuration/Config.xcconfig` 放 `PIKO_TEAM_ID`/`PIKO_BUNDLE_ID` 占位。
5. `iosApp/Piko/PikoApp.swift`：SwiftUI `@main` + `UIViewControllerRepresentable` 包 `MainViewControllerKt.MainViewController()`，Swift 侧不持任何应用状态。
6. `iosApp/Piko/Info.plist`：`NSAllowsLocalNetworking`（本机回环代理）、`UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace`（Documents 里的下载可见）、方向与启动屏键。`ExportOptions.plist` 走 ad-hoc。
7. `.github/workflows/ios-ipa.yml`：macOS 15 + JDK 21 + XcodeGen，archive → `exportArchive` 出 `.ipa`；没有 `PIKO_TEAM_ID` 时退到 `CODE_SIGNING_ALLOWED=NO` 归档并手工打 `Payload/` zip，两条路都上传产物与失败日志。

本轮本地可验证的只有静态部分：两个 plist 用 `plistlib` 解析通过（Info 22 键 / ExportOptions 6 键），`project.yml` 与 workflow YAML 解析通过；`./gradlew :ui:compileKotlinDesktop :shared:compileKotlinDesktop` 仍 exit 0（iosMain 与 iosApp 不参与桌面编译）。

没有验证、需要 macOS 才能推进的部分：`MainViewController.kt`/`IosClipCache.kt`/`IosVideoPlayerHost.kt` 的 Kotlin/Native 编译（AVKit、`NSData.create`、`NSFileManager.setAttributes` 的 K/N 签名），`xcodegen generate` 后的工程能否链接 `PikoKit`，`xcodebuild archive` 是否成功，签名与描述文件是否匹配 `dev.piko.pikpak.ios`。

## iOS 文件夹导入（`AppDirectoryUploadFolderImporter` + `importIosUploadFolder`）

1. 共享层新增 `shared/src/commonMain/kotlin/dev/piko/shared/upload/UploadFolderImporter.kt`：`UploadFolderSnapshot`（`kindOf`/`listDirectory`/`openFile`）描述一份外部文件夹的只读快照，`AppDirectoryUploadFolderImporter.importFolder(folderName, maxBytesPerFile, maxTotalBytes, checkCancelled, snapshot)` 把它整棵复制到 `Documents/Piko/imports/<uuid>/<文件夹名>` 并返回 `piko-app-upload:` 标识，直接交给 `PikoUploadSources.listFolder` 就能列出全部文件（`relativeDir` 保留原来的子目录结构）。
2. 发布方式与单文件导入一致但对象是整个目录：先复制进 `caches/upload-imports/<uuid>` 暂存，`guard`/`stagingGuard` 再校验一次路径，然后一次 `SystemFileSystem.atomicMove(stage, target)`；不支持目录原子移动时退回逐文件搬、最后删暂存。失败与取消清掉自己分配的目录，不碰别人的导入。
3. 跳过规则：名字含 `/`、`\`、`:`、NUL，或为 `.`、`..`、空白的条目一律跳过；`.DS_Store` 跳过（Finder 留下的），其它点文件照原样导入；快照报 `OTHER` 的（符号链接等）不跟随。另有 20000 条条目与 32 层嵌套的上限，超过整次失败。
4. iOS 侧新增 `shared/src/iosMain/kotlin/dev/piko/shared/upload/IosUploadFolderImporter.kt`：`importIosUploadFolder(directories, url, maxBytesPerFile, maxTotalBytes)` 在 `startAccessingSecurityScopedResource` + `NSFileCoordinator.coordinateReadingItemAtURL` 里用 `NSFileManagerFolderSnapshot` 读外部树，读完还回授权；不保存外部 URL、书签或授权。
5. UI 侧 `ui/src/iosMain/kotlin/dev/piko/ui/platform/IosPikoPlatform.kt`：`rememberFolderLauncher` 不再是空实现——`UIDocumentPickerViewController(documentTypes = ["public.folder"], inMode = UIDocumentPickerModeOpen)`（文件夹只能用 Open 模式），`IosFolderPickerDelegate` 在回调里调 `importIosUploadFolder`，成功才把标识交给上传队列；总额上限 = 单文件上限 × `folderImportFiles`（默认 20）。
6. 本地已验证：`shared/src/desktopTest/kotlin/dev/piko/shared/upload/AppDirectoryUploadFolderImporterTest.kt` 8 条测试全过（嵌套整棵复制且能经 `AppDirectoryUploadSources.listFolder` 列出、`.DS_Store` 跳过而 `.hidden` 保留、`OTHER` 跳过、单文件超限整体失败且不留残渣、总量超限同样、复制中取消清掉暂存、非法文件夹名拒绝、暂存目录落在上传根内被拒）。全量 `:shared:desktopTest` 807 条 0 失败 13 跳过，`:ui:compileKotlinDesktop` 通过。
7. 未验证：`NSFileManager` 在安全作用域里的列目录行为、`contentsOfDirectoryAtPath` 的 `List<*>` 到 `String` 过滤、`coordinateReadingItemAtURL` 对文件夹 URL 的协调语义、`public.folder` 的 UTType 在 iOS 16 上的选择器行为，都要 macOS 编译 + 真机/模拟器；iCloud 未下载文件的按需下载、provider 在后台失效也没有设备侧证据。

## iOS 片段截取与转封装（`IosPikoSegmentDownloader`）

1. 新增 `shared/src/iosMain/kotlin/dev/piko/shared/download/IosPikoSegmentDownloader.kt`，实现 `PikoSegmentDownloader` 的两个入口：`extract` 用 `AVAssetExportSession` 的直通档（`AVAssetExportPresetPassthrough`）加 `timeRange` 截取，`remux` 同一直通档不设范围，把 MPEG-TS 原样搬进 MP4；`outputFileType = AVFileTypeMPEG4`。
2. 语义与另外两端一致：起点对齐到之前的关键帧（AVFoundation 直通导出自带这个行为）、不转码、先写 `目的路径.part`、成功后删旧目标再 `moveItemAtPath` 替到位，失败与取消清掉 `.part`。取消时 `invokeOnCancellation { session.cancelExport() }`，完成回调按 `AVAssetExportSessionStatusCompleted/Cancelled` 分流，其它状态把 `NSError` 包成 `NSErrorException` 交回调用方。
3. 进度：导出本身只给 `progress` 的比例，所以起一个 200 ms 的轮询协程上报 0 到 1，完成或异常时在 `finally` 里取消，不留后台协程。
4. 源地址两种都收：本机路径走 `fileURLWithPath`，回环代理的 http 地址走 `URLWithString`（AVURLAsset 能直接读 http，Android 的 MediaExtractor 不能，所以那边才要 `openRandomAccess`；这里不用那条路）。
5. 已知差别（已在代码注释里写明）：直通导出的 moov 落在文件尾，`shouldOptimizeForNetworkUse` 只影响读到的时机、不能前置；桌面 FFmpeg 与 Android MediaMuxer 都能前置 moov。下载完成即完整文件，播放不受影响。
6. 未验证：`AVAssetExportSession(asset,presetName)`、`timeRange` 的 `CValue<CMTimeRange>` 赋值、`session.error` 到 `NSErrorException` 的包装、`NSFileSize` 取属性这些 K/N 签名都要在 macOS 上 Native 编译才知道对不对；直通导出对 TS 源的 codec 兼容性也没有设备侧证据。

## 仍需完成的应用能力

- iOS host 源码与 Xcode 工程已写完（`MainViewController.kt`、`IosServices.kt`、`IosVideoPlayerHost.kt`、`iosApp/`），但一次都没有编译过：需要在 macOS 上跑 `xcodegen generate` + `xcodebuild` 才知道 K/N 侧 API 与链接是否成立。没有用网页壳或空白 App 代替。
- 会话、偏好、目录、下载落盘、上传来源、图片引擎、播放后端都已由 `createIosServices` 与 `MainViewController()` 接上；**尚未有任何真实登录/网盘/播放跑过**。片段截取与转封装已由 `IosPikoSegmentDownloader` 接上，压缩包密码同步改用 `PortableSyncCipher`（与桌面密文双向互解），但该实现只有 JVM 侧对拍证据，iOS 侧仍待 Native 编译。
- 上传的两个入口都已接到系统选择器：文件（`public.item` + Import 模式，多选）与文件夹（`public.folder` + Open 模式，单选），选中的东西都先导入沙箱再进队列。Rust/桌面/Android 上文件夹上传是原地读，iOS 必须整棵复制，因此有逐文件与总额两道上限，见下节。
- 验证偏好在真实 iOS 进程重启后的恢复、容器路径重定位和 Foundation 异步刷盘行为；当前 MetaTube token 读取失败后的空值兜底语义沿用桌面旧行为，尚未完成 iOS 设备锁定场景的交互处理。
- 在有正确签名/entitlement 的 iOS host 验证 Keychain 真正的读写、service/key 隔离、重启后解锁与访问失败行为。Native 单测中的系统边界是假实现，不代表设备 Keychain 集成已通过。
- macOS 编译、链接及 iosTest 验证，修复发现的 Native API 或依赖兼容问题。
- 模拟器验证登录、网盘、播放与文件传输，再构建设备 archive。
- 选择签名分发方式、签名导出 IPA、在设备安装验证并记录 SHA-256。未签名包仍需侧载工具重新签名。

## 外部条件

- 当前主机为 Windows；iOS 应用归档需 macOS/Xcode。
- GitHub CLI 尚未登录；尚未触发远程构建。
- 真机分发还需匹配的签名配置。以上条件和剩余应用实现均未标记为完成。
