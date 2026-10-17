package dev.piko.shots

import dev.piko.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import androidx.compose.ui.geometry.Offset

internal fun verifySharedDetailsTasks() {
    ShotEnv("verify-shared-details-tasks", localDownloads = true).use { env ->
        AppScene.open(env, 1920, 1080, ThemeMode.LIGHT, caption = true).use { app ->
            app.resize(1280, 800)
            app.click("任务")
            check(app.pumpUntil { app.hasText("任务列表") && app.hasText("Project Sekai OST") })
            app.idle(700)
            val list = app.nodeBounds("任务列表")
            check(list.width > 900f) { "任务列表没有铺满内容区：$list" }
            check(!app.hasText("选择任务：Project Sekai OST Vol.3.zip")) { "默认显示了多选框" }
            val batchProgress = app.nodeBounds("任务进度：Frieren S01")
            val pausedProgress = app.nodeBounds("任务进度：Oppenheimer")
            check(kotlin.math.abs(batchProgress.left - pausedProgress.left) < 1f &&
                kotlin.math.abs(batchProgress.width - pausedProgress.width) < 1f) { "不同操作数量的任务进度条没有对齐" }
            val title = app.nodeBounds("Project Sekai OST Vol.3.zip")
            app.hover("Project Sekai OST Vol.3.zip")
            check(app.hasText("选择任务：Project Sekai OST Vol.3.zip") && !app.hasText("选择任务：Oppenheimer.2023.1080p.BluRay.x264.mkv")) { "悬停没有只显示当前行的选择框" }
            check(app.nodeBounds("Project Sekai OST Vol.3.zip").left == title.left) { "悬停导致任务名称位移" }
            app.moveTo(Offset(200f, 100f))
            check(app.pumpUntil { !app.hasText("选择任务：Project Sekai OST Vol.3.zip") }) { "移开鼠标后未隐藏选择框" }
            app.key("Ctrl+Shift+Tab")
            app.click("Project Sekai OST Vol.3.zip")
            if (!app.pumpUntil { app.hasText("关闭任务详情") }) {
                app.save(File("build/shots/shared-details-tasks/failure-row-click.png"))
                error("单击任务名称未打开详情")
            }
            app.key("Esc")
            check(app.pumpUntil { !app.hasText("关闭任务详情") })
            app.useTouchInput(true)
            app.longPress("Project Sekai OST Vol.3.zip")
            check(app.pumpUntil { app.hasText("已选 1 项") }) { "触屏长按未进入多选" }
            app.click("取消选择")
            check(app.pumpUntil { !app.hasText("选择任务：Project Sekai OST Vol.3.zip") }) { "触摸输入留下了悬停选择框" }
            app.useTouchInput(false)
            app.moveTo(Offset(200f, 100f))
            app.hover("Project Sekai OST Vol.3.zip")
            app.click("选择任务：Project Sekai OST Vol.3.zip")
            check(app.pumpUntil { app.hasText("已选 1 项") && app.hasText("选择任务：Project Sekai OST Vol.3.zip") }) { "进入多选后未显示多选框" }
            app.click("选择任务：Oppenheimer.2023.1080p.BluRay.x264.mkv")
            check(app.pumpUntil { app.hasText("已选 2 项") })
            app.click("选择任务：Oppenheimer.2023.1080p.BluRay.x264.mkv")
            check(app.pumpUntil { app.hasText("已选 1 项") })
            app.click("任务详情：Project Sekai OST Vol.3.zip")
            check(app.pumpUntil { app.hasText("关闭任务详情") })
            check(app.nodeBounds("任务列表").width == list.width) { "详情挤压了任务列表" }
            check(app.hasText("已选 1 项")) { "查看详情清除了批量勾选" }
            app.save(File("build/shots/shared-details-tasks/01-wide-details.png"))
            app.key("Esc")
            check(app.pumpUntil { !app.hasText("关闭任务详情") }) { "Esc 未关闭详情" }
            check(app.hasText("已选 1 项")) { "关闭详情清除了勾选" }
            app.click("任务详情：Project Sekai OST Vol.3.zip")
            check(app.pumpUntil { app.hasText("关闭任务详情") })
            app.resize(400, 860)
            if (!app.pumpUntil { app.hasText("关闭任务详情") && app.hasText("已选 1 项") }) {
                app.save(File("build/shots/shared-details-tasks/failure-narrow-details.png"))
                File("build/shots/shared-details-tasks/failure-narrow-details.txt").writeText(app.texts().joinToString("\n"))
                error("窗口变窄未保留详情与勾选")
            }
            val close = app.nodeBounds("关闭任务详情")
            check(close.left >= 0f && close.right <= 400f && close.top >= 0f && close.bottom <= 860f) { "任务详情在窄窗口可见范围外：$close" }
            app.save(File("build/shots/shared-details-tasks/02-narrow-details.png"))
            app.click("关闭任务详情")
            check(app.pumpUntil { !app.hasText("关闭任务详情") && app.hasText("已选 1 项") })
            app.click("取消选择")
            check(app.pumpUntil { !app.hasText("选择任务：Project Sekai OST Vol.3.zip") }) { "退出多选后仍显示多选框" }
            val mode = runBlocking { env.services.preferences.snailModeFlow.first() }
            app.click("蜗牛模式")
            val changed = runBlocking { env.services.preferences.snailModeFlow.first() }
            check(changed.enabled != mode.enabled && changed.downloadKiBps == mode.downloadKiBps && changed.uploadKiBps == mode.uploadKiBps)
            app.click("设置上限")
            check(app.hasText("蜗牛模式"))
            app.click("取消", topmost = true)
            app.save(File("build/shots/shared-details-tasks/03-narrow-list.png"))
            app.resize(1280, 800)
            app.save(File("build/shots/shared-details-tasks/04-wide-list.png"))
            app.resize(1920, 1080)
            app.save(File("build/shots/shared-details-tasks/06-large-list.png"))
            app.resize(1280, 800)
            app.click("文件")
            app.idle(700)
            val folder = app.nodeBounds("动画")
            app.key("Ctrl+I")
            check(app.pumpUntil { app.hasText("关闭属性") }) { "网盘属性未接入共用宿主" }
            check(app.nodeBounds("动画").width == folder.width) { "属性浮窗挤压了网盘列表" }
            app.save(File("build/shots/shared-details-tasks/05-file-properties.png"))
            app.resize(400, 860)
            check(app.hasText("关闭属性"))
            app.click("关闭属性")
            check(app.pumpUntil { !app.hasText("关闭属性") })
            println("共用详情：网盘/任务浮窗与 Sheet、满宽列表、选择保留、Escape、尺寸往返及蜗牛模式通过")
        }
    }
}
