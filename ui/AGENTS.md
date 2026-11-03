# ui 模块

Android 与桌面端共用的全部 Compose 界面。网盘浏览页细则见 `src/commonMain/kotlin/dev/piko/ui/screens/drive/AGENTS.md`，信息流细则见 `src/commonMain/kotlin/dev/piko/ui/screens/clips/AGENTS.md`。

`ui` 模块在桌面端与 Android 端链接的 Material 3 版本不同（详见 `../desktopApp/AGENTS.md` 的版本说明），同一处调用可能仅在其中一端编译报错，修改后必须双端编译核对。Material 3 1.12 中部分 Expressive API 仍处于实验性阶段，本模块已在构建脚本中统一 opt-in。

## 自适应工作区与状态归属

界面架构遵循 `../docs/development/adaptive-frontend-framework.md` 与 `../docs/development/interaction-refactor.md`（审查与验收边界见 `../docs/development/interaction-refactor-review.md` 与 `../docs/development/interaction-workspace-validation.md`）。工作身份、业务能力、空间布局与输入方式四者相互正交，严禁以平台名称代替窗口容量或输入来源，亦严禁用 `isDesktopLayout()` 裁减业务功能。

### 账号级工作集合与生命周期

- **统一工作集合**：`shared/presentation` 存放跨平台呈现状态持有者，业务会话不保存面板展开或报告筛选等纯视图态。所有平台共享 `DriveWorkspace` 维护文件标签的路径栈、浏览历史、最近访问、目录滚动锚点与文件剪贴板；`WorkspaceContexts` 在同一有序集合中按稳定 ID 记录文件标签、查重报告与独立导入草稿的活动项与顺序。文件仓库（`PikoDriveRepository`）仅接收 `FolderReferenceSource` 引用以保留缓存和修正索引，不拥有工作区导航。
- **ViewModel 与作用域隔离**：`WorkspaceViewModel` 以固定键挂在账号级 `AccountPresentationStores` 下，按上下文 ID 持有各文件标签（`TabNavigation`）、资料库页（`LibraryNavigation`）、查重（`DuplicateSession` 与 `DuplicateReportViewModel`）及导入草稿（`InstantDraft`、`AddLinkViewModel` 与 `ImportWorkspaceViewState`）的会话，不随主界面组合重建或窗口尺寸往返而销毁，退出账号时统一清除。查询与界面订阅使用浏览会话的子作用域，已提交的文件写操作使用账号级 `AccountExecutionScopes`。
- **浏览态与保存目标**：多选集合、搜索展开、筛选、分区折叠及「显示全部」归属各自浏览会话，仅有纯文件名解析结果进入 `DriveAnalysisCache`。资料库页使用独立路径栈与历史，同一工作区的浏览会话显式共享剪贴板。外部上传与新导入草稿的默认保存目录通过 `SaveTargetContext` 传入，每份新草稿在创建时捕获当时主文件工作区的目录作为固定默认目标，此后用户在文件标签中浏览其他目录不会改动该草稿的目标位置。
- **工作保留与结算边界**：切换标签、切换底部导航或从窄窗口总览切走均保留查重扫描与导入草稿编辑态；仅当显式关闭查重上下文时才停止该次扫描并丢弃报告，仅当关闭导入草稿上下文（关闭前按 `discardNeedsConfirm` 核对未提交的人工选择）时才释放编辑视图，而已提交的保存继续在业务执行作用域内结算。导入保存结果由业务会话接收并释放预览租约，待消费完成提示保留至主界面确认展示（仅提供「查看」动作，不强行切走用户当前正在浏览的页面），换号时清除。保留范围限于当前账号的本次应用进程会话，不承诺跨进程退出恢复草稿与查重报告（仅文件标签位置按账号持久化，重启后由 `restoreLocation` 恢复，旧缓存中无可恢复扫描的查重标签自动跳过）。

### 共享文件呈现构件与链接导入工作区

- **文件呈现构件**：网盘列表、海报墙、导入解析来源树与保存结果预览通过 `components/FilePresentation.kt` 与 `FileArtwork.kt` 共享图像分类、防窥模糊、双名称排版、元信息标记、星标/归档徽标、选择控件与 `SheetAction` 动作描述。图像构件仅负责渲染，不触发远端临时对象创建或播放租约借出。鼠标悬停仅切换前导勾选控件，不得导致文件名称发生水平位移；长按处理器始终保持注册并在触发瞬间读取当前指针输入来源，防止鼠标切触屏后的首次长按因回调从空变非空而重置手势。树形行（`WorkspaceTreeRow`）将展开箭头置于勾选框之前；纯文件列表不留箭头空列，含分支的树保留统一箭头列与层级缩进。
- **链接导入工作区（`ResolvedImportWorkspace`）**：导入草稿作为独立工作区上下文存在，不再与信息流争用网盘页右栏。主内容区始终复用普通网盘的 `DriveFileGrid` 与 `DrivePathTitle` 展示按当前勾选、字幕规则与规范命名投影出的虚拟保存树（`import-file:` / `import-dir:` 命名空间，禁用真实文件拖放注册），直接支持路径导航、列表/海报切换、点击按需预览、下载与查看属性。管理面板承载完整原始文件树（`ImportTree`，支持深层嵌套与 CJK 搜索，过滤不改变勾选状态，目录按真实后代计算半选）与保存设置：宽窗口并列于右侧，窄窗口复用 `TaskSheetScaffold` 的非模态底部 Sheet（通过拖动条、下滑或返回收起，不加额外收起箭头，收起后主内容区仍可直接交互）。单链接与多链接共用同一工作区，多条链接的原始树直接平铺为森林（单视频直接成行，保留各来源内部目录层级与独立勾选身份，同名条目不跨来源合并，不套按链接分组的卡片外壳）。管理面板标题右侧提供按需展开的搜索与保存设置按钮，标题下方不堆砌串联摘要，保存按钮固定在面板底部；普通保存与「以引用保存」共用目标位置与命名规则（以引用保存要求所选条目均具备已知 GCID；当解析清单不完整即 `truncated` 时，阻止整包离线裁剪以保护未列出文件）。输入框在单链接解析后折叠为单行，修改或粘贴新链接即在当前草稿会话内重新解析；更换批量链接则关闭当前草稿另开新上下文。

## 响应式布局与导航外壳

布局排列取决于窗口实际可用净宽高与 `WindowWidth.kt` 的宽度分档，能力取决于业务对象与宿主接口（`ui/.../adaptive/FormFactor.kt`）：

- **宽窗口（`isDesktopLayout()`，至少 `840×480dp`）**：启用侧边栏（`MainSidebar`）、圆角岛外框（`Frame.kt`）、并进内容的紧凑标题栏（`WindowCaption`）、常驻标签栏（`DriveTabBar`，即使仅有一个标签亦常驻显示，支持鼠标或触摸水平拖动排序、边缘自动滚动及 `Ctrl+Shift+PageUp/PageDown` 移动位置）、地址栏与命令栏，以及浮动的目录图与属性卡片。横排动作统一交由 `AdaptiveBar` 收纳溢出项，同时列表行仍保留触屏可达的操作入口。
- **窄窗口或低高度窗口**：启用紧凑顶栏、底部导航栏（或低高度下的折叠窄轨 Rail）与主操作 FAB。工作区标签入口呈现为 `24dp` 圆角描边方框内的活动标签计数（新建文件标签、查重或导入草稿时数字上下滚动切换，减少动画开启时直接跳变），点击打开全页双列页面缩略图总览：卡片采用 `3:4` 统一比例、深一档底壳与固定 `48dp` 顶部标题栏（含单行标题与关闭按钮），缩略图由 `WorkspacePageSnapshot` 从已绘制页面按最长边 `512px`、间隔 `350ms` 捕获，在账号级 `WorkspaceSnapshots` 中保留至多约 `8 MiB` 像素缓存；打开总览时暂停捕获并直接读取缓存，绝不在后台组合未激活业务页或触发解析、扫描与预览。窄窗口与宽窗口支持随时往返切换，工作集合顺序与会话状态完全一致；批量关闭（「关闭其他标签」）始终先弹窗确认并说明未提交草稿或查重结果的关闭后果。标签快捷键（`Ctrl+T`、`Ctrl+W`、`Ctrl+Tab`）在主壳层接收，仅在文件工作区及其浏览、查重、导入页生效，在任务页、设置页与播放器中不误触后台标签。
- **低高度窗口（`isHeightCompact()`，高度不足 `480dp`，典型场景为横握手机）**：紧凑导航固定采用折叠窄轨 `WideNavigationRailCollapsed`（显式指定，不交由组件库自动挑选，避免横握手机出现占满纵向空间的宽底栏）；临时面板在中等宽度下改用侧边抽屉形态；目录选择器与批量重命名改为全屏展示；全屏信息流自动隐藏系统状态栏与导航栏（`PikoPlatform.HideSystemBars`，不强制锁定屏幕方向）。
- **自适应横排工具栏（`components/AdaptiveBar`）**：所有顶栏、工具栏、命令栏与列表页眉必须使用 `AdaptiveBar` 排版。每个动作项声明显示优先级与溢出菜单项，空间不足时按优先级从低到高收入「更多」菜单，标题与窗口控制按钮永不折叠，整行绝不溢出。严禁使用普通 `Row` 配合 `TopAppBar` 的 `actions` 摆放多枚按钮（`TopAppBar` 会优先测量全部动作按钮再将剩余宽度留给标题，窄桌面窗口叠加三个窗口按钮后会将标题与返回键彻底挤没）。`PikoTopBar` 的 `actions` 参数已统一为 `List<BarItem>`，普通图标按钮使用 `iconBarItem` 构造。
- **侧边栏（`MainSidebar`）与快速访问**：侧边栏位于 `NavDisplay` 外层，打开资料库、「我的分享」或「设置」时保持可见（仅应用内全屏播放器或低高度全屏信息流覆盖整窗）。窗口宽度低于 `SidebarPushMinWindowWidth`（`1000dp`）时，侧边栏常驻窄轨，展开态以带遮罩的模态抽屉浮于内容之上（点击遮罩、返回或导航后自动收回，不改写持久化的折叠偏好）；宽度达到 `1000dp` 及以上时展开态推开内容区。顶部可点击开关或按 `主修饰键+B` 切换折叠窄轨（`sidebarCollapsedFlow`，设备本地偏好，各行读取 `LocalSidebarCollapsed` 渲染窄轨形态，不支持拖拽调宽）。侧边栏顶部为「文件」与「任务」连体切换按钮：任务按钮固定保留名称，第二行辅助展示当前最快一侧的传输速率或待关注项数（`TransferActivity`）；开启蜗牛模式（限速，`snailModeFlow`，设备本地偏好）时，无论当前是否有传输任务，任务按钮均以强调色高亮提示（原窗口底部状态栏已移除，不再恢复）。侧边栏中部为「快速访问」（`QuickAccessSection` / `QuickAccessState`），仅列出用户主动固定的文件夹（持久化于 `pinnedFolders` 并随设置同步，右键支持在新标签页打开或取消固定），高亮指示遵循唯一性原则：当前位于某个已固定文件夹时仅高亮该文件夹，否则高亮当前所属页面。禁止在侧边栏旁再并排增设第二列常驻导航栏。
- **圆角岛外框与紧凑标题栏（`theme/Frame.kt`、`WindowCaption`）**：有侧边栏的宽窗口以 `frame`（`surfaceContainerHigh`）为底色，其上浮动圆角岛块，岛与岛、岛与窗口边缘之间留出 `IslandGap`。网盘页与任务页右侧主岛上半段为深一档的页眉（`islandHeader`），下半段为页面本色列表，列表上沿以 `IslandInnerCorner` 小圆角压在页眉色之上，靠色阶自然分区而不画分割线；岛顶排列 `IslandTab`（网盘页为工作区标签，任务页为类别筛选，活动标签与页眉同色连通，首标签激活时岛左上角变为直角 `islandTopStart`）。无分类切换的页面（「我的分享」「设置」）不设伪标签，岛直接延伸至顶边与侧边栏齐平，页名置于页眉起始处（`IslandTitle`），标题与右侧动作之间以 `IslandHeaderSpace` 留白并注册为窗口拖动区。**凡两块不同明度表面相接处，一律由较浅一方以圆角压在较深一方之上**，不拼成硬直线（登录页右侧表单岛浮于左侧品牌底色之上亦遵循此规则）。所有页面统一使用 `PikoScaffold`（不直接调用 Material `Scaffold`），顶栏与底栏高度取 `FrameTopRowHeight` 与 `FrameBottomRowHeight` 以对齐侧边栏顶行与底栏账号行。Windows 开启「紧凑标题栏」且处于宽窗口（`sidebarMode`）时，右上角首行通过 `rememberCaptionSlot()` 绘制最小化、最大化与关闭按钮，空白区域经 `windowDragArea` 接管系统窗口拖动；窄窗口与登录页保留独立顶栏或系统标题栏。
- **返回栈与页面路由**：`PikoMainScaffold` 采用 Navigation 3 的 `NavDisplay`，栈底固定为 `Screen.Home`（承载文件工作区、任务页与紧凑布局下的「我的」页），资料库（`Screen.Library`）、「我的分享」（`Screen.MyShares`）、「设置」（`Screen.Settings`）及其子页（`Screen.WebDav`）、应用内播放器（`Screen.VideoPlayer`）等压栈其上。切换顶层页或收起详情页统一调用 `resetToHome()`，严禁对返回栈调用 `clear()` 清空栈底 `Home`。宽窗口侧边栏模式下不设独立的「我的」汇总页，资料库与设置直接列于侧边栏（账号切换、退出登录与关于并入设置页），打开详情页时占满右侧主岛且不显示返回箭头，按 `Esc` 出栈回到原页。
- **图标、顶栏滚动变色与搜索框**：常规状态一律使用描边图标（`Outlined`），选中、激活或打开状态切换为实心图标（`Filled`），选型时须避开实心与描边同形的图标（如 `History`、`Share`、`SyncAlt`，应选用 `FolderShared`、`SwapVerticalCircle` 等形态区分明显的图标）；叠于视频画面上的播放控制图标例外，统一使用实心。列表页顶栏滚动变色统一使用 `PikoTopBar.kt` 的 `rememberListScrollTint` 按列表是否处于顶端判定，禁止挂载 `pinnedScrollBehavior` 的 `nestedScroll`（后者累加滚动偏移量，切换子目录或列表不足一屏时仍会卡在变色态）。搜索输入框右端常驻取消按钮：框内有字时点击清空文本，无字时点击退出搜索，确保无物理 `Esc` 键的触屏设备可顺畅退出。

## 对话框、主操作、任务页与共用详情宿主

- **对话框（`PikoDialog`）**：全局统一使用 `PikoDialog`（字号收敛、圆角 `16dp`），不直接调用原生 `AlertDialog`。执行确认动作使用带填充底色的 `PikoDialogConfirm`，不可逆删除操作传入 `destructive = true`（错误色，按钮文案保持简洁，不改写为冗长的「并删除」亦不额外追加红字警告行），取消按钮保持文字按钮；纯通知确认（「知道了」「完成」）或多分支并列选择使用文字按钮。Android 端对话框为按内容自适应高度的独立居中窗口，内容高度跳变会导致整窗抖动，因此**对话框内部严禁做尺寸动画，校验提示行必须常驻占位、报错时仅切换文字颜色**（issue #9）。目录选择器在 `compact` 宽度或低高度横屏下全屏展示，更宽窗口下使用居中对话框。
- **页面主操作（`PrimaryAction.kt`）**：每页的核心主操作（如「添加链接」「清空回收站」「选中建议移走项」）统一经 `PrimaryAction.kt` 渲染：宽窗口呈现为命令栏最右端的填充按钮，窄窗口呈现为右下角扩展 FAB（不可逆清空类操作使用错误色），各页面仅声明 `SheetAction` 描述，不自行定制样式，亦不得将其收入溢出菜单或降级为顶栏纯图标。
- **任务页、后台进度与通知（`WorkProgress.kt`）**：任务页仅承载已提交执行的传输（下载、上传、云端离线）、服务端解压、归档与恢复、批量移入回收站清理及文件写入结果，按「需要处理」「进行中」「已完成」分组并支持类别筛选（「文件处理」涵盖解压、归档、文件写入与清理），不包含未提交的导入草稿或查重报告，亦不将草稿计入任务徽标，不在窗口右下角堆叠浮动进度卡。宽窗口按列对齐展示名称、状态与定宽进度条（`WorkMeterBar`）、容量、速率与目标位置，支持鼠标悬停前导图标切换勾选框及批量操作（批量选择期间锁定当前分组与行顺序，状态与进度实时刷新，退出多选后恢复动态排序）；窄窗口将同一投影上下排列。所有后台工作的标题、确定/不定进度值、主状态与次要说明统一由 `WorkProgress.kt` 单一纯函数构造（测试位于 `WorkProgressTest`），任务页列表、详情面板与 Android 后台通知共用同一数据源。Android 系统进度通知与完成通知仅在应用退至后台且存在活动任务（`hasBackgroundWork`）时由前台服务拉起（`app/.../download/AppVisibility`，基于 `ProcessLifecycleOwner`），回到前台后服务自动退下并撤除通知。凡记入 `DriveChangeJournal` 支持撤销的结局（移动、重命名、归档及部分完成归档）统一由主界面 `showChangeNotices` 在根级共享队列 `LocalRootSnackbar` 上弹出带「撤销」按钮的提示（任务页按 `Outcome.undoable` 跳过重复弹窗）；所有根页面统一复用 `LocalRootSnackbar` 并在各自 `PikoScaffold` 中放置 `SnackbarHost`。归档结果记录在当前账号运行期内保留可查（原 20 条反向撤销数据上限不变，撤销进行中不可移除记录）。
- **共用详情宿主（`components/DetailHost`）**：网盘条目属性（`PropertiesPane`）与任务执行详情共用 `DetailHost`、`DetailPanelState` 与 `DetailPanelStore`：宽窗口渲染为可自由拖动的无模态浮动卡片（`FloatingPanel`），窄窗口渲染为底部面板（`PikoSheet`）。当前查看对象、列表批量勾选集合与浮窗几何位置彼此独立，跨宽窄窗口切换时保留正在查看的详情，关闭详情仅结束界面展示而不中断后台任务。

## 设置页规范

全部设置项集中在 `screens/settings/SettingsScreen.kt`，布局容器位于同目录 `SettingsLayout.kt`，新增设置项须严格遵循以下结构：

- **功能分类（`SettingsSection`）**：固定按用户目标划分为「账号」「外观」「浏览与播放」「保存与命名」「下载与传输」「网络」「同步与数据」「关于」八类（宽窗口侧边栏模式下显示全部八类；紧凑模式下「账号」与「关于」位于「我的」页）。新增设置项优先归入现有分类，不为个别选项增设新类；同一分类内包含多组时才添加组标题，且所有窗口宽度下均保留一级类标题，不得在窄窗口省去类标题改由组标题代行。设置页宽度达到 `900dp` 时在左侧显示分类目录树，当前高亮分类由 `activeSettingsSection` 计算（点击跳转滚动途中及停稳后均保持目标分类高亮，滚至页面最底部时高亮末尾矮分类，由 `ActiveSettingsSectionTest` 守护）。
- **行与卡片组件**：标准设置行统一使用 `SettingsRow` 及其封装 `SettingsSwitchRow`、`SettingsNavigationRow` 并置于 `SettingsGroup` 内；非标准行结构（账号卡片、关于卡片、WebDAV 应用凭证卡）使用 `SettingsCard`。外侧大圆角由 `SettingsGroup` 统一裁切，各行仅绘制内侧小圆角，无需逐行传递首尾位置参数，单项组与多项组圆角自然一致。严禁直接拼接裸 `Surface` 或调用 `SegmentedListItem`。
- **对齐与平台密度（`settingsStyle()`）**：前导图标与行尾控件均相对于「标题 + 说明」整体垂直居中，下方附加控件（分段选择器、主题色块）左缘与标题文字严格对齐。桌面端采用卡片密度（`8dp` 圆角、行距 `4dp`、`20dp` 图标配 `14sp` 标题、行高 `64dp`）；移动端采用 M3 分段列表密度（外侧 `16dp`、内侧 `4dp`、行距 `2dp`、`24dp` 图标配 `16sp` 标题、最小行高 `56dp`）。
- **行尾控件与交互**：布尔项放开关；跳转子页面放右箭头；弹出对话框或当场执行动作不放箭头，触发按钮统一使用 `SettingsRowButton`（Tonal 或 Outlined 样式，禁用文字按钮以防禁用态退化为难以辨认的灰字）。二至三项单选使用 `SettingsSegmentedChoice`（竖屏窄宽度放不下时自动折至标题下方）；多选一弹窗使用 `SettingsChoiceDialog` 配 `SettingsChoiceOption`（点选即刻生效，底部仅设「关闭」）；文本输入弹窗使用 `SettingsInputDialog`（「取消」「保存」）配 `PikoTextField`。
- **级联依赖**：依赖同组紧邻上一行开关的子项使用 `DependentRow` 在父开关关闭时折叠收起；依赖其他分组或跨分类开关的设置项必须常驻显示并置为禁用态（图标、标题与控件降透明度，说明文字保持正常可读并写明「需先开启……」），避免隐藏后用户无法定位该功能。

## 键鼠、触屏与命令面板

- **按指针真实类型分流交互**：输入类型直接读取每次指针事件自带的 `PointerType`，不依赖全局「最近一次输入猜测」。网格与列表统一采用按行对齐的 `LazyVerticalGrid`（`PikoItemGrid`），不使用瀑布流（网盘页专用的等宽循环带视频画布 `GalleryCanvas` 除外）。
- **右键菜单与操作面板（`ContextMenuArea`、`ActionLayout.kt`）**：条目副键点击弹出上下文菜单，若点击项位于当前多选集合内则菜单作用于全部已选项。移动端触屏长按直接进入多选；桌面端触屏与手写笔长按抬起后在按下处弹出同一份上下文菜单（遵循 Windows 触屏规范，通过菜单内「选择」进入多选），鼠标左键按住不触发长按多选（桌面鼠标多选通过主修饰键点选、Shift 连选与空白处框选完成）。嵌套菜单区（如网格空白背景包裹条目）单次按住仅触发最内层。每类列表项通过统一的 `actionsFor` 提供动作表，同时驱动桌面右键菜单与移动底部面板（`PikoSheet`），并由 `layoutActions` 统一排布：破坏性动作（`destructive`）置底并着错误色，「属性」（`ActionGroup.Properties`）置于其前并各自独立成组；其余动作按 `SheetAction.tier` 分为顶部快捷图标行（底部面板带 `shortLabel` 至多 4 项，右键菜单纯图标配悬停提示至多 5 项）与分组列表（非危险项加属性总计不超过 6 项时取消分档全部平铺）。菜单与面板均不设嵌套「更多」翻页或级联子菜单，超出视口高度时整列垂直滚动。菜单打开时初始键盘焦点落在列表首行而非顶部图标行（避免首帧自动弹出图标悬停提示遮挡首行文字）；弹层内方向键导航须在弹层内部获取 `LocalFocusManager`。
- **快捷键、命令面板与桌面细节**：主修饰键统一通过 `PikoPlatform.shortcutModifier` 获取（Windows / Linux 为 `Ctrl`，macOS 为 `⌘`），禁止硬编码 `Ctrl`。新增快捷键必须同步写入快捷键一览表（`F1` 或 `主修饰键+/`，`ShortcutsDialog`）。命令面板（`主修饰键+K`，`CommandPalette`）支持模糊检索最近访问目录、快速访问目录、当前目录子文件夹、全局导航及当前页面通过 `ContributePaletteItems` 动态注册的页面专属命令。横向滚动行须挂载 `verticalWheelScrollsRow` 以支持鼠标垂直滚轮横向滚动；图标按钮统一使用 `TooltipIconButton` 并在提示中标明快捷键；下拉刷新等触屏专属手势在宽窗口必须配有显式刷新按钮。注意：Compose Desktop 悬停移动事件中 `previousPosition` 恒等于 `position`，判定鼠标是否发生真实位移必须自行记录上一帧坐标。**承载弹层（菜单、工具提示、对话框）的自定义布局必须使用普通 `Layout`，严禁使用 `SubcomposeLayout`**，否则桌面端在测量阶段销毁弹层会导致整窗崩溃（根因详见 `../desktopApp/AGENTS.md`）。

## 动效与减少动画

- **平台动效曲线**：动效风格按平台固定（`PikoPlatform.motionStyle`），不随触屏或鼠标输入动态切换：Android 采用富有弹性的 `MotionScheme.expressive()`（同距离约 `400ms`），桌面端采用克制利落的 `MotionScheme.standard()`（几乎无回弹，约 `200ms`）；播放器界面在 `PlayerTheme` 内固定使用 `expressive()`。页面转场统一读取 `theme/Motion.kt` 注入的 `LocalPikoMotion`（桌面端遵循 WinUI 采用 `83ms / 167ms / 250ms`，Android 采用 M3 `150~400ms`，离场动画在任何平台均不超过 `200ms`；顶层切页与压栈横滑均为旧页淡出完毕后新页再淡入，避免双页半透明叠影），禁止自行编写裸 `tween`。
- **菜单定位与面板即时关闭**：桌面右键菜单与命令栏菜单统一包裹 `MenuMotion`（仅 `80ms` 淡入，不做 `0.8 -> 1.0` 缩放动画，避免 `graphicsLayer` 缩放导致右键后快速点击落点偏移）；右键菜单采用 `PointerMenuPositionProvider` 以指针坐标为原点向右下展开，空间不足时单轴翻转，不套用普通下拉菜单整块翻至锚点上方的规则。底部面板执行动作统一调用 `hideThen` 先同步发出关闭状态再执行业务回调，不等待收起动画结束，防止回调修改开关状态被后续关闭覆盖或页面离场导致关闭通知丢失。
- **减少动画链路（`PikoMotionScale`）**：系统无障碍动画开关与应用设置中的「减少动画」（`reduceMotionFlow`，设备本地偏好）取逻辑或，汇总至进程唯一的 `PikoMotionScale`（实现 `MotionDurationScale`），开启时缩放因子置 `0`，所有补间与弹簧动画当场跳至终点，页面与面板切换亦直接跳切（Compose 的 `Transition` 与 `NavDisplay` 从 Recomposer 根协程上下文统一读取缩放因子，无法也不应针对个别页面补淡入淡出）。桌面端在 `Main.kt` 通过 `runBlocking(motionScale) { awaitApplication { ... } }` 注入根协程上下文并向所有子窗口与弹层继承（由 `MotionScaleInjectionTest` 守护），系统状态由 `SystemReducedMotion` 监听（Windows 读取 `SPI_GETCLIENTAREAANIMATION` 并响应 `WM_SETTINGCHANGE` 实时刷新；macOS 读取 `accessibilityDisplayShouldReduceMotion`；Linux 读取 GNOME `enable-animations`，后两者在应用重新激活时重读）；Android 端由 `MainActivity` 为 DecorView 安装注入缩放的 `WindowRecomposer` 并通过 `followSystemAnimatorScale` 衔接开发者选项动画缩放。缩放为 `0` 时无限循环动画会停在末帧，骨架屏等常驻循环装饰动画须读取 `LocalPikoMotion.current.reduced` 绘制静态样式。
