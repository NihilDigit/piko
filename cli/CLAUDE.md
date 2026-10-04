# 开发用 CLI

`:cli` 是开发工具，不随应用发布。`./gradlew :cli:installDist` 后执行 `cli/build/install/piko-cli/bin/piko-cli`：

- `snapshot -o <文件> [--root <路径>] [--depth <层数>] [--deep <名字,…>]`：只读列网盘目录，存成快照，
  只含文件名、类型、大小。会话取自 `~/.piko-dev`，token 轮换后写回，与开发版（`:desktopApp:run`）共用，不碰安装版的 `~/.piko`。
- `dryrun <快照> [--path <前缀>] [-o <文件>]`：离线对快照跑网盘页的解析流水线，逐行写出原名与界面上的样子。
  调的是 `DriveScreenState` 同一组函数（`analyzeDriveFolder`、`buildDriveItems`、`describeDriveFolder`）。
- `ls <路径>`：只读列一个目录，打印每项的 `params`。列目录接口在这里带回来源链接（离线下载的磁力、
  分享转存的 `mypikpak.com/s/` 链接）与视频的 `duration`、`width`、`height`，不必另查详情。
- `parse <文件名>…`：单独解析文件名。
- `share <分享链接> [--pass <提取码>] [--restore]`：只读列出分享的顶层。`--restore` 实测转存：把分享里
  最小的一个文件转存进根目录下新建的 `piko-probe-restore-*`，等任务结束后列出结果，再永久删除该文件夹。
  实测结论：文件直接落在目标目录下，不带分享里的上级目录；任务秒级完成；任务 params 里没有新旧 id 的映射。
- `archive-bench`、`archive-write-bench`：归档的并发实测，见 `docs/development/archive.md`。

Git Bash 会把以 `/` 开头的参数改写成 Windows 路径，传网盘路径时前面加 `MSYS_NO_PATHCONV=1`。

快照含真实文件名，放在仓库外，不要提交。改解析规则后重跑 `dryrun` 对比即可，不必重新请求网盘。
