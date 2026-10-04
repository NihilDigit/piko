# 截图（:shots）

只在全自动工作流里或明确要求时才跑，理由见根目录 CLAUDE.md。

`:shots` 是开发工具，不随应用发布：无头运行整个应用（`PikoApp`，与桌面入口同一套界面、状态与平台实现），
数据来自假的 PikPak 服务端，按任意窗口尺寸与深浅主题出 PNG。不必开真实账号，
也不用在 Windows 上：Linux 与没有显示器的机器同样能跑。

```bash
./gradlew :shots:run --args="all"                        # 一整套，写到 build/shots/，约一分钟
./gradlew :shots:run --args="shot starred --size 1100x800 --click 我的 --click 星标 --wait Dune"
./gradlew :shots:run --args="texts --click 传输"          # 打印界面上的文本，找 --click 的目标用
```

- 步骤有 `--click`、`--right-click`、`--hover`、`--key`、`--type`（往有焦点的输入框打字，中文也行）、`--drag`（按住左键拖，
  坐标按 dp）、`--release`、`--wait`、`--pump`，按写的顺序执行；点击按文本或内容描述找节点，
  弹层里的也算。`all` 的清单在 `shots/.../Main.kt` 的 `standardSet`，改了哪类界面就往里加一张。
- 数据在 `ShotEnv.kt` 的 `FakePikPak.seed()`：一部 12 集的番剧、一个子目录、电影与文档、回收站、星标、
  离线任务与四个本地下载。假服务端（`FakePikPak`）经 OkHttp 拦截器作答，SDK 的请求与解析仍走真实代码；
  只答界面读得到的接口，其余回 404，新页面要什么就补什么。与冒烟测试的 `FakePikPakServer` 是两份，那份要 MockEngine。
- 没有窗口外框：自绘标题栏与拖放层不在画面里。Linux 上没有微软雅黑，中文落到别的字体，字宽与 Windows 略有出入。
- 网络缩略图与海报不画，播放历史与我的分享是空的。
- 参数里有中文时，Linux 上要 UTF-8 的 locale（`LC_ALL=C.UTF-8`），否则 Gradle 传给进程时变成问号，按文本找不到节点。
