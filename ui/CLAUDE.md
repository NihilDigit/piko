# ui 模块

两端共用的全部界面。网盘页另见 `src/commonMain/kotlin/dev/piko/ui/screens/drive/CLAUDE.md`，
信息流另见 `screens/clips/CLAUDE.md`。

`ui` 的桌面端与 Android 端用的 material3 版本不同（见 `desktopApp/CLAUDE.md` 的「版本」），同一行代码可能只在一端报错；
1.12 的 material3 里部分 API 仍是实验性，本模块已统一 opt-in。

## 响应式布局

布局只看窗口宽度，不看设备：`ui/.../adaptive/WindowWidth.kt` 按 M3 断点给出 compact、medium、
expanded。桌面窗口缩放与平板分屏走同一套判断，桌面体验以 Android 平板为准。
另看高度一项：`isHeightCompact()`（不到 480dp，几乎就是横握的手机）。这时侧边栏只有窄轨、不能展开，不开详情栏与标签，
网盘页顶栏按窄屏的样子，信息流全屏并收起系统栏（`PikoPlatform.HideSystemBars`，不锁方向），目录选择器与批量重命名全屏，
面板在 medium 宽度下也用侧边形态。横握的手机不是缩小的桌面：它仍是触屏，三百多 dp 的高度放不下第二栏。
按宽度开第二栏或展开侧边栏的新代码要同时看它。
- 导航只有两套：compact 下是 `NavigationSuiteScaffold` 的底部导航栏（写死 `ShortNavigationBarCompact`，不交给库按窗口挑：
  库还看高度，横握的手机会得到一条横向底栏），比 compact 宽（`SidebarMinWindowWidth`，600dp）一律是一整条侧边栏
  （`MainSidebar`），连同外框与并进内容的标题栏。窗口不到 `SidebarPushMinWindowWidth`（1000dp）时侧边栏只占窄轨，
  展开的那一份带遮罩浮在内容上（照模态抽屉，点遮罩、返回或去了别处就收回，不改存下的收起状态）；更宽时展开是推开内容。
  侧边栏上面是去处，下面是快速访问（`QuickAccessSection` / `QuickAccessState`），
  照资源管理器只列用户固定的文件夹，文件夹右键「固定到快速访问」。PikPak 没有这项，存在偏好 `pinnedFolders` 里
  经设置同步带走（`PinnedFolders`，只存 ID 与名字，打开时按 ID 查上级）。只亮一处：人在固定的文件夹里时亮它，否则亮当前页。
  不要在导航栏旁边再并排一栏导航。侧边栏在 `NavDisplay` 外面，打开「我的」里的各页时不被盖住（应用内播放器这类
  整窗的页照旧盖住，见 `sidebarMode`）。快速访问的条目右键可以在新标签页打开或取消固定。侧边栏不能拖宽，只能收起成只剩图标的窄轨
  （顶上的开关或主修饰键+B，`sidebarCollapsedFlow`，每台设备各自的；各行读 `LocalSidebarCollapsed` 自己换成窄轨的样子）。
  「文件」与「传输」是一组连体按钮；有传输在跑时「传输」按钮上写速度（上下行取快的一边）或项数
  （`workbench/TransferActivity`）。蜗牛模式（限速，偏好 `snailModeFlow`，每台设备各自的，不同步）开着时「传输」按钮整个用强调色，
  有没有传输都是。原来窗口底部的状态栏已去掉，不要再加回。
  这一档画外框（`theme/Frame.kt`）：外框色（`frame`，`surfaceContainerHigh`）是底，上面浮着几块圆角的岛，岛与岛、岛与窗口边缘
  之间露 `IslandGap`。侧边栏一块；网盘页与传输页右边一块，上半段是页眉（`islandHeader`，比岛深一档），下半段是列表（页面本色），
  列表上沿是 `IslandInnerCorner` 的小圆角、露出一点页眉色，两段靠色阶分开，不画线。岛上沿是一排标签（`IslandTab`）：网盘页是位置，
  传输页是类别；活动标签与页眉同色相连，第一个活动时岛的左上角不圆（`islandTopStart`）。右侧详情栏直接落在外框色上，
  上面的行取页面本色才分得出。外框色加深过一档：只深一档时活动标签与外框几乎同色。
  **两块颜色相接处一律由浅的一方圆角压在深的一方上**，不让两块直接拼成一条硬直线：岛浮在外框上、列表的上沿露出页眉色、
  登录页右边的表单是浮在品牌区外框色上的岛，都是这一条。新写的页要分区时照做。
  各页经 `PikoScaffold` 的 `island`（`IslandPage`：标签、页眉、内容）得到这副骨架。标签只给要分类切换的页；没有分类的页
  （我的分享、设置）不放标签，岛直接画到顶（上沿与侧边栏岛对齐），页名写在岛的页眉开头（`IslandTitle`），窗口按钮画在
  页眉末尾，标题与操作之间用 `IslandHeaderSpace` 留空、可以拖动窗口。不要给这种页放一个写着页名的标签：没有别的可切，
  它只是在冒充标题；也不要在顶上空出一行只放窗口按钮。
  标题栏在 Windows 上
  可并进内容（设置里的「紧凑标题栏」，`WindowCaption`）：贴着窗口右上角的那一行自己画窗口按钮，空白处经 `windowDragArea` 登记为拖动区。
  并进与否不看宽度：主界面任何宽度都接管（手机宽度时是各页顶栏画按钮），登录页这类主界面之外的页仍是系统标题栏。
  新写的顶栏要能画按钮：挂 `rememberCaptionSlot()` 的 modifier，按钮接在动作后面，标题后面的空白登记为拖动区。
  各页一律用 `PikoScaffold`，不直接用 `Scaffold`：有外框时它把顶栏、底栏放在外框色上，内容裁成卡片；顶栏与底栏的高度
  取 `Frame.kt` 的 `FrameTopRowHeight`、`FrameBottomRowHeight`，与侧边栏的图标行、账号行对齐。
- 图标：平时一律描边（Outlined），选中、打开、正在生效时换实心（Filled），侧边栏、导航项、视图切换、开关按钮都照此。
  挑图标时选实心与描边长得不一样的：History、Share、SyncAlt 两种写法同形，切换了看不出。播放器叠在画面上的操作按钮例外，用实心。
- 顶栏滚动换色：列表页用 `PikoTopBar.kt` 的 `rememberListScrollTint`，按列表是否在顶端设顶栏状态，不要挂
  `pinnedScrollBehavior` 的 nestedScroll。后者累加滚动量，列表换了内容（进子文件夹、换筛选）或根本滚不动时
  仍停在换过色的状态。
- 搜索框右端一直有取消按钮：有字时清空，没字时关掉搜索。触屏上没有 Esc，没有这个按钮就退不出去。
- 返回栈：`PikoMainScaffold` 用 Navigation 3 的 `NavDisplay`，栈底 `Screen.Home` 是导航栏与三个根页面，
  其余页面压在上面、连同导航栏一起盖住。被盖住的 Home 离开组合，回来时重建，所以根页面的状态要经得起
  重建（网盘页的目录内容与滚动位置记在仓库里）。同理，要在离开页面后继续的工作不能用页面的 `rememberCoroutineScope`，
  要挂到 `PikoServices` 上的进程级会话。新页面加一个 `Screen` 子类、登记进 `NavKeyConfiguration`、
  在 `entryProvider` 里写一条 entry；切页与收起压栈页用 `resetToHome`，不要 `clear`，栈底必须留着 Home。
- 「我的」只在手机上是一页（底部导航栏的第三项）；有侧边栏时库与设置直接列在侧边栏上，账号、退出登录与关于并进设置，
  详情页（我的分享、设置）占满内容区、不给返回。原来 600–1200dp 的两栏（`ListDetailSceneStrategy`）已去掉。
- 对话框一律用 `PikoDialog`（参数同 AlertDialog，标题小一号、圆角 16dp），不直接用 `AlertDialog`。执行动作的确认用
  `PikoDialogConfirm`（有底色），删掉找不回的加 `destructive`（错误色），文案不随之改成「并删除」，也不另起一行红字；
  取消仍是文字按钮。只是告知、只有「知道了」「完成」的，以及几条路并列由人挑的，用文字按钮。
  M3 的基本对话框只用文字按钮，是按触屏设计的，在宽窗口里像漂在中间的手机卡片；两端统一用这一套，不按平台分。
- 一页的主操作（添加链接、清空回收站、选中查重建议移走的）用 `PrimaryAction.kt`：宽窗口是命令栏右端有底色的按钮，
  窄窗口是扩展 FAB，清空这类找不回的用错误色。页面只给出 `SheetAction`，不各自挑样式；不要再收进「更多」或做成顶栏图标。
- 目录选择器在 compact 下全屏，更宽时是居中的基本对话框。对话框在 Android 上是按内容定高、居中的独立窗口，
  内容高度一变整个对话框就跳：对话框里不做尺寸动画，提示行常驻、出错只变色（issue #9）。
- 面板：一律经 `PikoSheet`，承接临时任务（条目操作、传输详情、添加链接）。expanded 是从末端滑入的模态侧边面板，
  其余是只有展开一档的底部 sheet；不要直接用 `ModalBottomSheet`（播放器的面板另有横屏侧栏，除外）。
  面板一律浮着，不停进外框右侧那一栏：曾经停进去过，那一栏同时还放详情与信息流，三者轮流让位，返回键关哪个说不清。
- 后台在跑的与关了没做完的，按窗口分两套，不另设入口。宽窗口（expanded 且高度不 compact，即网盘页有标签栏时）：要浏览、
  要操作的开成网盘页的标签（查重），只看进度的是窗口右下角的浮动卡片（`workbench/FloatingTasks`：解压、归档、取消归档、
  收起的添加链接）。卡片挂在主界面这一层，切到哪一页都在，一项一张，点开就地展开明细，可整摞收成角落的胶囊。
  medium 与手机同一套，按「窄窗口里不做复杂操作」收窄：解压、归档与取消归档列进传输页的「云端」，开始时网盘页提示一次、
  「查看」跳过去，底部导航栏的「传输」挂项数徽标，Android 的常驻通知点进来也到传输页；查重扫描与收起的添加链接是网盘页底部的
  状态条与把手。不要再做汇总几样后台工作的底部条：M3 没有这种组件，Snackbar 一次一条、不能常驻多项。
  命令栏右端曾有一个「收着的东西」竖排菜单，看着像操作、实为导航，已删，不要再加回。
- 右侧那一栏只放常驻、要边看边对照的内容：详情或信息流，同一时刻一样。详情进来时信息流挂起（见 `screens/clips/CLAUDE.md`），
  继续刷时详情关掉。

## 鼠标与键盘

条目右键弹出与操作面板相同的菜单（`ContextMenuArea`）；右键点在几项选中里的一项上时菜单作用于全部选中的，照资源管理器。
列表一律用按行对齐的 `LazyVerticalGrid`（`PikoItemGrid`），不用瀑布流：瀑布流按最矮的一栏放，顺序会在各栏间跳。每页把一项的操作写成一个
`actionsFor`，面板与菜单都读它（网盘页是 `fileActions`）；新列表照做。
快捷键一览（F1 或主修饰键+/，`ShortcutsDialog`）是手写的一张表，加了快捷键要同时写进去。
命令面板（主修饰键+K，`CommandPalette`）：模糊搜索最近去过的与快速访问里的文件夹、当前目录的子文件夹、去处与命令，方向键挑、回车执行。
全局的命令在 `PikoMainScaffold` 的 `paletteItems`；某一页自己的命令在页里经 `ContributePaletteItems` 登记，页面离开组合时撤掉
（网盘页登记了新建文件夹、上传、视图、详情栏等）。新页面有值得键盘直达的操作就照这样登记。
横排的内容挂 `verticalWheelScrollsRow`，鼠标的竖滚轮才滚得动它；
图标按钮用 `TooltipIconButton`，快捷键写在提示里；Esc 经 `BackHandler` 触发返回。新加的界面同时照顾触屏与鼠标：
下拉刷新之类只有触屏能用的操作，宽窗口要另给按钮。快捷键的主修饰键取 `PikoPlatform.shortcutModifier`（mac 上是 ⌘），不要写死 Ctrl。
Compose 桌面端悬停移动事件的 `previousPosition` 恒等于 `position`，判断「鼠标动了」要自己记上一次的位置。

放弹层（菜单、提示、对话框）的自定义布局用普通 `Layout`，不用 `SubcomposeLayout`：桌面端测量途中销毁弹层会让整个窗口崩掉，
原因与其余规避见 `desktopApp/CLAUDE.md`。

## 动效

按平台分，不按输入方式分（按输入方式分时手感随触屏、鼠标来回变）：`PikoPlatform.motionStyle` 给出，
Android 是 `MotionScheme.expressive()`，桌面是 `standard()`（几乎不回弹，同样距离约 200ms 到位，expressive 要约 400ms）。
播放器控件另在 `PlayerTheme` 里用 expressive，不随平台。
- 页面转场不走 motionScheme，走 `theme/Motion.kt` 的 `PikoMotion`，经 `LocalPikoMotion` 随主题注入，按平台取时长：
  桌面取 WinUI 的 83、167、250ms，Android 取 M3 的 150 到 400ms；离场任何平台都不超过 200ms。切根页面是旧页淡完新页再淡入，
  压栈是横滑加淡化，旧页淡完新页才开始淡入，两页不叠成半透明。新写转场用它给的 `topLevel`、`forward` 一类，不要自己写 tween。
- 右键菜单与命令栏菜单在桌面上只淡入 80ms、不缩放（`MenuMotion`，只包这两处）。DropdownMenu 默认从 0.8 放大到 1，
  graphicsLayer 的缩放同样作用于点击判定，右键后立刻点会点偏。右键菜单以指针为原点（`PointerMenuPositionProvider`）：
  向右下展开，放不下就朝反方向，不用下拉菜单的规则（那会在下方放不下时整个翻到锚点上沿以上，锚点是整个条目乃至整片网格）。
  工具栏按钮的下拉菜单仍用下拉规则。
- 面板的 `hideThen` 当场通知关闭再执行动作，不等收起动画，面板直接消失。不能反过来先执行、等动画完再通知：
  动作若改了开关面板的那个状态会被随后的关闭清掉，动作若让页面离开组合，关闭通知就发不出去。
- 减少动画：系统设置与设置里的「减少动画」（`reduceMotionFlow`，每台设备各自的，不同步）取或，汇到进程里唯一的
  `PikoMotionScale`（`MotionDurationScale`），为 0 时所有动画当场跳到终点。它由入口放进 Recomposer 的协程上下文：
  桌面是 `Main.kt` 的 `runBlocking(motionScale) { awaitApplication { … } }`，窗口、弹层与对话框的 Recomposer 都从那里继承，
  `MotionScaleInjectionTest` 守着这条链，升级 Compose 时看它；Android 是 `MainActivity` 给装饰视图换一个带缩放的
  WindowRecomposer，注入之后 Compose 不再自己读开发者选项的动画缩放，由 `followSystemAnimatorScale` 接上。
  系统设置的读取在 `desktop/motion/SystemReducedMotion`：Windows 读 `SPI_GETCLIENTAREAANIMATION`（即「辅助功能 → 视觉效果
  → 动画效果」），经 `WindowsCaption` 窗口过程收到的 `WM_SETTINGCHANGE` 当场重读；macOS 读
  `accessibilityDisplayShouldReduceMotion`，Linux 读 GNOME 的 `enable-animations`，两者在应用重新激活时重读。
  减少动画时切页与面板也是跳切，与 Android 的「移除动画」一致。不要再试「归零之外给切页补淡入淡出」：Transition
  （AnimatedContent、AnimatedVisibility 都靠它）每一帧从自己所在 LaunchedEffect 的协程上下文读缩放，那是 Recomposer 的
  effect 上下文，整棵组合共用一份，没有按个别动画覆盖的入口；NavDisplay 又在内部自己驱动转场，外面包不进去。
  缩放为 0 时无限动画停在终点那一帧，一直循环的装饰动画要读 `LocalPikoMotion.current.reduced` 自己画静态的样子（见骨架屏）。
