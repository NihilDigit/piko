package dev.piko.shots

import androidx.compose.ui.input.pointer.PointerButton
import dev.piko.shared.presentation.WorkspaceContext
import dev.piko.ui.theme.ThemeMode

/** Exercise the real menu wiring at both widths; no file writes are submitted. */
internal fun verifyAdaptiveActions() {
    ShotEnv("verify-adaptive-actions", localDownloads = false).use { env ->
        AppScene.open(env, 400, 860, ThemeMode.LIGHT, caption = true).use { app ->
            val workspace = env.services.driveWorkspace
            app.useTouchInput(false)
            val fileId = workspace.contexts.activeContextId.value
            for (width in listOf(400, 1280, 400)) {
                app.resize(width, 860)
                app.click("动画", PointerButton.Secondary)
                for (label in listOf("查找重复", "按番号规范命名", "在新标签页打开", "固定到快速访问", "剪切", "复制")) {
                    check(app.hasText(label)) { "$width dp 文件夹菜单缺少 $label" }
                }
                app.key("Esc")
            }
            app.click("动画", PointerButton.Secondary)
            app.click("在新标签页打开", topmost = true)
            check(app.pumpUntil { workspace.tabsFlow.value.size == 2 }) { "窄窗口没有创建文件标签" }
            check(workspace.contexts.activeContextId.value == fileId) { "后台打开文件夹改变了活动标签" }
            app.click("标签总览")
            check(app.hasText("关闭标签总览"))
            app.click("关闭标签总览")
            check(app.pumpUntil { !app.hasText("关闭标签总览") }) { "总览无法返回文件页" }
            app.key("Ctrl+Shift+E")
            check(app.pumpUntil { app.hasText("目录图") }) { "窄窗口目录图不可达" }
            app.key("Esc")
            app.key("Ctrl+L")
            check(app.pumpUntil { app.hasText("转到路径") }) { "窄窗口路径面板不可达" }
            app.click("关闭", topmost = true)
            check(app.pumpUntil { !app.hasText("转到路径") }) { "路径面板未关闭" }
            app.click("动画", PointerButton.Secondary)
            app.click("查找重复", topmost = true)
            check(app.pumpUntil { workspace.contexts.contextsFlow.value.any { it is WorkspaceContext.Duplicate } }) {
                "窄窗口文件夹菜单未创建查重工作"
            }
            val duplicateId = workspace.contexts.activeContextId.value
            app.click("标签总览")
            check(app.hasText("3 个标签页")) { "总览未显示标签总数" }
            val beforeFileClose = workspace.contexts.contextsFlow.value
            app.click("关闭其他标签")
            check(app.hasText("关闭其他 2 个标签页？")) { "普通文件标签批量关闭未确认" }
            check(workspace.contexts.contextsFlow.value == beforeFileClose)
            app.click("取消", topmost = true)
            check(workspace.contexts.contextsFlow.value == beforeFileClose)
            app.click("关闭其他标签")
            app.click("关闭其他标签", topmost = true)
            check(app.pumpUntil { workspace.contexts.contextsFlow.value.map { it.id } == listOf(duplicateId) }) {
                "保留查重时未关闭其他文件标签"
            }
            check(app.hasText("1 个标签页"))
            app.click("关闭标签总览")
            app.key("Ctrl+W")
            if (app.hasText("确认关闭工作")) app.semanticsClick("确认关闭工作")
            check(app.pumpUntil { workspace.contexts.contextsFlow.value.singleOrNull() is WorkspaceContext.File }) {
                "关闭唯一查重未回到文件浏览"
            }
            app.click("任务")
            check(app.pumpUntil { app.hasText("任务列表") })
            check(!app.hasText("标签总览")) { "任务页错误显示文件标签按钮" }
            val retained = workspace.contexts.contextsFlow.value
            app.key("Ctrl+T"); app.key("Ctrl+W"); app.key("Ctrl+Tab")
            check(workspace.contexts.contextsFlow.value == retained) { "任务页快捷键改变了后台工作标签" }
            app.click("文件")
            app.key("Ctrl+T")
            check(workspace.folderStackFlow.value == listOf(dev.piko.shared.data.PikoDriveRepository.ROOT_BREADCRUMB)) { "新建标签继承了旧目录" }
            app.key("Ctrl+W")
            app.click("添加")
            app.click("添加链接")
            app.click("磁力链接、下载地址或分享链接")
            app.type("unsaved-draft")
            app.click("标签总览")
            app.click("新建标签")
            app.idle(900)
            check(workspace.folderStackFlow.value == listOf(dev.piko.shared.data.PikoDriveRepository.ROOT_BREADCRUMB)) { "总览新建标签未从根目录开始" }
            check(app.pumpUntil { !app.hasText("磁力链接、下载地址或分享链接") }) { "新建文件标签后仍显示草稿路由" }
            val keepId = workspace.contexts.activeContextId.value
            app.click("标签总览")
            val beforeClose = workspace.contexts.contextsFlow.value
            app.click("关闭其他标签")
            check(workspace.contexts.contextsFlow.value == beforeClose) { "关闭其他标签跳过了未保存草稿确认" }
            app.click("取消", topmost = true)
            check(workspace.contexts.contextsFlow.value == beforeClose)
            app.click("关闭其他标签")
            app.click("关闭其他标签", topmost = true)
            check(app.pumpUntil { workspace.contexts.contextsFlow.value.map { it.id } == listOf(keepId) }) {
                "批量关闭未保留预期标签 $keepId；剩余 ${workspace.contexts.contextsFlow.value}；活动 ${workspace.contexts.activeContextId.value}；界面 ${app.texts().takeLast(35)}"
            }
            println("能力一致性：宽窄菜单、总览计数、关闭其他标签及草稿确认、任务页标签路由隔离通过")
        }
    }
}
