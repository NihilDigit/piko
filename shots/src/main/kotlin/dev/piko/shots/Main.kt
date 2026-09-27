package dev.piko.shots

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import dev.piko.ui.theme.ThemeMode
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """用法：piko-shots <命令> [选项]

  all [-o <目录>]
      渲染一整套：网盘在四档窗口宽度与深色下、子目录、传输、我的及其三个详情页、条目详情面板。
  shot <名字> [--size <宽>x<高>] [--dark] [步骤…] [-o <目录>]
      渲染一张。步骤按写的顺序执行：
        --click <文本>        点击文本或内容描述为它的节点（先精确匹配，没有则包含匹配）
        --right-click <文本>  右键点击，看右键菜单
        --hover <文本>        鼠标停在上面，看悬停态与提示
        --drag <x,y:x,y>      按住左键从一点拖到另一点（dp），看框选；--release 在终点松手
        --key <键>            按一次键：Down、Tab、Enter、Esc、F2、Menu、Ctrl+A、Shift+F10 等
        --type <文字>         往有焦点的输入框里打字
        --wait <文本>         等到界面上出现它
        --pump <毫秒>         多等一会儿，让动画走完
  texts [--size <宽>x<高>] [步骤…]
      不存图，打印界面上所有文本与内容描述，写 --click 时找名字用。

窗口尺寸按 dp 计（密度 1），默认 1440x900。输出默认在 build/shots/<名字>.png，
经 gradle run 时相对仓库根目录。数据来自 FakePikPak.seed()，每张图都从登录后的网盘根目录开始。"""

private sealed interface Step {
    data class Click(val text: String, val button: PointerButton = PointerButton.Primary) : Step
    data class Hover(val text: String) : Step
    data class Drag(val from: Offset, val to: Offset) : Step
    data object Release : Step
    data class Key(val chord: String) : Step
    data class Type(val text: String) : Step
    data class Wait(val text: String) : Step
    data class Pump(val ms: Long) : Step
}

private class Shot(
    val name: String,
    val width: Int = 1440,
    val height: Int = 900,
    val mode: ThemeMode = ThemeMode.LIGHT,
    val steps: List<Step> = emptyList(),
)

/** `all` 的清单。改了布局先跑它，再挑有关的几张细看。 */
private val standardSet = listOf(
    Shot("files-1440x900"),
    Shot("files-1100x800", 1100, 800),
    Shot("files-760x800", 760, 800),
    Shot("files-400x860", 400, 860),
    Shot("files-1440x900-dark", mode = ThemeMode.DARK),
    Shot("files-subfolder-1440x900", steps = listOf(Step.Click("Frieren"), Step.Wait("SPs"))),
    // 浏览历史与快捷栏：进一个目录、后退、再进另一个，左侧「最近」记下两处，前进键亮着又被新的一步作废
    Shot(
        "files-history-1440x900",
        steps = listOf(Step.Click("Frieren"), Step.Wait("SPs"), Step.Key("Alt+Left"), Step.Pump(800), Step.Click("电影"), Step.Pump(1_000)),
    ),
    Shot("details-1440x900", steps = listOf(Step.Click("更多操作"), Step.Pump(800))),
    Shot("details-400x860", 400, 860, steps = listOf(Step.Click("更多操作"), Step.Pump(800))),
    Shot("context-menu-1440x900", steps = listOf(Step.Click("Oppenheimer", PointerButton.Secondary), Step.Pump(500))),
    // 鼠标：悬停出勾选框；在空白处拖出框选，松手前那一刻框还画着
    Shot("hover-check-1440x900", steps = listOf(Step.Hover("Oppenheimer"))),
    Shot(
        "marquee-1440x900",
        steps = listOf(Step.Click("动画"), Step.Wait("SPs"), Step.Pump(800), Step.Drag(Offset(1350f, 720f), Offset(800f, 400f))),
    ),
    // 拖放移动：拖到侧边栏的星标文件夹上、拖到网格里的文件夹上（松手前），以及松手后带「撤销」的提示
    Shot("drag-sidebar-1440x900", steps = listOf(Step.Pump(800), Step.Drag(Offset(820f, 292f), Offset(90f, 198f)))),
    Shot("drag-folder-1440x900", steps = listOf(Step.Pump(800), Step.Drag(Offset(820f, 292f), Offset(1200f, 150f)))),
    Shot("drag-dropped-1440x900", steps = listOf(Step.Pump(800), Step.Drag(Offset(820f, 292f), Offset(90f, 198f)), Step.Release, Step.Pump(1_500))),
    // 详情栏：什么也没指着时是当前目录，方向键指着一项时是它，框选一批时是这一批
    Shot("inspector-folder-1440x900", steps = listOf(Step.Click("详情"), Step.Pump(1_000))),
    Shot("inspector-item-1440x900", steps = listOf(Step.Click("详情"), Step.Pump(800), Step.Key("Down"), Step.Key("Down"), Step.Key("Right"), Step.Key("Down"))),
    Shot(
        "inspector-selection-1440x900",
        steps = listOf(
            Step.Click("动画"), Step.Wait("SPs"), Step.Pump(600), Step.Click("详情"), Step.Pump(800),
            Step.Drag(Offset(1050f, 780f), Offset(600f, 400f)), Step.Release, Step.Pump(600),
        ),
    ),
    // 命令面板：没输入时最近的文件夹与去处在前；输入后模糊匹配文件夹与命令
    Shot("palette-1440x900", steps = listOf(Step.Click("Frieren"), Step.Wait("SPs"), Step.Key("Alt+Left"), Step.Pump(800), Step.Key("Ctrl+K"), Step.Pump(800))),
    Shot("palette-query-1440x900", steps = listOf(Step.Key("Ctrl+K"), Step.Pump(600), Step.Type("视图"), Step.Pump(600))),
    // 大窗口底部的状态栏，以及从命令面板打开的活动面板
    Shot("activity-panel-1440x900", steps = listOf(Step.Pump(800), Step.Key("Ctrl+K"), Step.Pump(500), Step.Type("活动"), Step.Key("Enter"), Step.Pump(1_000))),
    // 标签：Ctrl+T 新建后进一个目录，右键另一个文件夹在新标签页打开
    Shot(
        "tabs-1440x900",
        steps = listOf(
            Step.Pump(800), Step.Key("Ctrl+T"), Step.Pump(600), Step.Click("Frieren"), Step.Wait("SPs"), Step.Key("Alt+Left"), Step.Pump(800),
            Step.Click("电影", PointerButton.Secondary), Step.Pump(400), Step.Click("在新标签页打开"), Step.Pump(800),
        ),
    ),
    // 键盘：方向键走到一项，描边标出焦点
    Shot("keyboard-focus-1440x900", steps = listOf(Step.Key("Down"), Step.Key("Down"), Step.Key("Right"), Step.Key("Down"))),
    // 信息流：宽窗口的侧栏、窄窗口的全屏、弹出到独立窗口后主窗口的样子
    Shot("feed-panel-1440x900", steps = listOf(Step.Click("信息流"), Step.Pump(1_000))),
    Shot("feed-full-760x800", 760, 800, steps = listOf(Step.Click("信息流"), Step.Pump(1_000))),
    // 侧边栏里点星标文件夹：只亮它，「文件」不再亮
    Shot("sidebar-starred-1440x900", steps = listOf(Step.Pump(1_000), Step.Click("动画"), Step.Wait("SPs"))),
    // 在文件夹上订阅信息流；此后网盘里进出不换掉它
    Shot("feed-subscribe-1440x900", steps = listOf(Step.Click("电影", PointerButton.Secondary), Step.Pump(500), Step.Click("在信息流中刷"), Step.Pump(1_000))),
    Shot("feed-popped-1440x900", steps = listOf(Step.Click("信息流"), Step.Pump(1_000), Step.Click("在独立窗口播放"), Step.Pump(800))),
    Shot("transfers-1440x900", steps = listOf(Step.Click("传输"), Step.Wait("Dandadan"))),
    Shot("profile-1440x900", steps = listOf(Step.Click("我的"), Step.Pump(1_000))),
    Shot("profile-starred-1440x900", steps = listOf(Step.Click("我的"), Step.Pump(1_000), Step.Click("已加星标的文件与文件夹"), Step.Wait("Dune"))),
    Shot("profile-trash-1440x900", steps = listOf(Step.Click("我的"), Step.Pump(1_000), Step.Click("回收站"), Step.Wait("old-backup"))),
    Shot("profile-settings-1440x900", steps = listOf(Step.Click("我的"), Step.Pump(1_000), Step.Click("设置"), Step.Pump(1_000))),
    Shot("profile-settings-760x800", 760, 800, steps = listOf(Step.Click("我的"), Step.Pump(1_000), Step.Click("设置"), Step.Pump(1_000))),
)

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
        println(USAGE)
        return
    }
    val rest = args.drop(1)
    val outDir = File(option(rest, "-o") ?: "build/shots")
    try {
        when (args[0]) {
            "all" -> standardSet.forEach { render(it, outDir) }
            "shot" -> {
                val name = rest.firstOrNull()?.takeUnless { it.startsWith("-") } ?: fail("shot 要一个名字")
                render(parseShot(name, rest.drop(1)), outDir)
            }
            "texts" -> printTexts(parseShot("texts", rest))
            else -> fail("未知命令：${args[0]}")
        }
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(2)
    }
    // Compose 与 OkHttp 留下的非守护线程会让进程挂着不退
    exitProcess(0)
}

private fun render(shot: Shot, outDir: File) {
    val file = File(outDir, "${shot.name}.png")
    run(shot) { app -> app.save(file) }
    println(file.absolutePath)
}

private fun printTexts(shot: Shot) = run(shot) { app -> app.texts().forEach(::println) }

private fun run(shot: Shot, finish: (AppScene) -> Unit) {
    ShotEnv().use { env ->
        AppScene.open(env, shot.width, shot.height, shot.mode).use { app ->
            var lastDragEnd: Offset? = null
            for (step in shot.steps) {
                when (step) {
                    is Step.Click -> app.click(step.text, step.button)
                    is Step.Hover -> app.hover(step.text)
                    is Step.Drag -> {
                        app.drag(step.from, step.to)
                        lastDragEnd = step.to
                    }
                    is Step.Release -> app.release(lastDragEnd ?: fail("--release 前面要有 --drag"))
                    is Step.Key -> app.key(step.chord)
                    is Step.Type -> app.type(step.text)
                    is Step.Wait -> if (!app.pumpUntil { app.hasText(step.text) }) {
                        System.err.println("[${shot.name}] 等不到「${step.text}」，照当前画面出图")
                    }
                    is Step.Pump -> app.pump(step.ms)
                }
            }
            app.pump(800)
            finish(app)
        }
    }
}

private fun parseShot(name: String, args: List<String>): Shot {
    var width = 1440
    var height = 900
    var mode = ThemeMode.LIGHT
    val steps = mutableListOf<Step>()
    var i = 0
    fun value(): String = args.getOrNull(++i) ?: fail("${args[i - 1]} 缺少参数")
    while (i < args.size) {
        when (val arg = args[i]) {
            "--size" -> {
                val (w, h) = value().split('x').map { it.toIntOrNull() ?: fail("尺寸写成 1440x900") }
                width = w
                height = h
            }
            "--dark" -> mode = ThemeMode.DARK
            "--click" -> steps += Step.Click(value())
            "--right-click" -> steps += Step.Click(value(), PointerButton.Secondary)
            "--hover" -> steps += Step.Hover(value())
            "--drag" -> {
                val (from, to) = value().split(':').map { point ->
                    val (x, y) = point.split(',').map { it.toFloatOrNull() ?: fail("--drag 写成 1300,700:900,300") }
                    Offset(x, y)
                }
                steps += Step.Drag(from, to)
            }
            "--release" -> steps += Step.Release
            "--key" -> steps += Step.Key(value())
            "--type" -> steps += Step.Type(value())
            "--wait" -> steps += Step.Wait(value())
            "--pump" -> steps += Step.Pump(value().toLongOrNull() ?: fail("--pump 要毫秒数"))
            "-o" -> value()
            else -> fail("未知选项：$arg")
        }
        i++
    }
    return Shot(name, width, height, mode, steps)
}

private fun option(args: List<String>, name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
