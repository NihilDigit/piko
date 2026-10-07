# 截图（:shots）

只在全自动工作流里或明确要求时才跑，理由见根目录 CLAUDE.md。

`:shots` 是开发工具，不随应用发布：无头运行整个应用（`PikoApp`，与桌面入口同一套界面、状态与平台实现），
数据来自假的 PikPak 服务端，按任意窗口尺寸与深浅主题出 PNG。不必开真实账号，
也不用在 Windows 上：Linux 与没有显示器的机器同样能跑。

```bash
./gradlew :shots:run --args="all"                        # 一整套，写到 build/shots/，约半小时
./gradlew :shots:run --args="all --only drive-trash"     # 只出名字以它开头的几张
# 别处同时在编译界面时，gradle run 直接读的 build/classes 会被换掉，跑到一半 NoClassDefFoundError。改用装出来的副本，在仓库根目录跑：
./gradlew :shots:installDist && shots/build/install/piko-shots/bin/piko-shots all   # JAVA_HOME 要指向 25
./gradlew :shots:run --args="shot starred --size 1100x800 --click 我的 --click 星标 --wait Dune"
./gradlew :shots:run --args="texts --click 传输"          # 打印界面上的文本，找 --click 的目标用
```

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
- 没有窗口外框：拖放层不在画面里，窗口按钮要 `--caption` 才画。Linux 上没有微软雅黑，中文落到别的字体，字宽与 Windows 略有出入。
- 网络缩略图与海报不画；图片查看器用的是本地生成的图。
- 参数里有中文时，Linux 上要 UTF-8 的 locale（`LC_ALL=C.UTF-8`），否则 Gradle 传给进程时变成问号，按文本找不到节点。
