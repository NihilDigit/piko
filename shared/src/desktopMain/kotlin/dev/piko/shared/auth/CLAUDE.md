# 桌面端机密存储

机密（会话与密码）按账号合成一份交给平台保管：Windows 是 DPAPI（FFM 直调），macOS 经 `/usr/bin/security` 进登录钥匙串
（不用 SecItem 直调：ad-hoc 签名每版都变，直调每次更新后都弹授权框），Linux 经 libsecret 进 Secret Service
（Flatpak 里自动走 portal），都用不了时退回 0600 文件。分层读取先读兜底文件：留在那里的只可能是平台存储锁着时写下的，
比平台里那份新。

本目录的实现与 CLI 共用；FFM 那几份在 desktopApp 的 `secrets` 包：shared 按 JDK 21 编译，FFM 在那里还是预览 API。
v0.10.0 起的明文文件在首次启动时迁移，读回一致才删。
