# 开发用命令行工具（`:cli`）

`:cli` 是面向开发与协议实测的内部命令行工具，不随正式客户端分发。执行 `./gradlew :cli:installDist` 后，通过 `cli/build/install/piko-cli/bin/piko-cli` 调用（进程启动时自动将 `piko.home` 指向 `~/.piko-dev`，与桌面开发版 `:desktopApp:run` 共用登录会话与令牌轮换写回，绝不触碰系统中已安装正式版的 `~/.piko`，防止双方各自轮换 `refresh_token` 导致正式版登录态失效）：

- `snapshot -o <文件> [--root <路径>] [--depth <层数>] [--deep <目录名,…>]`：只读遍历网盘目录树并将目录结构保存为本地 JSON 快照（仅记录文件名、类型与字节大小）。默认从根目录 `/` 起遍历 `1` 层，列在 `--deep` 中的直属子目录则递归遍历到底。
- `dryrun <快照> [--path <路径前缀>] [--visited] [-o <文件>]`：在完全离线的状态下对本地快照运行网盘页的文件名解析与折叠分组流水线（直接调用 `DriveScreenState` 所用的 `analyzeDriveFolder`、`buildDriveItems` 与 `describeDriveFolder`），逐行输出原始文件名与界面展示结构；`--visited` 模拟各子目录均已被浏览过，以子目录内部文件推导外层文件夹作品标题。修改解析规则后重跑 `dryrun` 对比输出差异即可，无需反复请求线上网盘。
- `score <快照>`：以快照中已人工整理规范的文件夹名称作为标准标注，统计子视频文件的番号识别准确率并列出误判样本。
- `ls <网盘路径>`：只读列出指定网盘目录，逐项打印文件名及服务端在列目录接口直接带回的 `params`（包含离线下载磁力链接、分享转存 `mypikpak.com/s/` 来源链接以及视频的 `duration`、`width`、`height`，验证无需逐个查详情即可获得的字段）。
- `media <文件ID>`：查询单个文件的详情并打印原画与各档转码流的分辨率及媒体直链（**注意：媒体直链属于敏感访问凭据，严禁粘贴进公开日志或 Issue**）。
- `parse <文件名>…`：对传入的一个或多个文件名单独执行 `parseMediaName` 并打印结构化解析结果。
- `bench <路径或文件夹ID> [--count <段数>] [--seconds <秒>] [--sequential] [--playing] [--background-first] [--store <目录>]`：只读流式吞吐实验，对指定目录子树内带 `720P` 转码的视频按信息流策略并发或顺序预取开头（默认 8 段各 5 秒），测量 SDK 实际吞吐率、并发在途连接数及启用本地 `FileClipCache`（`--store`）前后的命中表现；`--playing` 额外开启一路前台持续读取模拟正在播放的片段。
- `share <分享链接> [--pass <提取码>] [--restore]`：默认只读列出分享链接的顶层目录内容。附加 `--restore` 时执行真实转存探测：挑选分享中体积最小的一个文件转存至网盘根目录下临时新建的 `piko-probe-restore-*` 文件夹，等待异步转存任务完成后列出产物并永久删除该临时测试文件夹。（实测结论：转存文件直接落在目标目录根下，不保留分享链接中的外层父目录结构；转存任务秒级完成；任务 `params` 中不提供原分享文件 ID 到新网盘文件 ID 的映射表。）
- `archive-bench /Dramas [--parallel <1,2,4,8,16,32>] [--count <32>] [--timeout <15>] [--api-rate <1000>] [--combined]` 与 `archive-write-bench /Dramas [...]`（配套清理命令 `archive-write-cleanup`）：归档文件详情查询、CID 首尾抽样与可信清单并发写入基准实测（硬性限定仅允许在 `/Dramas` 测试路径内新建独立探测子目录并仅清理自身新建目录），实测数据汇总见 `docs/development/archive.md`。

## 使用注意事项

1. **Git Bash 路径转换拦截**：在 Windows Git Bash 中传入以 `/` 开头的网盘路径参数时，Git Bash 会自动将其改写为本机 Windows 磁盘路径；执行命令前必须在最前方加上 `MSYS_NO_PATHCONV=1`。
2. **快照隐私保护**：`snapshot` 导出的 JSON 快照包含用户网盘内的真实文件名，**必须存放在代码仓库目录之外，严禁提交入库**。
