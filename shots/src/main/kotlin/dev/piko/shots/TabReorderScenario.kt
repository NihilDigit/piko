package dev.piko.shots

import androidx.compose.ui.geometry.Offset
import dev.piko.shared.presentation.WorkspaceContext
import dev.piko.ui.theme.ThemeMode

internal fun verifyTabReorder() {
    ShotEnv("verify-tab-reorder", localDownloads = false).use { env ->
        AppScene.open(env, 1280, 800, ThemeMode.LIGHT, caption = true).use { app ->
            val workspace = env.services.driveWorkspace
            val first = workspace.contexts.activeContextId.value
            app.click("动画", androidx.compose.ui.input.pointer.PointerButton.Secondary)
            app.click("在新标签页打开", topmost = true)
            app.key("Ctrl+K"); app.type("添加链接"); app.key("Enter")
            check(app.pumpUntil { workspace.contexts.contextsFlow.value.size == 3 })
            app.idle(800)
            val draft = env.services.instantSession.drafts.single()
            val importId = workspace.contexts.activeContextId.value
            val folderId = workspace.contexts.contextsFlow.value.filterIsInstance<WorkspaceContext.File>().single { it.id != first }.id
            val from = app.nodeBounds("添加链接").center
            val to = from + Offset(240f, 0f)
            app.drag(from, to); app.release(to)
            check(app.pumpUntil { workspace.contexts.contextsFlow.value.map { it.id } == listOf(first, folderId, importId) }) {
                "鼠标拖动未改变混合标签顺序：${workspace.contexts.contextsFlow.value}"
            }
            check(workspace.contexts.activeContextId.value == importId)
            check(env.services.instantSession.drafts.single() === draft) { "排序重建了草稿会话" }
            app.key("Ctrl+Shift+PageUp")
            check(workspace.contexts.contextsFlow.value.map { it.id } == listOf(first, importId, folderId))
            val folderFrom = app.nodeBounds("动画").center
            val folderTo = folderFrom - Offset(450f, 0f)
            app.drag(folderFrom, folderTo); app.release(folderTo)
            check(app.pumpUntil { workspace.contexts.contextsFlow.value.map { it.id } == listOf(folderId, first, importId) })
            app.resize(400, 860)
            app.click("标签总览")
            check(app.nodeBounds("动画").left < app.nodeBounds("网盘").left) { "窄窗口总览未沿用排序" }
            app.click("关闭标签总览")
            app.resize(1280, 800)
            app.idle(700)
            val touchFrom = app.nodeBounds("动画").center
            app.swipe(touchFrom, touchFrom + Offset(450f, 0f))
            check(app.pumpUntil { workspace.contexts.contextsFlow.value.map { it.id } == listOf(first, importId, folderId) }) {
                "触摸拖动未沿用同一排序能力"
            }
            check(env.services.instantSession.drafts.single() === draft)
            println("标签排序：鼠标、触摸、键盘、混合标签身份与窄窗口顺序保留通过")
        }
    }
}
