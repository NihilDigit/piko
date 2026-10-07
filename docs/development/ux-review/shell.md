# Piko 界面审查：外壳、页面、更新、任务（shell- / pages- / update- / task-，116 张）

范围：`build/shots/` 下以 shell-、pages-、update-、task- 开头的全部截图。已排除 `docs/development/ui-states.md` 的已知问题与截图环境限制（无视频与缩略图、无窗口外框与系统栏、移动端设置页的「紧凑标题栏」）。
规范引用路径相对 `C:\Codes\docmirror4a\`。「已核实」指读过代码确认；「疑似」指只凭截图判断。

---

## 高：功能误导或不可用

### H1 解压密码框与「已开始解压」提示同时出现
- 图：task-extract-password-phone-400x860、task-extract-password-tablet-1280x800
- 现象：「需要密码 android-sdk-backup.7z」对话框正等待输入，下方 Snackbar 却写「已开始解压」并带「查看」。用户会以为已经开始解压，实际上还卡在密码这一步。桌面同一状态（task-extract-password-desktop）的浮动卡片写的是「需要密码」，两端说法不一致。
- 依据：`apple-hig-mirror/pages/writing.md`「Write clear error messages… be clear about what someone can do」；`m3-material-mirror/pages/components/snackbar.md` 第 77 行「Only one snackbar may be displayed at a time」，说明 Snackbar 应只反映当前这一条状态，不能和对话框说的相反。
- 建议：服务端回报需要密码时撤掉或不发「已开始解压」，改在密码提交后再提示；或把提示改成「需要密码」，与桌面卡片用同一份状态文案。

### H2 快捷键一览列了不存在的快捷键（已核实）
- 图：shell-shortcuts-desktop-1440x900、shell-shortcuts-phone-400x860、shell-shortcuts-tablet-1280x800
- 现象：桌面写着「Ctrl+1 / 2 / 3：切到文件、传输、我的」。桌面没有「我的」，`PikoMainScaffold.kt:1045` 在桌面对 `Key.Three` 直接 `return false`，按了没有反应；同一台桌面的命令面板「前往」里也只列了 Ctrl+1、Ctrl+2（shell-palette-desktop）。移动端（接键盘时可开）原样显示整张桌面表：「Ctrl+B 收起或展开侧边栏」（移动端没有侧边栏）、「鼠标侧键」「Ctrl+滚轮」「标签页」等。`ShortcutsDialog.kt` 是一张不分平台的硬编码表。
- 依据：`m3-material-mirror/pages/components/icon-buttons.md` 第 696 行「meaning should be clear and unambiguous」同样适用于帮助文本；`apple-hig-mirror/pages/writing.md`「Match your tone to the context」。
- 建议：表项按 `formFactor` 过滤。桌面写成「Ctrl+1 / 2：切到文件、传输」，移动端去掉侧边栏、标签页、鼠标三组。

---

## 中：明显违背规范或不一致

### M1 移动端「网盘」标题下的分组跳转下拉，看起来像当前路径
- 图：shell-nav-phone-400x860、shell-nav-tablet-1280x800、shell-nav-dark-phone-400x860、update-*-phone/tablet 背景、task-leave-duplicates-phone（「查找重复」下的「Frieren 02 ▾」）
- 现象：用户在根目录，顶栏标题「网盘」正下方写着「Kusuriya no Hitorigoto Seas… ▾」。这个位置在子目录里放的是面包屑（task-reveal-phone 的「网盘 › 动画」），在这里放的却是「跳到某个分组」的下拉。用户容易把它当成路径，以为自己在这个子文件夹里。桌面把同一个控件放在命令栏（shell-nav-desktop），没有这个歧义。
- 依据：`m3-material-mirror/pages/components/app-bars.md` 第 261–269 行，subtitle 用于补充标题，避免往里加额外控件；`ui-states.md`「顶栏目录名加面包屑」把这一行定义为面包屑。
- 建议：分组跳转挪进列表页眉（与排序、筛选同一行），或者只在列表的分组标题上提供；顶栏副标题行只放面包屑。

### M2 我的分享：骨架屏是列表，加载完成后是卡片网格
- 图：pages-shares-loading-desktop-1440x900、pages-shares-loading-phone-400x860、pages-shares-loading-tablet-1280x800，对比 pages-shares-desktop / tablet
- 现象：骨架屏是十行「方块加两条线」，实际内容是三栏（平板与桌面）或单栏的大卡片，每张带链接条、四项统计和按钮行，加载完成时整页布局跳变。
- 依据：`fluent-design-mirror/pages/components/web/react/core/skeleton/usage.md` 第 10 行「Skeletons are great for hinting at the structure of information in a layout… If you don't know the structure, try a progress bar or spinner」。
- 建议：骨架改成与分享卡片同形的占位卡（头部方块、两行标题、一条链接条），栏数与加载后的网格一致。

### M3 我的分享：加载失败时整页错误与 Snackbar 重复报同一件事
- 图：pages-shares-error-desktop-1440x900、pages-shares-error-phone-400x860、pages-shares-error-tablet-1280x800
- 现象：页面中央已经有「加载失败／读取分享失败／重试」，底部又弹一条「加载失败」Snackbar。两处都没有说明原因（断网、会话过期还是服务端错误），「读取分享失败」只是把标题换了个说法。
- 依据：`m3-material-mirror/pages/components/dialogs.md` 第 239 行，低优先级信息用 Snackbar；整页错误已经是持久提示，再弹 Snackbar 属于重复。`apple-hig-mirror/pages/writing.md` 第 64 行「Avoid robotic error messages with no helpful information」。
- 建议：首屏失败只保留整页错误态，Snackbar 留给「已有数据时刷新失败」。副标题写可操作的原因，如「网络不可用」或「登录已失效」。

### M4 登录出错时直接显示接口原文
- 图：shell-login-error-desktop-1440x900、shell-login-error-phone-400x860、shell-login-error-tablet-1280x800
- 现象：密码框下的错误文字是「PikPak error_code=4002 (http=400), error="invalid_account_or_password": 账号或密码错误」，占两行，真正有用的信息排在最后。
- 依据：`m3-material-mirror/pages/components/text-fields.md` 第 10 行「Keep labels and error messages brief and easy to act on」；`apple-hig-mirror/pages/writing.md` 第 32 行「avoiding jargon」。
- 建议：界面只显示「账号或密码错误」，错误码写进日志。已知错误码映射成中文，未知的统一写「登录失败，请稍后重试」。

### M5 登录页的错误提示把整块表单往上推
- 图：shell-login-desktop（logo 顶在 y≈210）对比 shell-login-error-desktop（y≈192）、shell-login-expired-desktop（y≈200）；手机端同样上移约 20px
- 现象：表单在页面里垂直居中，错误文字一出现，表单就增高，logo、标题和输入框整体上跳；跳动幅度还随错误行数变化。
- 依据：`m3-material-mirror/pages/components/text-fields.md` 第 521 行「Swapping supporting text with error text prevents new lines of text from bumping content and changing the layout」。
- 建议：给支持文本预留一行固定高度，或改为顶部对齐（固定上边距），不随内容高度重新居中。错误原文缩短后（见 M4）也能避免两行。

### M6 「会话失效」状态把用户名框也标成错误
- 图：shell-login-expired-desktop-1440x900、shell-login-expired-phone-400x860
- 现象：提示是「登录已失效，请重新输入密码」，只需要重输密码，但用户名框的描边和标签也是红色，看起来像用户名也有问题。
- 依据：`m3-material-mirror/pages/components/text-fields.md` 第 85 行，错误态只用于标出需要修正的字段。
- 建议：只给密码框加错误态；用户名框保持正常，可以设为只读。

### M7 同一个「显示密码」按钮，两处图标的语义相反
- 图：shell-login-*（密码隐藏时显示「划掉的眼睛」）对比 task-extract-password-*（密码隐藏时显示「睁开的眼睛」）
- 现象：登录页与解压密码框处于相同状态（密码隐藏），图标却相反，至少有一处会让用户以为点下去是隐藏。
- 依据：`m3-material-mirror/pages/components/text-fields.md` 第 692 行「when a password is hidden, the label for the view icon is "Show password"」，即图标表示点击后的动作；两处应统一。
- 建议：抽一个共用的密码输入框组件，两处都按「隐藏时显示睁眼（显示密码）」处理。

### M8 侧边栏同时亮两处
- 图：shell-nav-desktop-1440x900、shell-nav-collapsed-desktop-1440x900、shell-nav-dark-desktop-1440x900
- 现象：在根目录时，「文件」按钮是实心强调色，快速访问里的「网盘」也有选中底色；收起成窄轨后，文件夹图标与云朵图标同样同时被选中。
- 依据：`ui/CLAUDE.md` 自己的约定「只亮一处：人在固定的文件夹里时亮它，否则亮当前页」；`m3-material-mirror/pages/components/navigation-rail.md` 中 active indicator 只标一个目的地。
- 建议：人在已固定的文件夹里时，「文件」按钮退回描边样式，只亮快速访问那一项；否则反过来。

### M9 蜗牛模式几乎看不出来
- 图：shell-snail-desktop-1440x900 对比 shell-nav-desktop-1440x900（逐像素比对只差 151 个像素）
- 现象：开了限速以后，「2 项」按钮只有图标和文字从灰色变成偏紫的灰色，底色不变。`ui/CLAUDE.md` 写的是「『传输』按钮整个用强调色」，截图与设计描述不符；而且只靠色相的细微变化传达状态。
- 依据：`m3-material-mirror/pages/foundations/designing.md` 第 46 行，对比度要让用户能区分元素与状态；`ui/CLAUDE.md` 的蜗牛模式描述。
- 建议：按设计给整个按钮换强调容器色，再加一个蜗牛图标或「限速」字样，不只依赖颜色。若截图工具没有正确置入蜗牛状态，先修工具再复核。

### M10 浮动卡片挡住 Snackbar
- 图：task-coexist-desktop-1440x900
- 现象：Snackbar「正在查找『网盘』中的重复文件」右端被「Frieren S01」浮动卡片压住，如果它带操作按钮，按钮会被盖住。
- 依据：`m3-material-mirror/pages/components/snackbar.md` 第 218 行「snackbars can be nudged upwards to avoid overlapping with other UI elements near the bottom」，第 250–262 行要求 Snackbar 既不在 FAB 前面也不在后面。
- 建议：有浮动卡片时，Snackbar 让到卡片左侧（限制最大宽度）或上移到卡片栈上方；卡片收成胶囊时同理。

### M11 更新对话框没有「稍后」，下载期间整窗被锁
- 图：update-startup-*、update-downloading-*、update-installing-*
- 现象：开屏弹出的对话框只有「忽略此版本」和「下载并安装」，想以后再说只能靠点外部或 Esc，界面上看不出这条路；「忽略此版本」是永久性操作，却被放在 dismiss 的位置。下载 48 MB 期间是模态对话框，点外部与返回都无效，用户只能等或者取消。
- 依据：`m3-material-mirror/pages/components/dialogs.md` 第 366 行「If two actions are provided, one must be a confirming action, and the other a dismissing action」，「忽略此版本」不是 dismiss；`apple-hig-mirror/pages/loading.md` 第 22 行「Let people do other things in your app… while they wait」。
- 建议：动作改成「稍后」（文字按钮）与「下载并安装」，「忽略此版本」降为次要链接，或放进关于卡片。开始下载后允许关闭对话框，进度转到侧边栏账号行或浮动卡片，下载完成后再提示「重启并更新」。

### M12 「等待安装」没有说明在等什么，也出现在桌面截图里
- 图：update-installing-desktop-1440x900、update-installing-phone-400x860、update-installing-tablet-1280x800
- 现象：主按钮变成灰色的「等待安装」，说明行是空的，对话框中部留出一块空白。用户不知道该去系统安装器里确认，还是继续等。`ui-states.md` 写明这是 Android 独有的状态，但桌面也出了同一张图（疑似截图工具没有按平台过滤，也可能是实现没过滤）。
- 依据：`apple-hig-mirror/pages/loading.md` 第 30 行「Clearly communicate that content is loading and how long it might take」。
- 建议：说明行写「请在系统安装界面确认」，再加「重新打开安装界面」按钮；核对桌面是否真的能进入这个状态。

### M13 关于卡片在屏幕上，设置目录却亮着「传输与网络」
- 图：update-about-desktop-1440x900
- 现象：右侧滚到了「关于」卡片（Piko 版本 1.2.1、查看更新），左栏目录高亮的却是「传输与网络」，「关于」没有亮。
- 依据：`m3-material-mirror/pages/components/navigation-rail.md`，active indicator 应反映当前位置。
- 建议：滚动联动按视口中线或顶部判定当前分区；滚到底时强制高亮最后一节。

### M14 单项分组卡片的圆角与多项分组不一致
- 图：pages-settings-desktop-1440x900（WebDAV 卡片）、pages-settings-phone-400x860、pages-webdav-desktop/phone/tablet（「Infuse」应用卡片）
- 现象：多项分组（同步设置、同步播放记录）首尾是 16dp 左右的大圆角，只有一项的分组（WebDAV、Infuse）四角只有约 4dp，与整页其他卡片不协调（放大裁切后可以确认）。
- 依据：`m3-material-mirror/pages/components/lists.md` 第 73 行「An expressive list has a segmented style and round corners」，单项组同样是首也是尾。
- 建议：分组列表的圆角按「是否为首项」「是否为尾项」分别计算，单项组两者都成立，取四角大圆角。

### M15 分享卡片：文件夹显示成视频图标，过期与「永久」并存
- 图：pages-shares-desktop-1440x900、pages-shares-phone-400x860、pages-shares-tablet-1280x800
- 现象：「动画 13 项」是文件夹，图标却是电影场记板。「三体 全集.epub」标着「已过期」，有效期一栏仍写「永久」，自相矛盾（疑似：服务端可能因源文件删除而失效，界面没有说明原因）。
- 依据：`m3-material-mirror/pages/components/icon-buttons.md` 第 7 行「use a system icon with a clear meaning」；`apple-hig-mirror/pages/writing.md` 第 54 行。
- 建议：文件夹用文件夹图标；过期原因不是到期时，有效期一栏写失效原因（如「源文件已删除」），不写「永久」。

### M16 「查找重复」已经扫完且没有结果，仍要确认「扫描结果不会保存」
- 图：task-claim-duplicates-to-addlink-tablet-1280x800（sheet 显示「未发现重复文件」）、task-claim-duplicates-to-addlink-phone-400x860
- 现象：没有任何可丢失的结果，却仍弹出「结束查找重复并添加链接？扫描结果不会保存」。
- 依据：`gnome-hig-mirror/pages/patterns/feedback/dialogs.md` 第 20 行，用户习惯不读就点掉确认框，确认只用于真有损失的操作。
- 建议：扫描已结束且结果为空时，`claim` 直接放行，不再询问。

---

## 低：打磨

### L1 移动端条目上的「更多」按钮太小
- 图：shell-nav-phone-400x860、shell-nav-tablet-1280x800、task-reveal-phone-400x860
- 现象：每个条目右下角的 ⋮ 按钮只有约 22×22px 的可见底色，与文件名挤在同一行，按钮之间、按钮与相邻条目之间的间隔很小。
- 依据：`m3-material-mirror/pages/foundations/designing.md` 第 212 行「consider making touch targets at least 48 x 48dp」；`components/icon-buttons.md` 第 517 行。
- 建议：保持视觉尺寸，用 `minimumInteractiveComponentSize` 把热区扩到 48dp（疑似当前热区与可见尺寸相同，需要在真机上用布局检查器核对）。

### L2 移动端视图切换的位置不固定
- 图：shell-nav-phone（根目录：排序、筛选、⋮）对比 task-reveal-phone（子目录：排序加四个视图按钮）；平板四个按钮里第一个是孤立的「图片」图标
- 现象：同一个页面，视图切换在根目录收进 ⋮，在子目录展开成按钮组；平板上「图片」图标与另外三个分段按钮并排却不属于同一组，含义不明（疑似是「显示缩略图」开关）。
- 依据：`m3-material-mirror/pages/components/icon-buttons.md` 第 696 行，图标含义要清楚；第 10 行，桌面端悬停要有提示。
- 建议：「图片」开关与视图分段按钮之间拉开距离或加分隔，并给出 contentDescription；移动端列表页眉的项保持固定顺序，放不下时再收进「更多」。

### L3 「最近添加」的图标像警告
- 图：shell-nav-desktop（侧边栏）、pages-profile-*、shell-nav-collapsed-desktop（窄轨）
- 现象：用的是带感叹号的锯齿徽章（new_releases），在窄轨上只剩图标，容易读成「警告」或「出错」。
- 依据：`m3-material-mirror/pages/components/icon-buttons.md` 第 704 行「Ensure the meaning of the icon is clear」。
- 建议：换成 `Schedule`、`History` 一类表示时间的图标，或 `NewReleases` 之外的「新增」类图标。

### L4 窄轨侧边栏的图标没有文字
- 图：shell-nav-collapsed-desktop-1440x900、shell-nav-narrow-desktop-700x800、pages-settings-desktop-700x800
- 现象：库的五项只剩图标；「我的分享」与「最近添加」的图标在小尺寸下都难认。「2 项」挂在传输图标下方，看不出是传输项数。
- 依据：`m3-material-mirror/pages/components/navigation-rail.md` 第 336 行「All destinations with text labels」。
- 建议：确认每个窄轨图标都有悬停提示（TooltipIconButton）；「2 项」改成图标上的徽标数字。

### L5 连体按钮里的「2 项」没有说明是什么
- 图：shell-nav-desktop 及所有桌面截图；shell-cards-capsule-desktop
- 现象：「传输」按钮有任务时只写「2 项」，没有「传输」二字；胶囊又写「2 项」（浮动卡片数），侧边栏同时写「3 项」，两处都叫「项」却指不同的东西。
- 依据：`m3-material-mirror/pages/components/icon-buttons.md` 第 820 行，标签要描述它是什么或做什么。
- 建议：侧边栏写「传输 2」或在悬停提示里写全；胶囊改用图标叠放加数字，不与传输项数共用「N 项」的写法。

### L6 浮动卡片的前导图标是一团不明形状
- 图：shell-cards-stack-desktop、shell-cards-extract-desktop、shell-cards-capsule-desktop
- 现象：解压卡片左侧和胶囊里是一个深蓝色的不规则色块（疑似 M3 Expressive 的 LoadingIndicator 变形中的一帧），静态看去像图标渲染失败；加上旁边的进度条，进度表示重复了。
- 依据：`m3-material-mirror/pages/components/loading-indicator.md`，加载指示器用于时长未知的等待，已有确定进度时用进度条。
- 建议：卡片前导位置放任务类型图标（解压、归档），进度只用进度条。

### L7 我的分享：卡片高度不齐，按钮全靠图标
- 图：pages-shares-desktop-1440x900、pages-shares-select-desktop-1440x900
- 现象：中间那张卡片的文件名换成两行，整张卡片更高，三张卡片的按钮行不在同一水平线。文件夹和外链两个按钮只有图标；多选顶栏只有一个红色的「断链」图标表示「取消分享」，危险操作没有文字。选中后，卡片里「复制链接」的底色消失，样式随选中状态变化。
- 依据：`m3-material-mirror/pages/components/icon-buttons.md` 第 7 行、第 10 行；`ui/CLAUDE.md`「危险项……错误色」的同时要可辨认。
- 建议：同一行的卡片等高（按钮行贴底）；多选顶栏的危险操作写成「取消分享」文字按钮；卡片内图标按钮补提示。

### L8 账号切换行的 × 含义不明
- 图：pages-accounts-desktop-1440x900、pages-accounts-phone-400x860、pages-accounts-tablet-1280x800
- 现象：「备用账号」行末是 ×，可能被理解为「关闭」或「移除」；点行本身是切换账号，但行上没有任何提示。
- 依据：`m3-material-mirror/pages/components/icon-buttons.md` 第 820 行，accessibility label 要描述动作。
- 建议：× 换成「移除」类图标（如 PersonRemove）并加提示「移除账号」；行上加副标题「轻触切换」或 chevron。

### L9 设置页的分区标题在不同宽度下不一致
- 图：pages-settings-desktop-1440x900（大标题「账号与同步」，下面是小标题「同步」「外部访问」）、pages-settings-desktop-700x800（小标题「账号」「同步」）、pages-settings-tablet-1280x800（「账号与同步」之后直接是同步项，没有「同步」小标题，「外部访问」仍有）、pages-settings-phone（有「同步」）
- 现象：同一组设置，四种宽度下标题层级各不相同。
- 依据：`m3-material-mirror/pages/components/lists.md` 第 129 行，样式不影响行为，分组结构应保持一致。
- 建议：分区标题由同一份结构生成，平板缺失的「同步」小标题要补上。

### L10 更新对话框的说明区与按钮之间有一块空白
- 图：update-startup-*、update-about-*、update-installing-*、update-login-*
- 现象：更新说明卡片与按钮之间约 70px 空白，有提示行的状态（ready、failed、noinapp）才把它填上。这是为防止对话框跳动而预留的行，但在无提示的状态下显得像漏了内容。
- 依据：`ui/CLAUDE.md`「对话框里不做尺寸动画，提示行常驻」；`m3-material-mirror/pages/components/dialogs.md` 第 539 行。
- 建议：预留的提示行在空闲时放一句中性信息（如「下载约 48.0 MB」，并从副标题挪过来），空白就有了用途。

### L11 更新对话框与代理对话框风格不统一
- 图：shell-login-proxy-*（图标加居中标题）对比 update-*（左对齐标题、无图标）、task-upload-target-*（左对齐无图标）
- 现象：同属 `PikoDialog`，有的带 hero 图标、居中标题，有的左对齐。
- 依据：`m3-material-mirror/pages/components/dialogs.md`，使用 hero 图标时标题居中，不用时左对齐，本身都合规；问题在于选择规则不清。
- 建议：约定只有设置类与确认类对话框用 hero 图标，信息类与进度类不用，并写进 `ui/CLAUDE.md`。

### L12 「继续刷」口语化
- 图：task-claim-feed-phone-400x860、task-claim-feed-tablet-1280x800
- 现象：底部条写「继续刷『网盘』」。按仓库的文案约定（UI 文案用书面语），「刷」偏口语。
- 依据：`m3-material-mirror/pages/foundations/content-design/style-guide.md`（简洁、一致）；用户全局约定「UI 文案……一律用书面语」。
- 建议：改为「继续浏览『网盘』」或「返回信息流」。

### L13 上传去向对话框不列文件
- 图：task-upload-target-desktop、task-upload-target-phone、task-upload-target-tablet
- 现象：只写「上传 2 个文件」，看不到是哪两个文件，也没有总大小；外部拖入或分享多个文件时无法核对。
- 依据：`apple-hig-mirror/pages/writing.md` 第 54 行，提示要让人知道将发生什么。
- 建议：一个文件时标题写文件名；多个时在保存位置上方列前两三个文件名加总大小。

### L14 窄窗口命令栏里分组跳转只剩「Kusuriy…」
- 图：shell-nav-narrow-desktop-700x800、shell-nav-overlay-desktop-700x800
- 现象：700dp 宽时排序和筛选收进「…」，分组下拉却留在外面，只显示「Kusuriy…」，宽度不足以辨认，反而比排序更难用。
- 依据：`ui/CLAUDE.md`「AdaptiveBar……放不下时从低往高收进『更多』」；`m3-material-mirror/pages/components/app-bars.md` 第 327 行，可以截断，但要能辨认。
- 建议：把分组下拉的优先级调到排序之下，窄时先收它。

### L15 1440 宽桌面截图没有窗口按钮，700 宽却有（疑似）
- 图：shell-nav-desktop-1440x900（标签栏右侧空白）对比 shell-nav-narrow-desktop-700x800（有最小化、最大化、关闭）
- 现象：同为桌面主界面，一张有自绘窗口按钮，一张没有。截图环境说明里写了没有窗口外框，但两张不一致。
- 依据：`ui/CLAUDE.md`「主界面任何宽度都接管」紧凑标题栏。
- 建议：核对 `:shots` 是否只在窄宽度或 `--caption` 时画按钮；若实现确实依赖宽度，属于 bug。

### L16 深色模式下视频占位块仍是浅紫色
- 图：shell-nav-dark-desktop-1440x900、shell-nav-dark-phone-400x860
- 现象：MKV/MP4 的类型占位块在深色主题里仍是浅色 lavender，与周围深色文件夹形成很强的亮度跳变。这是没有缩略图时用户真实会看到的样子，不属于截图限制。
- 依据：`m3-material-mirror/pages/styles/color/roles.md`，容器色应随主题取对应的 container 角色。
- 建议：占位块改用 `tertiaryContainer` / `onTertiaryContainer` 这类随主题变化的角色色。

### L17 快捷键一览滚动区域截断在半行，没有分隔线
- 图：shell-shortcuts-desktop-1440x900（「图库里换格子大小」只露出半行）、shell-shortcuts-tablet-1280x800、shell-shortcuts-phone-400x860
- 现象：可滚动内容在半行处被切断，与按钮区之间没有分隔线，滚动提示不明显。
- 依据：`m3-material-mirror/pages/components/dialogs.md` 第 539 行，滚动时标题与按钮固定；可滚动内容与按钮之间应有分隔。
- 建议：滚动区与按钮行之间加一条 divider；滚动区高度取整到行高。
