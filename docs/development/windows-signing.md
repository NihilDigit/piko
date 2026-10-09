# Windows SignPath 签名

2026-10-09：Piko 已通过 SignPath Foundation 资格审核，测试证书可用，正式证书为 CSR PENDING。
release.yml 已接入两阶段签名：手动演练使用测试证书，tag 发布使用正式证书。

## 已配置的服务端信息

- Organization ID：`641251b4-b3c6-4958-88f3-a5e9349050cc`
- Project slug：`piko`
- 测试签名策略：`test-signing`；Submitters 包含 `CI builds`
- EXE 配置：`windows-launcher`，ZIP 根目录内的 `Piko.exe`
- MSI 配置：`windows-msi`，ZIP 根目录内的 `piko-windows-*.msi`
- GitHub Actions Secret：`SIGNPATH_API_TOKEN`（用户已配置，未读取其值）

## 接入顺序

1. 在 GitHub 托管的 x64、ARM64 runner 上生成 release 应用目录，完成 ProGuard 和 AOT 训练。
2. 使用 upload-artifact 单独上传 Piko.exe，以 artifact-id 提交 SignPath 请求。
3. 等待签名完成，下载并验证签名后的 EXE，供后续打包使用。
4. 从签名后的应用目录生成 MSI、files.json、app.zip、image.zip 和便携 7z。
5. MSI 的 transactional-upgrade.ps1 修改完成后，再上传 MSI 并签名。
6. 验证最终产物，再交给现有 release job 生成差分包、校验和及构建来源证明。

签名会改变文件内容，不能在生成更新清单后才替换 EXE。不能只签 MSI，否则便携版和增量更新
里的启动器仍无签名。第三方运行时 DLL 不使用项目证书重新签名。

## 实现约束

签名 Action 使用 `signpath/github-action-submit-signing-request@v3`，明确传入
artifact-configuration-slug，不依赖初始默认配置。使用 Action 默认 connector-url。
现有 upload-artifact@v6 已满足文档要求的 v4+，无需仅为签名升级上传 Action。
默认上传会自动包 ZIP，不应再给单个 EXE 或 MSI 手工套一层 ZIP。

签名结果先下载到 runner.temp/signed-launcher，再用 robocopy 保留时间戳复制完整应用目录到
runner.temp/signed-image/Piko，最后替换其中的启动器。通过 pikoSignedAppImage 属性指定
MSI 和更新包使用此目录，便携包也取同一目录。原始 Gradle 输出与签名后的输入分开，后续
依赖任务即使重建原始应用目录，也不会覆盖签名后的启动器。

Windows job 应显式配置 contents: read、actions: read。所有签名前置 job 都须使用 GitHub
托管 runner。API token 对应的 CI 用户须有签名策略的 Submitter 权限。

## 测试与正式发布

首次沿用 release.yml 的 workflow_dispatch 演练机制，使用 test-signing，只保留 workflow
产物，不创建 Release。tag 发布应使用 release-signing；正式证书未就绪时应失败，不能自动
退回测试签名或无签名发布。

验证范围包括：

- EXE 和 MSI 的签名完整性、签名证书指纹及时间戳；测试证书不受系统信任需与签名损坏区分。
- MSI 安装或解包后的 EXE、便携包中的 EXE、app.zip/image.zip 中的 EXE 内容一致。
- files.json 的启动器大小、SHA-256 与实际签名文件一致。
- 签名后全新安装、启动、旧版增量更新；保留现有 AOT 时间戳与安装事务行为。
- x64 和 ARM64 分别在对应 GitHub runner 上验证。

verify-windows-signature.ps1 使用系统 Authenticode 验证签名，并要求 EXE、MSI 使用同一证书。
测试证书只在 GitHub runner 的 LocalMachine 根证书存储中临时信任，验证完移除；CurrentUser
根存储会弹出交互式确认窗口，不适合 CI。正式签名
还要求时间戳。verify-windows-packages.ps1 检查清单与更新 ZIP，解开便携包并通过
msiexec /a 解开 MSI，比对各处启动器的 SHA-256。

本机 Gradle 原生缓存需要沙箱外访问；此前 native-platform.dll 加载失败与此有关。
首次演练完成后的真实签名结果以 Actions run 和 Step summary 中的 SignPath 请求链接为准。

首次完整演练的所有前置测试已通过，GitHub App 安装后两个架构的 EXE 均成功签名。
调试后续签名步骤可用 `gh workflow run release.yml -f version=9.9.9 -f windows-only=true`，
仅运行 Windows 构建和两个架构各自的桌面单测；Windows 产物保留在 workflow 中，不汇总
其他平台、不创建 Release。默认完整演练与 tag 发布仍要求全部前置测试。

## 官方依据

- [GitHub integration](https://docs.signpath.io/trusted-build-systems/github)
- [Artifact configuration](https://docs.signpath.io/artifact-configuration/)
- [Configuration examples](https://docs.signpath.io/artifact-configuration/examples)
- [Signing Action v3 inputs](https://github.com/signpath/github-action-submit-signing-request/blob/v3/action.yml)
- [CI users and token lifecycle](https://docs.signpath.io/users/#ci-users)
