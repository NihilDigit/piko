package dev.piko.shots

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import dev.piko.ui.theme.ThemeMode
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """用法：piko-shots <命令> [选项]

  all [--only <前缀>] [-o <目录>]
      渲染一整套：网盘在四档窗口宽度与深色下、子目录、传输、我的及其三个详情页、条目详情面板。
      --only 只渲染名字以它开头的几张，如 --only rename。
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
    /** [topmost] 只在最上层（最后打开的对话框、菜单）里找，底下的页面有同名节点时用。 */
    data class Click(val text: String, val button: PointerButton = PointerButton.Primary, val topmost: Boolean = false) : Step
    data class Hover(val text: String) : Step
    data class LongPress(val text: String) : Step
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
    /** 网盘页的视图，LIST、POSTER、GALLERY；null 是默认的海报墙。 */
    val viewMode: String? = null,
    /** 只给这一张加的数据，放在 [seed] 之后。公共的 seed 不动，免得别的图跟着变。 */
    val extraSeed: FakePikPak.() -> Unit = {},
    val showPlayer: Boolean = false,
    val initialLink: String? = null,
    val highlightName: String? = null,
    /** 照桌面端标题栏并进内容的样子，在贴着右上角的那一行末尾画窗口按钮，见 AppScene 的 ShotWindowCaption。 */
    val caption: Boolean = false,
)

/** 进番剧目录，框选几集后按 F2 打开批量重命名。框从 SPs 那一行右侧的空白处拖起，起点落在空白处才是框选。 */
private fun renameSteps(from: Offset = Offset(1380f, 250f), to: Offset = Offset(600f, 700f)): List<Step> = listOf(
    Step.Click("Frieren"), Step.Key("Enter"), Step.Wait("SPs"), Step.Pump(800), Step.Drag(from, to), Step.Release, Step.Pump(400),
    Step.Key("F2"), Step.Pump(1_500),
)

/**
 * 拼一组积木：每加一块会自动打开它的编辑面板，点一下预览的列头收起再加下一块。不用 Esc：
 * 焦点不在弹出的面板里，Esc 到不了它。也不点标题「批量重命名」：网盘页工具栏的按钮同名，先找到的是它。
 */
private fun blockSteps(): List<Step> {
    val close = listOf(Step.Pump(500), Step.Click("原名"), Step.Pump(300))
    // 菜单弹出时有放大动画，动画没走完就点，落点会偏到上一项
    fun add(menu: String, item: String) = listOf(Step.Click(menu), Step.Pump(500), Step.Click(item))
    return add("添加查找块", "开头") + close +
        add("添加查找块", "括号及内容") + listOf(Step.Pump(500), Step.Click("[ ]")) + close +
        add("添加查找块", "任意文字") + close +
        // 打完字先点别处收成积木：否则点添加按钮时输入框失焦、积木条重排，按钮在点下去之前就挪了位置
        listOf(Step.Click("输入要查找的文字"), Step.Type(" - ")) + close +
        add("添加查找块", "数字") + listOf(Step.Pump(500), Step.Click("记为片段，供替换使用")) + close +
        listOf(Step.Click("输入替换成的文字"), Step.Type("第")) + close +
        add("添加替换块", "片段①") + close +
        listOf(Step.Click("输入替换成的文字"), Step.Type("集"), Step.Pump(800))
}

/**
 * 右键「文档」文件夹，点「移动到」打开目录选择器；移动的是文件夹，列表里能看到它自己置灰。
 * 先等归档条目列出来：它插进网格后各项的位置会变，早点右键的话落在空白处，弹出的是空白处的菜单。
 */
private fun pickerSteps(): List<Step> = listOf(
    Step.Wait("Blade"), Step.Pump(1_500),
    Step.Click("文档", PointerButton.Secondary), Step.Pump(800), Step.Click("移动到"), Step.Pump(3_000),
)

/** 第一行原名里「02」的位置按 1440x900 的布局量出，布局改了要跟着改。 */
/** `all` 的清单。改了布局先跑它，再挑有关的几张细看。 */
private val standardSet = listOf(
    Shot("reveal-folder-400x860", 400, 860, steps = listOf(Step.Pump(9_000)), highlightName = "文档"),
    Shot("content-share-400x860", 400, 860, steps = listOf(Step.Pump(2_000)), initialLink = "https://mypikpak.com/s/SHOT"),
    Shot("content-magnet-400x860", 400, 860, steps = listOf(Step.Pump(2_000)), initialLink = "magnet:?xt=urn:btih:" + "a".repeat(40)),
    Shot("player-portrait", 400, 860, showPlayer = true),
    Shot("player-portrait-menu", 400, 860, steps = listOf(Step.Click("更多操作")), showPlayer = true),
    Shot("player-landscape", 860, 400, showPlayer = true),
    Shot("player-boost-portrait", 400, 860,
        steps = listOf(Step.Drag(Offset(200f, 260f), Offset(200f, 260f)), Step.Pump(800)), showPlayer = true),
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
    // 归档标记：根目录里一个归档条目；「电影」里直接放着归档条目，列过一次后文件夹上也挂标记。三种视图各一张
    Shot("vault-poster-1440x900", steps = listOf(Step.Wait("Blade"), Step.Pump(1_500))),
    Shot("vault-list-1440x900", viewMode = "LIST", steps = listOf(Step.Wait("Blade"), Step.Pump(1_500))),
    Shot("vault-gallery-1440x900", viewMode = "GALLERY", steps = listOf(Step.Pump(2_000))),
    Shot("vault-movies-list-1440x900", viewMode = "LIST", steps = listOf(Step.Click("电影"), Step.Wait("Arrival"), Step.Pump(800), Step.Key("Alt+Left"), Step.Pump(1_200))),
    // 目录选择器：右键「移动到」打开，进一层子文件夹；手机宽度时全屏。
    // 进子文件夹要点对话框里的「动画」，网格里有同名的文件夹，所以只找最上层
    Shot("picker-1440x900", steps = pickerSteps()),
    Shot("picker-sub-1440x900", steps = pickerSteps() + listOf(Step.Click("动画", topmost = true), Step.Pump(1_500))),
    Shot("picker-760x800", 760, 800, steps = pickerSteps()),
    Shot("picker-dark-1440x900", mode = ThemeMode.DARK, steps = pickerSteps() + listOf(Step.Click("动画", topmost = true), Step.Pump(1_500))),
    Shot("picker-400x860", 400, 860, steps = pickerSteps()),
    Shot("picker-sub-400x860", 400, 860, steps = pickerSteps() + listOf(Step.Click("动画", topmost = true), Step.Pump(1_500))),
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
    // 批量重命名：框选一批后 F2。刚打开时与输入查找替换后各一张，宽窗口两栏，medium 单栏
    Shot("rename-1440x900", steps = renameSteps()),
    // 查找命中共同开头里的一段，再去掉替换后仍共有的开头与结尾：新名是「第02.mkv」这样
    Shot(
        "rename-typed-1440x900",
        steps = renameSteps() + listOf(
            Step.Type("Frieren - "), Step.Click("输入替换成的文字"), Step.Type("第"), Step.Pump(500),
            Step.Click("移除开头"), Step.Click("移除结尾"), Step.Pump(800),
        ),
    ),
    // 积木：开头、方括号、任意字符、「 - 」、取出的数字，替换成「第①集」；预览按积木上色
    Shot("rename-blocks-1440x900", steps = renameSteps() + blockSteps()),
    // 使用说明：底栏左下角的问号，宽窗口两栏、手机宽度一栏
    Shot("rename-guide-1440x900", steps = renameSteps() + listOf(Step.Click("使用说明"), Step.Pump(800))),
    Shot("rename-guide-400x860", 400, 860, steps = renameSteps(Offset(390f, 260f), Offset(60f, 700f)) + listOf(Step.Click("使用说明"), Step.Pump(800))),
    // 同一组积木切到正则文本
    Shot("rename-textmode-1440x900", steps = renameSteps() + blockSteps() + listOf(Step.Click("正则表达式"), Step.Pump(800))),
    Shot("rename-typed-800x860", 800, 860, steps = renameSteps(Offset(780f, 250f), Offset(300f, 700f)) + listOf(Step.Type("Frieren - "), Step.Pump(800))),
    Shot("rename-400x860", 400, 860, steps = renameSteps(Offset(390f, 260f), Offset(60f, 700f))),
    // 冲突：正则把集号都换成同一个字母，各项的新名重复
    Shot(
        "rename-conflict-1440x900",
        steps = renameSteps() + listOf(
            Step.Click("正则表达式"), Step.Pump(300), Step.Click("查找（正则表达式）"), Step.Type("[0-9]+"), Step.Key("Tab"), Step.Type("E"), Step.Pump(800),
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
    // 快捷键一览（F1），侧边栏快捷访问的右键菜单
    Shot("shortcuts-1440x900", steps = listOf(Step.Pump(800), Step.Key("F1"), Step.Pump(800))),
    Shot("sidebar-menu-1440x900", steps = listOf(Step.Pump(1_200), Step.Click("动画", PointerButton.Secondary), Step.Pump(600))),
    // 键盘：方向键走到一项，描边标出焦点
    Shot("keyboard-focus-1440x900", steps = listOf(Step.Key("Down"), Step.Key("Down"), Step.Key("Right"), Step.Key("Down"))),
    // 信息流：宽窗口的侧栏、窄窗口的全屏、弹出到独立窗口后主窗口的样子
    Shot("feed-panel-1440x900", steps = listOf(Step.Click("信息流"), Step.Pump(1_000))),
    Shot("feed-full-760x800", 760, 800, steps = listOf(Step.Click("信息流"), Step.Pump(1_000))),
    // 侧边栏里点星标文件夹：只亮它，「文件」不再亮
    Shot("sidebar-starred-1440x900", steps = listOf(Step.Pump(1_000), Step.Click("动画"), Step.Wait("SPs"))),
    Shot("feed-popped-1440x900", steps = listOf(Step.Click("信息流"), Step.Pump(1_000), Step.Click("在独立窗口播放"), Step.Pump(800))),
    Shot("transfers-1440x900", steps = listOf(Step.Click("传输"), Step.Wait("Dandadan"))),
    // 文件夹下载收成一组，展开后各文件接在下面
    // 宽窗口的「传输」按钮有传输时写的是项数：暂停的一部电影与这一组
    Shot("transfers-batch-1440x900", steps = listOf(Step.Click("2 项"), Step.Wait("Frieren S01"), Step.Click("展开"), Step.Pump(800))),
    Shot("transfers-batch-400x860", 400, 860, steps = listOf(Step.Click("传输"), Step.Wait("Frieren S01"), Step.Click("展开"), Step.Pump(800))),
    Shot("profile-1440x900", steps = listOf(Step.Click("我的"), Step.Pump(1_000))),
    Shot("profile-starred-1440x900", steps = listOf(Step.Click("我的"), Step.Pump(1_000), Step.Click("已加星标的文件与文件夹"), Step.Wait("Dune"))),
    Shot("profile-trash-1440x900", steps = listOf(Step.Click("我的"), Step.Pump(1_000), Step.Click("回收站"), Step.Wait("old-backup"))),
    Shot("profile-settings-1440x900", steps = listOf(Step.Click("我的"), Step.Pump(1_000), Step.Click("设置"), Step.Pump(1_000))),
    Shot("profile-settings-760x800", 760, 800, steps = listOf(Step.Click("我的"), Step.Pump(1_000), Step.Click("设置"), Step.Pump(1_000))),
    // 对话框与面板：各在宽窗口与手机宽度下一张
    *dialogShots("dlg-share", menuSteps("文档", "分享")),
    *dialogShots("dlg-share-custom", menuSteps("文档", "分享") + listOf(Step.Click("自定义", topmost = true), Step.Pump(600))),
    *dialogShots("dlg-rename", menuSteps("文档", "重命名")),
    // 重命名文件：打开即有焦点，只选主名
    Shot("dlg-rename-file-1440x900", steps = menuSteps("Oppenheimer", "重命名")),
    Shot("picker-newfolder-1440x900", steps = pickerSteps() + listOf(Step.Click("新建文件夹", topmost = true), Step.Pump(800))),
    // 名称里带「?」，确认时弹出修正名称。输入框打开时没有焦点，先点一下
    *dialogShots(
        "dlg-unsupported",
        menuSteps("文档", "重命名") + listOf(Step.Click("新名称", topmost = true), Step.Type("?"), Step.Click("确定", topmost = true), Step.Pump(800)),
    ),
    *dialogShots(
        "dlg-duplicates",
        listOf(Step.Pump(800), Step.Key("Ctrl+K"), Step.Pump(500), Step.Type("查找重复"), Step.Key("Enter"), Step.Pump(4_000)),
        extraSeed = { seedDuplicates() },
    ),
    // 设置里的几个对话框只出宽窗口：手机宽度下那几行在首屏之外，截图脚本不会滚动
    Shot("dlg-proxy-1440x900", steps = settingsSteps("网络代理")),
    Shot("dlg-proxy-manual-1440x900", steps = settingsSteps("网络代理") + listOf(Step.Click("手动", topmost = true), Step.Pump(600))),
    Shot("dlg-domain-1440x900", steps = settingsSteps("服务器域名")),
    Shot("dlg-download-location-1440x900", steps = settingsSteps("下载位置")),
    Shot("dlg-logout-1440x900", steps = listOf(Step.Pump(800), Step.Key("Ctrl+Comma"), Step.Pump(1_200), Step.Click("退出登录"), Step.Pump(1_000))),
    *dialogShots("dlg-shortcuts", listOf(Step.Pump(800), Step.Key("F1"), Step.Pump(800))),
    *dialogShots("dlg-actions", listOf(Step.Wait("Blade"), Step.Pump(1_000), Step.Key("Down"), Step.Key("Shift+F10"), Step.Pump(1_000))),
)

/** 同一组步骤在宽窗口（1440x900）与手机宽度（400x860）下各出一张。 */
private fun dialogShots(name: String, steps: List<Step>, extraSeed: FakePikPak.() -> Unit = {}): Array<Shot> = arrayOf(
    Shot("$name-1440x900", steps = steps, extraSeed = extraSeed),
    Shot("$name-400x860", 400, 860, steps = steps, extraSeed = extraSeed),
)

/** 查重要有结果：另一个字幕组的两集，与「动画」里的同集构成「同集不同版本」。 */
private fun FakePikPak.seedDuplicates() {
    val folder = addFolder("备份")
    for (ep in 1..2) {
        addFile("[ANi] Frieren - %02d [1080P][Baha][WEB-DL][AAC AVC][CHT].mp4".format(ep), 380L * (1 shl 20), parentId = folder.id)
    }
}

/** 右键 [target]，点菜单里的 [item]。先等归档条目列出来，理由见 [pickerSteps]。 */
private fun menuSteps(target: String, item: String): List<Step> = listOf(
    Step.Wait("Blade"), Step.Pump(1_500),
    Step.Click(target, PointerButton.Secondary), Step.Pump(800), Step.Click(item, topmost = true), Step.Pump(1_500),
)

/** 经快捷键打开设置，由左侧目录跳到「传输与网络」，点其中的 [row]。 */
private fun settingsSteps(row: String): List<Step> = listOf(
    Step.Pump(800), Step.Key("Ctrl+Comma"), Step.Pump(1_200), Step.Click("传输与网络"), Step.Pump(1_000), Step.Click(row), Step.Pump(1_000),
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
            "all" -> {
                val only = option(rest, "--only").orEmpty()
                // 一张找不到要点的节点不拖累其余几张
                standardSet.filter { it.name.startsWith(only) }.forEach { shot ->
                    try {
                        render(shot, outDir)
                    } catch (e: IllegalStateException) {
                        System.err.println("[${shot.name}] ${e.message}")
                    }
                }
            }
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
    ShotEnv(shot.viewMode, shot.extraSeed).use { env ->
        AppScene.open(env, shot.width, shot.height, shot.mode, shot.showPlayer, shot.caption).use { app ->
            shot.highlightName?.let { name ->
                val file = kotlinx.coroutines.runBlocking { env.services.driveRepository.listBrowsable("", dev.piko.shared.data.PikoFileSortOrder.TIME_DESC).getOrThrow().first { it.name == name } }
                edt { env.services.driveRepository.requestHighlight(setOf(file.id)) }
            }
            shot.initialLink?.let { link -> edt { env.services.instantSession.start(link) } }
            var lastDragEnd: Offset? = null
            for (step in shot.steps) {
                when (step) {
                    is Step.Click -> app.click(step.text, step.button, step.topmost)
                    is Step.Hover -> app.hover(step.text)
                    is Step.LongPress -> app.longPress(step.text)
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
    var caption = false
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
            "--caption" -> caption = true
            "--click" -> steps += Step.Click(value())
            "--right-click" -> steps += Step.Click(value(), PointerButton.Secondary)
            "--hover" -> steps += Step.Hover(value())
            "--long-press" -> steps += Step.LongPress(value())
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
    return Shot(name, width, height, mode, steps, caption = caption)
}

private fun option(args: List<String>, name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
