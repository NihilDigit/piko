# 网盘页截图 UX 审查

范围：`build/shots/` 下 `drive-*`、`tabs*` 共 85 张，跳过 `drive-foldermap*`。截图环境的已知限制（无缩略图、无外框与系统栏）不计。
规范路径均相对 `C:\Codes\docmirror4a\`。凡标「疑似」的，是从图上能看到现象、但不能排除是有意设计或截图脚本所致。

## 高

### H1 桌面 Ctrl+A 全选后看不出选了几项，也没有勾选框与退出入口
- 图：`drive-select-desktop-1440x900`、`drive-trash-select-desktop-1440x900`
- 现象：shots 脚本对桌面是「单击 Oppenheimer 再 Ctrl+A」。结果所有卡片都描了边，但命令栏左端仍是「+ 新建」，没有 `drive-marquee`、`drive-drag-folder` 里那组「✕ 已选 N 项」，卡片上也没有勾选框。只有 Oppenheimer 多一层深底色（焦点），看上去像「只选中了一项，其余是某种描边样式」。回收站同样：三项都描边，命令栏只有两枚图标。
- 依据：`m3-material-mirror/pages/foundations/interaction/selection.md` Click 一节：桌面上选中一项之后，所有条目都显示勾选框；`gnome-hig-mirror/pages/patterns/containers/selection-mode.md:29`：选择模式的标题要写出所选数量，并给取消按钮。框选得到的状态本身就符合这一条，两条入口不一致。
- 建议：全选走与框选相同的多选状态：命令栏换成「✕ 已选 N 项」，卡片显示勾选框。

### H2 「查找重复」与「复制」两枚图标几乎相同，并排出现在命令栏
- 图：`drive-keyboard-desktop-1440x900`、`drive-select-desktop-1440x900`、`drive-marquee-desktop-1440x900`、`tabs-desktop-1440x900`
- 现象：有焦点项或选中项时，命令栏前段是剪切、复制（ContentCopy）、重命名、分享、删除，后段在「全选」旁又是一枚 FileCopy，即「查找重复」。两枚都是叠放的两张纸，都不带文字。没有选中时（`drive-root-desktop-1440x900`）只剩后面那枚，读者更容易把它当成「复制」。
- 依据：`fluent-design-mirror/pages/components/web/react/core/toolbar/usage.md:72`：工具栏可以用图标代替文字，但要用常见图标，不能让人去猜；`m3-material-mirror/pages/components/icon-buttons.md:696`：图标的含义要清楚、无歧义。
- 建议：「查找重复」带文字标签，或者收进「更多」（它的使用频率远低于复制）；如果保留图标，换一个与复制形状不同的。

### H3 回收站的「恢复」「彻底删除」只有图标，两者都是垃圾桶形
- 图：`drive-trash-select-desktop-1440x900`、`drive-trash-select-phone-400x860`、`drive-trash-select-tablet-1280x800`
- 现象：多选后顶栏和命令栏只有两枚垃圾桶：一枚带向上箭头（恢复），一枚带叉、红色（彻底删除），区别只在桶身上的小记号和颜色。触屏上没有悬停提示，只能靠猜。彻底删除找不回。
- 依据：`fluent-design-mirror/pages/components/web/react/core/toolbar/usage.md:72`（同上）；`apple-hig-mirror/pages/accessibility.md:84`：不能只靠颜色传达信息，要辅以不同的形状或文字（错误色是这两枚之间唯一显眼的差别）。
- 建议：回收站多选时只有两到三个动作，宽度足够，两端都改成带文字的按钮（「恢复」「彻底删除」）。移动端顶栏放不下时，可以把「全选」收进溢出菜单，给这两项让出位置。

### H4 加载失败直接显示服务端原始报错，同一件事还连说两遍
- 图：`drive-error-*`（三张）、`drive-stale-*`（三张）
- 现象：整页失败的正文是 `PikPak error_code=3 (http=400), error="invalid_argument": 服务暂时不可用`；旧数据横幅同样把这串原样拼在「内容可能不是最新的：」后面，手机上被截成两行半（`error="invali…`）。同一时刻底部还弹出 Snackbar「加载失败」，与整页标题或横幅重复。
- 依据：`apple-hig-mirror/pages/alerts.md:62`：不要写成「Error 329347 occurred」这类不传达信息的标题；`apple-hig-mirror/pages/writing.md:32,54`：用平实的语言，避免术语，说明怎样解决；`m3-material-mirror/pages/components/snackbar.md:77-88`：同一时刻只出现一条消息，并按重要程度只选一种组件。
- 建议：正文只写「服务暂时不可用，稍后重试」这类可理解的一句，错误码放进日志，或放进可展开的「详细信息」。页面上已经有整页错误或横幅时，不再弹 Snackbar。

### H5 旧数据横幅上的「重试」对比度约 2.4:1
- 图：`drive-stale-desktop-1440x900`、`drive-stale-phone-400x860`、`drive-stale-tablet-1280x800`
- 现象：横幅底色取样为 #FA746F，「重试」是主色蓝（最深处 #45608A），算得对比度约 2.4:1。正文深红 #6E0A12 约 4.6:1，刚好过线。手机 FAB「清空回收站」「清空播放历史」也是这块底色加深红字，同样处在临界值。
- 依据：`m3-material-mirror/pages/foundations/designing.md:58-62`：小号文字至少 4.5:1。
- 建议：横幅改用 errorContainer 与 onErrorContainer 这一对色，「重试」也用 onErrorContainer。

### H6 桌面空文件夹提示「右下角的按钮」，桌面并没有
- 图：`drive-empty-desktop-1440x900`
- 现象：说明写「可用右下角的按钮添加链接、上传文件或新建文件夹」。桌面没有 FAB，「新建」在命令栏左端，「添加链接」在右上角。移动端（`drive-empty-phone`、`-tablet`）的说法是对的。
- 依据：`gnome-hig-mirror/pages/patterns/feedback/placeholders.md:26-30`：空态的说明要指向真实的入口，最好直接在空态里放相关操作。
- 建议：桌面文案改成指向命令栏，或者直接在空态下面放「新建文件夹」「上传」「添加链接」几个按钮，两端共用，也就不必再描述按钮的位置。

## 中

### M1 分区跳转显示的分区与眼前内容不符，换目录后还留着上一页的分区
- 图：`drive-root-*`、`drive-locate-phone-400x860`、`drive-skeleton-*`
- 现象：根目录首屏看到的是没有分区的文件夹和电影，标题下（移动）与命令栏里（桌面）却写着当前分区「Kusuriya no Hitorigoto Season 2」，而这个分区在首屏之外。进「文档」时骨架屏阶段，副标题与命令栏仍是根目录的「Kusuriya…」。反过来，「动画」里有「Frieren」分区，却不出跳转器（`drive-subfolder-*`）。
- 依据：`apple-hig-mirror/pages/searching.md:28` 的同一原则（清楚显示当前所在范围）；项目自己在 `DriveTopBars.kt` 注释里把它定义为「眼前所在的分区」。
- 建议：未分区的首段不应报告某个分区名（可以显示「全部」或不显示）；换目录时立即清掉；只有一个分区时是否出现，两种目录要一致。

### M2 后退、前进按钮按有无历史显隐，前进会占到后退的位置
- 图：`drive-root-desktop-1440x900`（只有 ↑）、`drive-backforward-desktop-1440x900`（← ↑）、`tabs-desktop-1440x900` 与 `drive-vault-movies-desktop-1440x900`（→ ↑）
- 现象：没有历史时后退、前进都不画，「上一级」却以禁用态常驻。只能前进时，→ 出现在平时 ← 所在的位置，手按肌肉记忆去点「后退」会点成「前进」。地址栏的起点也随之左右移动 48dp；命令栏的主页按钮同样只在非根目录出现，整排按钮跟着平移。
- 依据：`apple-hig-mirror/pages/the-menu-bar.md:45`：始终显示同一组命令，不可用时禁用而非隐藏；资源管理器和 Finder 的后退、前进都常驻，按历史置灰。
- 建议：后退、前进、上一级三枚常驻，没有历史时禁用；主页按钮也常驻或干脆去掉（地址栏首段已经能回根目录）。

### M3 全盘搜索后范围开关消失，也回不到当前文件夹；结果不显示位置
- 图：`drive-search-*` 对比 `drive-search-global-*`
- 现象：当前文件夹搜索时，搜索框右端有一个纯文字的「全盘」；点了以后它就不见了，只剩一行「全盘找到 13 项」，没有切回当前文件夹的入口。「全盘」写成文字链接的样子，看不出是一个有开关两态的范围选择。全盘结果是 13 个同名剧集，看不出各自在哪个文件夹。
- 依据：`apple-hig-mirror/pages/searching.md:28`：清楚显示当前的搜索范围；`apple-hig-mirror/pages/search-fields.md:30-32`：用 scope bar 筛选结果。
- 建议：改成常驻的两段范围切换（「此文件夹／全盘」，segmented 或 filter chip），选中态明确；全盘结果在名字下面加一行所在路径。

### M4 移动端在库、压缩包、查重结果里显示排序，桌面不显示；播放历史写着「按创建时间」
- 图：`drive-starred-*`、`drive-recent-*`、`drive-history-*`、`drive-trash-*`、`drive-archive-*`、`drive-duplicates-*`
- 现象：桌面命令栏在这些位置都不给排序，符合 `screens/drive/CLAUDE.md`「库里平铺不解析、不折叠、不给排序」。移动端列表页眉照旧显示「按创建时间 ↓」：播放历史本该按播放时间排，「最近添加」按创建时间排也只是重复它的定义。
- 依据：项目约定（上述 CLAUDE.md）；`m3-material-mirror/pages/components/toolbars.md:440`：工具栏的操作应与当前页面相关。
- 建议：移动端页眉按同一条规则 `driveCommands` 隐藏排序，或者在库里显示该库实际采用的顺序且不可改。

### M5 同一个文件夹在海报墙、列表叫「Frieren」，在图库、地址栏、标签叫「动画」
- 图：`drive-root-desktop-1440x900`、`drive-view-list-*` 对比 `drive-view-gallery-*`、`drive-subfolder-desktop-1440x900`
- 现象：海报墙与列表把文件夹显示为解析出的作品名「Frieren」，点进去以后标签、地址栏、移动端标题都是真实名称「动画」；图库视图直接显示「动画」。人在两处看到两个名字，在地址栏里也找不到刚点的那一项。
- 依据：`apple-hig-mirror/pages/writing.md:20,40`：维护一份常用词表，全应用保持用语一致。解析名显示是有意设计，这里只指出两处名字对不上。
- 建议：解析名作为主标题时，把真实名称放进副行（列表视图的第二行目前给了标签 chip）；或者进入后在地址栏或标题里同时出现解析名。三种视图取名规则一致。

### M6 密码错误时对话框变高，手机上还清空了输入
- 图：`drive-archive-password-*` 对比 `drive-archive-password-wrong-*`
- 现象：出错后多出一行「服务端拒绝了这个密码」，对话框高度从约 302px 增至 324px（手机），整块往上跳；标题同时从「需要密码」改成「密码错误」，和提示行说的是同一件事。手机上密码框被清空，平板、桌面保留了「••••」（疑似截图步骤差异）。
- 依据：`ui/CLAUDE.md`「对话框里不做尺寸动画，提示行常驻、出错只变色（issue #9）」；`apple-hig-mirror/pages/writing.md:64`：错误紧贴字段，告诉人怎么改，避免生硬的说法。
- 建议：提示行常驻（平时写「密码会保存在本机，下次自动尝试」之类，出错时换成「密码不正确」并变色），标题不变；三端保持同样的清空或保留策略。

### M7 网格里的勾选框贴着下一列的文件名
- 图：`drive-marquee-desktop-1440x900`、`drive-drag-folder-desktop-1440x900`、`drive-select-phone-400x860`、`drive-trash-select-*`
- 现象：勾选框画在每张卡片标题行的最右端，列间距只有约 8dp。于是「02」卡片的勾选框紧挨着「03」这几个字，比离它自己的标题「02」还近，扫一眼会把勾和右边那一项对上。
- 依据：`m3-material-mirror/pages/foundations/interaction/selection.md:25-27`：选中用勾选、复选框或底色表示，前提是能看出它属于哪一项。
- 建议：勾选框挪到卡片缩略图内的左上角（照 Google 相册、资源管理器的缩略图视图），或者把标题行左对齐放勾选框。

### M8 移动端从「我的」进库，底栏高亮的却是「文件」
- 图：`drive-library-empty-phone-400x860`、`drive-starred-phone-400x860`、`drive-trash-phone-400x860`、`drive-history-phone-400x860` 及对应平板
- 现象：按 `ui-states.md`，移动端的库是「从『我的』进入，离开即回『我的』」，返回也回到「我的」；但导航栏的活动指示停在「文件」。
- 依据：`m3-material-mirror/pages/components/navigation-bar.md:43,52`：活动指示表示当前所在的目的地。
- 建议：在库里时让「我的」保持高亮（它本质上是压在「我的」上的一层），或者把库做成 `Screen` 压栈页、盖住导航栏。

### M9 查重结果页：重复的「查看」、两个刷新、网格里看不出大小与位置
- 图：`drive-duplicates-desktop-1440x900`、`drive-duplicates-phone-400x860`、`drive-duplicates-tablet-1280x800`
- 现象：人已经在结果页上，底部还弹 Snackbar「找到 2 组重复文件 [查看]」。桌面命令栏右端有刷新，下面一行摘要右侧又有一个刷新加 ✕，两枚刷新上下相距 60px，作用不同（重列目录、重新查重），图标相同。海报墙视图里每份副本只有文件名，没有大小与所在位置，挑留哪一份时缺少依据（组标题只有合计大小）。
- 依据：`m3-material-mirror/pages/components/snackbar.md:77-88`；`m3-material-mirror/pages/components/toolbars.md:506`：同一工具栏里控件要一致，不要混杂。
- 建议：结果页可见时不弹「查看」提示；摘要行的按钮改成「重新查找」文字按钮；查重结果默认用列表视图，或者在海报墙卡片的副行显示大小与所在文件夹。

### M10 窄桌面窗口里「更多」夹在中间，分区跳转只剩七个字母
- 图：`drive-root-desktop-700x800`
- 现象：命令栏是「+ 新建 ⋯ Kusuriy… ▾ ⟳ …」，溢出按钮不在末端；「全部类型」被收进去了，分区跳转却保留着，并被截到无法辨认。
- 依据：`m3-material-mirror/pages/components/toolbars.md:607,645`：放不下的项收进末端（trailing）的溢出菜单；`fluent-design-mirror/pages/components/web/react/core/toolbar/usage.md:34`：溢出按钮取代最后一项。
- 建议：溢出按钮固定在可收起区域的末端；分区跳转在低于最小可读宽度时整体收进「更多」，不截成残字。

## 低

### L1 键盘焦点框压住文件名首字母
- 图：`drive-keyboard-desktop-1440x900`
- 现象：焦点框画在卡片外沿，而标题文字与卡片左沿齐平，「Perfect Days」的「P」被框线压掉一半。
- 依据：`m3-material-mirror/pages/foundations/interaction/states.md:45-51`：焦点态用来标出键盘停在哪一项，示例里焦点框与内容之间留有间距；压住文字属于实现问题。
- 建议：焦点框整体外扩 2 到 4dp，或者标题加左内边距。

### L2 「在网盘中显示」的定位高亮与选中描边一模一样
- 图：`drive-locate-*`
- 现象：落到「文档」时只是描了一圈与选中相同的框，没有勾选框，也没有过渡；用户分不清它是「被选中」还是「被指出」。
- 依据：`m3-material-mirror/pages/foundations/interaction/selection.md:7,27`：选中靠底色或勾选来表现。定位高亮借用了同一种视觉，两者因此混淆（疑似）。
- 建议：定位用一次性的强调（如两次闪烁的 tertiary 底色），几秒后淡去。

### L3 列表、海报墙、图库对同一条目的遮蔽标记不一致
- 图：`drive-view-list-*`、`drive-view-gallery-*` 对比 `drive-root-*`
- 现象：Blade Runner 在列表与图库里是灰底加划掉的眼睛（预览遮蔽），在海报墙里是灰底加播放键，没有遮蔽标记；图库里它连名字都没有。
- 依据：`m3-material-mirror/pages/components/icon-buttons.md:696`（图标含义要一致、无歧义）。
- 建议：三种视图同一套标记；图库卡片无论是否遮蔽都显示名字。

### L4 图库卡片的文件名过小
- 图：`drive-view-gallery-*`
- 现象：卡片底部文字约 10 到 11px，浅底上的深灰字，比列表与海报墙小两档。
- 依据：`m3-material-mirror/pages/foundations/designing.md:58-62`：小号文字对比度至少 4.5:1，浅蓝底上的小字接近这个下限（疑似）。
- 建议：至少 labelMedium（12sp），并给底部加深渐变遮罩保证对比。

### L5 卡片尺寸按钮没有标签，又与「图库」图标相似
- 图：`drive-root-tablet-1280x800`、`drive-view-gallery-tablet-1280x800`、`drive-history-phone-400x860`
- 现象：视图三段按钮左边单独一枚 PhotoSizeSelect 图标（卡片大小），无底色、无文字，与右边「图库」的照片图标形似；切到列表视图时它消失，整排按钮右移。
- 依据：`fluent-design-mirror/pages/components/web/react/core/toolbar/usage.md:72`；`m3-material-mirror/pages/components/toolbars.md:506`。
- 建议：把卡片大小并进视图菜单（桌面已经这样做），或者给它一个带文字的下拉按钮；列表视图下置灰而非移除。

### L6 移动端列表页眉的内容随可用宽度变化
- 图：`drive-history-phone-400x860` 对比 `drive-recent-phone-400x860`
- 现象：播放历史没有「全部类型」，视图切换就直接摆在页眉里；其他页面视图切换收在 ⋮ 中。同一部手机上，视图切换的位置随页面变化。
- 依据：`m3-material-mirror/pages/components/toolbars.md:506`。
- 建议：手机宽度下视图切换固定收进 ⋮。

### L7 压缩包内没有只读提示，空包写「此文件夹为空」
- 图：`drive-archive-*`、`drive-archive-empty-*`
- 现象：包里只能打开、下载、解压，但界面除地址栏里的「.zip」外没有任何只读提示；空的一层标题写「此文件夹为空」，副行才说「压缩包里的这一层」。
- 依据：`gnome-hig-mirror/pages/patterns/feedback/placeholders.md:26`：标题要直接说明状态。
- 建议：空态标题改为「这一层没有内容」；可在列表上沿放一行只读提示，或者把「全部解压」的说明写进地址栏后的副文字。

### L8 两个「网盘」标签无法区分；非活动标签没有关闭按钮
- 图：`tabs-desktop-1440x900`
- 现象：前两个标签都是文件夹图标加「网盘」；关闭按钮只在活动标签上出现，关闭后台标签要先切过去。
- 依据：`gnome-hig-mirror/pages/patterns/nav/tabs.md:17`：每个标签都提供关闭（上下文菜单的最后一项）。
- 建议：悬停时在非活动标签上显示 ×；库与压缩包标签用各自的图标（目前回收站、星标的标签也是文件夹图标，见 `drive-trash-desktop`、`drive-starred-desktop`）。

### L9 文件名在任意字符处断行
- 图：`drive-history-desktop-1440x900`（「…x264.mk / v」）、`drive-search-phone-400x860`（「…18301 / 2.jpg」）、`drive-starred-phone-400x860`
- 现象：长文件名在第二行留下一两个孤立字符，扩展名被拆开。
- 依据：无直接条文，属可读性问题（疑似）。
- 建议：文件名改为中间省略（保留扩展名），或在 `.`、`_`、`-`、`[` 处优先断行。

### L10 整页失败与空回收站时仍给出无效操作
- 图：`drive-error-*`（FAB、「新建」「添加链接」「信息流」仍可用）、`drive-library-empty-*`（空回收站仍有搜索）
- 依据：`apple-hig-mirror/pages/the-menu-bar.md:45`（不可用时禁用）。
- 建议：目录列不出来时禁用新建与信息流；空库里禁用搜索。

### L11 移动端子目录顶栏没有面包屑
- 图：`drive-subfolder-phone-400x860`、`drive-empty-phone-400x860`、`drive-stale-phone-400x860`
- 现象：`ui-states.md` 与 `screens/drive/CLAUDE.md` 都写着移动端是「目录名作标题、上级另成一行面包屑」，图上标题下只有分区跳转（或什么都没有），看不到上级。
- 依据：项目文档与实现不一致；`m3-material-mirror/pages/components/app-bars.md:440`。
- 建议：确认面包屑是否已被分区跳转取代；若是，更新两份文档；若不是，补上面包屑行。

## 截图覆盖（非界面问题，供修 shots 用）

- `drive-drag-folder-desktop`、`drive-drag-dropped-desktop`：按下点在未选中的 Oppenheimer 上，按设计这是框选而不是拖放，两张图实际拍的是框选了四项，没有拖放预览，也没有带「撤销」的提示。要先单击 Oppenheimer 让它取得焦点再拖。
- `drive-select-desktop`：如果 Ctrl+A 不进入多选是有意设计（H1 不成立），这张图就拍不到桌面多选，建议改为 Ctrl+单击两项。
