# 无头截图与交互回归（`:shots`）

`:shots` 是面向界面回归与自适应布局验证的内部开发工具，不随正式客户端发布；日常开发中仅在自动化验证流程或明确要求时运行，常规界面修改无需补写截图步骤（原因见根目录 `AGENTS.md`）。

该模块在无头（Headless）环境下直接启动完整的共享界面（`PikoApp`，复用桌面端真实状态层与平台能力实现），通过 OkHttp 拦截器挂载内存假服务端（`FakePikPak`），无需登录真实账号，亦不依赖显示器或 Windows 宿主（Linux 与无头 CI 服务器均可运行），可按任意窗口像素尺寸与深浅主题渲染出 PNG 图像。

## 常用命令

```bash
./gradlew :shots:renderAll                                   # 渲染全套标准状态图至 build/shots/ 并与上一版逐像素比对（16 核 6~8 进程约 6 分钟）
./gradlew :shots:renderAll --args="--only drive-trash"       # 仅渲染指定前缀的截图，基线挪动与差异清单亦仅针对该子集
./gradlew :shots:renderAll --args="--keep-baseline"          # 反复微调同一处界面时保留既有基线不覆盖，始终与修改前初版比对
./gradlew :shots:run --args="shot starred --size 1100x800 --click 我的 --click 星标 --wait Dune"
./gradlew :shots:run --args="texts --click 任务"              # 不出图，打印当前画面语义树中的全部文本与无障碍描述，供编写 --click 定位目标
./gradlew :shots:run --args="adaptive-prototypes"            # 在 build/shots/adaptive-prototypes/ 生成自适应工作区静态设计原型及 index.html 对照页
```

### 自适应框架交互验证套件

除静态状态表外，`:shots:run` 内置多组端到端状态机与窗口尺寸往返验证场景（均运行在真实 `PikoApp` 与 `FakePikPak` 之上，用于检验布局切换、状态保留与组件组合，不替代物理触屏与硬件解码真机手测）：

- `./gradlew :shots:run --args=verify-file-building-blocks`：验证列表/海报悬停勾选、鼠标切换触屏后的首次长按、部分解析清单（`truncated`）裁剪拦截保护、树形行空槽消除与多级缩进、虚拟保存结果区文件操作、非模态管理 Sheet、宽窄/低高度切换与保存快捷键，以及多链接平铺森林与同名来源独立勾选（输出至 `build/shots/file-building-blocks/`）。
- `./gradlew :shots:run --args=verify-import-panel`：独立验证多链接管理面板右上角按需展开搜索、底部固定保存栏与宽窄切换（输出同目录 `10-panel-wide.png`、`11-panel-narrow.png`，不提交保存以免完成回执遮挡面板）。
- `./gradlew :shots:run --args=verify-adaptive-workspace`：在同一个 `PikoApp` 实例中动态往返调整窗口尺寸，验证宽窗口常驻标签栏与窄窗口实际页面缩略图总览（`WorkspacePageSnapshot`）、重复打开总览不触发后台重解析、来源勾选与虚拟保存树投影、标签切换恢复及蜗牛模式入口（输出至 `build/shots/adaptive-framework/`；注：`AppScene` 底层画布按场景最大宽高一次性分配，调用 `resize` 改变窗口约束并在落盘时按当前窗口尺寸裁切）。
- `./gradlew :shots:run --args=verify-shared-details-tasks`：验证网盘属性与任务详情共用 `DetailHost`（宽窗口 `FloatingPanel` / 窄窗口 `PikoSheet`）、任务列表满宽列对齐、批量勾选期间顺序锁定、Escape 关闭与蜗牛模式（输出至 `build/shots/shared-details-tasks/`）。
- 另有 `verify-workspace-origins`（资料库、搜索、外链与任务定位的返回来源恢复）、`verify-workspace-imports`（分享与批量草稿会话重建）、`verify-workspace-preview` 与 `verify-workspace-target-picker`。

## 运行机制与确定性设计

- **打包后多进程分片渲染（`renderAll`）**：`renderAll` 任务会先执行 `installDist` 产出独立发行包再调用其 jar 运行 `all`，避免其他终端同时编译模块替换 `build/classes` 引发 `NoClassDefFoundError`（跑全套严禁使用 `:shots:run`）。由于单个 JVM 进程内所有场景共享同一条 Swing EDT 只能串行出帧，`all` 默认按 `--jobs`（CPU 核心数一半，上限 `8`；`--jobs 1` 则在当前进程串行执行）拉起多个子进程，按场景序号取模分片（`--shard i/n`，避免连续分段导致重耗时章节集中在同一子进程），汇总输出至同一目录。
- **像素级基线差异比对**：`all` 渲染开始前将输出目录中上一版 PNG 移至 `build/shots-baseline/`，渲染完毕后逐像素比对并生成 `build/shots/changes.md`（列出变化、新增、删除与失败项，变化项精确给出差异像素数与包围盒坐标），同时在 `build/shots/diff/` 下生成「旧图 / 新图 / 差异高亮」三栏并排对照图。存在任何渲染失败时进程以退出码 `1` 终止。
- **画面判稳与动效归零**：每个场景默认注入独立的 `MotionDurationScale = 0`（不触碰连接设置页「减少动画」开关的平台级 `motionScale`）并关闭输入框光标闪烁（`LocalCursorBlinkEnabled`）。开场、显式 `--pump` 与收尾均采用「连续 `500ms` 逐像素完全不变」作为画面稳定判据（上限开场 `3s`、收尾 `3s`；`500ms` 刻意大于加载指示器 `200ms` 延迟、链接解析 `350ms` 防抖及压缩包准备 `400ms` 延迟，**严禁调小该阈值**；若自上次判稳后无任何输入、时间推进或 `Step.Run` 状态变更则直接短路通过）。需抓拍动画中途帧的场景（如信息流双击星爆 `feed-star`、长按倍速浮标 `feed-boost`）显式声明 `motion = true`，按真实速率推进指定时长。指针悬停（`--hover`）固定停留 `1.2 秒`；点击按钮后若下方元素改变可能意外触发延迟工具提示，须插入 `Step.MoveTo` 移开指针以确保出图确定性。
- **稀疏临时文件与孤儿目录回收**：每张图拥有独立的 `%TEMP%/piko-shots/<主进程PID>/<图名>/` 临时目录，单图渲染结束立即删除，主进程退出（含 `Ctrl+C`）时终止全部子进程并清理整个 PID 目录（子进程监控父进程存活，父进程被强杀时自动退出；残留孤儿目录在下次启动时按 PID 存活状态自动清扫）。预置已完成下载任务的占位大文件必须以 `StandardOpenOption.SPARSE` 稀疏文件打开并仅写入末尾 1 字节（NTFS 下普通 `RandomAccessFile.setLength` 会按十余 GB 真实分配磁盘簇，曾导致跑完一套占满系统盘）；启用稀疏文件后全套并行峰值磁盘占用仅约 `150 MB`。
- **已知非确定性噪声与环境约束**：
  1. 连跑两次仍可能出现像素差异的三类固有噪声：设置页「上次同步于 HH:MM」读取系统真实时钟；信息流章节走真实 `libmpv` 与真实时间；Material 3 的 `LoadingIndicator` 不随 `MotionDurationScale = 0` 停在固定相位（如解压卡片与登录中状态）。审阅 `changes.md` 时将这三类视为预期噪声。其余要素（固定按图名派生的下载路径、固定剩余磁盘空间、渲染单图瞬间才计算的相对时间戳、通过语义 `ScrollBy` 精确像素距离滚动而非依赖事件间隔算速度的滚轮事件）均已做确定性锚定。
  2. 步骤指令支持 `--click`、`--right-click`、`--long-press`（按住 `800ms`）、`--hover`、`--drag`、`--release`、`--key`、`--type`（支持直接输入中文）、`--wait`、`--pump`。窗口窄于 `840dp` 或显式传 `--mobile` 时左键模拟触屏（单击直接打开、长按进多选；移动形态绑定 `VideoPlayerHost.InApp`，桌面形态绑定 `Detached`）；桌面模式左键单击仅选中，打开需再接 `--key Enter`。懒加载列表中尚未组合进视口的远端条目无法通过文本直接找到，需先通过搜索缩小列表。
  3. 标准清单 `standardSet` 位于 `shots/.../Main.kt`，按 `docs/development/ui-states.md` 章节编排（前缀 `shell-`、`drive-`、`tabs-`、`panel-`、`dlg-`、`transfers-`、`pages-`、`update-`、`feed-`、`player-`、`task-`），通过 `tri()` 同时生成桌面 `1440×900`、手机 `400×860` 与平板 `1280×800` 三种形态（另含桌面窄窗 `700×800` 与手机横屏 `860×400`；需模拟紧凑标题栏右上角三枚窗口按钮挤占顶栏空间时开启 `--caption`）。
  4. 假数据种子位于 `ShotEnv.kt` 的 `FakePikPak.seed()`（单图定制数据通过 `Shot.extraSeed` 与延迟求值的 `Shot.prefs` 注入；应用内更新替换为 `ShotUpdater`；主播放器仅通过 `PlayerPreview` 渲染控件层，信息流与片段面板建立真实 mpv 预览后端但画面为黑底；网络缩略图不发起公网请求，图片查看器使用本地合成图像）。Linux 环境下传递含中文的 `--args` 参数时必须确保 UTF-8 Locale（如 `LC_ALL=C.UTF-8`），且因无微软雅黑字体，字宽与 Windows 存在细微排版差异。
