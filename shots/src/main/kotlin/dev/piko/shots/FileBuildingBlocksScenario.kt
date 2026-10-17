package dev.piko.shots

import androidx.compose.ui.geometry.Offset
import dev.piko.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.runBlocking

internal fun verifyImportPanel() {
    ShotEnv("verify-import-panel").use { env ->
        AppScene.open(env, 1280, 860, ThemeMode.LIGHT, caption = true).use { app ->
            env.server.resourceResponse = """{"list":{"resources":[{"name":"单视频.mkv","file_size":"100","is_dir":false,"meta":{"hash":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}}]}}"""
            edt { env.services.instantMagnetRepository.onIncomingMagnet(listOf('a', 'b', 'c').joinToString("\n") {
                "magnet:?xt=urn:btih:" + it.toString().repeat(40)
            }) }
            app.changed()
            check(app.pumpUntil { app.exactTextBounds("单视频.mkv").size >= 3 && app.hasText("全部保存") })
            check(!app.hasText("搜索原始文件") && !app.hasText("普通文件") && !app.hasText("已选 3"))
            app.semanticsClick("搜索来源文件")
            check(app.pumpUntil { app.hasText("搜索原始文件") })
            app.type("no-match")
            check(app.pumpUntil { !app.hasText("选择来源 单视频.mkv") })
            app.semanticsClick("搜索来源文件")
            check(app.pumpUntil { app.hasText("选择来源 单视频.mkv") && !app.hasText("搜索原始文件") })
            app.moveTo(Offset(100f, 400f))
            app.save(File("build/shots/file-building-blocks/10-panel-wide.png"))
            app.resize(400, 860)
            app.semanticsClick("搜索来源文件")
            check(app.pumpUntil { app.exactTextBounds("搜索原始文件").any { it.top in 100f..500f } })
            app.semanticsClick("搜索来源文件")
            app.moveTo(Offset(10f, 80f))
            app.save(File("build/shots/file-building-blocks/11-panel-narrow.png"))
            val save = app.exactTextBounds("全部保存").maxBy { it.top }
            val source = app.exactTextBounds("单视频.mkv").maxBy { it.top }
            check(save.top > source.bottom && save.bottom <= 860f)
            check(!app.hasText("收起管理面板") && !app.hasText("展开管理面板") && !app.hasText("搜索原始文件"))
            println("导入面板：右上角按需搜索、无摘要与额外箭头、保存固定底部、宽窄布局通过")
        }
    }
}

internal fun verifyFileBuildingBlocks() {
    ShotEnv("verify-file-building-blocks").use { env ->
        runBlocking { env.services.preferences.setDriveViewMode("LIST") }
        AppScene.open(env, 1280, 860, ThemeMode.LIGHT, caption = true).use { app ->
            fun expandManagement() {
                check(app.pumpUntil { app.exactTextBounds("管理链接").any { it.top > 100f } })
                val title = app.exactTextBounds("管理链接").maxBy { it.top }
                if (title.top > 600f) app.click(Offset(200f, title.top - 38f))
                check(app.pumpUntil { app.exactTextBounds("管理链接").any { it.top in 100f..400f } })
            }
            fun clearSelection() {
                app.click("退出多选")
                app.moveTo(Offset(100f, 150f))
                check(app.pumpUntil { !app.hasText("退出多选") })
            }
            app.useTouchInput(false)
            app.moveTo(Offset(100f, 150f))
            val before = app.nodeBounds("Oppenheimer")
            app.hover("Oppenheimer")
            check(app.hasText("选择 Oppenheimer")) { "列表悬停没有选择入口" }
            check(app.nodeBounds("Oppenheimer").left == before.left) { "列表悬停改变了名称位置" }
            app.click("选择 Oppenheimer")
            check(app.pumpUntil { app.hasText("退出多选") })
            clearSelection()
            app.useTouchInput(true)
            app.longPress("Oppenheimer")
            check(app.pumpUntil { app.hasText("退出多选") }) { "列表鼠标转触屏后的首次长按无效" }
            clearSelection()
            app.save(File("build/shots/file-building-blocks/01-list.png"))

            runBlocking { env.services.preferences.setDriveViewMode("POSTER") }
            app.changed(); app.idle(800)
            app.useTouchInput(false)
            app.moveTo(Offset(100f, 150f)); app.hover("Oppenheimer")
            app.click("选择 Oppenheimer.2023.1080p.BluRay.x264.mkv")
            check(app.pumpUntil { app.hasText("退出多选") }) { "海报悬停选择无效" }
            clearSelection()
            app.useTouchInput(true)
            app.longPress("Oppenheimer")
            check(app.pumpUntil { app.hasText("退出多选") }) { "海报鼠标转触屏后的首次长按无效" }
            clearSelection()
            app.save(File("build/shots/file-building-blocks/02-posters.png"))

            env.server.resourceResponse = """{"list":{"page_size":500,"next_page_token":"NEXT","resources":[{"name":"合集","is_dir":true,"dir":{"resources":[{"name":"Frieren - 01.mkv","file_size":"100","is_dir":false,"meta":{"hash":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}},{"name":"Frieren - 02.mkv","file_size":"200","is_dir":false,"meta":{"hash":""}}]}}]}}"""
            val writesBeforeImport = env.server.calls.count { it.startsWith("POST /drive/v1/files?") }
            edt { env.services.instantMagnetRepository.onIncomingMagnet("magnet:?xt=urn:btih:" + "c".repeat(40)) }
            app.changed()
            check(app.pumpUntil { app.hasText("清单不完整") && app.hasText("选择来源 Frieren - 02.mkv") })
            check(app.hasText("搜索来源文件") && !app.hasText("搜索原始文件") && app.hasText("未收录"))
            val beforeSearch = env.services.instantSession.drafts.single().state.sourceSelectedIndices
            app.semanticsClick("搜索来源文件")
            check(app.pumpUntil { app.hasText("搜索原始文件") })
            app.type("01")
            check(app.pumpUntil { !app.hasText("选择来源 Frieren - 02.mkv") })
            check(env.services.instantSession.drafts.single().state.sourceSelectedIndices == beforeSearch)
            app.semanticsClick("搜索来源文件")
            check(app.pumpUntil { !app.hasText("搜索原始文件") && app.hasText("选择来源 Frieren - 02.mkv") })
            check(!app.hasText("下载 Frieren") && !app.hasText("预览 Frieren")) { "管理树仍然带有预览下载按钮" }
            check(app.hasText("只保存已收录文件")) { "不完整清单未提供已收录部分的保存方式" }
            val draft = env.services.instantSession.drafts.single().state
            check(draft.savePlan?.blocked == true)
            check(!app.hasText("以引用保存") && !app.hasText("磁力链接、下载地址或分享链接")) { "管理选项仍挤在主内容区" }
            app.idle(600)
            app.save(File("build/shots/file-building-blocks/03-partial-source-wide.png"))
            val titleBounds = app.nodeBounds("Frieren - 01.mkv")
            val checkBounds = app.nodeBounds("选择来源 Frieren - 01.mkv")
            check(titleBounds.left - checkBounds.right <= 48f) { "叶子文件仍为不存在的展开箭头留空：$titleBounds / $checkBounds" }
            app.click("保存设置")
            check(app.pumpUntil { app.hasText("以引用保存") })
            app.click("以引用保存")
            check(app.pumpUntil { draft.saveAsReference })
            app.click("以引用保存")
            check(app.pumpUntil { !draft.saveAsReference })
            app.click("保存设置")
            check(app.pumpUntil { !app.hasText("以引用保存") })
            check(env.server.calls.count { it.startsWith("POST /drive/v1/files?") } == writesBeforeImport) { "仅显示来源就创建了远端文件" }
            app.save(File("build/shots/file-building-blocks/03-partial-source-wide.png"))
            // Main result browser uses normal drive navigation and the same item action sheet.
            app.useTouchInput(true)
            app.click(app.exactTextBounds("合集").maxBy { it.top }.center)
            app.save(File("build/shots/file-building-blocks/03-result-navigation.png"))
            fun mainVideoBounds() = (app.exactTextBounds("Frieren - 01") + app.exactTextBounds("Frieren - 01.mkv")).filter { it.left < 890f }
            check(app.pumpUntil { mainVideoBounds().isNotEmpty() }) { "主内容区未进入文件夹：${app.texts()}" }
            app.click(mainVideoBounds().first().center, androidx.compose.ui.input.pointer.PointerButton.Secondary)
            check(app.pumpUntil { app.hasText("预览") && app.hasText("下载") && app.hasText("属性") })
            app.click("属性", topmost = true)
            check(app.pumpUntil { app.hasText("关闭属性") })
            app.click("关闭属性")
            app.resize(400, 860)
            expandManagement()
            check(app.pumpUntil { app.hasText("选择来源 Frieren - 01.mkv") })
            val selected = draft.sourceSelectedIndices
            app.click("选择来源 Frieren - 02.mkv")
            check(app.pumpUntil { draft.sourceSelectedIndices != selected }) { "来源勾选没有回写保存计划" }
            check(draft.savePlan?.blocked == false)
            app.save(File("build/shots/file-building-blocks/04-source-narrow.png"))
            check(!app.hasText("以引用保存") && !app.hasText("保存较慢"))
            app.click("保存设置")
            check(app.pumpUntil { app.hasText("以引用保存") })
            app.save(File("build/shots/file-building-blocks/05-management-narrow.png"))
            app.click(Offset(200f, app.nodeBounds("管理链接").top - 24f))
            check(app.pumpUntil { app.nodeBounds("管理链接").top > 600f }) { "点击拖动条未收起管理面板" }
            // The collapsed sheet has no modal scrim; the visible drive controls still receive touches.
            app.click("切换保存结果视图")
            app.save(File("build/shots/file-building-blocks/05-collapsed-narrow.png"))
            app.resize(900, 400)
            check(app.hasText("管理链接") && app.hasText("保存"))
            app.save(File("build/shots/file-building-blocks/06-short-window.png"))
            app.resize(1280, 860)
            check(app.hasText("选择来源 Frieren - 01.mkv") && draft.sourceSelectedIndices != selected)
            // Settings, including link editing, stay in the manager across a resize.
            check(app.pumpUntil { app.hasText("磁力链接、下载地址或分享链接") })
            app.clickEditableText(draft.input)
            env.server.resourceResponse = """{"list":{"resources":[{"name":"重新解析.mkv","file_size":"100","is_dir":false,"meta":{"hash":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}}]}}"""
            app.key("Ctrl+A")
            app.type("magnet:?xt=urn:btih:" + "b".repeat(40))
            check(app.pumpUntil { draft.resolution != null && app.hasText("选择来源 重新解析.mkv") && app.hasText("以引用保存") }) {
                "编辑链接后丢失了管理工作区"
            }
            app.clickEditableText(draft.input)
            app.key("Ctrl+Enter")
            check(app.pumpUntil { env.services.instantSession.drafts.isEmpty() }) { "管理面板内的保存快捷键失效" }
            check(env.server.calls.count { it.startsWith("POST /drive/v1/files?") } > writesBeforeImport)
            env.server.resourceResponse = """{"list":{"resources":[{"name":"嵌套测试","is_dir":true,"dir":{"resources":[{"name":"子目录","is_dir":true,"dir":{"resources":[{"name":"文件.mkv","file_size":"100","is_dir":false,"meta":{"hash":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}}]}}]}}]}}"""
            edt { env.services.instantMagnetRepository.onIncomingMagnet("magnet:?xt=urn:btih:" + "d".repeat(40)) }
            app.changed()
            check(app.pumpUntil { app.exactTextBounds("子目录").any { it.left > 900f } && app.exactTextBounds("文件.mkv").any { it.left > 900f } })
            val folderTitle = app.exactTextBounds("子目录").maxBy { it.left }
            app.save(File("build/shots/file-building-blocks/07-nested-source.png"))
            val leafTitle = app.exactTextBounds("文件.mkv").maxBy { it.left }
            check(kotlin.math.abs(leafTitle.left - folderTitle.left - 16f) < 1f) { "叶子文件的缩进破坏了目录层级：$folderTitle / $leafTitle" }
            val next = env.services.instantSession.drafts.single().state
            env.server.resourceResponse = """{"list":{"resources":[{"name":"单视频.mkv","file_size":"100","is_dir":false,"meta":{"hash":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}}]}}"""
            edt { next.updateInput(listOf('e', 'f', 'a').joinToString("\n") { "magnet:?xt=urn:btih:" + it.toString().repeat(40) }) }
            app.changed()
            check(app.pumpUntil { next.batch?.rows?.all { it.state.resolution != null } == true })
            check(app.exactTextBounds("单视频.mkv").size + app.exactTextBounds("单视频").size == 6) { "多链接没有同时铺平在结果与来源中：${app.texts()}" }
            check(!app.hasText("第 1 项") && app.hasText("全部保存"))
            app.save(File("build/shots/file-building-blocks/08-flat-multi-link.png"))
            val first = next.batch!!.rows.first().state
            val firstCheck = app.nodeBounds("选择来源 单视频.mkv")
            app.click(firstCheck.center)
            check(app.pumpUntil { first.sourceSelectedIndices.isEmpty() })
            check(next.batch!!.rows.drop(1).all { it.state.sourceSelectedIndices.size == 1 }) { "同名文件勾选串到了其他链接" }
            app.resize(400, 860)
            expandManagement()
            app.save(File("build/shots/file-building-blocks/09-flat-multi-link-narrow.png"))
            check(app.pumpUntil { app.exactTextBounds("全部保存").any { it.height > 0f } }) { "展开后的保存按钮不可见：${app.texts()}" }
            val saveBounds = app.exactTextBounds("全部保存").maxBy { it.top }
            val lastSource = app.exactTextBounds("单视频.mkv").maxBy { it.top }
            check(saveBounds.top > lastSource.bottom && saveBounds.bottom <= 860f) { "保存按钮没有固定在文件树底部：$saveBounds / $lastSource" }
            check(!app.hasText("收起管理面板") && !app.hasText("搜索原始文件"))
            println("文件构件：悬停与首次触摸、结果文件操作、原始树选择、非模态 Sheet、平铺多链接及同名来源隔离通过")
        }
    }
}
