package dev.piko.shots

import dev.piko.ui.theme.ThemeMode
import java.io.File

/** Real PikoApp event paths and services, retaining one scene across size and input changes. */
internal fun verifyAdaptiveWorkspace() {
    ShotEnv("verify-adaptive-workspace", localDownloads = true).use { env ->
        AppScene.open(env, 1280, 860, ThemeMode.LIGHT, mobile = true).use { app ->
            app.resize(1280, 800)
            val workspace = env.services.driveWorkspace
            app.idle(500)
            app.key("Ctrl+T"); app.idle(500)
            check(workspace.tabsFlow.value.size == 2) { "平板宽窗口未创建真实文件标签" }
            val fileIds = workspace.tabsFlow.value.map { it.id }
            val link = "magnet:?xt=urn:btih:" + "a".repeat(40)
            edt { env.services.instantMagnetRepository.onIncomingMagnet(link) }
            app.changed(); app.idle(1_800)
            check(app.pumpUntil { app.hasText("保存结果") }) { "导入没有进入正常保存结果浏览区" }
            val active = workspace.contexts.activeContextId.value
            val draft = env.services.instantSession.drafts.single().state
            val selection = draft.selectedIndices
            app.click("选择来源 Frieren - 02.mkv"); app.idle(500)
            check(draft.selectedIndices.size < selection.size) { "来源树勾选未回写真实草稿" }
            val afterSelection = draft.selectedIndices
            check(draft.projectedSaveFiles.size == afterSelection.size) { "保存结果没有投影来源选择" }
            app.useTouchInput(false)
            app.click("搜索来源文件"); app.type("no-match"); app.idle(400)
            check(draft.selectedIndices == afterSelection) { "来源搜索改变了保存选择" }
            app.click("清空来源搜索"); app.idle(400)
            app.save(File("build/shots/adaptive-framework/01-tablet-import.png"))
            app.resize(400, 860)
            check(workspace.contexts.activeContextId.value == active && draft.selectedIndices == afterSelection)
            app.useTouchInput(true)
            val resolutionRequests = env.server.calls.count { "/drive/v1/resource/list" in it }
            app.click("标签总览"); app.idle(600)
            check(app.hasText("页面快照：Frieren S01")) { "导入标签没有保存实际页面快照" }
            check(app.exactTextCount("页面快照：网盘") == 2) { "已访问的文件标签没有保留各自的页面快照" }
            check(app.nodeBounds("关闭 Frieren S01").bottom <= app.nodeBounds("页面快照：Frieren S01").top + 1f) { "标题/关闭按钮没有位于页面缩略图上方" }
            app.save(File("build/shots/adaptive-framework/02-phone-overview.png"))
            repeat(2) {
                app.click("关闭标签总览"); app.idle(500)
                app.click("标签总览"); app.idle(500)
                check(app.hasText("页面快照：Frieren S01"))
            }
            check(env.server.calls.count { "/drive/v1/resource/list" in it } == resolutionRequests) { "打开总览重新执行了后台链接解析" }
            check(draft.selectedIndices == afterSelection) { "捕获或查看快照改变了草稿选择" }
            app.resize(1280, 800)
            check(!app.hasText("关闭标签总览")) { "宽窗口仍显示标签管理页" }
            check(workspace.tabsFlow.value.map { it.id } == fileIds) { "尺寸变化创建了新的文件owner" }
            check(workspace.contexts.activeContextId.value == active && draft.selectedIndices == afterSelection)
            app.key("Ctrl+Tab"); app.idle(500)
            check(workspace.contexts.activeContextId.value != active)
            app.key("Ctrl+Shift+Tab"); app.idle(500)
            check(workspace.contexts.activeContextId.value == active)
            check(draft.selectedIndices == afterSelection)
            app.click("任务"); app.idle(900)
            app.useTouchInput(false)
            app.click("Project Sekai OST Vol.3.zip"); app.idle(500)
            check(app.hasText("关闭任务详情") && app.hasText("任务列表")) { "宽窗口未显示浮动任务详情" }
            app.resize(400, 860)
            check(app.hasText("关闭任务详情") && app.hasText("任务列表")) { "窄窗口任务详情未使用面板承载" }
            app.save(File("build/shots/adaptive-framework/03-phone-task-detail.png"))
            app.click("关闭任务详情"); app.idle(400)
            if (!app.pumpUntil { !app.hasText("关闭任务详情") && app.hasText("任务列表") }) {
                app.save(File("build/shots/adaptive-framework/failure-task-close.png"))
                File("build/shots/adaptive-framework/failure-task-close.txt").writeText(app.texts().joinToString("\n"))
                error("关闭任务详情未返回保留列表")
            }
            app.resize(1280, 800)
            app.click("蜗牛模式"); app.idle(500)
            check(app.hasText("限速")) { "任务页蜗牛模式不可达" }
            app.click("设置上限"); app.idle(400)
            app.save(File("build/shots/adaptive-framework/03-snail-limits.png"))
            app.key("Esc"); app.idle(300)
            app.resize(400, 860)
            app.save(File("build/shots/adaptive-framework/04-phone-tasks.png"))
            println("自适应框架：真实标签、窗口尺寸往返、输入切换、来源选择/搜索/保存投影、标签恢复、任务详情与蜗牛入口通过")
        }
    }
}
