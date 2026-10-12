package dev.piko.shots

import androidx.compose.ui.geometry.Offset
import dev.piko.ui.theme.ThemeMode
import java.io.File

/** Runs the real app against the fake PikPak server, using the same input path as screenshot scripts. */
internal fun verifyWorkspaceContexts() {
    verifyMobileContexts()
    verifyMobileDuplicateReplacement()
    verifyDesktopContexts()
    println("工作区上下文：移动端与桌面端场景通过")
}

private fun verifyMobileDuplicateReplacement() {
    ShotEnv("verify-workspace-replacement", localDownloads = false).use { env ->
        AppScene.open(env, 400, 860, ThemeMode.LIGHT, mobile = true).use { app ->
            fun findDuplicates() {
                app.click("更多")
                app.idle(500)
                app.click("查找重复", topmost = true)
            }

            findDuplicates()
            app.await("操作：查重：网盘")
            findDuplicates()
            app.await("操作：查重：网盘")
            check(!app.hasText("替换当前查重？")) { "同一起点重复打开却要求替换" }

            app.click("电影")
            app.await("Arrival")
            findDuplicates()
            app.await("替换当前查重？")
            app.click("取消")
            app.expect("操作：查重：网盘")

            findDuplicates()
            app.await("替换当前查重？")
            app.click("替换")
            app.await("操作：查重：电影")
            check(!app.hasText("操作：查重：网盘")) { "更换起点后旧查重页仍在" }
            app.click("操作：查重：电影")
            app.click("结束查重", topmost = true)
            app.idle(800)
            check(!app.hasText("操作：查重：电影")) { "关闭最后一个报告后面板仍在" }
        }
    }
}

private fun verifyMobileContexts() {
    ShotEnv("verify-workspace-mobile", localDownloads = false).use { env ->
        AppScene.open(env, 400, 860, ThemeMode.LIGHT, mobile = true).use { app ->
            app.click("更多")
            app.idle(500)
            app.click("查找重复")
            app.await("操作：查重：网盘")
            app.expandSheet()
            app.click("查看更多")
            app.await("返回面板")
            app.click("返回面板")
            app.await("查看更多")
            app.collapseSheet()

            app.click("添加")
            app.idle(600)
            app.click("添加链接")
            app.await("操作：添加链接")
            app.expandSheet()
            app.expect("查重", "添加链接")
            // Edit inside the expanded Sheet, switch contexts, and verify the draft survives.
            app.click("磁力链接、下载地址或分享链接")
            app.type("piko-context-draft")
            app.await("piko-context-draft")
            app.click("切换到：查重：网盘")
            app.await("操作：查重：网盘")
            app.click("切换到：添加链接")
            app.await("操作：piko-context-draft")
            app.expect("piko-context-draft")
            app.assertExpandedSheetLayout()
            app.moveTo(Offset(5f, 5f))
            app.idle(300)
            app.save(File("build/shots/workspace-mobile-expanded.png").apply { parentFile.mkdirs() })

            app.click("查看更多")
            app.await("返回面板")
            app.save(File("build/shots/workspace-mobile-details.png"))
            app.await("piko-context-draft")
            app.click("返回面板")
            app.await("查看更多")
            app.collapseSheet()
            app.moveTo(Offset(5f, 5f))
            app.idle(300)
            app.save(File("build/shots/workspace-mobile-collapsed.png"))
            app.expandSheet()
            app.click("切换到：查重：网盘")
            app.await("操作：查重：网盘")
            app.click("切换到：添加链接")
            app.await("操作：piko-context-draft")
            app.expect("piko-context-draft")

            // Swipe back to the duplicate page, then close the active page. The draft must remain.
            app.swipe(Offset(65f, 650f), Offset(330f, 650f))
            app.await("操作：查重：网盘")
            app.idle(500)
            app.moveTo(Offset(5f, 5f))
            app.save(File("build/shots/workspace-mobile-swipe.png"))
            app.click("操作：查重：网盘")
            app.await("结束查重")
            app.save(File("build/shots/workspace-mobile-actions.png"))
            app.click("结束查重")
            app.expect("操作：piko-context-draft", "piko-context-draft")
            check(!app.hasText("操作：查重：网盘")) { "关闭查重后仍保留了旧页：${app.texts().take(50)}" }

            app.click("操作：piko-context-draft")
            app.click("放弃草稿", topmost = true)
            app.idle(800)
            if (app.hasText("放弃添加？")) app.click("放弃")
            app.idle(800)
            check(!app.hasText("操作：piko-context-draft")) { "最后一个上下文关闭后面板仍在：${app.texts().take(50)}" }
        }
    }
}

private fun verifyDesktopContexts() {
    ShotEnv("verify-workspace-desktop", localDownloads = false).use { env ->
        AppScene.open(env, 1440, 900, ThemeMode.LIGHT).use { app ->
            fun openDuplicate() {
                app.key("Ctrl+K")
                app.type("查找重复")
                app.key("Enter")
                app.await("查重：")
            }

            openDuplicate()
            app.await("查重：网盘")
            app.key("Ctrl+Tab")
            app.click("电影")
            app.key("Enter")
            app.await("Arrival")
            openDuplicate()
            app.expect("查重：网盘", "查重：电影")

            app.key("Ctrl+Tab")
            app.key("Ctrl+Tab")
            app.key("Ctrl+K")
            app.type("添加链接")
            app.key("Enter")
            app.await("添加链接")
            app.expect("查重：网盘", "查重：电影")
            app.save(File("build/shots/workspace-desktop-contexts.png").apply { parentFile.mkdirs() })

            app.click("查重：网盘")
            app.click("关闭标签页")
            app.expect("查重：电影", "添加链接")
            app.save(File("build/shots/workspace-desktop-after-close.png"))
            check(!app.hasText("查重：网盘")) { "关闭根目录报告影响了其他标签：${app.texts().take(50)}" }
        }
    }
}

private fun AppScene.await(text: String) {
    if (pumpUntil { hasText(text) }) return
    val failure = File("build/shots/workspace-context-failure.png")
    failure.parentFile.mkdirs()
    save(failure)
    File("build/shots/workspace-context-failure.txt").writeText(texts().joinToString("\n"))
    error("未出现「$text」；画面与文本已保存到 ${failure.parentFile.absolutePath}")
}

private fun AppScene.expect(vararg texts: String) {
    texts.forEach { await(it) }
}

private fun AppScene.assertExpandedSheetLayout() {
    val footer = requireNotNull(find("查看更多")) { "展开态缺少查看更多" }.boundsInRoot
    val navigation = requireNotNull(find("我的")) { "展开态缺少底部主导航" }.boundsInRoot
    val handle = requireNotNull(find("Drag handle")) { "展开态缺少拖动条" }.boundsInRoot
    check(footer.width > 0f && footer.bottom < navigation.top) {
        "查看更多未固定在可见导航栏上方：footer=$footer, nav=$navigation"
    }
    check(navigation.top - handle.top <= navigation.top * 0.72f) {
        "展开面板超过内容区七成上限：handle=$handle, nav=$navigation"
    }
}

private fun AppScene.expandSheet() {
    val handle = find("Drag handle")?.boundsInRoot?.center ?: error("找不到底部面板拖动条")
    swipe(handle, Offset(handle.x, (handle.y - 420f).coerceAtLeast(80f)))
    await("查看更多")
}

private fun AppScene.collapseSheet() {
    val handle = find("Drag handle")?.boundsInRoot?.center ?: error("找不到底部面板拖动条")
    swipe(handle, Offset(handle.x, 750f))
    idle(500)
    check(!hasText("查看更多")) { "面板未收回低位：${texts().take(50)}" }
}
