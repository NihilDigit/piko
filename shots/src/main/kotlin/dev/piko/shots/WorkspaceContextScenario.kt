package dev.piko.shots

import androidx.compose.ui.geometry.Offset
import dev.piko.ui.theme.ThemeMode
import java.io.File

/** Real PikoApp input paths against FakePikPak, with screenshots of the retained-work lifecycle. */
internal fun verifyWorkspaceContexts(only: String? = null) {
    require(only == null || only in setOf("phone", "landscape", "tablet", "desktop", "desktop-narrow", "selection", "isolation", "switcher")) {
        "未知工作区形态：$only"
    }
    if (only == "switcher") { verifyWorkspaceChooser(); return }
    if (only == "selection") { verifyWorkspaceSelection(); return }
    if (only == "isolation") { verifyWorkspaceIsolation(); return }
    listOf(WorkspaceForm("phone", 400, 860), WorkspaceForm("landscape", 860, 400), WorkspaceForm("tablet", 1280, 800))
        .filter { only == null || it.name == only }.forEach(::verifyMobileContexts)
    listOf(WorkspaceForm("desktop", 1440, 900), WorkspaceForm("desktop-narrow", 700, 800))
        .filter { only == null || it.name == only }.forEach(::verifyDesktopContexts)
    println("工作区：${only ?: "手机、横屏、平板、桌面与窄桌面"}场景通过")
}

private data class WorkspaceForm(val name: String, val width: Int, val height: Int)
private const val DRAFT_A = "piko-context-draft-a"
private const val DRAFT_B = "piko-context-draft-b"
private const val INPUT_HINT = "磁力链接、下载地址或分享链接"

private fun verifyMobileContexts(form: WorkspaceForm) {
    ShotEnv("verify-workspace-${form.name}", localDownloads = false, extraSeed = { seedDuplicates() }).use { env ->
        AppScene.open(env, form.width, form.height, ThemeMode.LIGHT, mobile = true).use { app ->
            fun shot(state: String) = app.workspaceShot(form, state)
            fun minimize() { app.click("最小化"); app.idle(700); check(!app.hasText("关闭当前工作")) }
            fun works() { app.click("正在进行"); app.idle(700) }
            fun draft(input: String) {
                app.click("添加"); app.idle(500); app.click("添加链接", topmost = true)
                app.await(INPUT_HINT); app.click(INPUT_HINT); app.type(input); app.idle(600)
                app.expectInput(input)
            }
            fun duplicates() {
                app.click("更多"); app.idle(500); app.click("查找重复", topmost = true)
                app.await("最小化"); app.idle(2_000)
            }
            fun closeListed(title: String) {
                val label = "关闭：$title"
                app.semanticsClick(label); app.moveTo(Offset(5f, 5f)); app.idle(700)
                if (app.hasText("取消")) app.confirmWorkspaceEnd()
                app.idle(700)
                if (app.hasText(label)) { shot("failure-close"); error("关闭后仍有工作：$title") }
            }

            draft(DRAFT_A)
            shot("01-editor")
            minimize()
            shot("02-files-retained")
            draft(DRAFT_B)
            minimize()
            works()
            app.expect("关闭：$DRAFT_A", "关闭：$DRAFT_B")
            shot("03-work-list")
            app.semanticsClick(DRAFT_A); app.idle(600)
            app.expectInput(DRAFT_A)
            app.clickEditableText(DRAFT_A); app.key("End"); app.type("-continued")
            app.expectInput("$DRAFT_A-continued")
            minimize()

            app.click("任务"); app.idle(900)
            check(!app.hasText(DRAFT_A) && !app.hasText(DRAFT_B)) { "草稿仍混入任务列表" }
            shot("04a-tasks-without-drafts")
            works()
            app.semanticsClick("$DRAFT_A-continued"); app.await("最小化")
            app.expectInput("$DRAFT_A-continued")
            minimize()
            app.expect("筛选任务类型", "任务")
            shot("04-task-return")
            app.click("文件"); app.idle(700)
            works()
            app.semanticsClick("关闭：$DRAFT_A-continued"); app.idle(500)
            app.expect("取消", "确认关闭工作")
            shot("05-close-confirmation")
            app.click("取消"); app.idle(500)
            app.expect("关闭：$DRAFT_A-continued", "关闭：$DRAFT_B")
            closeListed("$DRAFT_A-continued")
            app.expect("关闭：$DRAFT_B")
            closeListed(DRAFT_B)
            if (app.hasText("关闭工作列表")) app.semanticsClick("关闭工作列表") else app.key("Esc")
            app.idle(700)

            draft("magnet:?xt=urn:btih:" + "a".repeat(40))
            app.await("已选 2 / 2"); app.expectInput("Frieren S01"); app.idle(1_000)
            shot("05b-resolved-draft")
            if (form.name == "phone") {
                app.click("更换保存位置"); app.await("选择保存位置")
                shot("05c-target-picker")
                app.click("关闭"); app.idle(600)
                app.expectInput("magnet:?xt=urn:btih:" + "a".repeat(40))
            }
            app.click("关闭当前工作"); app.idle(500)
            app.confirmWorkspaceEnd(); app.idle(700)

            duplicates()
            app.expect("查重：网盘")
            app.click("不同版本"); app.idle(1_000)
            app.await("备份")
            shot("06-report-root")
            app.click("备份"); app.idle(800)
            app.await("返回上一级")
            shot("07-report-folder")
            if (form.name == "phone") {
                app.longPress("Frieren 01"); app.idle(700)
                shot("07b-report-file-actions")
                app.click("属性"); app.idle(700)
                shot("07c-report-file-properties")
                app.click("Close sheet"); check(app.pumpUntil { !app.hasText("Close sheet") })
                app.longPress("Frieren 01"); app.idle(500); app.click("选择"); app.await("退出多选")
                shot("07d-report-selection")
                app.semanticsClick("全选"); app.await("已选择 2 项")
                app.click("操作"); app.idle(700)
                shot("07e-report-selection-actions")
                app.click("Close sheet"); check(app.pumpUntil { !app.hasText("Close sheet") })
                app.click("退出多选"); app.idle(600)
            }
            app.click("返回上一级"); app.idle(700)
            app.expect("备份", "最小化")
            minimize()
            duplicates()
            minimize()
            works()
            check(app.exactTextCount("关闭：查重：网盘", topmost = true) == 1) { "同一起点产生了多份查重" }
            app.semanticsClick("关闭工作列表"); app.idle(600)
            app.click("电影"); app.await("Arrival")
            duplicates()
            app.expect("查重：电影")
            minimize()
            app.expect("Arrival")
            works()
            app.expect("关闭：查重：网盘", "关闭：查重：电影")
            shot("08-multiple-reports")
            app.semanticsClick("查重：网盘"); app.await("最小化")
            app.expect("备份")
            app.click("关闭当前工作"); app.idle(500)
            if (form.name == "phone") shot("08b-close-current")
            app.click("取消"); app.idle(500)
            app.expect("最小化", "备份")
            app.click("关闭当前工作"); app.idle(500)
            app.confirmWorkspaceEnd(); app.idle(700)
            app.expect("Arrival")
            works()
            check(!app.hasText("关闭：查重：网盘"))
            app.expect("关闭：查重：电影")
            closeListed("查重：电影")
            shot("09-all-closed")
            println("${form.name}: 草稿保留、独立工作恢复、来源返回、多个查重、同根复用与关闭通过")
        }
    }
}

private fun verifyWorkspaceSelection() {
    ShotEnv("verify-workspace-selection", localDownloads = false, extraSeed = { seedDuplicates() }).use { env ->
        AppScene.open(env, 400, 860, ThemeMode.LIGHT, mobile = true).use { app ->
            app.click("更多"); app.idle(500); app.click("查找重复", topmost = true)
            app.await("不同版本"); app.click("不同版本"); app.await("备份")
            app.click("备份"); app.await("Frieren 01"); app.idle(700)
            app.longPress("Frieren 01"); app.idle(700)
            app.workspaceShot(WorkspaceForm("phone", 400, 860), "selection-debug-actions")
            app.click("属性"); app.idle(700)
            app.workspaceShot(WorkspaceForm("phone", 400, 860), "selection-debug-properties")
            app.click("Close sheet"); check(app.pumpUntil { !app.hasText("Close sheet") })
            app.longPress("Frieren 01"); app.idle(500); app.click("选择"); app.await("退出多选")
            app.workspaceShot(WorkspaceForm("phone", 400, 860), "07d-report-selection")
            app.semanticsClick("全选"); app.await("已选择 2 项")
            app.click("操作"); app.idle(700)
            app.workspaceShot(WorkspaceForm("phone", 400, 860), "07e-report-selection-actions")
            app.click("Close sheet"); check(app.pumpUntil { !app.hasText("Close sheet") })
            app.click("退出多选"); app.await("返回上一级")
            println("phone: 查重树长按多选与退出通过")
        }
    }
}

private fun verifyWorkspaceIsolation() {
    ShotEnv("verify-workspace-isolation", localDownloads = false).use { env ->
        AppScene.open(env, 400, 860, ThemeMode.LIGHT, mobile = true).use { app ->
            fun draft(value: String) {
                app.click("添加"); app.idle(500); app.click("添加链接")
                app.await(INPUT_HINT); app.click(INPUT_HINT); app.type(value); app.expectInput(value)
            }
            draft(DRAFT_A); app.click("最小化"); app.idle(600)
            draft(DRAFT_B)
            app.click("正在进行"); app.idle(600)
            app.semanticsClick("关闭：$DRAFT_A"); app.idle(500)
            app.confirmWorkspaceEnd(); app.idle(600)
            check(!app.hasText("关闭：$DRAFT_A")); app.await("关闭：$DRAFT_B")
            app.semanticsClick("关闭工作列表"); app.idle(600)
            app.expect("关闭当前工作", "最小化"); app.expectInput(DRAFT_B)
            app.workspaceShot(WorkspaceForm("phone", 400, 860), "isolation-active-b")
            app.clickEditableText(DRAFT_B); app.key("End"); app.type("-still-active")
            app.expectInput("$DRAFT_B-still-active")
            println("phone: B工作页关闭A后保持B可见且可继续输入")
        }
    }
}

private fun verifyDesktopContexts(form: WorkspaceForm) {
    ShotEnv("verify-workspace-${form.name}", localDownloads = false, extraSeed = { seedDuplicates() }).use { env ->
        AppScene.open(env, form.width, form.height, ThemeMode.LIGHT, mobile = false).use { app ->
            fun command(text: String) { app.key("Ctrl+K"); app.type(text); app.key("Enter"); app.idle(1_200) }
            command("查找重复")
            app.await("查重：网盘")
            app.key("Ctrl+Tab"); app.idle(500)
            app.click("电影"); app.key("Enter"); app.await("Arrival")
            command("查找重复")
            app.expect("查重：网盘", "查重：电影")
            app.key("Ctrl+Tab"); app.idle(500)
            command("添加链接")
            app.await(INPUT_HINT); app.click(INPUT_HINT); app.type(DRAFT_A); app.idle(700)
            app.expectInput(DRAFT_A)
            app.workspaceShot(form, "01-contexts")
            check(!app.hasText("正在进行")) { "桌面仍显示重复的工作选择器入口" }
            check(!app.hasText("关闭工作列表"))
            app.semanticsClick("查重：网盘"); app.idle(500)
            app.click("关闭标签页"); app.idle(500)
            if (app.hasText("取消")) { app.confirmWorkspaceEnd(); app.idle(600) }
            app.expect("查重：电影", DRAFT_A)
            check(!app.hasText("查重：网盘"))
            app.workspaceShot(form, "02-after-close")
            app.click(DRAFT_A); app.idle(500); app.expectInput(DRAFT_A)
            println("${form.name}: 桌面标签共存、关闭隔离与草稿保留通过")
        }
    }
}

private fun AppScene.workspaceShot(form: WorkspaceForm, state: String) {
    moveTo(Offset(5f, 5f)); idle(700)
    save(File("build/shots/workspace-${form.name}-${form.width}x${form.height}-$state.png"))
}

private fun AppScene.await(text: String) {
    if (pumpUntil { hasText(text) }) return
    val failure = File("build/shots/workspace-context-failure.png")
    failure.parentFile.mkdirs(); save(failure)
    File("build/shots/workspace-context-failure.txt").writeText(texts().joinToString("\n"))
    error("未出现「$text」；画面与文本已保存到 ${failure.parentFile.absolutePath}")
}

private fun AppScene.expect(vararg texts: String) { texts.forEach { await(it) } }

private fun AppScene.expectInput(text: String) {
    check(pumpUntil { text in editableTexts() }) { "草稿输入未保留；期望=$text，实际=${editableTexts()}" }
}

private fun AppScene.confirmWorkspaceEnd() {
    semanticsClick("确认关闭工作")
}


private fun verifyWorkspaceChooser() {
    listOf(WorkspaceForm("phone", 400, 860), WorkspaceForm("landscape", 860, 400), WorkspaceForm("tablet", 1280, 800), WorkspaceForm("desktop", 1440, 900)).forEach { form ->
        ShotEnv("verify-chooser-${form.name}", localDownloads = false, extraSeed = { seedDuplicates(); clearTasks() }).use { env ->
            val desktop = form.name == "desktop"
            AppScene.open(env, form.width, form.height, ThemeMode.LIGHT, mobile = !desktop).use { app ->
                if (desktop) {
                    app.key("Ctrl+K"); app.type("查找重复"); app.key("Enter")
                    app.await("查重：网盘"); app.idle(1_500); app.key("Ctrl+Tab"); app.idle(600)
                } else {
                    app.click("更多"); app.idle(500); app.click("查找重复", topmost = true)
                    app.await("最小化"); app.idle(1_500); app.click("最小化"); app.idle(600)
                }
                val link = "magnet:?xt=urn:btih:" + "a".repeat(40)
                edt { env.services.instantMagnetRepository.onIncomingMagnet(link) }
                app.changed(); app.await("已选 2 / 2")
                if (!desktop) { app.click("最小化"); app.idle(600) }
                if (desktop) {
                    app.expect("Frieren S01", "查重：网盘")
                    check(!app.hasText("正在进行") && !app.hasText("关闭工作列表"))
                    app.workspaceShot(form, "10-tabs")
                } else {
                    app.click("正在进行"); app.idle(600)
                    app.expect("导入草稿", "查重", "Frieren S01", "关闭：Frieren S01", "关闭：查重：网盘")
                    app.workspaceShot(form, "10-mixed-work")
                    app.semanticsClick("关闭工作列表"); app.idle(500)
                }
                app.click("任务"); app.await("暂无任务")
                check(!app.hasText("Frieren S01") && !app.hasText("查重：网盘")) { "保留工作污染任务列表" }
                app.workspaceShot(form, "11-tasks-empty")
                if (desktop) check(!app.hasText("正在进行"))
                if (!desktop) {
                    app.click("正在进行"); app.idle(600)
                    app.semanticsClick("Frieren S01"); app.await("最小化")
                    app.expectInput(link); app.expect("已选 2 / 2")
                    app.click("最小化"); app.await("暂无任务")
                }
            }
        }
    }
    println("工作选择器：混合分组、任务列表隔离与恢复通过")
}
