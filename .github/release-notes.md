## 下载

| 系统 | 安装包 | 便携版与其他架构 |
|---|---|---|
| **Android** | [piko-{{version}}-arm64-v8a.apk](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-{{version}}-arm64-v8a.apk)（绝大多数手机、平板） | [通用包 universal](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-{{version}}-universal.apk)、[旧 32 位 armeabi-v7a](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-{{version}}-armeabi-v7a.apk)、[模拟器 x86_64](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-{{version}}-x86_64.apk) |
| **Windows** | [piko-windows-x64-{{version}}.msi](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-windows-x64-{{version}}.msi)（Intel / AMD）<br>[piko-windows-arm64-{{version}}.msi](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-windows-arm64-{{version}}.msi)（ARM 骁龙等） | [x64 便携版 .7z](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-windows-x64-{{version}}.7z)<br>[ARM64 便携版 .7z](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-windows-arm64-{{version}}.7z) |
| **macOS**（实验性） | [piko-macos-arm64-{{version}}.dmg](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-macos-arm64-{{version}}.dmg)（Apple 芯片，macOS 12+） | — |
| **Linux**（实验性） | [piko-linux-x64-{{version}}.AppImage](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-linux-x64-{{version}}.AppImage)（x64，glibc 2.38+） | — |
| **iOS / iPadOS**（实验性） | [piko-ios-arm64-{{version}}.ipa](https://github.com/NihilDigit/piko/releases/download/v{{version}}/piko-ios-arm64-{{version}}.ipa)（iOS 15+，未签名侧载包） | — |

- **Windows**：`.msi` 装入当前用户目录免管理员权限，`.7z` 解压运行 `Piko.exe`（Win10 可用 [NanaZip](https://apps.microsoft.com/detail/9n8g7tscl18r)），均支持应用内更新；首次遇到 SmartScreen 拦截请选择「更多信息 → 仍要运行」。
- **macOS**：支持应用内更新；安装包未经公证，首次打开被拦截时请在「系统设置 → 隐私与安全性」中选择「仍要打开」。
- **Linux**：需 Ubuntu 24.04、Debian 13、Fedora 39 或更新系统及硬件 OpenGL 驱动，`chmod +x` 后直接运行，支持应用内更新。
- **iOS / iPadOS**：未签名包需通过 AltStore、SideStore、Sideloadly 或 TrollStore 等工具自签侧载安装，更新时下载新 `.ipa` 覆盖侧载。

<details>
<summary>应用内更新文件与完整性校验说明</summary>

**其余附件无需手动下载**：`-app.zip`、`-image.zip`、`-files.json`、`-from-<旧版本>.zip`、`.AppImage.zsync` 与 `release.json` 供应用内更新使用，`linux-x64-<版本>.tar.gz` 供发行版打包，`mapping.txt` 用于还原崩溃堆栈。

附件均由 GitHub Actions 基于本 tag 构建，Android APK 经过签名：

- 校验和：`SHA256SUMS.txt`
- 构建来源：`gh attestation verify <文件> --repo NihilDigit/piko`
- SLSA Build L3：`slsa-verifier verify-artifact <文件> --provenance-path multiple.intoto.jsonl --source-uri github.com/NihilDigit/piko`

</details>
