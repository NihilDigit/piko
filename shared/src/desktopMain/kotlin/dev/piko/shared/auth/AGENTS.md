# 桌面端机密存储

账号机密（OAuth 会话令牌、账号密码与压缩包密码）按账号合并为单份载荷交由各桌面操作系统原生保管设施持久化：

- **Windows（DPAPI 加密文件）**：通过 FFM 直调系统 `CryptProtectData` / `CryptUnprotectData` 将密文落盘（`DpapiSecretVault`）。密文文件创建时即显式绑定仅允许当前 Windows 账户访问的受限 ACL，文件名附带当前 Windows 账户标识（`<key>.<账户标记>.bin`），外层目录沿用继承权限——当便携版所在目录先后被同一台或多台机器上的不同操作系统账户使用时，各账户读写各自独立的密文文件，互不覆盖亦不越权访问。Windows **不配置明文文件兜底层**（`FileSecretVault` 豁免 `PlainFileVault`）：DPAPI 为内核/系统级组件，不存在外部守护进程暂时未解锁的过渡态，若加密失败属于真实系统异常，直接向调用方报错而绝不在同目录回退写入明文。
- **macOS（登录钥匙串）**：通过调用 `/usr/bin/security` 命令行工具读写系统登录钥匙串（`MacKeychainVault`）。**刻意不经 FFM 直调 `SecItem` C 接口**：本应用在无付费开发者证书环境下采用 ad-hoc 代码签名，每次版本更新后二进制的代码签名哈希均会变化，直调 `SecItem` 会触发 macOS 钥匙串在每次更新后反复弹出系统授权询问框。
- **Linux（Secret Service 与 0600 兜底）**：通过 FFM 调用 `libsecret` 接入 `org.freedesktop.secrets`（`SecretServiceVault`，在 Flatpak 沙箱内由系统自动路由至 Portal）；无桌面环境或未安装 Secret Service 守护进程时回退至权限为 `0600` 的本机文件（`PlainFileVault`）。非默认数据根目录（如开发版 `~/.piko-dev`）自动通过 `PikoHome.secretNamespace` 为钥匙串与 Secret Service 条目名追加命名空间后缀，与正式版凭据彻底隔离。

## 分层读取优先级与自动提升（`LayeredVault`）

针对 macOS 钥匙串与 Linux Secret Service 这类可能因系统刚唤醒或未解锁而暂时写入失败的外部服务，`LayeredVault` 配有 `0600` 文件兜底层，并严格执行**先读兜底层、后读主存储层**的逆直觉顺序：

- 正常写入主存储成功或提升成功时均会立即删除兜底副本；因此一旦兜底层中存在记录，必然是此前某次令牌轮换刷新时主存储处于锁定状态而写入的最新凭据，主存储中残留的则是已被服务端作废的旧 `refresh_token`。若先读主存储，解锁后读回旧令牌会导致服务端判定令牌重放失效而将用户强制退回登录页。
- 读取命中兜底层且当前主存储可用时，自动触发 `promote`：将凭据写入主存储并立即读回逐字节比对一致后，再删除兜底副本；若主存储仍处于锁定状态则保留原处，待下次读取再试。

## 模块分层与历史迁移

本目录内的抽象与实现（`SecretVault`、`LayeredVault`、`MacKeychainVault`、`PowerShellDpapiVault` 等）由桌面应用与 `:cli` 开发工具共用；直接调用 JDK FFM 的原生实现（`DpapiSecretVault`、`SecretServiceVault`）则放置在按 JDK 25 编译的 `desktopApp/.../secrets/` 包下，原因在于 `shared` 模块须兼容 Android 编译目标而停留在 JDK 21 字节码级别（FFM 在 JDK 21 中仍属预览 API）。`v0.10.0` 早期的遗留明文凭据文件在首次启动时自动迁入机密存储，读回核对一致后删除旧文件。
