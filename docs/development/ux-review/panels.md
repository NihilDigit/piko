# 面板与对话框 UX 审查

范围：`build/shots/` 下 `panel-*`、`dlg-*` 共 111 张。规范路径相对于 `C:\Codes\docmirror4a\`。
截图环境的已知限制（无画面、图片查看器只有顶栏、无外框）不计。标「疑似」的是仅凭截图无法确认的。

## 高

### H1 批量重命名在单栏形态下首屏看不到预览，确认按钮却已可用
- 图：`panel-rename-narrow-desktop-800x860`、`panel-rename-phone-400x860`
- 现象：窄窗只剩规则栏，底部「重命名 6 项」已是可点的主按钮，但整屏没有一行「原名 → 新名」。手机上「仅显示变更项」被底栏压住一半，预览疑似在规则下方，须滚动才能看到，且没有任何提示。批量改名是一次改几十个文件的操作，看不到结果就能提交。
- 依据：`m3-material-mirror/pages/components/dialogs.md` 第 539 行，滚动时标题与按钮固定，所选内容须与它们同时可见；`gnome-hig-mirror/pages/patterns/feedback/dialogs.md` 的 Action Dialogs 一节，动作对话框先给出动作的选项与信息，再执行。
- 建议：单栏时把预览做成与规则并列的第二个分段（「规则／预览 6」），或在按钮行上方常驻一行摘要「6 项将改名」，点开跳到预览。至少让底部按钮旁的状态文字（宽窗的「无需改名」「6 项有问题」）在单栏下同样出现。

### H2 解压密码对话框写「仅存于本机」，与跨设备同步的事实相反
- 图：`dlg-archive-passwords-desktop-1440x900`、`-phone-400x860`、`-tablet-1280x800`
- 现象：说明文字为「验证通过的密码，最近用过的在前，仅存于本机。」而 `shared/.../sync/ArchivePasswordSync.kt` 会把这些密码加密后写进网盘 `.piko/archive-passwords-*.json`，换设备即可取回。这是机密的存放位置，用户据此判断风险。
- 依据：`apple-hig-mirror/pages/alerts.md` 第 62 行，说明要完整、具体地描述实际情况；`fluent-design-mirror/pages/components/web/react/core/dialog/usage.md` 第 143 行，有后果时先写后果。
- 建议：改为与同步开关一致的事实，例如「验证通过的密码，最近用过的在前。开启设置同步时加密存入网盘。」（`ui/.../ArchivePasswordDialog.kt` 第 231 行）。

## 中

### M1 同类确认对话框有的带顶部图标、有的不带
- 图：带图标居中标题：`dlg-logout-*`、`dlg-unsupported-*`、`dlg-archive-passwords-*`、`dlg-share-*`、`dlg-speed-*`、`dlg-domain-*`、`dlg-proxy-*`、`dlg-quality-*`、`dlg-download-location-*`；不带图标左对齐：`dlg-account-remove-*`、`dlg-history-clear-*`、`dlg-trash-*`、`dlg-newfolder-*`、`dlg-rename-*`、`dlg-vault-*`、`dlg-unvault-*`、`panel-segment-discard-*`。
- 现象：「退出登录」与「移除账号」后果几乎相同（清除本机凭据），一个有红色图标加居中标题，一个没有；「清空回收站」这种真正找不回的反而没有图标。两套标题对齐方式交替出现，看不出规律。
- 依据：`m3-material-mirror/pages/components/dialogs.md` 第 43–44、103–109 行，图标可选，有图标时标题居中、无图标时左对齐；可选不等于随意，同一应用应有一条取舍规则。
- 建议：定一条规则写进 `ui/CLAUDE.md` 的对话框一节，例如「设置项的选择与表单对话框带该设置项的图标，确认类一律不带」，然后把 `dlg-logout` 的图标去掉。

### M2 设置里的选择对话框有四种提交方式
- 图：`dlg-quality-setting-*`（只有「取消」，点选即生效）、`dlg-download-location-*`（只有「完成」）、`dlg-domain-*`（「重新测速」「完成」）、`dlg-proxy-*`（「取消」「保存」）。
- 现象：播放画质只给一个「取消」。若点选即生效，「取消」暗示能撤销却撤销不了；若不生效，又没有确认按钮。四个相邻设置项的单选对话框各用一种模型，用户无法预期点一下单选项会不会立刻生效。
- 依据：`m3-material-mirror/pages/components/dialogs.md` 第 362–366 行，只有一个按钮时它必须是确认知悉类，两个按钮时一个确认一个取消；第 438 行，确认动作要说清后果，避免「完成」「确定」这类含糊词；`gnome-hig-mirror/.../dialogs.md` 第 39 行同义。
- 建议：点选即生效的（画质、下载位置、域名）统一只留一个「关闭」，测速改为列表上方的图标按钮；要填写字段才能生效的（代理、速度上限）用「取消」「保存」。

### M3 文本框有三种外形
- 图：`dlg-rename-*`、`dlg-newfolder-*`（中等圆角、浮动标签）；`dlg-proxy-manual-*`、`dlg-speed-*`（约 4dp 方角）；`dlg-share-custom-*`、`panel-rename-*`、`panel-addlink-*`（全圆角胶囊）。
- 现象：同是对话框里的单行输入，方角、中圆角与胶囊并存，代理对话框里胶囊形的分段按钮下面紧跟方角输入框，对比尤其明显。
- 依据：`m3-material-mirror/pages/components/text-fields.md` 第 359 行，同一界面内的文本框应保持一致，不在同一区域混用。
- 建议：选定一种圆角（与 `PikoDialog` 的 16dp 协调的中圆角即可），收进一个共用的 `PikoTextField`。

### M4 目录选择器与批量重命名的外壳不是 `PikoDialog`
- 图：`panel-picker-desktop-1440x900`、`panel-picker-tablet-1280x800`、`panel-rename-desktop-1440x900`、`panel-rename-tablet-1280x800`
- 现象：这两处圆角约 28dp、标题内边距约 16dp，其他对话框是 16dp 圆角、24dp 内边距。目录选择器上再叠一个新建文件夹对话框（`panel-picker-newfolder-desktop`）时，两种圆角上下重叠，差别一眼可见。
- 依据：`ui/CLAUDE.md` 的对话框约定，一律用 `PikoDialog`（标题小一号、圆角 16dp）；`m3-material-mirror/pages/components/dialogs.md` 第 23–26 行说明圆角与标题字号、内边距是成套变化的。
- 建议：两者改用 `PikoDialog` 的形状与内边距，或明确把它们定义为「大面板」并写进约定。

### M5 面板头部有四种排法
- 图：`panel-actions-desktop`（无标题，× 单独一行，下面是条目信息）、`panel-addlink-desktop`（标题与 × 同行）、`panel-segment-desktop`/`-tablet`（× 单独一行，标题在下一行）、`panel-properties-tablet`（标题与 × 同行）与 `panel-properties-phone`（无标题）。
- 现象：同是 `PikoSheet`，标题的位置和有无各不相同；属性在平板上有「属性」标题，在手机上没有。`panel-addlink-desktop` 与 `panel-properties-tablet` 的标题比下方内容多缩进约 8px，左缘对不齐。
- 依据：`m3-material-mirror/pages/components/side-sheets.md` 第 39–42、216–219 行，侧边面板的结构是标题与关闭按钮同处顶栏。
- 建议：`PikoSheet` 统一提供「标题加 ×」的顶栏，标题与内容共用一个左内边距；条目操作面板可以把条目名当作标题放进这一行。

### M6 添加链接面板重复信息
- 图：`panel-addlink-share-phone-400x860`、`-tablet-1280x800`、`-desktop-1440x900`；`panel-addlink-magnet-phone-400x860`、`-tablet-1280x800`
- 现象：分享链接状态下，手机与平板的面板标题是链接本身，下面的输入框又是同一条链接，再往下是「Frieren 分享内容」标题，卡片里又是「分享内容」小标题；同一条信息出现两到三次。磁力状态下，标题「Frieren S01」与输入框的值相同，「已选 2 / 2」在标题副行和列表上方各出现一次。桌面端标题是「添加链接」，没有这些重复，三种形态不一致。
- 依据：`fluent-design-mirror/pages/components/web/react/core/dialog/usage.md` 第 143 行，正文不重复标题；`m3-material-mirror/pages/components/bottom-sheets.md` 第 197 行，标准底部 sheet 的头部只承担收起与标识。
- 建议：部分展开一档保留现在的标题与副行（这是收起时唯一可见的部分），展开后隐藏列表上方的「已选」行与「Frieren 分享内容」行，卡片小标题直接写「Frieren」。

### M7 磁力默认全选，分享链接默认全不选
- 图：`panel-addlink-magnet-*`（已选 2 / 2，主按钮「保存」可点）、`panel-addlink-share-*`（全部未勾，主按钮为禁用的「勾选要转存的内容」）
- 现象：两种来源的默认选择相反，用户在一种链接上养成的「直接保存」习惯在另一种上失效。
- 依据：`m3-material-mirror/pages/components/dialogs.md` 第 349 行，确认动作在做出选择前禁用，这一点分享链接做到了；问题在于两种来源的初始状态不一致。
- 建议：两者取同一默认值；若分享链接不全选是出于额度考虑，在禁用按钮上方说明原因。

### M8 查重无结果时，空态与 Snackbar 说同一句话
- 图：`panel-duplicates-desktop-1440x900`
- 现象：页面中央已是「未发现重复文件」空态与「重新扫描」，底部又弹出 Snackbar「未发现重复文件」，带一个「查看」动作；人已经在结果页上，「查看」无处可去。
- 依据：`m3-material-mirror/pages/components/snackbar.md` 第 88–95 行，Snackbar 用于不需要用户操作、且别处没有呈现的轻量消息。
- 建议：当前标签就是查重标签时不发这条 Snackbar，只在查重在后台完成时发。

### M9 条目操作面板的标题与网格卡片的名字不同
- 图：`panel-actions-desktop-1440x900`、`-phone-400x860`、`-tablet-1280x800`
- 现象：高亮的卡片显示为「Frieren」，操作面板头部写「动画」。目录选择器（`panel-picker-*`）里同一个文件夹也叫「动画」。用户在卡片上看到的名字，在面板和选择器里都找不到。疑似是文件名解析把文件夹显示成作品名的有意设计。
- 依据：`m3-material-mirror/pages/components/lists.md` 第 1057–1061 行，选中项要有颜色之外的明确指示；面板应当让人确认作用对象。
- 建议：卡片显示作品名时，面板头部写「Frieren」，副行写「文件夹 动画」，与卡片对得上。

### M10 归档对话框在没有可归档文件时仍给出完整表单
- 图：`dlg-vault-desktop-1440x900`、`-phone-400x860`
- 现象：副行写「无可归档的文件」，但下面仍是红色警告卡与三个复选项，主按钮「归档」禁用，没有说明是哪个条件导致没有文件，也没有说明改哪个选项能改变结果。
- 依据：`m3-material-mirror/pages/components/dialogs.md` 第 349 行，禁用确认动作的前提是用户还能做出选择；`gnome-hig-mirror/.../dialogs.md` 第 40 行，所需选项未满足时禁用确认，此时须让人知道缺的是什么。
- 建议：副行写出原因，例如「2 个文件均无来源记录」；若改动选项能产生可归档文件，就保持现状加原因；若无论如何都没有，只给说明与「知道了」。

### M11 下载片段的放弃确认弹出时，面板已经消失（疑似）
- 图：`panel-segment-discard-desktop-1440x900`、`-phone-400x860`
- 现象：确认框「放弃下载片段?」后面是网盘页，片段面板已不在。按「继续编辑」的人看不到自己正在编辑的区间。
- 依据：`m3-material-mirror/pages/components/dialogs.md` 第 418、450 行，放弃未保存的修改时，确认对话框出现在被编辑的界面前方。
- 建议：先弹确认，面板保留在下层；确认放弃后再关面板。若这是截图流程先关面板造成的，可以忽略。

## 低

### L1 文案
- `dlg-unsupported-*`：「PikPak 不支持名称中的字符 ?。」问号与句号相连，难以分辨哪个是被禁字符。建议「名称含 PikPak 不支持的字符「?」」。
- `panel-segment-discard-*`：标题「放弃下载片段?」用了半角问号，应为「？」。
- `panel-actions-*`、`panel-contextmenu-desktop`：「移动到...」「复制到...」用三个句点，中文应为「…」。依据 `gnome-hig-mirror/pages/guidelines/writing-style.md` 第 81–87 行，省略号表示还需进一步输入。
- `panel-picker-newfolder-*`：「创建于 网盘，创建后自动进入」中「于」后多一个空格。
- `dlg-rename-*`、`dlg-rename-file-*`：确认按钮是「确定」，新建是「创建」。依据 `m3-material-mirror/pages/components/dialogs.md` 第 438 行与 `gnome-hig-mirror/.../dialogs.md` 第 39 行，应用具体动词，改为「重命名」。
- `dlg-speed-*`：入口行叫「速度上限」，对话框标题是「蜗牛模式」；两个字段下各重复一遍「大于 0 的整数」。建议标题与入口同名，提示合并为一行。
- `dlg-unvault-*`：标题「取消归档」与按钮「取消」同字，按钮是撤销对话框还是执行取消归档，需要多读一遍。依据 `fluent-design-mirror/.../dialog/usage.md` 第 147 行，按钮应回应标题。建议标题改为「恢复到网盘」或「从归档中恢复」。
- `dlg-logout-*`：「将清除本机保存的该账号登录凭据。」多账号时没有指明是哪个账号；「移除账号」一条写出了账号名。
- `panel-addlink-magnet-*`：「1000.0 MB」「500.0 MB」保留无意义的 .0，前者应进位为 GB。

### L2 退出登录、移除账号使用错误色
- 图：`dlg-logout-*`、`dlg-account-remove-*`
- 现象：两者只清本机凭据，重新登录即可恢复，却与「彻底删除」用同一种红色主按钮。
- 依据：`ui/CLAUDE.md`：删掉找不回的才加 destructive；`apple-hig-mirror/pages/alerts.md` 第 78 行，用户主动选择的操作不必再用破坏性样式。
- 建议：改用普通的 `PikoDialogConfirm`。

### L3「已在此目录」用错误色
- 图：`panel-picker-desktop-1440x900`、`-phone-400x860`、`-tablet-1280x800`
- 现象：底部本该写「目标位置」的标签换成红字「已在此目录」。这是正常状态（还没选新目录），不是错误。
- 依据：`m3-material-mirror/pages/styles/color/roles.md` 第 133 行，error 表示紧急。
- 建议：用 onSurfaceVariant，文字保持「已在此目录」即可。

### L4 批量重命名的布局
- 图：`panel-rename-desktop-1440x900`、`panel-rename-typed-desktop`、`panel-rename-tablet-1280x800`
- 现象：左栏内容超出可视区时被直接截断，没有滚动条或渐隐：桌面底部「移除结尾」只露出半行，平板底部「区分大小写」被切掉，`panel-rename-typed` 顶部「常用」一行被切掉。右侧预览中原名固定在约 220px 的窄列里折成三行，改名前列右侧大片空白（`panel-rename-desktop` 尚无新名时整个右半为空）。查找框里的「+」与历史图标紧跟在文字之后，悬在输入框中段，而不在末端。
- 依据：`m3-material-mirror/pages/components/dialogs.md` 第 539 行（可滚动内容）；`m3-material-mirror/pages/components/text-fields.md` 第 39–42 行，图标位于前端或末端。
- 建议：桌面端左栏显示滚动条或底部渐隐；原名列与新名列按可用宽度均分；两个图标固定在输入框末端。

### L5 文件名在扩展名中间折行
- 图：`panel-properties-phone-400x860`、`-tablet-1280x800`（「...x264.m / kv」），`dlg-rename-file-phone-400x860`（「x2 / 64」）
- 建议：长文件名按「.」「-」「_」「空格」优先断行，或主名截断、扩展名保留完整。

### L6 分享对话框的提取码框
- 图：`dlg-share-desktop-1440x900`、`-phone-400x860`
- 现象：选「随机」或「无」时，下面仍是一个禁用的空输入框，像是漏填；对比度很低。保留它是为了避免对话框跳动（issue #9），这个约束成立。
- 建议：保留占位，但在「随机」时显示「创建后随机生成」，「无」时显示「无需提取码」，让禁用框有内容可读。

### L7 片段的「−1」「+1」没有单位
- 图：`panel-segment-*`
- 建议：写成「−1 秒」「+1 秒」，或在悬停提示里写明；进度条上 0–1:00 的已选区间几乎看不出，可加深选中段的颜色。

### L8 嵌套对话框
- 图：`panel-picker-newfolder-desktop-1440x900`、`-tablet-1280x800`，`panel-rename-guide-desktop-1440x900`
- 现象：目录选择器上叠新建文件夹对话框，批量重命名上叠使用说明。手机上选择器是全屏，叠一层合乎 M3；桌面与平板上是对话框叠对话框。
- 依据：`fluent-design-mirror/.../dialog/usage.md` 第 117–119 行，不嵌套对话框；`m3-material-mirror/pages/components/dialogs.md` 第 478 行只允许全屏对话框上叠简单对话框。
- 建议：新建文件夹改为列表顶部的一行内联输入；使用说明改为批量重命名右栏可切换的一页。

### L9 桌面属性卡片盖住它所描述的条目
- 图：`panel-properties-desktop-1440x900`
- 现象：卡片出现在 Oppenheimer 卡片正下方，顶边压住了「Dune Part Two 2024」与「Oppenheimer」的名字。卡片可拖动，所以问题不大。
- 建议：初始位置避开被右键的条目。

### L10 查重的三种叫法
- 图：`panel-duplicates-*`
- 现象：桌面标签写「查重：网盘」，地址栏写「查找重复」，移动端写「查找重复「网盘」」。
- 建议：统一为「查找重复：网盘」或在标签里用全称。

### L11 设置页背景的目录高亮与打开对话框的分区不符（背景问题，顺带记录）
- 图：`dlg-download-location-desktop`（高亮「网盘」，下载位置在「传输与网络」下）、`dlg-quality-setting-desktop`（高亮「外观」，播放画质在「播放」下）、`dlg-archive-passwords-desktop`（高亮「外观」，解压密码在网盘分区）
- 建议：检查左栏滚动跟随的阈值，疑似落后一节。

## 不计入的截图环境现象（疑似）
- 触屏点图标后残留的提示气泡：`dlg-account-remove-tablet`（「移除」）、`panel-picker-newfolder-phone`（「新建文件夹」）、`panel-rename-guide-phone`（「使用说明」）。
- `dlg-proxy-manual-phone` 中「不使用代理」「手动」两行同时带按下态底色。
- 手机与平板图里出现 Windows 路径（`dlg-download-location-*`），是截图在桌面 JVM 上跑造成的。

## 做得好的地方（供保留）
- 回收站、清空播放历史的标题与按钮动词对应（「清空回收站」「彻底删除」），破坏性按钮只用于找不回的删除。
- 批量重命名的冲突态（`panel-rename-conflict-desktop`）逐项标红并在底部汇总，主按钮同时禁用，反馈完整。
- 修正名称对话框（`dlg-unsupported-*`）原名高亮被删字符、按钮为「返回修改」「使用此名称」，选项清楚。
- 桌面右键菜单顶部的无标签图标行与 Windows 11 资源管理器的右键菜单一致。
