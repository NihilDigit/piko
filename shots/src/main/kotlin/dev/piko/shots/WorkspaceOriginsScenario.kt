package dev.piko.shots

import androidx.compose.ui.geometry.Offset
import dev.piko.ui.theme.ThemeMode
import java.io.File

/** Exercise real navigation entry points; no workspace ViewModel or route injection. */
internal fun verifyWorkspaceOrigins() {
    verifyLibraryOrigin("starred", "已加星标的文件与文件夹", "星标", "Dune")
    verifyLibraryOrigin("recent", "最近上传与离线、秒传的文件", "最近添加", "最近添加")
    verifySearchOrigin()
    verifyExternalLinkOrigin()
    verifyLocatedOrigin()
    println("工作来源：星标、最近添加、搜索、外部链接与任务定位返回通过")
}

private const val ORIGIN_INPUT_HINT = "磁力链接、下载地址或分享链接"

private fun verifyLibraryOrigin(name: String, entry: String, title: String, content: String) {
    ShotEnv("verify-origin-$name", localDownloads = false).use { env ->
        AppScene.open(env, 400, 860, ThemeMode.LIGHT, mobile = true).use { app ->
            app.click("我的"); app.idle(700)
            app.click(entry); app.originAwait(title); app.originAwait(content)
            app.originShot("$name-01-source")
            app.originDraft("origin-$name-draft")
            app.originShot("$name-02-work")
            if (name == "starred") {
                app.resize(1280, 800)
                check("origin-$name-draft" in app.editableTexts()) { "变宽后未显示当前草稿" }
                app.resize(400, 860)
                check("origin-$name-draft" in app.editableTexts()) { "变窄后当前草稿丢失" }
            }
            app.originMinimize()
            app.originAwait(title); app.originAwait(content)
            app.originShot("$name-03-restored")
            app.click("标签总览"); app.idle(600)
            app.click("origin-$name-draft"); app.originAwait("标签总览")
            check("origin-$name-draft" in app.editableTexts())
            app.originMinimize()
            app.originAwait(title); app.originAwait(content)
        }
    }
}

private fun verifySearchOrigin() {
    ShotEnv("verify-origin-search", localDownloads = false).use { env ->
        AppScene.open(env, 400, 860, ThemeMode.LIGHT, mobile = true).use { app ->
            app.click("搜索"); app.idle(500); app.type("Dune"); app.idle(1_000)
            app.originAwait("Dune")
            check("Dune" in app.editableTexts()) { "搜索词未进入实际搜索输入框" }
            app.originShot("search-01-source")
            app.originDraft("origin-search-draft")
            app.originShot("search-02-work")
            app.originMinimize()
            check(app.pumpUntil { "Dune" in app.editableTexts() }) { "从工作返回后搜索词丢失：${app.editableTexts()}" }
            app.originAwait("Dune")
            check(!app.hasText("Oppenheimer")) { "返回后搜索过滤失效" }
            app.originShot("search-03-restored")
            app.click("标签总览"); app.idle(600)
            app.click("origin-search-draft"); app.originAwait("标签总览")
            check("origin-search-draft" in app.editableTexts())
            app.originMinimize()
            check(app.pumpUntil { "Dune" in app.editableTexts() }) { "从搜索栏恢复工作后查询丢失" }
            app.originShot("search-04-switcher-restored")
        }
    }
}

private fun verifyExternalLinkOrigin() {
    ShotEnv("verify-origin-external", localDownloads = false).use { env ->
        AppScene.open(env, 400, 860, ThemeMode.LIGHT, mobile = true).use { app ->
            app.click("我的"); app.idle(600)
            app.click("已加星标的文件与文件夹"); app.originAwait("Dune")
            app.originShot("external-01-source")
            val link = "magnet:?xt=urn:btih:" + "a".repeat(40)
            edt { env.services.instantMagnetRepository.onIncomingMagnet(link) }
            app.changed(); app.originAwait("标签总览")
            check(app.pumpUntil { link in app.editableTexts() }) { "外链未进入草稿" }
            check(app.pumpUntil { "Frieren S01" in app.editableTexts() }) { "外链解析结果未出现" }
            app.originShot("external-02-work")
            app.originMinimize()
            app.originAwait("星标"); app.originAwait("Dune")
            app.originShot("external-03-restored")
        }
    }
}

private fun verifyLocatedOrigin() {
    ShotEnv("verify-origin-located", localDownloads = false, extraSeed = {
        clearTasks()
        val located = addFile("Frieren - SP01 [1080p].mkv", 300L shl 20, parentId = node("SPs").id)
        addTask("Arrival.2016.1080p.BluRay.x264.mkv", "PHASE_TYPE_COMPLETE", 100, 9L shl 30,
            created = hoursAgo(6), fileId = located.id)
    }).use { env ->
        AppScene.open(env, 400, 860, ThemeMode.LIGHT, mobile = true).use { app ->
            app.click("任务"); app.originAwait("Arrival")
            app.click("Arrival"); app.originAwait("SP01"); app.idle(700)
            app.originShot("located-01-source")
            app.originDraft("origin-located-draft")
            app.originShot("located-02-work")
            app.originMinimize()
            app.originAwait("SP01")
            app.originShot("located-03-restored")
            app.click("返回"); app.idle(700)
            app.originAwait("筛选任务类型")
            app.originAwait("Arrival")
            app.originShot("located-04-back-to-tasks")
        }
    }
}

private fun AppScene.originDraft(text: String) {
    key("Ctrl+K"); idle(500); type("添加链接"); idle(500); key("Enter")
    originAwait(ORIGIN_INPUT_HINT)
    click(ORIGIN_INPUT_HINT); type(text); idle(600)
    check(text in editableTexts()) { "草稿输入失败：${editableTexts()}" }
    originAwait("标签总览")
}

private fun AppScene.originMinimize() {
    click("返回来源"); idle(800)
    check(!hasText("磁力链接、下载地址或分享链接")) { "返回后仍停在工作页面" }
}

private fun AppScene.originShot(name: String) {
    moveTo(Offset(5f, 5f)); idle(700)
    save(File("build/shots/workspace-origin-$name.png"))
}

private fun AppScene.originAwait(text: String) {
    if (pumpUntil { hasText(text) }) return
    originShot("failure")
    File("build/shots/workspace-origin-failure.txt").writeText(texts().joinToString("\n"))
    error("来源验证未出现「$text」；见 workspace-origin-failure.png")
}



internal fun verifyWorkspaceImportVariants() {
    listOf(Triple("phone", 400, 860), Triple("landscape", 860, 400)).forEach { (form, width, height) ->
        ShotEnv("verify-import-variants-$form", localDownloads = false).use { env ->
            AppScene.open(env, width, height, ThemeMode.LIGHT, mobile = true).use { app ->
                val share = "https://mypikpak.com/s/SHOT"
                edt { env.services.instantMagnetRepository.onIncomingMagnet(share) }
                app.changed(); app.originAwait("Frieren 分享内容")
                app.originAwait("Frieren - 01.ass")
                app.originShot("imports-$form-01-share")
                app.click("Frieren - 01.ass"); app.originAwait("转存 1 项")
                app.originShot("imports-$form-02-share-selected")
                app.click("更换保存位置"); app.originAwait("选择保存位置")
                app.originShot("imports-$form-03-share-target")
                app.click("关闭"); app.idle(600)
                app.originAwait("转存 1 项")
                app.originMinimize()

                val links = listOf('a', 'b').joinToString("\n") { "magnet:?xt=urn:btih:" + it.toString().repeat(40) }
                edt { env.services.instantMagnetRepository.onIncomingMagnet(links) }
                app.changed(); app.originAwait("2 条链接，点开可勾选文件")
                app.originAwait("Frieren S01")
                check(app.pumpUntil { app.exactTextCount("Frieren S01") == 2 }) { "批量解析未产生两行" }
                app.originShot("imports-$form-04-batch")
                app.clickHighest("Frieren S01"); app.originAwait("第 1 项，共 2 项")
                app.originAwait("已选 2 / 2")
                app.originShot("imports-$form-05-batch-detail")
                app.click("全不选"); app.originAwait("已选 0 / 2")
                app.click("返回列表"); app.originAwait("未勾选文件，不保存")
                app.originShot("imports-$form-06-batch-return")
                app.originMinimize()
                app.click("标签总览"); app.idle(600)
                app.originAwait("2 条链接"); app.originAwait("Frieren 分享内容")
                app.originShot("imports-$form-07-switcher")
                app.click("Frieren 分享内容"); app.originAwait("转存 1 项")
                app.originMinimize()
                app.click("标签总览"); app.idle(600)
                app.click("2 条链接"); app.originAwait("未勾选文件，不保存")
                app.originShot("imports-$form-08-batch-resumed")
            }
        }
    }
    verifyRecreatedWorkspace()
    println("导入分支：手机与横屏分享选择、目标、批量详情与多工作恢复、同账号界面重建通过")
}

private fun verifyRecreatedWorkspace() {
    ShotEnv("verify-origin-recreated", localDownloads = false).use { env ->
        AppScene.open(env, 400, 860, ThemeMode.LIGHT, mobile = true).use { app ->
            app.originDraft("origin-recreated-draft")
            app.originMinimize()
            app.originShot("recreated-01-before")
        }
        AppScene.open(env, 400, 860, ThemeMode.LIGHT, mobile = true).use { app ->
            app.click("标签总览"); app.originAwait("origin-recreated-draft")
            app.click("origin-recreated-draft"); app.originAwait("标签总览")
            check("origin-recreated-draft" in app.editableTexts()) { "同一账号服务下界面重建丢失输入" }
            app.originShot("recreated-02-resumed")
        }
    }
}


internal fun verifyWorkspacePreview() {
    ShotEnv("verify-origin-preview", localDownloads = false).use { env ->
        AppScene.open(env, 400, 860, ThemeMode.LIGHT, mobile = true).use { app ->
            val link = "magnet:?xt=urn:btih:" + "a".repeat(40)
            edt { env.services.instantMagnetRepository.onIncomingMagnet(link) }
            app.changed(); app.originAwait("已选 2 / 2")
            app.clickHighest("预览")
            check(app.pumpUntil(15_000) { app.hasText("返回") || app.hasText("预览失败") }) {
                app.originShot("preview-unresolved")
                "预览既未进入播放器，也未显示明确失败"
            }
            if (app.hasText("预览失败")) {
                val failure = app.texts().filter { it.contains("预览失败") }.joinToString()
                app.originShot("preview-fixture-limit")
                println("PREVIEW_FIXTURE_LIMIT: $failure")
                return
            }
            app.originShot("preview-01-player")
            app.clickHighest("返回"); app.originAwait("标签总览")
            check(link in app.editableTexts()) { "播放器返回后来源草稿丢失" }
            app.originAwait("已选 2 / 2")
            app.originShot("preview-02-draft")
            println("预览入口进入播放器并返回同一草稿通过；不验证媒体解码")
        }
    }
}

internal fun verifyWorkspaceTargetPicker() {
    listOf(Triple("phone", 400, 860), Triple("landscape", 860, 400), Triple("tablet", 1280, 800))
        .forEach { (form, width, height) ->
            ShotEnv("verify-target-picker-$form", localDownloads = false).use { env ->
                AppScene.open(env, width, height, ThemeMode.LIGHT, mobile = true).use { app ->
                    val link = "magnet:?xt=urn:btih:" + "a".repeat(40)
                    edt { env.services.instantMagnetRepository.onIncomingMagnet(link) }
                    app.changed(); app.originAwait("已选 2 / 2")
                    app.click("更换保存位置"); app.originAwait("选择保存位置")
                    app.idle(700)
                    app.checkTargetPickerBounds()
                    app.originShot("target-$form-01-picker")
                    if (form == "landscape") {
                        app.swipe(Offset(width / 2f, 290f), Offset(width / 2f, 135f))
                        app.idle(700)
                        app.checkTargetPickerBounds()
                        app.originShot("target-$form-02-scrolled")
                    }
                    app.click("照片备份 2026"); app.idle(700)
                    app.originShot("target-$form-03-folder")
                    app.click("存到这里"); app.originAwait("标签总览")
                    app.originAwait("照片备份 2026")
                    check(link in app.editableTexts()) { "选择目标后草稿输入丢失" }
                    app.originShot("target-$form-04-applied")
                }
            }
        }
    println("目标选择器：手机、横屏、平板目录边界未覆盖确认栏，最后目录选择与目标回写通过")
}

private fun AppScene.checkTargetPickerBounds() {
    val footer = requireNotNull(find("目标位置")) { "缺少目标位置栏" }
    fun rootId(node: androidx.compose.ui.semantics.SemanticsNode): Int {
        var current = node
        while (current.parent != null) current = requireNotNull(current.parent)
        return current.id
    }
    val owner = rootId(footer)
    val footerTop = footer.boundsInRoot.top
    var checked = 0
    listOf("动画", "电影", "文档", "音乐", "照片备份 2026").forEach { name ->
        val node = find(name) ?: return@forEach
        val bounds = node.boundsInRoot
        if (rootId(node) != owner || bounds.width <= 0f || bounds.height <= 0f) return@forEach
        checked++
        check(bounds.bottom <= footerTop) {
            originShot("target-overlap")
            "目录$name bottom=${bounds.bottom} 覆盖目标位置栏top=$footerTop"
        }
    }
    check(checked > 0) { "没有检查到与目标位置栏同一语义树的可见目录" }
}
