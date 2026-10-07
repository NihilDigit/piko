# 截图（:shots）

只在全自动工作流里或明确要求时才跑，理由见根目录 CLAUDE.md。

`:shots` 是开发工具，不随应用发布：无头运行整个应用（`PikoApp`，与桌面入口同一套界面、状态与平台实现），
数据来自假的 PikPak 服务端，按任意窗口尺寸与深浅主题出 PNG。不必开真实账号，
也不用在 Windows 上：Linux 与没有显示器的机器同样能跑。

```bash
./gradlew :shots:renderAll                                   # 一整套，写到 build/shots/，并与上一版比对；16 核 6 到 8 个进程约 6 分钟
./gradlew :shots:renderAll --args="--only drive-trash"       # 只出名字以它开头的几张，基线与清单也只管这几张
./gradlew :shots:renderAll --args="--keep-baseline"          # 反复改同一处时，始终对照改之前的那一版
./gradlew :shots:run --args="shot starred --size 1100x800 --click 我的 --click 星标 --wait Dune"
./gradlew :shots:run --args="texts --click 传输"              # 打印界面上的文本，找 --click 的目标用
```

- `renderAll` 先 `installDist`，再用装出来的 jar 跑 `all`：别处同时在编译界面时，`gradle run` 直接读的
  `build/classes` 会被换掉，跑到一半 `NoClassDefFoundError`。整套不要用 `:shots:run` 跑。
- 并行：所有场景共用一条 Swing EDT，一个进程里只能逐张渲染，`all` 按 `--jobs`（默认核数的一半，至多 8）拉起子进程，
  子进程用主进程的 java、JVM 参数与类路径，各带 `--shard i/n` 只渲染第 i 片，写同一个输出目录，主进程逐张报进度、
  最后汇总张数、失败与用时。分片按序号取模，不切连续的段：同一节的耗时相近，切段会让某一片整节都慢。`--jobs 1` 在本进程里跑。
- 基线与差异：`all` 渲染前把输出目录里的上一版挪到 `build/shots-baseline/`，渲染完逐像素比对，清单写到
  `build/shots/changes.md`（变化、新增、删除、失败，变化的给差异像素数与包围盒），变了的图在 `build/shots/diff/`
  下各有一张旧、新、差异三栏并排的对照图。审查只看清单里的图。`--keep-baseline` 不挪基线，只清掉上一版输出再出。
  有失败时进程以 1 退出。
- 等待：动画时长缩放为 0（场景自己的 `MotionDurationScale`，不碰平台的 `motionScale`，后者连着设置页「减少动画」的开关），
  光标不闪（`LocalCursorBlinkEnabled`）。开场、`Step.Pump`/`--pump` 与收尾都是「等画面稳定」：连续 500ms 逐像素不变即算稳，
  有上限（开场 3 秒，收尾 3 秒，`Pump` 为写的毫秒数），到了照当时的画面出图，收尾没停下的记进清单。500ms 长过界面里
  看不见的等待（加载指示器晚 200ms 出现、解析链接防抖 350ms、压缩包提示晚 400ms），不要往小调。
  上次判稳之后没有输入、没推进时间也没经 `Step.Run` 改状态，再判直接通过，连着的等待不重复静止。
  要拍动画中途的（`feed-star` 的星爆、`feed-boost` 按住时的提示）给 `motion = true`：动画按真实速度播，等待按写的时长推满。
  判稳看不见计时：提示框的出现延迟、排队的提示条期间画面不变（`drive-duplicates` 桌面图里因此仍是「正在查找」那一条）。
  悬停固定停 1.2 秒；点完按钮指针还停在原处、底下换了东西会冒出提示框的，用 `Step.MoveTo` 把指针移开。
- 临时文件：每张图一个目录，在 `%TEMP%/piko-shots/<主进程 pid>/<图名>/`，日志、参数文件与生成的图片也在这一层。
  一张图结束即删它的目录，主进程退出（含 Ctrl+C）时先结束子进程再删整个 pid 目录；子进程盯着主进程，主进程被强行结束就跟着退。
  被强行结束留下的，下次启动按 pid 是否还活着清掉（旧版留下的 `piko-shots-*` 按一小时未改动清）。
  预置下载的「已下完」文件用稀疏文件：`RandomAccessFile.setLength` 在 NTFS 上按长度实际分配，一张图十几 GB，
  一套跑下来曾占满 C 盘；改为以 `StandardOpenOption.SPARSE` 打开、只写末尾一个字节。整套并行时临时目录峰值约 150 MB。
- 连跑两次仍会变的图（截图环境管不到）：设置页的「上次同步于 HH:MM」是真实时钟（`PikoSettingsSync` 的时钟在 `PikoServices`
  里建，截图注入不进去）；信息流一节取段与播放走真实 libmpv 和真实时间，选到哪一集、画面状态每次不同；
  M3 的 `LoadingIndicator` 不随动画缩放停在固定的一帧（解压卡片、登录中）。清单里这几类的变化先当噪声看。
  为了让其余的图可复现：临时目录按图名固定（下载位置的路径会显示出来），磁盘空间给定值，设置项里的时刻在开始渲染那一张时才算，
  滚到可见范围外的节点经容器的 `ScrollBy` 按算出的距离滚，不发滚轮事件（滚轮按事件间隔算速度，每次停的位置不同）。

- `--caption` 在贴着右上角的那一行末尾画三个窗口按钮（尺寸照桌面端，只画字形），桌面窄窗口里顶栏放不放得下要带上它看。
- 步骤有 `--click`、`--long-press`（按住 800ms，触屏进多选）、`--right-click`、`--hover`、`--key`、`--type`（往有焦点的输入框打字，中文也行）、`--drag`（按住左键拖，
  坐标按 dp）、`--release`、`--wait`、`--pump`，按写的顺序执行；点击按文本或内容描述找节点，
  弹层里的也算。移动端（窄于 840dp 或 `--mobile`）的左键按成触屏：点条目即打开、长按进多选；桌面单击只选中，打开要再按回车。
  要点的节点在可滚动容器的可见范围外时先滚过去，懒加载列表里没组合出来的项仍找不到，换用搜索缩小列表。
- `all` 的清单在 `shots/.../Main.kt` 的 `standardSet`，按 `docs/development/ui-states.md` 的节排列，前缀是
  `shell-`、`drive-`、`tabs-`、`panel-`、`dlg-`、`transfers-`、`pages-`、`update-`、`feed-`、`player-`、`task-`。
  每个状态经 `tri()` 出桌面 1440x900、手机 400x860、平板 1280x800（移动端）三张，只在一种交互模型里有的状态只出那一种；
  桌面窄窗口 700x800 与手机横屏 860x400 另加。改了哪类界面就往对应的节里加一行。
- 步骤里另有只在 `standardSet` 用的：按坐标点或长按（信息流画面没有文本）、点同名节点里最靠上的那个（传输页类别标签）、
  `Step.Run` 在步骤之间拨假服务端的开关（列目录、离线任务、分享列表的延迟或失败，登录变慢或被拒）或调进程级会话。
- 数据在 `ShotEnv.kt` 的 `FakePikPak.seed()`：一部 12 集的番剧、一个子目录、电影与文档、回收站、星标、
  离线任务与四个本地下载，另有最近添加与播放历史、三个压缩包（有内容、空、要密码 piko）、三条分享、一个 WebDAV 应用。
  只给某几张的数据走 `Shot.extraSeed` 与 `Shot.prefs`（上传任务、整包离线这类存在设置里的）。登录前的画面由 `LoginSeed` 定。
  假服务端（`FakePikPak`）经 OkHttp 拦截器作答，SDK 的请求与解析仍走真实代码；
  只答界面读得到的接口，其余回 404，新页面要什么就补什么。与冒烟测试的 `FakePikPakServer` 是两份，那份要 MockEngine。
- 应用内更新换成 `ShotUpdater`：真实的更新检查用自建的 HttpClient 访问 GitHub，拦截器接不到。
- 播放器只渲染控件（`PlayerPreview`），没有播放后端；信息流与片段面板会建 mpv 的预览播放器，画面是黑的，控件照常。
  移动端的图用 `VideoPlayerHost.InApp`（与 Android 一致），桌面用 `Detached`；套错了宿主，信息流顶栏会多出或少了「在独立窗口播放」。
- 没有窗口外框：拖放层不在画面里，窗口按钮要 `--caption` 才画。Linux 上没有微软雅黑，中文落到别的字体，字宽与 Windows 略有出入。
- 网络缩略图与海报不画；图片查看器用的是本地生成的图。
- 参数里有中文时，Linux 上要 UTF-8 的 locale（`LC_ALL=C.UTF-8`），否则 Gradle 传给进程时变成问号，按文本找不到节点。
