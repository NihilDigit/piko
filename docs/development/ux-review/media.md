# 传输、信息流、播放器截图 UX 审查

范围：`build/shots/` 下 `transfers-*`、`feed-*`、`player-*` 共 137 张，逐张看过。规范引用均为本地镜像路径（`C:\Codes\docmirror4a\` 下）。
「已核实」指读过对应代码确认成因；「疑似」指只凭截图判断，或可能是截图环境所致。

---

## 高

### H1 服务端解压行空白（已核实，成因明确）

- 图：`transfers-extract-desktop-1440x900`、`transfers-extract-phone-400x860`、`transfers-extract-tablet-1280x800`
- 现象：「进行中」第一格只有一条横贯整格的灰底，中间一个解压图标，右端孤零零一个进度条的停止点；文件名、「解压」类别、百分比与进度条都不见。浮动卡片（桌面右下）同一任务显示正常。
- 成因：`ListLeadingIcon`（`ui/.../components/FileListItem.kt:333`）是 `Box(Modifier.fillMaxSize())`，自己不定尺寸，要靠外面包一层定尺寸的盒子。其余各行都经 `ListLeadingMedia(fallback = { ListLeadingIcon(...) })` 调用（它带 `.size(ListLeadingSize).clip(...)`），唯独 `ServerWorkRows.kt:72` 的 `ExtractTransferRow` 与 `:154` 的 `VaultTransferRow` 直接把 `ListLeadingIcon` 交给 `ListItem` 的 leading 槽，`fillMaxSize` 吃掉整行宽度，标题与 supporting 被挤成零宽。归档、取消归档行有同样的问题，只是截图集里没有这两种行。
- 依据：`m3-material-mirror/pages/components/lists.md` 第 724–727 行，列表项由 leading、label、supporting 组成，label 与 supporting 至少要能截断显示，不能整块消失。
- 改法：改掉前提而不是在两处补包装。让 `ListLeadingIcon` 自己定尺寸（加 `size: Dp = ListLeadingSize` 参数，内部用 `.size(size).clip(MaterialTheme.shapes.small)` 取代 `fillMaxSize()`），`ListLeadingMedia` 的 fallback 不再依赖子项撑满；这样任何调用方都不会再撑爆一行。若暂不动组件，最小改法是这两行照 `InstantTransferRow.kt:47` 写成 `ListLeadingMedia(thumbnail = null, fallback = { ListLeadingIcon(...) })`。

### H2 手机传输页筛选的数目被截断，读成错误的数字（已核实）

- 图：`transfers-phone-400x860`（「全部 1」，实为 17）、`transfers-uploads-phone-400x860`（「全部 1」，实为 16）、`transfers-cloud-phone-400x860`（「全部 1」）
- 现象：选中项以外的数目照常显示，「全部」的两位数被裁掉一位，没有省略号，看上去就是「1」。这不是排版瑕疵，是给出错误信息。
- 成因：`TransfersHeader.kt` 的 `KindFilter` 在手机上 `fillWidth = true`，四个按钮 `weight(1f)` 等分；`FilterLabel` 两段 `Text` 都是 `maxLines = 1`、无 overflow，放不下时直接裁切。`InlineFilterMinWidth = 260.dp` 是按「下载 12」估的常数，而等宽时每格都要容下最宽的「全部 17」，四格加标题实际需要约 310dp，估值偏小，`AdaptiveBar` 因而没有把操作收进「更多」，也没有让筛选另起一行。
- 依据：`m3-material-mirror/pages/components/tabs.md` 第 252、268 行，「Don’t truncate labels unless required, as truncated text can impede comprehension」；`fluent-design-mirror/pages/components/web/react/core/tablist/usage.md` 第 88 行，放不下时应收进溢出菜单而不是硬挤。
- 改法：门槛不要用常数，按当前数目量出最宽一格乘以四再加标题（`TextMeasurer` 量一次即可），不够就走已有的 `filterBelow` 分支另起一行；或者在手机上改为按内容定宽、`weight(1f, fill = false)`。数目文字至少要 `softWrap = false` 且不允许被裁，宁可整格变宽。

### H3 文件夹下载的子项名字从尾部截断，八行读起来一模一样（已核实）

- 图：`transfers-batch-desktop-1440x900`、`transfers-batch-tablet-1280x800`、`transfers-batch-phone-400x860`
- 现象：展开「Frieren S01」后，进行中的子项都写作「[SubsPlease] Sousou no Friere...」，唯一能区分的集号被截掉；已完成的同名子项却换行显示了完整名字（「… - 01 (1080p).mkv」），同一网格里行高一高一低。
- 成因：`LocalTransferRow.kt:153` 等处 `headlineMaxLines = if (task.showsProgress) 1 else 2`，进行中的行为了保持行高只给一行，`FileListItem` 用 `TextOverflow.Ellipsis` 从尾部截。
- 依据：`m3-material-mirror/pages/components/lists.md` 第 764–771 行，label 要简短、可扫读；`gnome-hig-mirror/pages/patterns/feedback/progress-bars.md` 第 24 行，进度要有能说明「哪一项、完成多少」的文字。
- 改法：两条可以叠加。一是子项去掉与父文件夹或兄弟项共有的前缀，只显示差异部分（「01 (1080p).mkv」），完整名放悬停提示与详情；二是进行中的行改用中间省略（Compose 1.8 起有 `TextOverflow.MiddleEllipsis`，若当前版本没有，可自己按扩展名与尾部保留若干字符截断），保住集号与扩展名。

---

## 中

### M1 文件夹展开后子项与顶层任务没有层级区分

- 图：`transfers-batch-desktop-1440x900`、`transfers-batch-tablet-1280x800`、`transfers-batch-phone-400x860`
- 现象：父行占满整行，子项与 Oppenheimer、Dandadan 等顶层任务同一缩进、同一网格、同一底色；在手机上 Oppenheimer 紧接在 12 个子项后面，看不出它已不属于这个文件夹。
- 依据：`gnome-hig-mirror/pages/patterns/containers/boxed-lists.md`（分组列表用容器把一组行框在一起）；`m3-material-mirror/pages/components/lists.md` 的分组与分隔用法。
- 改法：子项包进一个与父行相连的容器（`surfaceContainerLow` 底、圆角，与 ui/CLAUDE.md「浅的一方圆角压在深的一方上」一致），或子项整体缩进一个 leading 宽度；展开组结束处留出组间距。

### M2 选中态下进度条的轨道消失

- 图：`transfers-select-desktop-1440x900`、`transfers-select-tablet-1280x800`、`transfers-select-phone-400x860`、`transfers-delete-desktop-1440x900`
- 现象：选中行的底色与进度条轨道（secondaryContainer）几乎同色，只剩已完成那一段和末端停止点，读不出总量与剩余。
- 依据：`m3-material-mirror/pages/components/progress-indicators.md` 第 33–36 行，2023 年的改版专门提高了轨道与指示的对比度（non-text contrast），并加停止点。
- 改法：行处于选中或高亮时给进度条另配 `trackColor`（如 `surfaceContainerHighest` 或 `onSurface` 12%），由 `FileListItem` 经 CompositionLocal 下发，避免每种行各写一遍。

### M3 失败与待确认原因在移动端被截断

- 图：`transfers-cloud-phone-400x860`、`transfers-deleted-phone-400x860`（「资源已失效，...」）、`transfers-tablet-1280x800`（同）、`transfers-folder-failed-phone-400x860`（「文件夹里没有可下...」）、`transfers-folder-quota-phone-400x860`（「待下载 5...」）
- 现象：「需要处理」一节正是要人读原因再决定的，原因却截在一半。同一节里「Mushishi Complete」的「离线超时」单独占第二行，显示完整，写法不一。
- 依据：`m3-material-mirror/pages/components/lists.md` 第 769–771 行，supporting text 可用一到三行。
- 改法：需要处理的行统一把原因放到第二行（与 Mushishi 那种写法一致），类别与状态留在第一行；原因行允许两行。

### M4 暂停的上传不显示进度（已核实）

- 图：`transfers-uploads-desktop-1440x900`、`transfers-uploads-phone-400x860`、`transfers-desktop-1440x900`（vlog-2026-10.mp4）
- 现象：暂停的下载有「5.7 GB / 14.3 GB」与进度条，暂停的上传只写「上传 已暂停 2.0 GB」，看不出传了多少、续传还剩多少。
- 成因：`UploadTransferRow.kt:66` 的 `showsProgress` 只认 `HASHING`、`UPLOADING`。
- 依据：`gnome-hig-mirror/pages/patterns/feedback/progress-bars.md` 第 24 行，进度要写成「12.1 of 30 MB」这类已完成量。
- 改法：`PAUSED`（以及中断后可续传的失败）也显示进度条与「已传 / 总量」，与下载行一致。

### M5 桌面播放器底栏没有音量控件

- 图：`player-desktop-1440x900`、`player-paused-desktop-1440x900`、`player-hud-desktop-1440x900`
- 现象：底栏只有上一集、下一集、时间、倍速、选集、旋转、全屏；音量只能靠滚轮或方向键，调时才在顶部出现 HUD。鼠标用户找不到静音与音量入口。
- 依据：`apple-hig-mirror/pages/playing-video.md` 第 67 行，「Support the interactions people expect, regardless of the input device」；桌面播放器的通行做法是底栏常驻音量键与滑块。
- 改法：桌面形态在时间左侧加音量按钮（点按静音，悬停或点开出横向滑块，复用 HUD 的那条 `PlayerLevelControl`）；移动端维持手势调节不变。

### M6 播放器里的对话框是浅色，与播放器的深色面板不一致

- 图：`player-quality-desktop-1440x900`、`player-quality-phone-400x860`、`player-share-desktop-1440x900`、`player-share-phone-400x860`
- 现象：选择画质下载、分享两个对话框是应用的浅色主题，压在黑色播放画面上非常刺眼；同一播放器里的设置面板、选集面板、错误卡都是深色。
- 依据：`m3-material-mirror/pages/components/dialogs.md` 第 56 行，对话框按所在主题取浅色或深色的色彩角色；播放器既已用 `PlayerTheme` 的深色，弹层应在同一主题下。
- 改法：这两个对话框在播放器内弹出时包进 `PlayerTheme`（或由播放器把自己的 `colorScheme` 传给 `PikoDialog`）。

### M7 信息流「收藏」与提示「已添加星标」用词不一

- 图：`feed-star-desktop-1440x900`、`feed-star-phone-400x860`、`feed-star-tablet-1280x800`
- 现象：按钮写「收藏」「已收藏」，Snackbar 写「已添加星标」，侧边栏的库叫「星标」。同一个动作两个名字，用户会以为「收藏」是另一处列表。另外星爆动画、按钮实心化、Snackbar 三重反馈同时出现，Snackbar 是多余的一层。
- 依据：`fluent-design-mirror/pages/content-design.md`（同一概念全程用同一个词）；`m3-material-mirror/pages/components/snackbar.md` 第 72 行，Snackbar 用于告知已执行的过程，界面本身已有明确反馈时不必再弹。
- 改法：按钮改为「星标」「已星标」（或「加星标」「已加星标」），与库名一致；去掉这条 Snackbar，只在加星标失败时提示。

### M8 播放设置里「选择画质」与「清晰度」混淆

- 图：`player-settings-desktop-1440x900`、`player-settings-phone-400x860`、`player-settings-tablet-1280x800`、`player-settings-phone-landscape-860x400`
- 现象：顶部一排是「分享、下载、选择画质」，其中「选择画质」实际打开的是「选择画质下载」对话框；下面另有「清晰度」一节切换播放画质。两处都在讲画质，前者读起来像是改播放清晰度。
- 依据：`gnome-hig-mirror/pages/guidelines/writing-style.md`，按钮标签要说清动作；`m3-material-mirror/pages/components/segmented-buttons.md` 第 239 行，选项必须清楚表达所代表的内容。
- 改法：把「下载」与「选择画质」合成一个「下载」：有转码档时点下载即弹画质对话框，没有时直接下原画；若要保留两项，改名为「下载原画」「按画质下载」。

### M9 窄窗口的全屏信息流里，关闭信息流的 × 紧挨窗口的 ×（疑似）

- 图：`feed-full-desktop-700x800`
- 现象：信息流顶栏的四个按钮被挤到中间，最右是关闭信息流的 ×，往右隔一小段就是窗口按钮的最小化、最大化、关闭。两个 × 并排，误点即关窗口（主窗口有传输时会藏进托盘，没有时直接退出）。
- 依据：ui/CLAUDE.md 对并进内容的标题栏的约定（标题后的空白是拖动区，操作接在其前）；`apple-hig-mirror/pages/playing-video.md` 第 123 行的精神是不让其他内容与播放控件混淆。
- 改法：全屏信息流本就把返回当作关闭（ui-states.md「信息流」节），窗口按钮在场时把 × 换成行首的返回箭头，右侧只留静音、防窥、弹出；顶栏按钮靠右排，空出的中段登记为拖动区。

### M10 「继续刷」条压在列表末尾的条目上（疑似）

- 图：`feed-suspended-phone-landscape-860x400`（盖住「09」的更多按钮与名字）、`feed-suspended-phone-400x860`（盖住一张 PDF 卡的标签）、`feed-suspended-tablet-1280x800`
- 现象：`FeedResumeBar` 浮在网格上，与 FAB 同一基线；截图停在中段，无法判断滚到底时列表是否留出了它的高度。
- 依据：`m3-material-mirror/pages/components/snackbar.md` 第 218–220 行，浮在底部的元素要避开常用触控目标。
- 改法：确认网格的 `contentPadding.bottom` 包含这条的高度（与 FAB 取大者），滚到底时最后一行完全露出；横屏手机高度只有 400dp，可考虑把这条并进 FAB 左侧同一行，或收成带文字的小号扩展 FAB。

### M11 「全屏播放」胶囊不随控件收起（已核实）

- 图：`player-boost-phone-400x860`（长按倍速时其余控件全收，只剩它和倍速读数）；另见 `player-fullscreen-phone-400x860`
- 现象：长按加速的设计是「控件一并收起，只留倍速读数」（`PlayerControlsOverlay.kt:281`），这枚胶囊却还在画面下方。
- 成因：`PlayerControlsOverlay.kt:708` 的显隐只看 `!isLocked && !isLandscape && isLandscapeVideo && errorMessage == null`，没有 `chromeVisible`。`player-fullscreen-phone` 里它与「退出全屏」图标同时出现，是因为截图窗口转不了屏、`isLandscape` 仍为假，真机上转屏后会消失，那张不算问题。
- 依据：`apple-hig-mirror/pages/playing-video.md` 第 69 行，控件要帮人尽快回到观看本身。
- 改法：显隐条件加上 `chromeVisible`（或至少在 boost 时隐藏）；它若是要在控件收起后仍常驻黑边，也应在长按倍速与沉浸类状态下让位。

### M12 进度条在黑底上几乎看不见，信息流的进度条贴着手势区（疑似）

- 图：所有 `player-*`（未播放部分是深灰细线）、`feed-phone-400x860`、`feed-tablet-1280x800`（底边一条约 2dp 的灰线）
- 现象：播放器滑块的未播放轨道与黑底对比很低，暗场景里看不出总长；信息流的进度条贴在屏幕最底边，在 Android 手势导航下落在系统手势区，拖动会与「回到桌面」冲突。截图没有系统栏，无法确认实际是否让出了手势区。
- 依据：`m3-material-mirror/pages/components/sliders.md` 第 45–56 行（为非文本对比度改版轨道与停止点）；`m3-material-mirror/pages/foundations/designing.md` 第 210–212 行（触控目标至少 48dp）；`android-docs-mirror/pages/develop/ui/compose/system/insets.md` 第 47 行（`mandatorySystemGestures` 无法被 `systemGestureExclusion` 排除）。
- 改法：未播放轨道提到白色 30% 左右，并保留底部渐变遮罩；信息流的进度条整体上移出 `mandatorySystemGestures` 区域，可拖动的触控高度至少 48dp，视觉上仍可是细线。

---

## 低

### L1 「某类为空」只有一行字，信息量远少于全空态

- 图：`transfers-kind-empty-desktop-1440x900`、`transfers-kind-empty-phone-400x860`、`transfers-kind-empty-tablet-1280x800`
- 现象：全空时有图标、说明与「新建离线任务」；某类为空时只有「没有上传任务」。
- 依据：`gnome-hig-mirror/pages/patterns/feedback/placeholders.md` 第 28–30 行，空态宜附一句如何添加内容的说明，必要时给出相关操作。
- 改法：与全空态共用一个占位组件，按类别换图标与说明；上传类给「上传文件」，云端类给「新建离线任务」，下载类说明从网盘页发起。

### L2 全空与首次加载时，桌面岛顶空出一条

- 图：`transfers-empty-desktop-1440x900`、`transfers-skeleton-desktop-1440x900`
- 现象：类别标签整排消失，岛没有随之上移，顶端留一条外框色的空带，岛的上沿与侧边栏岛不齐。
- 依据：ui/CLAUDE.md：没有标签的页「岛直接画到顶」；`TransfersHeader.kt:310` 的注释本身也说「某一类为空时照样列出、数目为 0，按钮随任务出现消失的话，一行的位置老在变」。
- 改法：全空与骨架时同样画出四个标签（数目为 0 或占位），与 `KindFilter` 的既定原则一致，切换时版式不跳。

### L3 骨架屏是单栏，实际列表是三栏

- 图：`transfers-skeleton-desktop-1440x900` 对照 `transfers-desktop-1440x900`
- 改法：骨架按 `PikoItemGrid` 的同一栏数排，加载完成时不发生整页重排。

### L4 文件夹列出「失败」实为空文件夹，却给重试

- 图：`transfers-folder-failed-desktop-1440x900`、`transfers-folder-failed-phone-400x860`
- 现象：「文档 列出失败 文件夹里没有可下载的文件」配一个重试按钮。这不是失败，重试也不会有不同结果。
- 改法：空文件夹单列为「无可下载的文件」，用中性色，操作只给「移除」；网络错误一类才叫「列出失败」并给重试。

### L5 超额待确认的行用错误红与纯图标按钮

- 图：`transfers-folder-quota-desktop-1440x900`、`transfers-folder-quota-phone-400x860`
- 现象：「将超出今日下载额度」用 error 色，右侧只有一个下载箭头，看不出点了是「仍要下载」。这是待人确认，不是出错。
- 依据：`m3-material-mirror/pages/components/lists.md` 的 trailing 元素宜含义自明；GNOME writing-style 对按钮标签的要求。
- 改法：状态用 tertiary（与解压的「需要密码」一致），桌面给带文字的「仍要下载」按钮，移动端放进操作面板首项。

### L6 文案细节

- `transfers-delete-desktop-1440x900`、`transfers-delete-phone-400x860`：标题「删除所选的 7 项?」用了半角问号，应为全角「？」。
- `player-tracks-desktop-1440x900`、`player-tracks-phone-400x860`：「从本机选择字幕...」「从网盘选择字幕...」是三个半角句点，应为省略号「…」（`gnome-hig-mirror/pages/guidelines/writing-style.md` 第 81–87 行）。
- 多选标题桌面写「已选 7 项」（`transfers-select-desktop`），移动写「已选择 1 项」（`transfers-select-phone`），宜统一，取短的「已选 N 项」。
- 倍速写法三种：底栏「1x」、播放器长按读数「2×」（`player-boost-*`）、信息流长按「2 倍速」（`feed-boost-*`）。宜统一为「2×」或「2 倍速」之一。
- `player-resume-*`：「从 12:34 继续播放」像一个待点的操作，实际已经续播，宜写「已从 12:34 继续」；截图里当前时间仍是 01:00，与提示矛盾，疑似截图数据未同步。

### L7 「在网盘中显示」标签比按钮宽，贴着屏幕边

- 图：`feed-phone-400x860`、`feed-tablet-1280x800`、`feed-full-desktop-700x800`、`feed-star-phone-400x860`
- 现象：竖排的三个操作中，最下一项的标签向右溢出到离边缘约 10px，三个标签的中心也不再对齐。
- 改法：标签改短为「定位」或「去网盘」，或给这一列统一的固定宽度与右边距，标签在其中居中。

### L8 信息流空态没有出路，无关按钮仍在

- 图：`feed-empty-desktop-1440x900`、`feed-empty-phone-400x860`、`feed-empty-tablet-1280x800`
- 现象：只有「这里没有可播放的视频」与说明；顶栏仍有静音、横屏、弹出窗口，此刻都没有意义；遍历失败也落在这里（已列在 ui-states.md 问题 15）。
- 依据：`gnome-hig-mirror/pages/patterns/feedback/placeholders.md` 第 30 行。
- 改法：空态只留关闭，正文下给一个「关闭」或「换个文件夹」按钮；遍历失败另给「重试」。

### L9 分享对话框在「随机」下仍画着自定义输入框

- 图：`player-share-desktop-1440x900`、`player-share-phone-400x860`
- 现象：选「随机」时下方仍是一个空的「自定义提取码」输入框，像是还要填；输入框与按钮之间另有一大段空白。
- 依据：ui/CLAUDE.md 要求对话框里不做尺寸变化，所以占位保留是对的，问题在占位里放了什么。
- 改法：同一位置在「随机」下显示已生成的提取码（只读，可换一个），在「无」下显示一行说明，在「自定义」下才是输入框；高度不变，内容都有用。

### L10 网盘字幕选择器没有「此处没有字幕文件」的提示（疑似）

- 图：`player-drive-subtitles-desktop-1440x900`、`player-drive-subtitles-phone-400x860`
- 现象：「网盘 / 动画」下只列出上一级与一个子文件夹，看不出这一层是没有字幕，还是字幕被过滤掉了。
- 改法：当前层没有可用字幕时在列表末尾加一行说明「此文件夹没有字幕文件」，并注明支持的格式。

### L11 桌面播放器常驻锁定按钮（疑似，取决于取舍）

- 图：`player-desktop-1440x900`、`player-lock-desktop-1440x900`
- 现象：锁定是防误触的触屏功能，桌面端用鼠标键盘不存在误触，按钮却占着画面右侧中部最显眼的位置。ui-states.md 写明两端相同，属有意设计，列此供复核。
- 改法：桌面端把锁定移进播放设置或只在触屏输入时显示（输入类型按指针事件判断，ui/CLAUDE.md 已有同样的做法）。

### L12 范围外顺带：深色主题下网格里的视频卡仍是浅紫

- 图：`feed-dark-desktop-1440x900`
- 现象：深色主题下文件夹卡已转深，MKV、MP4 占位卡仍是浅紫高亮色，旁边的信息流又是全黑，对比刺眼。属网盘页，供参考。

---

## 截图环境造成、不计为问题的

- 移动端信息流的顶栏有「在独立窗口播放」按钮（`feed-phone-*`、`feed-tablet-*`、`feed-*-landscape-*`）：`PikoMainScaffold.kt:699` 由 `detachedHost` 决定，截图在桌面 JVM 上加 `--mobile` 渲染，带进了桌面的宿主；真机上应为 null。建议 `:shots` 的移动模式把 `detachedHost` 置空，否则每次审查都会看到它。
- 触屏截图里出现悬停提示：`transfers-delete-phone-400x860` 的「删除 (Delete)」、`feed-landscape-lock-tablet-1280x800` 的「退出横屏」。真机上触屏只在长按时出提示，不算问题；但移动端提示里写键位「(Delete)」只在接键盘时有意义。
- `player-fullscreen-phone-400x860` 里「全屏播放」与「退出全屏」同时出现，见 M11。
- `transfers-folder-failed-desktop`、`transfers-folder-quota-desktop` 中某行带灰底，是截图时的悬停态。
