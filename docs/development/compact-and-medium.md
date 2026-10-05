# 手机与中等窗口的承载方式

记录日期：2026-10-05。宽窗口已改为「岛 + 标签 + 浮动卡片」，手机与中等窗口尚未跟进，本文记录现状、已定方向与待定问题，供下次直接接着做。

## 已定方向

- 手机（compact）与中等窗口（medium）不做标签。标签解决的是并排开着几个位置、来回切换，小屏上用处不大。
- medium 是大号手机，与 compact 同一套：底部导航栏、无外框无岛、无侧边栏，侧边栏与外框从 840dp（expanded）起。已实现。
- 横排的东西一律经 `AdaptiveBar` 排，放不下的收进「更多」；桌面窄窗口里有窗口按钮时，传输页的筛选另起一行。已实现。
- 原则：窄窗口里不做复杂操作。几样后台工作同时挂着的局面在这里不该出现，因此不做汇总条。M3 没有这种组件：
  Snackbar 一次一条、不能叠放（「Only one snackbar may be displayed at a time」），toolbar 只放当前页的操作，
  FAB 不放通知；多个并发任务只有 progress indicator 的「一组用一个」可循。
- 解压、归档与取消归档：进度列进传输页的「云端」，开始时网盘页提示一次，「查看」跳过去；底部导航栏的「传输」挂项数徽标；
  Android 的常驻通知点进来到传输页。已实现，见下文「各档现状」。
- 收起的添加链接维持底部把手，挂起的信息流维持现状（压栈加返回）。
- 查重改作网盘列表的一种过滤（Filter），不放进 sheet。尚未实现。
- 手机不用岛，传输页与无标签页保持通栏样式。
- 宽窗口的形态不动：标签只给要分类切换的页，只看进度的放右下角浮动卡片。约定见 `ui/CLAUDE.md` 的外框一节与「后台在跑的与关了没做完的」一条。

## 各档现状

宽度档见 `ui/.../adaptive/WindowWidth.kt`：compact 小于 600dp，medium 600–840dp，expanded 840dp 起。另有高度 compact（小于 480dp，即横握的手机）。

medium 与 compact 同一套，下表只分两列。

| 内容 | expanded | compact 与 medium |
| --- | --- | --- |
| 导航与外框 | 侧边栏，外框与岛 | 底部导航栏，无外框 |
| 网盘页标签 | 常驻标签栏 | 无 |
| 查重进度 | 查重标签上转圈 | 网盘页底部状态条 |
| 查重结果 | 切到查重标签 | 提示或把手上「查看」进入查重位置，后退离开 |
| 解压、归档、取消归档 | 浮动卡片 | 传输页「云端」，开始时提示一次，导航栏徽标 |
| 收起的添加链接 | 浮动卡片 | 网盘页底部把手（`InstantSheetHandle`） |
| 挂起的信息流 | 「信息流」按钮上的小圆点 | `FeedResumeBar` |
| 传输页 | 类别标签 + 岛 | 标题、筛选与操作同一行；桌面有窗口按钮时筛选另起一行 |
| 我的分享、设置 | 无标签的岛，窗口按钮在页眉末尾 | 顶栏 |
| 页面主操作 | 命令栏右端实心按钮 | 扩展 FAB（`PrimaryActionFab`） |

## 待定问题

1. **查重做成过滤的具体形态。**
   - 现在是网盘里的一个位置（`DriveLibrary.DUPLICATES`），借用整个网盘页：缩略图、播放、详情、多选、删除、撤销都能用。改作过滤后这些应当保留。
   - M3 的 filter chip 用于「filters for a collection」，与查重相符；但不能单独出现（「Don't display a single chip by itself」），需与别的过滤并列，或不用 chip 的形式。
   - filter chip 一节没有「需先扫描、带进度」的状态，扫描进度要另用 progress indicator 表达，放在列表上方。
   - 扫描在离开过滤后是否继续：倾向停止并保留已扫结果，回来接着用，使它不再算后台工作。
2. **`headerFieldColor` 的两种取值。** 原为 medium 网盘页页眉在外框色上而设，medium 不再有外框后是否还需要，待核对。

## 已有约束

- 面板一律经 `PikoSheet`。底部 sheet 只有展开一档，跳过半开：用鼠标时滚轮会拖动 sheet，又没有松手吸附的那一下。
- 对话框在 Android 上是按内容定高、居中的独立窗口，内容高度一变整个对话框就跳，里面不做尺寸动画（issue #9）。
- 横握手机（高度 compact）不是缩小的桌面：仍是触屏，三百多 dp 的高度放不下第二栏，面板在 medium 宽度下也用侧边形态。
- 面板不停进外框右侧那一栏：那一栏只放详情或信息流。
- 不要直接用 `ModalBottomSheet`，播放器的面板除外。

## 相关代码

| 位置 | 内容 |
| --- | --- |
| `ui/.../components/PikoSheet.kt` | 面板：expanded 是侧边面板，其余是底部 sheet |
| `ui/.../components/PikoScaffold.kt` | `IslandPage`、`IslandScaffold`、`IslandHeaderSpace` |
| `ui/.../components/IslandTabs.kt` | `IslandTab` 与标签形状 |
| `ui/.../workbench/FloatingTasks.kt` | 浮动任务卡片，只在宽窗口（与标签栏同一条件） |
| `ui/.../screens/transfers/ServerWorkRows.kt` | 传输页里的解压与归档 |
| `ui/.../workbench/TransferActivity.kt` | 「传输」按钮上的读数与导航栏徽标的项数 |
| `ui/.../screens/drive/DuplicatesUi.kt` | 查重的页顶、空态、扫描条与把手 |
| `ui/.../screens/drive/DriveScreen.kt` | `findDuplicates`、查重标签、底部把手与 FAB |
| `ui/.../components/PrimaryAction.kt` | 页面主操作：按钮或扩展 FAB |

## 其他遗留

- **安装包冒烟测试没有跑过。** 便携版改动（zip 里的 `portable` 标记、整包更新跳过它、日志在 `data\logs`）只有它覆盖，推送时会跳过。手动跑 `gh workflow run test.yml -f desktop=true`。
- **老便携版用户的数据。** 1.1.0 的 zip 已发布，数据在 `~/.piko`；重新下载新 zip 的用户会改读 `Piko\data`，看起来像登录与设置都丢了。待定：首次启动时若 `data\` 不存在而 `~/.piko` 存在，提示一次导入（只复制不移动），更新日志写明。
- **Coil 图片缓存在 `%TEMP%`。** 不随数据目录走，开发版与安装版同时运行时共用，是否冲突未证实。待定是否挪进数据目录。
- **归档外层标记的历史数据。** 这次改动之前归档的文件夹没有记录，外层不显示，要重新归档一次；可考虑进入已归档的文件夹时补记上层。
- **链接解析结果开成标签。** 宽窗口里把磁力或分享链接解析出的文件树开成一个标签，与查重同一条路，需要标签能装网盘以外的内容。
