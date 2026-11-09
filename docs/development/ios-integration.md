# iOS 集成

## 结构与契约

- 最低部署目标为 iOS/iPadOS 15.0，Swift 宿主与 Kotlin 原生二进制保持一致。系统 API 必须兼容该基线；高版本 API 需提供可用性检查与低版本实现。
- `iosApp` 是 SwiftUI 宿主，通过 XcodeGen 的 `project.yml` 生成工程，调用 `ui` 导出的静态 `PikoKit` framework。
- `shared` 与 `ui` 启用 `iosArm64`、`iosSimulatorArm64`；JVM 专用实现仍保留在 JVM 源集。共享短临界区使用可重入的 `PikoLock`，不能在持锁期间挂起协程。
- 账号索引继续使用主干的 SQLite WAL 模型。iOS 平台工厂需实现相同的连接生命周期、游标关闭、事务和账号归属校验，不能退回旧的全盘 JSON 常驻堆实现。
- `StoredPikoPreferences` 是桌面与 iOS 的共享偏好实现。桌面适配器继续使用原设置键与机密存储；新增偏好需要同时检查 Android 实现与共享实现。
- 外部播放器通过 `ExternalVideoPlayer.requiresDirectUrl` 声明是否接受本机回环代理；需要跨应用直链的实现不得取得仅供当前应用会话持有的代理 URL。
- 共享显示格式继续经 `PikoLocale`、`tr` 与 `enCount` 提供五种语言，不依赖 JVM 的 `Locale` 或 `String.format`。

## 验证边界

跨平台基础验证：

```bash
./gradlew :shared:compileCommonMainKotlinMetadata :ui:compileCommonMainKotlinMetadata :app:compileDebugKotlin :desktopApp:compileKotlinDesktop :shared:desktopTest :ui:desktopTest
```

Windows 上的 metadata、Android 和 Desktop 验证不等于 Apple 原生链接通过。iOS API 类型检查、SQLite 系统库绑定、模拟器测试和 framework 链接必须在 macOS 执行：

```bash
./gradlew :shared:iosSimulatorArm64Test :ui:iosSimulatorArm64Test
```

`.github/workflows/ios-native.yml` 为主干、Pull Request 和手动验证入口。`.github/workflows/ios-ipa.yml` 仅允许 `ios-v*` tag 与手动触发，负责归档和可选签名。签名所需团队、证书、描述文件不保存在源码中；未签名 IPA 不代表可以直接安装。

原生 CI 还构建最低部署目标为 15.0 的 Swift 宿主，检查产物的 `MinimumOSVersion`，并在 runner 已安装的最旧可用 iPhone 模拟器上启动。现代 Xcode 的模拟器运行时不一定包含 iOS 15；部署目标检查和较新模拟器通过不能替代 iOS 15 真机验收。最低版本验收需覆盖冷启动、登录、目录浏览、文件选择、上传下载、外部播放器跳转和前后台恢复。

从 PR #47 集成时不引入分支临时状态文档或覆盖主干 README，不回退主干的目录索引、偏好键、固定标签、归档引用与多语言能力。
