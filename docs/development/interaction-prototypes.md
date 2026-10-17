# 工作恢复界面原型

## 2026-10-17：任务页视觉讨论（候选）

这一轮先讨论整体布局与视觉，尚未替换实际任务页面。重新阅读 docmirror4a 中 Material 的 canonical-examples、lists、color/roles、progress-indicators，以及 Android canonical-layouts 后，候选方案如下：

- 用户已确定：任务列表铺满内容区，优先提高可用空间与批量管理效率。详情复用网盘属性的承载方式：宽窗口使用不挤压列表的 FloatingPanel，窄窗口使用 PikoSheet，不常驻侧栏。Material Scaffold 的单个可伸缩 pane 适用于这一组织方式。
- 页面沿用 Frame 的底壳、页眉和浅色内容区；同一布局区域在不同窗口宽度下使用相同颜色角色。
- 状态组采用 M3 Expressive 分段列表，外侧 16dp 圆角、组内相接处 4dp 圆角，用间隙分组。选中项可以使用完整圆角与选中色；默认行保持中性表面色。
- 标题、状态/阶段、进度和末端操作分别占稳定的位置。查看详情与批量勾选是不同模式，避免同一高亮同时表达两种选中含义。
- 速度与蜗牛模式作为固定的一组全局控制，任务类型筛选不借用工作标签的视觉语义。
- 已知总量的过程使用确定进度，解析、核对等总量未知的阶段保留阶段文字与不确定反馈。

详情承载已确定复用网盘属性弹窗：沿用锚点定位、标题栏、拖动、内容滚动及关闭行为；一次显示一个任务，查看另一任务时替换内容。任务的阶段、进度、来源、目标、结果与能力动作使用任务自己的内容组件。详情当前任务与批量勾选集合分别持有，打开或关闭详情不清除列表筛选、滚动和勾选。

批量管理候选细节：宽窗口按任务、阶段/进度、规模/速度、目标与操作对齐；窄窗口将辅助列收进次行或展开区。复选框与批量操作条提供当前筛选结果的选择和按能力计数的动作。批量选择期间保留行位置，状态继续更新，避免任务跳组影响操作。这些细节继续讨论后确定。

Material 同时支持 standard 与 segmented，选用分段列表是结合本项目圆角容器风格的设计判断。参考章节：

- `docmirror4a/m3-material-mirror/pages/components/lists.md`：Styles、Shape morphing、Gaps & dividers、Flexibility & slots
- `docmirror4a/m3-material-mirror/pages/styles/color/roles.md`：Surface roles、跨断点的区域颜色映射
- `docmirror4a/m3-material-mirror/pages/foundations/layout/scaffold.md`：Single-pane layouts、固定与弹性 pane
- `docmirror4a/m3-material-mirror/pages/foundations/layout/canonical-examples.md`：List-detail、布局切换与状态保留（先前候选布局）
- `docmirror4a/m3-material-mirror/pages/components/progress-indicators.md`：确定/不确定进度及样式一致性
- `docmirror4a/android-docs-mirror/pages/develop/ui/compose/layouts/adaptive/canonical-layouts.md`：List-detail

## 2026-10-17：任务页面

视觉修订采用 `Frame.kt` 的外框和页眉色阶、16dp 内容容器、12dp 内层圆角；移除分割线、大图标和逐项有色卡片。
速度旁直接复用现有 `SnailModeToggle` 与上限设置对话框。`25-tasks-phone-snail` 和 `26-tasks-phone-limits`
展示开启状态和设置入口；开关验证同时检查上下行限值保持不变。

`TaskPrototype.kt` 使用假数据展示“需要处理、进行中、已完成”分组、业务类型筛选与列表详情布局。
手机点开详情并返回；宽窗口选中任务后并列显示详情。任务按固定 ID 关联，结果更新保持当前详情。
样例包括解压密码、复制结果待核对、归档准备阶段、上传、下载及保存引用完成。
归档重解析阶段是方案演示，尚未接入真实探测逻辑。

依据：`ui/CLAUDE.md` 的任务寿命和动作要求、`adaptive-interaction-spec.md` 的列表详情与执行结果规则，
以及 docmirror4a 中 Material Lists、Progress indicators、Canonical layout examples 的原始指南。
需要输入和待核对采用辅助强调色，明确失败时才使用错误语义。

运行 `:shots:run --args="adaptive-prototypes"`，在[对照页](../../build/shots/adaptive-prototypes/index.html)顶部查看：
`19-tasks-phone`、`20-tasks-wide`、`21-tasks-phone-detail`、`22-tasks-phone-attention`、
`23-tasks-phone-completed`、`24-tasks-wide-dark`。
渲染时验证了打开归档详情再返回，以及重新核对后保持同一任务身份；执行结果由本地样例状态模拟。

## 2026-10-16：统一标签与添加链接工作台

后续树形方案：主内容区显示选中内容整理后的保存结果树；管理栏显示完整来源文件树，增加选择框、预览和下载。
来源树支持嵌套目录、CJK 原名、目录半选、未选筛选和广告补选；引用保存沿用同一份选择。
新增 `17-import-phone-unselected.png` 和 `18-import-wide-reference.png` 展示补选与引用归档。
树形原型位于 `TreeImportPrototype.kt`，当前只用于假数据设计评审，共用生产组件的抽取另行实施。

Page Flows 参考了 [Dropbox iOS 添加文件](https://pageflows.com/post/ios/adding-files/dropbox/) 与
[Dropbox Web 添加文件](https://pageflows.com/post/desktop-web/adding-files/dropbox/) 的公开截图。
两组页面标注为 2018 年版本；可见内容用于比较连续文件行、导航与动作分区。订阅遮挡的后续步骤未纳入参考。

当前方案使用统一工作标签：宽窗口常驻标签栏，窄窗口通过方框数字入口进入页面缩略图总览。
添加链接由内容预览与导入管理两部分组成，宽窗口左右并列，手机使用非模态管理面板。

[查看当前对照页](../../build/shots/adaptive-prototypes/index.html)。运行
`.\gradlew.bat :shots:run --args="adaptive-prototypes"` 可重新生成。

| 截图 | 评审内容 |
| --- | --- |
| `16-import-wide.png` | 左侧视频预览、右侧链接来源和文件选择，保存目标与提交区固定在管理栏底部 |
| `13-import-phone-collapsed.png` | 收起到摘要，保留展开入口，预览区可操作 |
| `14-import-phone-half.png` | 半展开时同时看到视频、文件选择与保存入口 |
| `15-import-phone-expanded.png` | 完全展开后集中管理链接、文件和命名选项 |

原型使用 PikoTheme、Compose Material 3 和固定假数据；预览画面由 Canvas 绘制。
源码为 shots 下的 `AdaptivePrototypes.kt` 和 `AddLinkPrototype.kt`。
已运行渲染与语义点击验证：标签切换、管理面板三档切换、改变预览对象保持保存选择。
面板高度通过按钮和把手点击切换；真实拖动、媒体播放和保存执行留待正式交互实现。

## 2026-10-13：历史方案

2026-10-13，基于 `b4818cf6`。对应[交互设计研究](interaction-design-research.md)中的三个可行方向。

本轮只用于比较关键界面。三个 agent 分别在独立工作树中编写 Compose 原型，经 `:shots` 渲染；数据为固定样例，未实现完整业务，也未合入主分支。

## 查看

[打开三方案对比页](../../build/interaction-prototypes/index.html)。顶部可以切换文件入口、恢复列表、导入编辑器和查重报告，每组并排展示三个方案。点击图片查看原始尺寸。

截图统一为 400 × 860 的应用内容，不包含系统状态栏。工作数据使用两份导入草稿、一份查重报告以及上传中的资料备份。不同 agent 的布局细节有所差异，重点比较入口位置、恢复路径与空间分配，不把所有视觉差异都归因于方案本身。

| 方案 | 恢复入口 | 主要取舍 | 工作树名称 |
| --- | --- | --- | --- |
| A | 现有任务页，草稿、进行中与报告分组 | 稳定、无需额外常驻控件；往返需要经过任务列表 | `prototype-task-hub` |
| B | 右上角“工作”入口，展开切换列表 | 切换直接；增加顶栏入口并需与任务管理区分 | `prototype-work-switcher` |
| C | 底部最近工作摘要，另有全部工作列表 | 最近一项容易发现和继续；长期占用底部空间 | `prototype-resume-summary` |

## 重跑

在各自工作树根目录运行，沿用本机 Android SDK 和项目 JDK 工具链。不要在其他原型树内执行这些命令。

```powershell
# A
.\gradlew.bat :shots:run --args="prototype-task-hub -o build/shots/prototype-a" --max-workers=2

# B
.\gradlew.bat :shots:run --args="prototype-switcher" --max-workers=2

# C
.\gradlew.bat :shots:run --args="prototype-resume -o build/shots/prototype-resume" --max-workers=2
```

原始输出与具体实现说明留在各自工作树，对比页使用主工作区 `build/interaction-prototypes/` 下的副本。该目录为本地产物，不进入版本控制。若需要跨设备保留评审画面，可在确定方案后将选定图片作为设计资料归档。

## 看图时优先判断

1. 从文件页离开后，是否知道去哪里继续草稿或查看报告？
2. 两份草稿能否凭标题和目标辨认，是否容易误开？
3. 扫描报告和导入编辑器是否得到足够空间？
4. 最近工作提示值得占用多少文件页空间？
5. 工作切换与后台任务管理是否容易区分？

静态截图不能证明输入法、手势、焦点、跨进程保存或真实执行行为。选定方向后再实现并验证完整调用链。

## 本轮验证结果

三份原型分别通过 `:shots:run` 编译与渲染，12 张截图均逐张检查可读性、截断和遮挡。主工作区副本尺寸全部为 400 × 860，对比页及图片的本地 HTTP 访问通过。

方案 C 另外通过 AppScene 的列表、编辑器、报告与返回路径点击验证。A、B 以静态画面验证为主；三者都没有验证真实业务、持久化与真机手势。
