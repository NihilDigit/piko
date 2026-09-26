## 下载

| 设备 | 下载 |
|---|---|
| Android 手机、平板（绝大多数设备） | [piko-{{version}}-arm64-v8a.apk](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-{{version}}-arm64-v8a.apk) |
| Android，不确定设备架构 | [piko-{{version}}-universal.apk](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-{{version}}-universal.apk) |
| Android，旧款 32 位设备 | [piko-{{version}}-armeabi-v7a.apk](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-{{version}}-armeabi-v7a.apk) |
| Android 模拟器、x86 设备 | [piko-{{version}}-x86_64.apk](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-{{version}}-x86_64.apk) |
| Windows 电脑（Intel、AMD 处理器） | [piko-windows-x64-{{version}}.msi](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-windows-x64-{{version}}.msi) |
| Windows on ARM（骁龙等 ARM 处理器） | [piko-windows-arm64-{{version}}.msi](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-windows-arm64-{{version}}.msi) |
| Windows 电脑，便携版 | [piko-windows-x64-{{version}}.zip](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-windows-x64-{{version}}.zip) |
| Windows on ARM，便携版 | [piko-windows-arm64-{{version}}.zip](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-windows-arm64-{{version}}.zip) |

`.msi` 安装至当前用户目录，无需管理员权限，支持应用内更新。`.zip` 为便携版，解压后运行 `Piko.exe`，仅在改动较小的版本支持应用内更新，其余版本需手动下载。

Windows 安装包未经代码签名，首次运行时 SmartScreen 会拦截，选择「更多信息」→「仍要运行」。

**以下文件无需下载**：`-app.zip` 与 `-files.json` 供应用内更新使用，`mapping.txt` 用于还原崩溃堆栈。

## 校验

附件均由 GitHub Actions 基于本 tag 构建，APK 经过签名。每个附件的 SHA-256 显示在附件列表中，APK 的校验和另见 `SHA256SUMS.txt`。
