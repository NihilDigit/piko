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
| Mac（Apple 芯片，实验性） | [piko-macos-arm64-{{version}}.dmg](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-macos-arm64-{{version}}.dmg) |

`.msi` 安装至当前用户目录，无需管理员权限，支持应用内更新。`.zip` 为便携版，解压后运行 `Piko.exe`，仅在改动较小的版本支持应用内更新，其余版本需手动下载。

Windows 安装包未经代码签名，首次运行时 SmartScreen 会拦截，选择「更多信息」→「仍要运行」。

Mac 版为实验性版本，需 macOS 12 及以上，有新版本时需手动下载安装。安装包未经公证，首次打开会被拦截：在「系统设置」→「隐私与安全性」中选择「仍要打开」。

**以下文件无需下载**：`-app.zip` 与 `-files.json` 供应用内更新使用，`mapping.txt` 用于还原崩溃堆栈，`SHA256SUMS.txt` 与 `multiple.intoto.jsonl` 用于校验。

## 校验

附件均由 GitHub Actions 基于本 tag 构建，APK 经过签名。

- 校验和：`SHA256SUMS.txt`
- 构建来源：`gh attestation verify <文件> --repo NihilDigit/piko`
- SLSA Build L3：`multiple.intoto.jsonl`

  ```
  slsa-verifier verify-artifact <文件> --provenance-path multiple.intoto.jsonl --source-uri github.com/NihilDigit/piko
  ```
