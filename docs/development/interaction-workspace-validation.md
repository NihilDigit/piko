# 移动工作页面验收

本轮实现目标：移动端以「正在进行」入口切换导入草稿和查重，完整页面承载编辑与报告；最小化保留工作并回到来源，关闭移除该项。手机允许多个不同起点的查重，同一起点恢复已有会话。桌面标签保持现行行为。恢复范围限当前账号的应用会话，不承诺进程退出后的草稿或报告恢复。

本文区分静态审查、自动运行和设备实测。以下矩阵记录验收范围与证据层级；「待验证」不表示通过。原型 PNG 只展示候选视觉，不作为真实调用链证据。

## 调用链边界

- `PikoMainScaffold` 持有 Navigation 3 返回栈、外部链接消费和任务恢复导航。`Screen.Workspace(contextId)` 必须注册序列化器；页面恢复时还须核对会话是否存在。
- `WorkspaceViewModel` 持有草稿、查重和浏览会话。「正在进行」直接使用工作区上下文注册表；任务页只管理已提交执行和结果。原 `TaskContinuations` 已移除。
- `DriveScreen.findDuplicates` 和 `openAddLink` 优先走工作区；其无工作区的后备入口仍有旧单会话行为。库、搜索、临时定位页应继承同一个 `LocalWorkspaceContexts`。
- `DriveScreen` 的返回处理按属性、多选、高亮、搜索、虚拟目录层级逐步退出，到查重根部才离开工作页。报告继续由 `DuplicateReportViewModel` 投影虚拟树，文件操作保留原调用链。
- `InstantSheetContent` 持有目标选择器、临时通知及预览事件收集；保存、选择与解析由草稿状态持有。全屏布局需要独立处理可用高度、保存栏、输入法和焦点。
- `instantSession.drafts` 移除草稿会触发 `closeContext`。自动移除与用户关闭都只能清理对应导航项，不能弹走另一个活动工作或播放器。

## 验收矩阵

| 范围 | 操作与预期 | 状态 |
| --- | --- | --- |
| 主文件页入口 | 新建草稿直接进入完整编辑页；最小化回原目录，选择与滚动不被清空 | 核心入口与最小化通过；完整选择/滚动恢复待专测 |
| 库入口 | 从星标、历史或最近文件打开工作；最小化回原库，保留原浏览会话 | 星标、最近添加的打开、恢复及返回通过；播放历史未单独跑 |
| 搜索入口 | 搜索状态下打开工作；返回仍保留关键词和结果状态 | 手机搜索 Dune 后打开草稿、最小化返回，以及搜索栏“正在进行”恢复再返回均通过 |
| 临时定位入口 | 从 `Screen.Browse` 打开工作；最小化回该定位页，不强制回主文件页 | 任务完成行定位 SP01 → 草稿 → 最小化回定位页 → 返回任务通过 |
| 外部链接 | 从已有工作、库、任务页接收新链接；只消费一次，新草稿保留正确来源与默认目标 | 星标页接收外链、真实解析后最小化回星标通过；已有工作/任务来源和重复消费待专测 |
| 草稿目标 | 新建时固定主文件位置；之后普通浏览不改变目标；目标选择器取消不改目标，确认只改当前草稿 | 三形态目标选择器打开、取消、滚到末项并确认更新当前草稿通过；未更改主文件浏览位置 |
| 多草稿 | 两份不同来源草稿相互切换；输入、勾选、批量展开状态各自保留 | 双草稿输入、分享选择、批量单项选择变更与多工作切换恢复通过；长树滚动待专项设备检查 |
| 多查重 | 不同根目录并存；同根 ID 恢复原报告，不重扫、不丢其他报告；相同名称但不同 ID 不合并 | 不同根并存与同根复用通过；同名不同 ID 待验证 |
| 工作选择列表 | 名称、来源/目标、状态可辨认；每项有直接关闭入口，点击关闭不同时激活该项 | 打开、切换与关闭入口通过；手机、横屏侧板、平板侧板目标文案已图审 |
| 关闭非当前项 | 在 B 工作页关闭 A，只释放 A，B 保持可见；关闭提示取消保留 A | 文件页列表单项关闭与取消确认通过；独立 isolation 场景验证 B 页关闭 A 后 B 仍可见且可继续输入 |
| 关闭当前项 | 清理当前上下文并离开失效页面；停止查重并丢报告，已接受保存按业务寿命结算 | 核心场景关闭及全部清空通过；执行中关闭结算待验证 |
| 保存中关闭 | 关闭独立草稿移除编辑器；已接受保存继续结算并在任务页留结果，未提交选择按 `discardNeedsConfirm` 确认 | shared 的 InstantFlowSmokeTest 覆盖关闭草稿后已接受保存继续、待确认结果保留；界面中竞态待专项测试 |
| 保存自动关闭 | A 保存完成时用户正在 B/预览页；只移除 A，不改变 B 或预览页的返回目标 | WorkspaceRoutesTest 验证仅移除指定工作、保留其上播放器/定位页；保存完成触发这一路径的端到端竞态未单独跑 |
| 任务页与工作区隔离 | 任务列表及执行详情不再列草稿、查重或编辑恢复动作；从顶栏“正在进行”恢复后最小化回任务页 | 更新后的共享模块测试确认草稿/查重不计任务行数；五形态场景加入任务页无草稿断言 |
| 任务旧入口 | 草稿/报告不再注册任务页恢复回调 | 旧注册表、行组件、继续编辑动作及相应旧测试已删除；工作区仍按稳定ID管理 |
| 查重虚拟树 | 分类、目录层级、选择与滚动切走后保留；返回先退出局部状态再上一级，根部才最小化 | 树内返回、切换后分类/目录保留通过；手机精确长按进入多选和退出通过，滚动恢复待专测 |
| 查重文件操作 | 预览、属性、下载、移入回收站、定位来源继续走真实文件命令；定位页返回恢复报告 | 手机更多菜单及属性通过；预览、下载、回收站与定位链路待验证 |
| 文件预览 | 草稿/报告打开播放器后返回同一工作；关闭其他工作不移除播放器所在工作 | 草稿现有播放入口、临时转存、播放器请求与返回草稿通过；静态播放器控件夹具，不验证解码 |
| 内层返回 | 目标选择、批量条目详情、确认弹窗先处理返回，不被外层最小化抢占 | 目标选择取消、关闭确认取消及批量单项详情返回列表通过；系统手势另行验证 |
| 输入法与焦点 | 键盘显示时先收键盘；保存栏可达，切换后焦点不留在隐藏工作；外接键盘保存快捷键只作用可见草稿 | 待设备验证 |
| 布局 | 400×860 手机、横屏紧凑高度与 1280×800 平板无遮挡；短工作选择器不变成编辑器 | 核心与扩展共 88 张正式图已逐张检查；横屏编辑双栏及目标选择器确认栏重叠均已修复并复测 |
| 账号与重建 | 当前账号界面重建保留会话；账号切换后不显示旧账号工作；进程重启不恢复失效上下文页面 | 同账号服务存续下重建界面恢复草稿通过；账号退出执行清理由 shared 冒烟测试覆盖；设备重建与进程死亡未实测 |
| 桌面回归 | 文件/草稿/查重标签、关闭、Ctrl+Tab、Ctrl+W、任务恢复行为不变 | 宽、窄桌面标签并存、关闭隔离、草稿保留、Ctrl+Tab 切换通过；Ctrl+W 与桌面任务恢复待专测 |

## 静态审查发现

以下是实现开始时从旧代码确认的适配点，不是对最终实现的缺陷判定：

1. `PikoMainScaffold` 原 `activationRevision` 消费与外部链接处理均清空返回栈并切文件页，会丢失来源。
2. `WorkspaceContextHost` 原报告交互许可依赖 Sheet 展开状态，报告根部返回调用旧 Sheet 方法。
3. 原 `InstantSheetContent` 默认按 Sheet 内容收缩，全高编辑器要显式占满高度；批量详情另有返回处理。
4. 工作区草稿自动关闭由异步流触发，清理导航时必须按 ID，不能无条件弹顶页。
5. `PikoMainScaffold.openDuplicateReport` 原来选第一份报告；多个报告时特定恢复入口必须携带身份，不能误开另一份。
6. 此前 `TaskContinuations.draftEntries` 过滤空草稿和已关联执行的草稿（该旧注册表现已删除）。「正在进行」若需要列出全部保留工作，应以工作区上下文为准，不能直接复用任务页可见行数。

## 集成实现复查

复查覆盖导航差异、工作选择器、完整页面宿主与草稿列表状态。发现并交给实现方核对的具体问题：

- 失效工作页若连同其后的返回栈全部删除，会在草稿异步完成时关闭仍在使用的播放器或定位页。应只清理失效上下文对应页面。
- 导入文件树原 `LaunchedEffect(filtering)` 每次重入组合都滚到顶部，会覆盖恢复的滚动位置；筛选值未变化时应保留位置。
- 选择器只用标题与进度可能无法区别同名资源、不同目标的草稿，应补充目标信息。

以上三项已修改代码；自动关闭的路由清理已有新增测试，尚待最终统一运行。新增目标文案已在手机、横屏及平板工作列表图中确认；滚动恢复仍待专测。

首轮图审发现手机横屏的文件预览区域被挤没。双栏修复已通过定点重跑：左侧输入、目标与保存，右侧文件预览，860×400 下全部可见。

## 运行记录

### 首轮集成运行

- `build/reports/ongoing-work-build.log`：Android、桌面编译，UI 测试与 shots 编译通过，末尾 `BUILD SUCCESSFUL`。这是首轮结果，不包含后续新增的 7 项路由测试及横屏双栏修复。
- `build/reports/ongoing-work-scenarios.log`：手机、手机横屏、平板、桌面、窄桌面五形态核心场景通过，产出 39 张截图，末尾 `BUILD SUCCESSFUL`。覆盖双草稿输入与切换、任务恢复、取消关闭、多查重及同根复用、树内返回、关闭与全部清空。
- 手机目标选择器取消、更多菜单、属性已通过场景检查。首轮多选截图只显示高亮，后续已补精确长按及多选状态断言，见下。

最终统一编译和测试由主 agent 记录。以下结果来自无头 Compose 场景，尚未进行 Android 真机输入法、手势返回或外接键盘实测。

### 定点复核与来源验证

- `build/reports/ongoing-work-landscape.log`：双栏修改后的横屏完整工作生命周期通过，`BUILD SUCCESSFUL`，58 秒。10 张截图已重新检查；解析草稿的文件选择、目标与保存同时可见，报告页不再出现当前报告自己的重复完成提示。
- `build/reports/ongoing-work-selection.log`：`verify-workspaces --only selection` 通过，`BUILD SUCCESSFUL`。精确长按 `Frieren 01` 后断言出现「退出多选」，截图呈现「已选择 1 项」、选中边框和勾选框，再点击退出恢复目录。完整链路另确认：Esc 后属性 Sheet 仍存在，下一次长按落在遮罩上并关闭属性，未触及条目。脚本已显式点击 `Close sheet` 并等待其移出语义树，再执行真实长按。
- `build/reports/ongoing-work-origins.log`：星标、最近添加、搜索、星标页接收外链，以及任务定位页五条真实导航路径通过，`BUILD SUCCESSFUL`，46 秒，16 张截图。来源 agent 检查了关键画面。
- 多选动作菜单 `07e` 已在完整短链专测中通过：打开更多与属性、显式关闭属性后长按，截图确认菜单内容；「全选」通过可访问的 `SemanticsActions.OnClick` 执行，断言「已选择 2 项」后退出。菜单节点在无头 Popup 中报告局部坐标，因此这一步验证的是语义点击路径；物理弹层外点击和 Escape 关闭待设备测试。`ongoing-work-selection.log` 最新重跑为 `BUILD SUCCESSFUL`，13 秒。
- `build/reports/ongoing-work-isolation.log`：在 B 编辑页打开工作列表，关闭 A，关闭列表，验证 B 页面与输入仍保留，继续输入也成功；`BUILD SUCCESSFUL`，16 秒。
- `build/reports/ongoing-work-phone.log`：修正属性 Sheet 关闭与 Popup 点击路径后，手机完整生命周期再次通过，`BUILD SUCCESSFUL`，1 分 26 秒。覆盖实际属性关闭、长按选择、菜单全选到两项、退出选择，以及其后的多查重复用、关闭和清空，最新 `07e` 图已检查。

复现命令（仓库根目录）：

```powershell
.\gradlew.bat :shots:run --args="verify-workspaces" --max-workers=2
.\gradlew.bat :shots:run --args="verify-workspaces --only landscape" --max-workers=2
.\gradlew.bat :shots:run --args="verify-workspaces --only selection" --max-workers=2
.\gradlew.bat :shots:run --args="verify-workspaces --only isolation" --max-workers=2
.\gradlew.bat :shots:run --args="verify-workspace-origins" --max-workers=2
```

### 已验证截图索引

图片位于仓库根目录 `build/shots/`，由真实 `PikoApp`、`AppScene` 和 `FakePikPak` 生成，不是候选方案工作树中的写死原型。

| 文件前缀 | 已检查状态 | 张数 |
| --- | --- | --- |
| `workspace-phone-400x860-` | `01-editor`、`02-files-retained`、`03-work-list`、`04-task-return`、`05-close-confirmation`、`05b-resolved-draft`、`05c-target-picker`、`06-report-root`、`07-report-folder`、`07b-report-file-actions`、`07c-report-file-properties`、`07d-report-selection`、`07e-report-selection-actions`、`08-multiple-reports`、`08b-close-current`、`09-all-closed`、`isolation-active-b` | 17 |
| `workspace-landscape-860x400-` | `01-editor`、`02-files-retained`、`03-work-list`、`04-task-return`、`05-close-confirmation`、`05b-resolved-draft`、`06-report-root`、`07-report-folder`、`08-multiple-reports`、`09-all-closed` | 10 |
| `workspace-tablet-1280x800-` | 与横屏相同的 10 个状态 | 10 |
| `workspace-desktop-1440x900-` | `01-contexts`、`02-after-close` | 2 |
| `workspace-desktop-narrow-700x800-` | `01-contexts`、`02-after-close` | 2 |
| `workspace-origin-{starred,recent,search,external,located}-` | 来源系列，含搜索栏恢复入口 | 17 |
| `workspace-origin-imports-`、`workspace-origin-recreated-` | 分享、批量导入、单项详情、选择保留及同账号界面重建 | 18 |
| `workspace-origin-preview-` | 现有播放器入口与返回草稿 | 2 |
| `workspace-origin-target-` | 三形态目标选择、滚动末项、确认更新草稿；具体名称见生成目录 | 10 |

每个状态名之后均为 `.png`。`workspace-context-failure.*`、`workspace-phone-400x860-failure-close.png`、`selection-debug-*` 和不含本轮尺寸的旧截图为调试或历史产物，不计入验收证据。截图无法证明 Android 输入法、系统栏、手势返回、文件选择器和进程重建行为。


### 导入分支与界面重建

- `build/reports/ongoing-work-imports.log`：手机和横屏分享链接的选择、目标选择取消，批量两条磁力的详情、选择变更、返回批次与工作切换通过；同一 `ShotEnv` 服务存续下销毁并重建 `AppScene` 后仍能恢复草稿输入。`BUILD SUCCESSFUL`，2 分 37 秒，18 张图。此重建不等于进程死亡恢复。
- `build/reports/ongoing-work-origins-final.log`：来源专项加入搜索栏紧凑入口后再次通过，57 秒。
- `build/reports/ongoing-work-preview.log`：现有播放入口经假服务端临时转存进入 `Screen.VideoPlayer`，使用现有播放器控件夹具显示真实请求标题，再通过实际 `onBack` 回到原草稿；输入及两项选择保留，27 秒。工具原先的移动播放器宿主为空，已补齐测试宿主；生产播放器未修改，媒体解码和网络播放不在此验证范围。

```powershell
.\gradlew.bat :shots:run --args="verify-workspace-imports" --max-workers=2
.\gradlew.bat :shots:run --args="verify-workspace-preview" --max-workers=2
```

### Android 设备检查步骤

自动场景之外，以下需要在实际设备上完成，本文不记为已通过：

1. 输入两份草稿，保持输入法打开，分别最小化、切换和关闭；检查光标、输入、目标和保存按钮是否可达。
2. 查重树进入目录、长按多选、打开属性，再用系统返回退出；确认先退出局部面板和选择，最后才最小化工作。
3. 横竖屏和系统分屏来回切换，检查草稿选择、滚动、查重目录及焦点；大字号下重复关键操作。
4. 从草稿打开现有播放器，再返回；核对原生播放、输入法和手势行为。换账号后检查旧工作不再显示。


## 最终集成结果（2026-10-13）

- `build/reports/ongoing-work-final.log`：Android/桌面编译、UI/共享模块测试，以及最新五形态完整工作场景，`BUILD SUCCESSFUL`，3 分 31 秒。
- 逐图检查进一步发现横屏目录选择器将列表画在底部确认栏下面。`MoveTargetDialog.kt` 改为正文实际扣除完整 Scaffold 内边距，列表不再靠底部内容留白代替视口边界。
- `build/reports/ongoing-work-target-picker.log`：手机、横屏、平板对目录文字与确认栏的几何边界断言、真实滚动至最后目录、确认后更新当前草稿目标，全部通过；10 张图逐张检查。随后 `ongoing-work-imports.log` 再次成功，旧分享目标图已重生并确认修复。
- `build/reports/ongoing-work-build-final.log`：上述最后修复后的双端编译、UI 和共享模块测试再次成功，43 秒。UI 71 项、shared 606 项，共 677 项，6 跳过，0 失败、0 错误。
- [实际界面检查页](../../build/interaction-prototypes/ongoing/index.html)汇总 88 张正式截图；调试、未解决和失败截图不列入。图片和 HTTP 页面检查通过。

平板信息流双栏属于独立研究，见[平板适配研究](tablet-interaction-research.md)，本轮未接入。独立媒体预览栏已从方案中删除。设备级输入法、系统返回、外部点击关闭与媒体解码仍按上面的设备步骤检查；不以无头场景代替这些验证。


## 工作切换列表的后续收敛

根据界面反馈，草稿与查重只从“正在进行”或桌面标签继续；任务列表、筛选计数、徽标及执行详情均不再混入这些工作。已提交保存仍按执行身份显示，不因编辑器是否打开而遗漏或重复计数。

选择器按“导入草稿”“查重”分组，名称是继续入口，状态和目标作为次级信息。面板右上角用叉号，只关闭选择器；条目统一使用文字“关闭”，确认时注明具体工作、未提交内容或扫描结果如何处理。手机（包括低高度横屏）使用底部Sheet，平板宽窗口使用居中且随内容定高的对话框，取消靠边悬浮的形式。

相关日志：`ongoing-work-refinement-build.log` 与 `ongoing-work-refinement-scenes.log`。旧截图中“任务页草稿行”和多个叉号的表现仅代表此前版本，不应作为现行设计约定。


### 选择器与任务列表分离的最终验证

- `ongoing-work-refinement-scenes.log`：最新手机、横屏、平板、宽/窄桌面完整流程通过。覆盖任务列表不含草稿、从顶栏选择器恢复后回到任务页、统一关闭与取消；其中新增桌面对话框的方案现已撤回。双端编译及UI 71项、shared 605项测试通过，6跳过、0失败。
- `ongoing-work-refinement-switcher.log`：手机、横屏、平板和桌面使用真实解析草稿与查重混合分组；保留两份工作时任务页仍为空，选择器恢复正常。46秒，BUILD SUCCESSFUL。
- `ongoing-work-refinement-imports.log`：分享和批量导入继续保留选择及来源；分享标题采用服务端资源名。1分7秒，BUILD SUCCESSFUL。
- 标题右上叉号只关闭列表；条目统一用文字“关闭”。确认框点明工作名称与关闭后果，不在列表中混用放弃、结束、移除等按钮。
- 对话框标题、分组和条目内容左侧对齐；顶部为标题和关闭按钮留白，底部内容边界为24dp。手机保持Sheet自身的拖动条和边距，平板选择器使用应用对话框的圆角与底色。
- [本轮关键界面](../../build/interaction-prototypes/refined/index.html)只展示当前方案。此前完整截图页属于阶段性检查记录，不应把旧版多个叉号或草稿任务行当作现行实现。


### 桌面入口纠正

桌面仅通过原有标签切换和关闭，不再增加“正在进行”入口或弹窗。手机保留Sheet，平板宽窗口保留对话框；任务列表仍只包含已提交执行与结果。

`desktop-tabs-only.log` 验证双端编译、UI测试以及宽/窄桌面的标签切换、关闭隔离和不存在额外选择器入口。截图页的桌面示例改为实际标签页，旧桌面对话框图片仅为历史产物，不再列入当前展示。
