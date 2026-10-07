package dev.piko.shots

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import dev.piko.download.DownloadStatus
import dev.piko.shared.data.OfflinePackJob
import dev.piko.shared.data.OfflinePackStage
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.upload.UploadSelection
import dev.piko.shared.upload.UploadStatus
import dev.piko.shared.upload.UploadTask
import dev.piko.ui.theme.ThemeMode
import dev.piko.update.UpdateStatus
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val USAGE = """用法：piko-shots <命令> [选项]

  all [--only <前缀>] [--jobs <n>] [--keep-baseline] [-o <目录>]
      渲染一整套，按 docs/development/ui-states.md 的状态表逐项出图，每个状态桌面、手机、平板各一张。
      渲染前把输出目录里的上一版挪到 <目录>-baseline，渲染完逐像素比对，清单写到 <目录>/changes.md，
      变了的图在 <目录>/diff/ 下各有一张旧、新、差异并排的对照图。
      --only 只渲染名字以它开头的几张，如 --only drive-trash；基线与清单也只管这几张。
      --jobs 分给几个子进程并行渲染，默认按核数取；1 在本进程里逐张渲染。
      --keep-baseline 不挪基线，继续和已有的基线比：改一处界面反复重跑时，始终对照改之前的那一版。
      --shard <i>/<n> 只渲染第 i 片（共 n 片），不动基线、不写清单。--jobs 拉起的子进程用它。
  shot <名字> [--size <宽>x<高>] [--dark] [步骤…] [-o <目录>]
      渲染一张。步骤按写的顺序执行：
        --click <文本>        点击文本或内容描述为它的节点（先精确匹配，没有则包含匹配）
        --right-click <文本>  右键点击，看右键菜单
        --long-press <文本>   按住 800ms，触屏进多选
        --hover <文本>        鼠标停在上面，看悬停态与提示
        --drag <x,y:x,y>      按住左键从一点拖到另一点（dp），看框选；--release 在终点松手
        --key <键>            按一次键：Down、Tab、Enter、Esc、F2、Menu、Ctrl+A、Shift+F10 等
        --type <文字>         往有焦点的输入框里打字
        --wait <文本>         等到界面上出现它
        --pump <毫秒>         多等一会儿：等到画面稳定，至多这么久
  texts [--size <宽>x<高>] [步骤…]
      不存图，打印界面上所有文本与内容描述，写 --click 时找名字用。

窗口尺寸按 dp 计（密度 1），默认 1440x900。窄于 840 按移动端拍，--mobile 与 --desktop 显式指定
（平板横屏是 --size 1280x800 --mobile）。移动端的左键按成触屏。输出默认在 build/shots/<名字>.png，
经 gradle run 时相对仓库根目录。数据来自 FakePikPak.seed()，每张图都从登录后的网盘根目录开始。
动画一律当场跳到终点，存图前等画面稳定（连续 500ms 逐像素不变，至多 3 秒）。"""

private sealed interface Step {
    /** [topmost] 只在最上层（最后打开的对话框、菜单）里找，底下的页面有同名节点时用。 */
    data class Click(val text: String, val button: PointerButton = PointerButton.Primary, val topmost: Boolean = false) : Step
    /** 按坐标点，没有文本可找的地方（信息流的画面）用；[double] 连点两下。 */
    data class ClickAt(val at: Offset, val double: Boolean = false) : Step
    /** 点同名节点里最靠上的那个，页顶的类别标签用。 */
    data class ClickHighest(val text: String) : Step
    data class Hover(val text: String) : Step
    /**
     * 鼠标移到某处、不按键。点完按钮后指针还停在原处，界面在它底下变了（侧边栏收起）时会冒出提示框；
     * 提示框晚几百毫秒才出，出不出在图上取决于判稳赶在它之前还是之后，移开才每次一样。
     */
    data class MoveTo(val at: Offset) : Step
    data class LongPress(val text: String) : Step
    /** 按坐标长按；[release] 为 false 时不松手，拍按住期间的样子。 */
    data class LongPressAt(val at: Offset, val release: Boolean = true) : Step
    data class Drag(val from: Offset, val to: Offset) : Step
    /** 手指划过，下拉刷新用。 */
    data class Swipe(val from: Offset, val to: Offset) : Step
    data object Release : Step
    data class Key(val chord: String) : Step
    data class Type(val text: String) : Step
    data class Wait(val text: String) : Step
    /** 等画面稳定，至多 [ms]。 */
    data class Pump(val ms: Long) : Step
    /** 在步骤之间改假服务端或进程级状态，例如让下一次列目录失败。在 EDT 外执行，可以等网络。 */
    class Run(val action: ShotEnv.() -> Unit) : Step
}

/** 假的新版本：[onStartup] 时开屏即弹，状态由 [status] 给出。 */
private class UpdateSpec(val onStartup: Boolean = true, val canInstall: Boolean = true, val status: (ShotUpdate) -> UpdateStatus = { UpdateStatus.Available(it) })

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
    /** 非空时只渲染播放器控件，见 PlayerPreview。 */
    val player: PlayerShot? = null,
    val initialLink: String? = null,
    val highlightName: String? = null,
    /** 照桌面端标题栏并进内容的样子，在贴着右上角的那一行末尾画窗口按钮，见 AppScene 的 ShotWindowCaption。 */
    val caption: Boolean = false,
    /** 按移动端的交互模型拍，见 MobileShotPlatform。窄于 840dp 的默认是手机；要看窄的桌面窗口显式传 false。 */
    val mobile: Boolean = width < 840,
    val login: LoginSeed = LoginSeed.SIGNED_IN,
    val localDownloads: Boolean = true,
    /**
     * 只给这一张写的设置项，开始渲染这一张时才取：里面的时刻（上传任务的更新时间）要与本机下载任务的时刻同时算，
     * 在建清单时就算好的话，两者先后随这一张排在进程里第几张而变，传输页的排序跟着变。
     */
    val prefs: () -> Map<String, String> = { emptyMap() },
    val update: UpdateSpec? = null,
    /** 开场等到它出现再走步骤；null 不等。 */
    val waitFor: String? = "Kusuriya",
    /**
     * 动画按真实速度播，步骤里的等待按写的时长推满。只给要拍动画中途的几张：默认动画当场跳到终点、
     * 等待改为等画面稳定，见 AppScene.settle。
     */
    val motion: Boolean = false,
    /** 走完步骤后、存图前等画面稳定的上限（[motion] 时是推进的时长）；为 0 时立刻存图，拍转瞬即逝的提示用。 */
    val settleMs: Long = if (motion) 800 else SETTLE_MAX_MS,
)

/** 收尾等稳定的上限。画面一直在变（无限动画没停住）时照这时的画面存图，并报出名字。 */
private const val SETTLE_MAX_MS = 3_000L

/** 状态表要求的三种形态：桌面、手机、平板横屏。名字后缀按它写。 */
private enum class Form(val tag: String, val width: Int, val height: Int, val mobile: Boolean) {
    DESKTOP("desktop", 1440, 900, false),
    PHONE("phone", 400, 860, true),
    TABLET("tablet", 1280, 800, true),
    ;

    val desktop get() = !mobile
    val suffix get() = "$tag-${width}x$height"
}

/**
 * 一个状态在三种形态下各一张，名字是 `<name>-<形态>-<宽>x<高>`。[steps] 对某个形态返回 null 表示该形态里
 * 没有这个状态（标签只在桌面，「我的」只在移动端），不出那一张。
 */
private fun tri(
    name: String,
    forms: List<Form> = Form.entries,
    viewMode: String? = null,
    extraSeed: FakePikPak.() -> Unit = {},
    initialLink: String? = null,
    highlightName: String? = null,
    login: LoginSeed = LoginSeed.SIGNED_IN,
    localDownloads: Boolean = true,
    prefs: () -> Map<String, String> = { emptyMap() },
    update: UpdateSpec? = null,
    waitFor: String? = "Kusuriya",
    player: PlayerShot? = null,
    motion: Boolean = false,
    settleMs: Long = if (motion) 800 else SETTLE_MAX_MS,
    steps: (Form) -> List<Step>? = { emptyList() },
): List<Shot> = forms.mapNotNull { form ->
    val s = steps(form) ?: return@mapNotNull null
    Shot(
        "$name-${form.suffix}", form.width, form.height, steps = s, viewMode = viewMode, extraSeed = extraSeed,
        player = player, initialLink = initialLink, highlightName = highlightName, mobile = form.mobile, login = login,
        localDownloads = localDownloads, prefs = prefs, update = update, waitFor = waitFor, motion = motion, settleMs = settleMs,
    )
}

private val DESKTOP_ONLY = listOf(Form.DESKTOP)
private val MOBILE_ONLY = listOf(Form.PHONE, Form.TABLET)

// ---- 各形态通用的几步 ----

/** 打开条目：桌面单击只选中，回车打开；移动端点一下即打开。 */
private fun open(form: Form, name: String): List<Step> =
    if (form.desktop) listOf(Step.Click(name), Step.Key("Enter")) else listOf(Step.Click(name))

/** 进「动画」：网格里显示的是解析出的作品名 Frieren。 */
private fun intoAnime(form: Form): List<Step> = open(form, "Frieren") + listOf(Step.Wait("SPs"), Step.Pump(800))

/** 去传输页。桌面的「传输」按钮有传输时写速度或项数，文本不定，用快捷键。 */
private fun toTransfers(form: Form): List<Step> =
    listOf(if (form.desktop) Step.Key("Ctrl+2") else Step.Click("传输"), Step.Pump(1_500))

/** 去库里的一页。桌面在侧边栏，移动端从「我的」进。 */
private fun toLibrary(form: Form, title: String, mobileRow: String = title): List<Step> =
    if (form.desktop) listOf(Step.Pump(600), Step.Click(title), Step.Pump(1_500))
    else listOf(Step.Click("我的"), Step.Pump(1_000), Step.Click(mobileRow), Step.Pump(1_500))

/** 打开设置：桌面主修饰键+逗号，移动端「我的」里的「设置」。 */
private fun toSettings(form: Form): List<Step> =
    if (form.desktop) listOf(Step.Pump(800), Step.Key("Ctrl+Comma"), Step.Pump(1_200))
    else listOf(Step.Click("我的"), Step.Pump(1_000), Step.Click("外观、网盘、传输与同步"), Step.Pump(1_200))

/** 设置里点 [row]，首屏之外的行由 AppScene 滚过去。不点桌面左栏的分区：「网盘」与侧边栏的同名项撞名，先找到的是侧边栏。 */
private fun settingsRow(form: Form, row: String): List<Step> =
    toSettings(form) + listOf(Step.Click(row), Step.Pump(2_000))

/** 条目的操作：桌面右键 [target] 出菜单；移动端先搜索把列表缩成它，再点它的更多按钮。 */
private fun actionsOf(form: Form, target: String, search: String = target): List<Step> =
    if (form.desktop) listOf(Step.Wait("Blade"), Step.Pump(1_500), Step.Click(target, PointerButton.Secondary), Step.Pump(800))
    else searchFor(form, search) + listOf(Step.Click("更多操作"), Step.Pump(800))

/** 条目操作里的 [item]。 */
private fun action(form: Form, target: String, item: String, search: String = target): List<Step> =
    actionsOf(form, target, search) + listOf(Step.Click(item, topmost = true), Step.Pump(1_500))

/** 「下载到本地」在面板的图标行里只写「下载」。 */
private fun downloadLabel(form: Form) = if (form.desktop) "下载到本地" else "下载"

/** 下载文件夹或视频先弹画质对话框，点它的「下载」照默认画质下。 */
private fun download(form: Form, target: String, search: String = target): List<Step> =
    action(form, target, downloadLabel(form), search) + listOf(Step.Click("下载", topmost = true), Step.Pump(1_500))

/** 在当前文件夹里搜 [text]：桌面 Ctrl+F 展开搜索框，移动端点顶栏的搜索图标。 */
private fun searchFor(form: Form, text: String): List<Step> =
    (if (form.desktop) listOf(Step.Pump(600), Step.Key("Ctrl+F")) else listOf(Step.Click("搜索"))) +
        listOf(Step.Pump(500), Step.Type(text), Step.Pump(1_200))

/** 压缩包在海报墙的首屏之外，先搜 [search] 把列表缩小再打开。 */
private fun openArchive(form: Form, name: String, search: String): List<Step> =
    searchFor(form, search) + open(form, name) + listOf(Step.Pump(1_500))

/** 多选：桌面全选，移动端长按 [target]。 */
private fun selectSome(form: Form, target: String): List<Step> =
    if (form.desktop) listOf(Step.Pump(800), Step.Click(target), Step.Key("Ctrl+A"), Step.Pump(600))
    else listOf(Step.LongPress(target), Step.Pump(600))

/** 点拖动条把手机上的任务 sheet 收到部分展开，露出 FAB。平板的 sheet 窄、不挡 FAB，不必收。 */
private fun collapseSheet(form: Form): List<Step> =
    if (form == Form.PHONE) listOf(Step.Click("Drag handle"), Step.Pump(800)) else emptyList()

/** 移动端 FAB 菜单里的 [item]；桌面没有 FAB。 */
private fun fab(item: String): List<Step> = listOf(Step.Click("添加"), Step.Pump(600), Step.Click(item, topmost = true), Step.Pump(1_200))

/** 在 EDT 外按名字取根目录里的条目，交给进程级会话。 */
private fun ShotEnv.rootFile(name: String) = runBlocking {
    services.driveRepository.listBrowsable("", PikoFileSortOrder.TIME_DESC).getOrThrow().first { it.name == name }
}

/** 进番剧目录，框选几集后按 F2 打开批量重命名。框从 SPs 那一行右侧的空白处拖起，起点落在空白处才是框选。 */
private fun renameSteps(from: Offset = Offset(1380f, 250f), to: Offset = Offset(600f, 700f)): List<Step> =
    intoAnime(Form.DESKTOP) + listOf(Step.Drag(from, to), Step.Release, Step.Pump(400), Step.Key("F2"), Step.Pump(1_500))

/**
 * 移动端：长按一集进多选，再点三集，按 F2 打开批量重命名。手机上「批量重命名」收在多选条的溢出菜单里，
 * 与列表页眉的溢出按钮同名，按文本点不到，改用接键盘时的快捷键。
 */
private fun mobileRenameSteps(form: Form): List<Step> =
    intoAnime(form) + listOf(Step.LongPress("01"), Step.Pump(500), Step.Click("02"), Step.Click("03"), Step.Click("04"), Step.Pump(500),
        Step.Key("F2"), Step.Pump(1_500))

private fun renameOpen(form: Form): List<Step> = if (form.desktop) renameSteps() else mobileRenameSteps(form)

/**
 * 拼一组积木：每加一块会自动打开它的编辑面板，点一下预览的列头收起再加下一块。不用 Esc：
 * 焦点不在弹出的面板里，Esc 到不了它。也不点标题「批量重命名」：网盘页工具栏的按钮同名，先找到的是它。
 */
private fun blockSteps(): List<Step> {
    val close = listOf(Step.Pump(500), Step.Click("应用于"), Step.Pump(300))
    // 菜单弹出时有放大动画，动画没走完就点，落点会偏到上一项
    fun add(menu: String, item: String) = listOf(Step.Click(menu), Step.Pump(500), Step.Click(item))
    return add("添加查找块", "开头") + close +
        add("添加查找块", "括号及内容") + listOf(Step.Pump(500), Step.Click("[ ]")) + close +
        add("添加查找块", "任意文字") + close +
        // 打完字先点别处收成积木：否则点添加按钮时输入框失焦、积木条重排，按钮在点下去之前就挪了位置
        listOf(Step.Click("输入要查找的文字"), Step.Type(" - ")) + close +
        add("添加查找块", "数字") + listOf(Step.Pump(500), Step.Click("记为片段")) + close +
        listOf(Step.Click("输入替换成的文字"), Step.Type("第")) + close +
        add("添加替换块", "片段①") + close +
        listOf(Step.Click("输入替换成的文字"), Step.Type("集"), Step.Pump(800))
}

/** 「文档」的操作里点「移动到」打开目录选择器；移动的是文件夹，列表里能看到它自己置灰。 */
private fun pickerSteps(form: Form): List<Step> = action(form, "文档", "移动到") + listOf(Step.Pump(1_500))

/** 查重要有结果：另一个字幕组的两集，与「动画」里的同集构成「同集不同版本」。 */
private fun FakePikPak.seedDuplicates() {
    val folder = addFolder("备份")
    for (ep in 1..2) {
        addFile("[ANi] Frieren - %02d [1080P][Baha][WEB-DL][AAC AVC][CHT].mp4".format(ep), 380L * (1 shl 20), parentId = folder.id)
    }
}

/** 开查重：桌面经命令面板，移动端 FAB。 */
private fun startDuplicates(form: Form): List<Step> =
    if (form.desktop) listOf(Step.Pump(800), Step.Key("Ctrl+K"), Step.Pump(500), Step.Type("查找重复"), Step.Key("Enter"), Step.Pump(4_000))
    else fab("查找重复") + listOf(Step.Pump(3_000))

/** 传输页的丰富数据：云端各阶段、文件已删除、整包离线；上传的暂停、失败与完成另由 [transferPrefs] 写进设置。 */
private fun FakePikPak.seedTransfers() {
    addTask("[ANi] Frieren S2 - 03.mp4", "PHASE_TYPE_ERROR", 0, 1_200L shl 20, created = hoursAgo(4),
        message = "资源已失效，无法离线", url = "magnet:?xt=urn:btih:" + "b".repeat(40))
    addTask("Sintel.2010.4K.mkv", "PHASE_TYPE_ERROR", 100, 3L shl 30, created = hoursAgo(30),
        message = "File deleted", url = "magnet:?xt=urn:btih:" + "c".repeat(40))
    addTask("Arrival.2016.1080p.BluRay.x264.mkv", "PHASE_TYPE_COMPLETE", 100, 9L shl 30, created = hoursAgo(6),
        url = "magnet:?xt=urn:btih:" + "d".repeat(40), fileId = node("Oppenheimer.2023.1080p.BluRay.x264.mkv").id)
    addTask("Ghibli Collection", "PHASE_TYPE_RUNNING", 63, 48L shl 30, created = hoursAgo(1), url = "magnet:?xt=urn:btih:" + "e".repeat(40))
}

private fun transferPrefs(): Map<String, String> {
    val account = "demo@piko.dev"
    val now = System.currentTimeMillis()
    val uploads = listOf(
        UploadTask("u1", account, "/tmp/piko-shots/vlog-2026-10.mp4", "vlog-2026-10.mp4", 2L shl 30, now, "", "网盘",
            status = UploadStatus.PAUSED, createdAtMs = now - 60_000),
        UploadTask("u2", account, "/tmp/piko-shots/scan.pdf", "合同扫描件.pdf", 8L shl 20, now, "", "网盘",
            status = UploadStatus.FAILED, errorMessage = "网络中断", createdAtMs = now - 120_000),
        UploadTask("u3", account, "/tmp/piko-shots/IMG_0001.HEIC", "IMG_0001.HEIC", 3L shl 20, now, "", "网盘",
            status = UploadStatus.COMPLETED, fileId = "F1", isInstant = true, createdAtMs = now - 3_600_000),
    )
    val packs = listOf(
        OfflinePackJob(account, "pack-done", "magnet:?xt=urn:btih:" + "f".repeat(40), "", "Frieren BD 合集", setOf("1", "2"),
            totalFiles = 30, totalBytes = 64L shl 30, createdAtMs = now - 7_200_000, keptBytes = 41L shl 30,
            stage = OfflinePackStage.DONE, outputId = "F1", finishedAtMs = now - 3_600_000, message = "已清理 12 个不需要的文件"),
        OfflinePackJob(account, "pack-failed", "magnet:?xt=urn:btih:" + "9".repeat(40), "", "Mushishi Complete", setOf("1"),
            totalFiles = 26, totalBytes = 20L shl 30, createdAtMs = now - 9_000_000, stage = OfflinePackStage.FAILED,
            finishedAtMs = now - 8_000_000, message = "离线超时"),
    )
    return mapOf(
        "upload.tasks" to Json.encodeToString(ListSerializer(UploadTask.serializer()), uploads),
        "download.offlinePacks" to Json.encodeToString(ListSerializer(OfflinePackJob.serializer()), packs),
    )
}

/** 桌面的添加链接停在右栏，标题行的按钮把它收成浮动卡片。 */
private val collapseAddLink = Step.Click("收起添加链接")

/** 秒传记录在缓存里，账号登录后才能写，放在步骤开头。 */
private val instantRecord = Step.Run {
    services.instantSaveRecords.add("[SweetSub] Frieren S01 合集", 13, 6L shl 30, "动画", server.node("动画").id)
}

// 信息流画面的中心与左侧，按坐标点：画面上没有文本
// 桌面 1440 宽时信息流是右侧一栏，约占 1000 到 1420
private fun feedCenter(form: Form) = if (form.desktop) Offset(1205f, 450f) else Offset(form.width * 0.4f, form.height * 0.45f)

/** 打开信息流。移动端的入口在顶栏的「更多」或图标上，名字同为「信息流」。 */
private fun openFeed(): List<Step> = listOf(Step.Pump(600), Step.Click("信息流"), Step.Pump(3_000))

private val feedSeed: FakePikPak.() -> Unit = { giveVideosDuration() }

/** 播放器的一种画面在三种形态加手机横屏下各一张。 */
private fun playerShots(name: String, player: PlayerShot = PlayerShot(), settleMs: Long = SETTLE_MAX_MS, steps: (Form) -> List<Step> = { emptyList() }): List<Shot> =
    tri(name, player = player, waitFor = null, settleMs = settleMs, steps = steps) +
        Shot("$name-phone-landscape-860x400", 860, 400, steps = steps(Form.PHONE), player = player, mobile = true, waitFor = null, settleMs = settleMs)

/** 先暂停让控件常驻，再点 [button]。 */
private fun playerPanel(vararg buttons: String): List<Step> =
    buttons.flatMap { listOf(Step.Click(it, topmost = true), Step.Pump(900)) }

/** `all` 的清单，按状态表的节排列。改了布局先跑它，再挑有关的几张细看。 */
private val standardSet: List<Shot> = buildList {
    // ===== 根状态（归在 shell-） =====
    addAll(tri("shell-init", login = LoginSeed.STUCK, waitFor = null) { listOf(Step.Pump(1_500)) })
    addAll(tri("shell-login", login = LoginSeed.NONE, waitFor = "使用 PikPak 账号") { listOf(Step.Pump(600)) })
    addAll(tri("shell-login-proxy", login = LoginSeed.NONE, waitFor = "使用 PikPak 账号") { listOf(Step.Click("网络代理"), Step.Pump(800)) })
    addAll(tri("shell-login-expired", login = LoginSeed.EXPIRED, waitFor = "登录已失效") { listOf(Step.Pump(600)) })
    addAll(tri("shell-login-add") { form ->
        (if (form.desktop) toSettings(form) else listOf(Step.Click("我的"), Step.Pump(1_000))) + listOf(Step.Click("添加账号"), Step.Pump(1_200))
    })
    fun typeCredentials() = listOf(
        Step.Click("邮箱或用户名"), Step.Type("demo@piko.dev"), Step.Click("密码"), Step.Type("hunter2"), Step.Pump(300),
    )
    addAll(tri("shell-login-busy", login = LoginSeed.NONE, waitFor = "使用 PikPak 账号") {
        listOf(Step.Run { server.signinDelayMs = 20_000 }) + typeCredentials() + listOf(Step.Key("Enter"), Step.Pump(800))
    })
    addAll(tri("shell-login-error", login = LoginSeed.NONE, waitFor = "使用 PikPak 账号") {
        listOf(Step.Run { server.signinRejects = true }) + typeCredentials() + listOf(Step.Key("Enter"), Step.Pump(2_500))
    })

    // ===== 外壳 =====
    // 导航：桌面展开的侧边栏、收成窄轨、窄窗口里浮出；移动端手机底栏、平板 Rail，「传输」挂项数徽标
    addAll(tri("shell-nav"))
    addAll(tri("shell-nav-dark", forms = listOf(Form.DESKTOP, Form.PHONE)).map { Shot(it.name, it.width, it.height, ThemeMode.DARK, mobile = it.mobile) })
    // 收起后指针正落在「网盘」那一项上，移到标签栏的空白处，免得它的提示框时有时无
    add(Shot("shell-nav-collapsed-desktop-1440x900", steps = listOf(Step.Pump(600), Step.Click("收起侧边栏"), Step.MoveTo(Offset(1000f, 22f)), Step.Pump(800))))
    add(Shot("shell-nav-narrow-desktop-700x800", 700, 800, mobile = false, caption = true))
    add(Shot("shell-nav-overlay-desktop-700x800", 700, 800, mobile = false, caption = true,
        steps = listOf(Step.Pump(600), Step.Click("展开侧边栏"), Step.Pump(800))))
    // 侧边栏内容：快速访问里固定一个文件夹，右键它；蜗牛模式；账号行的新版本红点
    add(Shot("shell-quickaccess-desktop-1440x900", steps = action(Form.DESKTOP, "Frieren", "固定到快速访问") +
        listOf(Step.Pump(800), Step.Click("动画", PointerButton.Secondary), Step.Pump(600))))
    add(Shot("shell-snail-desktop-1440x900", prefs = { mapOf("transfer.snail.enabled" to "true") }))
    add(Shot("shell-update-dot-desktop-1440x900", update = UpdateSpec(onStartup = false)))
    // 浮动卡片（仅桌面）：解压、收起的添加链接、整摞收成胶囊
    add(Shot("shell-cards-extract-desktop-1440x900", steps = listOf(Step.Run { services.archiveExtractSession.extract(listOf(rootFile("Project Sekai OST Vol.3.zip"))) }, Step.Pump(2_500))))
    add(Shot("shell-cards-addlink-desktop-1440x900", initialLink = "magnet:?xt=urn:btih:" + "a".repeat(40),
        steps = listOf(Step.Pump(2_000), collapseAddLink, Step.Pump(1_000))))
    add(Shot("shell-cards-stack-desktop-1440x900", initialLink = "magnet:?xt=urn:btih:" + "a".repeat(40),
        steps = listOf(Step.Pump(2_000), collapseAddLink, Step.Pump(600),
            Step.Run { services.archiveExtractSession.extract(listOf(rootFile("Project Sekai OST Vol.3.zip"))) }, Step.Pump(2_500))))
    add(Shot("shell-cards-capsule-desktop-1440x900", initialLink = "magnet:?xt=urn:btih:" + "a".repeat(40),
        steps = listOf(Step.Pump(2_000), collapseAddLink, Step.Pump(600),
            Step.Run { services.archiveExtractSession.extract(listOf(rootFile("Project Sekai OST Vol.3.zip"))) }, Step.Pump(2_000),
            Step.Click("收到角落"), Step.Pump(1_000))))
    // 命令面板与快捷键一览：移动端只在接键盘时可开，这里照样按键
    addAll(tri("shell-palette") { intoAnime(it) + listOf(Step.Key("Alt+Left"), Step.Pump(800), Step.Key("Ctrl+K"), Step.Pump(800)) })
    addAll(tri("shell-palette-query") { listOf(Step.Pump(600), Step.Key("Ctrl+K"), Step.Pump(600), Step.Type("视图"), Step.Pump(600)) })
    addAll(tri("shell-shortcuts") { listOf(Step.Pump(800), Step.Key("F1"), Step.Pump(800)) })

    // ===== 网盘页：位置 =====
    addAll(tri("drive-root"))
    add(Shot("drive-root-desktop-1100x800", 1100, 800))
    add(Shot("drive-root-desktop-700x800", 700, 800, mobile = false, caption = true))
    addAll(tri("drive-subfolder") { intoAnime(it) })
    addAll(tri("drive-skeleton") { listOf(Step.Run { server.listDelayMs = 20_000 }) + open(it, "文档") + listOf(Step.Pump(800)) })
    // 整页失败：根目录头一次就列不出来。进子文件夹时失败到不了这一页：列表留着上一个目录的内容，只出横幅
    addAll(tri("drive-error", extraSeed = { listFails = true }, waitFor = "重试") { listOf(Step.Pump(800)) })
    addAll(tri("drive-stale") { form ->
        intoAnime(form) + listOf(Step.Run { server.listFails = true }) +
            // 移动端没有刷新按钮，只有下拉刷新
            (if (form.desktop) listOf(Step.Key("F5")) else listOf(Step.Swipe(Offset(form.width / 2f, 200f), Offset(form.width / 2f, 600f)))) +
            listOf(Step.Pump(1_500))
    })
    addAll(tri("drive-empty") { open(it, "文档") + listOf(Step.Pump(1_200)) })
    addAll(tri("drive-search-empty") { searchFor(it, "zzz") })
    addAll(tri("drive-library-empty", extraSeed = { purgeTrash() }) { toLibrary(it, "回收站") })
    addAll(tri("drive-archive-empty") { openArchive(it, "截图合集.zip", "截图") })
    // 库
    addAll(tri("drive-recent") { toLibrary(it, "最近添加", "最近上传与离线、秒传的文件") })
    addAll(tri("drive-starred") { toLibrary(it, "星标", "已加星标的文件与文件夹") + listOf(Step.Wait("Dune")) })
    addAll(tri("drive-history") { toLibrary(it, "播放历史", "与 PikPak 官方客户端同步") })
    addAll(tri("drive-trash") { toLibrary(it, "回收站") + listOf(Step.Wait("old-backup")) })
    addAll(tri("drive-trash-select") { form ->
        toLibrary(form, "回收站") + listOf(Step.Wait("old-backup")) +
            if (form.desktop) listOf(Step.Click("old-backup"), Step.Key("Ctrl+A"), Step.Pump(600)) else listOf(Step.LongPress("old-backup"), Step.Pump(600))
    })
    // 查重结果：桌面是查重标签，移动端扫完自动进结果页
    // 桌面的查重标签开在后台，点过去看
    addAll(tri("drive-duplicates", extraSeed = { seedDuplicates() }) { form ->
        startDuplicates(form) + if (form.desktop) listOf(Step.Click("查重"), Step.Pump(1_000)) else emptyList()
    })
    // 压缩包：进入、要密码、密码错
    addAll(tri("drive-archive") { openArchive(it, "Project Sekai OST Vol.3.zip", "Sekai") + listOf(Step.Wait("booklet"), Step.Pump(800)) })
    addAll(tri("drive-archive-password") { openArchive(it, "android-sdk-backup.7z", "android") })
    addAll(tri("drive-archive-password-wrong") {
        openArchive(it, "android-sdk-backup.7z", "android") + listOf(Step.Type("1234"), Step.Click("打开", topmost = true), Step.Pump(1_500))
    })
    // 搜索：当前目录与全盘。「动画」在网格里显示成 Frieren，按名字搜却搜不到，当前目录用「2026」
    addAll(tri("drive-search") { searchFor(it, "2026") })
    addAll(tri("drive-search-global") { searchFor(it, "Frieren") + listOf(Step.Click("全盘"), Step.Pump(2_500)) })
    // 多选
    addAll(tri("drive-select") { selectSome(it, "Oppenheimer") })
    // 视图：海报墙即 drive-root
    addAll(tri("drive-view-list", viewMode = "LIST"))
    addAll(tri("drive-view-gallery", viewMode = "GALLERY", waitFor = null) { listOf(Step.Pump(2_500)) })
    // 目录图（仅桌面）
    add(Shot("drive-foldermap-desktop-1440x900", steps = listOf(Step.Pump(800), Step.Key("Ctrl+Shift+E"), Step.Pump(1_500))))
    // 剪贴板（仅桌面）：剪切后的提示与命令栏的「粘贴」
    add(Shot("drive-clipboard-desktop-1440x900", steps = listOf(Step.Pump(800), Step.Click("Oppenheimer"), Step.Key("Ctrl+X"), Step.Pump(600)) + intoAnime(Form.DESKTOP)))
    // 定位高亮：从别处「在网盘中显示」落到文件上
    addAll(tri("drive-locate", highlightName = "文档") { listOf(Step.Pump(2_000)) })
    // 桌面的键鼠细节：浏览历史、悬停、框选、键盘焦点、拖放
    add(Shot("drive-backforward-desktop-1440x900", steps = intoAnime(Form.DESKTOP) + listOf(Step.Key("Alt+Left"), Step.Pump(800)) + open(Form.DESKTOP, "电影") + listOf(Step.Pump(1_000))))
    add(Shot("drive-hover-desktop-1440x900", steps = listOf(Step.Pump(800), Step.Hover("Oppenheimer"))))
    add(Shot("drive-marquee-desktop-1440x900", steps = intoAnime(Form.DESKTOP) + listOf(Step.Drag(Offset(1350f, 720f), Offset(800f, 400f)))))
    add(Shot("drive-keyboard-desktop-1440x900", steps = listOf(Step.Pump(800), Step.Key("Down"), Step.Key("Down"), Step.Key("Right"), Step.Key("Down"))))
    // 拖放移动：从 Oppenheimer 的卡片拖到「文档」上（坐标按 1440x900 的网格量出），松手前与松手后带「撤销」的提示。
    // 先单击让它取得焦点：按在没选中、没有焦点的条目上拖是框选。单击后停一会儿，免得按下与单击连成双击
    val dragToDocs = listOf(Step.Pump(1_500), Step.Click("Oppenheimer"), Step.Pump(600), Step.Drag(Offset(1283f, 440f), Offset(990f, 245f)))
    add(Shot("drive-drag-folder-desktop-1440x900", steps = dragToDocs))
    add(Shot("drive-drag-dropped-desktop-1440x900", steps = dragToDocs + listOf(Step.Release, Step.Pump(1_500))))
    // 归档标记：根目录里一个归档条目；「电影」里直接放着归档条目
    add(Shot("drive-vault-list-desktop-1440x900", viewMode = "LIST", steps = listOf(Step.Wait("Blade"), Step.Pump(1_500))))
    // 「电影」列过一次后，退回根目录，文件夹上挂着归档标记
    add(Shot("drive-vault-movies-desktop-1440x900", viewMode = "LIST",
        steps = open(Form.DESKTOP, "电影") + listOf(Step.Wait("Arrival"), Step.Pump(800), Step.Key("Alt+Left"), Step.Pump(1_200))))

    // ===== 标签（仅桌面） =====
    add(Shot("tabs-desktop-1440x900", steps = listOf(Step.Pump(800), Step.Key("Ctrl+T"), Step.Pump(600)) + intoAnime(Form.DESKTOP) +
        listOf(Step.Key("Alt+Left"), Step.Pump(800), Step.Click("电影", PointerButton.Secondary), Step.Pump(400), Step.Click("在新标签页打开", topmost = true), Step.Pump(800))))
    add(Shot("tabs-duplicates-scanning-desktop-1440x900", extraSeed = { seedDuplicates() },
        steps = listOf(Step.Pump(800), Step.Run { server.listDelayMs = 3_000 }, Step.Key("Ctrl+K"), Step.Pump(500), Step.Type("查找重复"), Step.Key("Enter"), Step.Pump(1_200))))

    // ===== 面板与对话框 =====
    // 条目操作：桌面右键菜单与 Shift+F10 的操作面板，移动端更多按钮
    addAll(tri("panel-actions") { form ->
        if (form.desktop) listOf(Step.Wait("Blade"), Step.Pump(1_000), Step.Key("Down"), Step.Key("Shift+F10"), Step.Pump(1_000))
        else listOf(Step.Pump(800), Step.Click("更多操作"), Step.Pump(1_000))
    })
    add(Shot("panel-contextmenu-desktop-1440x900", steps = listOf(Step.Pump(800), Step.Click("Oppenheimer", PointerButton.Secondary), Step.Pump(600))))
    addAll(tri("panel-properties", extraSeed = { giveVideosDuration() }) { action(it, "Oppenheimer", "属性", "Oppenheimer") })
    // 添加链接：空的、分享链接、磁力
    addAll(tri("panel-addlink") { form -> if (form.desktop) listOf(Step.Pump(600), Step.Click("添加链接"), Step.Pump(1_000)) else fab("添加链接") })
    addAll(tri("panel-addlink-share", initialLink = "https://mypikpak.com/s/SHOT") { listOf(Step.Pump(2_500)) })
    addAll(tri("panel-addlink-magnet", initialLink = "magnet:?xt=urn:btih:" + "a".repeat(40)) { listOf(Step.Pump(2_500)) })
    // 查找重复：桌面是查重标签，移动端独占 sheet；不加重复数据，sheet 停在扫完无结果
    addAll(tri("panel-duplicates") { form ->
        startDuplicates(form) + if (form.desktop) listOf(Step.Click("查重"), Step.Pump(1_000)) else emptyList()
    })
    // 目录选择器：桌面居中对话框，手机全屏，平板对话框；进一层子文件夹，再新建文件夹
    addAll(tri("panel-picker") { pickerSteps(it) })
    addAll(tri("panel-picker-sub") { pickerSteps(it) + listOf(Step.Click("动画", topmost = true), Step.Pump(1_500)) })
    addAll(tri("panel-picker-newfolder") { pickerSteps(it) + listOf(Step.Click("新建文件夹", topmost = true), Step.Pump(800)) })
    // 下载片段：打开，改过区间后关闭时的确认
    addAll(tri("panel-segment") { action(it,"Oppenheimer", "下载指定段落") + listOf(Step.Pump(2_000)) })
    // 关闭：桌面与平板侧边面板点 ×，手机底部 sheet 点上方的遮罩
    addAll(tri("panel-segment-discard") { form ->
        action(form, "Oppenheimer", "下载指定段落") + listOf(Step.Pump(2_000), Step.Click("+1"), Step.Pump(300)) +
            (if (form == Form.PHONE) listOf(Step.ClickAt(Offset(200f, 80f))) else listOf(Step.Click("关闭", topmost = true))) + listOf(Step.Pump(800))
    })
    // 新建、重命名、分享、下载画质（下载视频即弹出）
    addAll(tri("dlg-newfolder") { form ->
        if (form.desktop) listOf(Step.Pump(600), Step.Click("新建"), Step.Pump(500), Step.Click("新建文件夹", topmost = true), Step.Pump(800))
        else fab("新建文件夹")
    })
    addAll(tri("dlg-rename") { action(it, "文档", "重命名") })
    addAll(tri("dlg-rename-file") { action(it, "Oppenheimer", "重命名") })
    addAll(tri("dlg-unsupported") { action(it, "文档", "重命名") + listOf(Step.Click("新名称", topmost = true), Step.Type("?"), Step.Click("确定", topmost = true), Step.Pump(800)) })
    addAll(tri("dlg-share") { action(it, "文档", "分享") })
    addAll(tri("dlg-share-custom") { action(it, "文档", "分享") + listOf(Step.Click("自定义", topmost = true), Step.Pump(600)) })
    addAll(tri("dlg-quality") { action(it, "Oppenheimer", downloadLabel(it)) + listOf(Step.Pump(1_500)) })
    // 批量重命名：桌面左规则右预览，手机全屏单栏，平板同桌面
    addAll(tri("panel-rename") { renameOpen(it) })
    addAll(tri("panel-rename-guide") { renameOpen(it) + listOf(Step.Click("使用说明"), Step.Pump(800)) })
    add(Shot("panel-rename-typed-desktop-1440x900", steps = renameSteps() + listOf(
        Step.Type("Frieren - "), Step.Click("输入替换成的文字"), Step.Type("第"), Step.Pump(500), Step.Click("移除开头"), Step.Click("移除结尾"), Step.Pump(800),
    )))
    add(Shot("panel-rename-blocks-desktop-1440x900", steps = renameSteps() + blockSteps()))
    add(Shot("panel-rename-textmode-desktop-1440x900", steps = renameSteps() + blockSteps() + listOf(Step.Click("正则表达式"), Step.Pump(800))))
    add(Shot("panel-rename-conflict-desktop-1440x900", steps = renameSteps() + listOf(
        Step.Click("正则表达式"), Step.Pump(300), Step.Click("查找（正则表达式）"), Step.Type("[0-9]+"), Step.Key("Tab"), Step.Type("E"), Step.Pump(800),
    )))
    add(Shot("panel-rename-narrow-desktop-800x860", 800, 860, mobile = false,
        steps = renameSteps(Offset(780f, 250f), Offset(300f, 700f)) + listOf(Step.Type("Frieren - "), Step.Pump(800))))
    // 库内确认、归档与取消归档确认
    addAll(tri("dlg-trash-clear") { form ->
        toLibrary(form, "回收站") + listOf(Step.Wait("old-backup"), Step.Click("清空回收站"), Step.Pump(800))
    })
    addAll(tri("dlg-trash-delete") { form ->
        toLibrary(form, "回收站") + listOf(Step.Wait("old-backup")) +
            (if (form.desktop) listOf(Step.Click("old-backup", PointerButton.Secondary), Step.Pump(600)) else listOf(Step.Click("更多操作"), Step.Pump(800))) +
            listOf(Step.Click("彻底删除", topmost = true), Step.Pump(800))
    })
    addAll(tri("dlg-history-clear") { toLibrary(it, "播放历史", "与 PikPak 官方客户端同步") + listOf(Step.Click("清空播放历史"), Step.Pump(800)) })
    addAll(tri("dlg-vault") { action(it, "文档", "归档") + listOf(Step.Pump(1_500)) })
    addAll(tri("dlg-unvault") { action(it, "电影", "取消归档") + listOf(Step.Pump(2_000)) })
    // 图片查看器：桌面有翻页按钮，移动端靠手势
    addAll(tri("panel-image", extraSeed = { node("IMG_20260801_183012.jpg").thumbnail = shotImage() }) { searchFor(it, "IMG") + open(it, "IMG_20260801") + listOf(Step.Pump(1_500)) })

    // ===== 传输页 =====
    val richTransfers: FakePikPak.() -> Unit = { seedTransfers() }
    addAll(tri("transfers", extraSeed = richTransfers, prefs = ::transferPrefs) { listOf(instantRecord) + toTransfers(it) + listOf(Step.Pump(800)) })
    add(Shot("transfers-dark-desktop-1440x900", mode = ThemeMode.DARK, extraSeed = richTransfers, prefs = ::transferPrefs,
        steps = listOf(instantRecord) + toTransfers(Form.DESKTOP) + listOf(Step.Pump(800))))
    add(Shot("transfers-desktop-700x800", 700, 800, mobile = false, caption = true, extraSeed = richTransfers, prefs = ::transferPrefs,
        steps = listOf(instantRecord) + toTransfers(Form.DESKTOP) + listOf(Step.Pump(800))))
    addAll(tri("transfers-skeleton", localDownloads = false, extraSeed = { tasksDelayMs = 20_000 }) { toTransfers(it) })
    addAll(tri("transfers-empty", localDownloads = false, extraSeed = { clearTasks() }) { toTransfers(it) + listOf(Step.Pump(800)) })
    // 类别标签与行里的「云端」「上传」标注同名，点最靠上的那个
    addAll(tri("transfers-kind-empty") { toTransfers(it) + listOf(Step.ClickHighest("上传"), Step.Pump(800)) })
    addAll(tri("transfers-cloud", extraSeed = richTransfers, prefs = ::transferPrefs) { listOf(instantRecord) + toTransfers(it) + listOf(Step.ClickHighest("云端"), Step.Pump(800)) })
    addAll(tri("transfers-uploads", extraSeed = richTransfers, prefs = ::transferPrefs) { toTransfers(it) + listOf(Step.ClickHighest("上传"), Step.Pump(800)) })
    // 「文件已删除」一段在最后，列表长了手机上组合不到它；只留这一类任务
    addAll(tri("transfers-deleted", localDownloads = false, extraSeed = {
        clearTasks()
        seedTransfers()
    }) { toTransfers(it) + listOf(Step.Wait("文件已删除"), Step.Click("文件已删除"), Step.Pump(800)) })
    addAll(tri("transfers-batch") { toTransfers(it) + listOf(Step.Wait("Frieren S01"), Step.Click("展开"), Step.Pump(800)) })
    addAll(tri("transfers-select") { form ->
        toTransfers(form) + listOf(Step.Wait("Oppenheimer")) +
            if (form.desktop) listOf(Step.Click("Oppenheimer"), Step.Key("Ctrl+A"), Step.Pump(600)) else listOf(Step.LongPress("Oppenheimer"), Step.Pump(600))
    })
    // 详情面板：移动端行尾的更多按钮。桌面的行没有这个按钮（ListMoreButton 在桌面不画），同一组操作在右键菜单里
    addAll(tri("transfers-detail", forms = MOBILE_ONLY) { toTransfers(it) + listOf(Step.Wait("Oppenheimer"), Step.Click("更多操作"), Step.Pump(1_000)) })
    add(Shot("transfers-contextmenu-desktop-1440x900",
        steps = toTransfers(Form.DESKTOP) + listOf(Step.Wait("Frieren S01"), Step.Click("Frieren S01", PointerButton.Secondary), Step.Pump(800))))
    addAll(tri("transfers-delete") { form ->
        toTransfers(form) + listOf(Step.Wait("Oppenheimer")) +
            (if (form.desktop) listOf(Step.Click("Oppenheimer"), Step.Key("Ctrl+A"), Step.Pump(400), Step.Key("Delete"))
            else listOf(Step.LongPress("Oppenheimer"), Step.Pump(500), Step.Click("删除"))) + listOf(Step.Pump(800))
    })
    // 文件夹下载的列出失败与超额待确认：从网盘页下载一个空文件夹与「动画」，后者超出今日额度
    // 各下一个文件夹：空的「文档」列出失败，「动画」超出今日额度（网格里显示成 Frieren，按原名搜）
    addAll(tri("transfers-folder-failed") { form ->
        download(form, "文档") + listOf(Step.Pump(2_000)) + toTransfers(form) + listOf(Step.Pump(1_000))
    })
    addAll(tri("transfers-folder-quota", extraSeed = { dailyDownloadUsed = (1L shl 40) - (1L shl 30) }) { form ->
        download(form, "Frieren", "动画") + listOf(Step.Pump(3_000)) + toTransfers(form) + listOf(Step.Pump(1_000))
    })
    // 服务端解压：进行中一行
    addAll(tri("transfers-extract") {
        listOf(Step.Run { services.archiveExtractSession.extract(listOf(rootFile("Project Sekai OST Vol.3.zip"))) }, Step.Pump(2_500)) + toTransfers(it) + listOf(Step.Pump(800))
    })

    // ===== 其他页面 =====
    addAll(tri("pages-profile", forms = MOBILE_ONLY) { listOf(Step.Click("我的"), Step.Pump(1_200)) })
    add(Shot("pages-profile-dark-phone-400x860", 400, 860, ThemeMode.DARK, steps = listOf(Step.Click("我的"), Step.Pump(1_200))))
    addAll(tri("pages-profile-free", forms = MOBILE_ONLY, extraSeed = { premium = false }) { listOf(Step.Click("我的"), Step.Pump(1_200)) })
    addAll(tri("pages-shares") { toLibrary(it, "我的分享", "复制或取消已创建的分享链接") + listOf(Step.Pump(800)) })
    addAll(tri("pages-shares-loading", extraSeed = { sharesDelayMs = 20_000 }) { toLibrary(it, "我的分享", "复制或取消已创建的分享链接") })
    addAll(tri("pages-shares-error", extraSeed = { shares = null }) { toLibrary(it, "我的分享", "复制或取消已创建的分享链接") + listOf(Step.Pump(800)) })
    addAll(tri("pages-shares-empty", extraSeed = { shares = emptyList() }) { toLibrary(it, "我的分享", "复制或取消已创建的分享链接") + listOf(Step.Pump(800)) })
    // 我的分享的多选只有鼠标框选（仅桌面）
    add(Shot("pages-shares-select-desktop-1440x900", steps = toLibrary(Form.DESKTOP, "我的分享") + listOf(Step.Pump(800), Step.Drag(Offset(1420f, 880f), Offset(300f, 180f)), Step.Release, Step.Pump(600))))
    addAll(tri("pages-settings") { toSettings(it) })
    add(Shot("pages-settings-dark-desktop-1440x900", mode = ThemeMode.DARK, steps = toSettings(Form.DESKTOP)))
    add(Shot("pages-settings-desktop-700x800", 700, 800, mobile = false, caption = true, steps = toSettings(Form.DESKTOP)))
    addAll(tri("pages-webdav") { settingsRow(it, "WebDAV") + listOf(Step.Pump(800)) })
    // 设置里的对话框
    addAll(tri("dlg-proxy") { settingsRow(it,"网络代理") })
    addAll(tri("dlg-proxy-manual") { settingsRow(it,"网络代理") + listOf(Step.Click("手动", topmost = true), Step.Pump(600)) })
    addAll(tri("dlg-domain") { settingsRow(it,"服务器域名") })
    addAll(tri("dlg-speed") { settingsRow(it,"蜗牛模式") + listOf(Step.Click("速度上限"), Step.Pump(1_000)) })
    addAll(tri("dlg-quality-setting") { settingsRow(it,"播放画质") })
    addAll(tri("dlg-archive-passwords", prefs = { mapOf("drive.archivePasswords" to """["hunter2","p@ss-2026"]""") }) { settingsRow(it,"解压密码") })
    addAll(tri("dlg-download-location") { settingsRow(it,"下载位置") })
    // 账号切换、移除账号、退出登录：桌面在设置的账号区，移动端在「我的」
    fun accountArea(form: Form) = if (form.desktop) toSettings(form) else listOf(Step.Click("我的"), Step.Pump(1_200))
    addAll(tri("pages-accounts", login = LoginSeed.TWO_ACCOUNTS) { accountArea(it) })
    addAll(tri("dlg-account-remove", login = LoginSeed.TWO_ACCOUNTS) { accountArea(it) + listOf(Step.Click("移除"), Step.Pump(800)) })
    addAll(tri("dlg-logout") { accountArea(it) + listOf(Step.Click("退出登录"), Step.Pump(1_000)) })

    // ===== 应用内更新 =====
    addAll(tri("update-startup", update = UpdateSpec()) { listOf(Step.Pump(1_200)) })
    addAll(tri("update-downloading", update = UpdateSpec()) { listOf(Step.Pump(1_000), Step.Click("下载并安装", topmost = true), Step.Pump(1_000)) })
    addAll(tri("update-ready", update = UpdateSpec { UpdateStatus.ReadyToRestart(it) }) { listOf(Step.Pump(1_200)) })
    addAll(tri("update-installing", update = UpdateSpec { UpdateStatus.Installing(it) }) { listOf(Step.Pump(1_200)) })
    addAll(tri("update-failed", update = UpdateSpec { UpdateStatus.Failed("网络不通，检查网络后重试", it) }) { listOf(Step.Pump(1_200)) })
    addAll(tri("update-noinapp", update = UpdateSpec(canInstall = false)) { listOf(Step.Pump(1_200)) })
    addAll(tri("update-login", login = LoginSeed.NONE, waitFor = "使用 PikPak 账号", update = UpdateSpec()) { listOf(Step.Pump(1_200)) })
    // 设置的关于卡片打开同一个对话框，次按钮换成「在浏览器中查看」
    addAll(tri("update-about", update = UpdateSpec(onStartup = false)) { form ->
        (if (form.desktop) settingsRow(form, "查看更新") else listOf(Step.Click("我的"), Step.Pump(1_200), Step.Click("查看更新"), Step.Pump(1_000)))
    })

    // ===== 信息流 =====
    addAll(tri("feed-empty") { openFeed() })
    addAll(tri("feed", extraSeed = feedSeed) { openFeed() + listOf(Step.Pump(3_000)) })
    add(Shot("feed-dark-desktop-1440x900", mode = ThemeMode.DARK, extraSeed = feedSeed, steps = openFeed() + listOf(Step.Pump(3_000))))
    add(Shot("feed-full-desktop-700x800", 700, 800, mobile = false, caption = true, extraSeed = feedSeed, steps = openFeed() + listOf(Step.Pump(3_000))))
    add(Shot("feed-phone-landscape-860x400", 860, 400, mobile = true, extraSeed = feedSeed, steps = openFeed() + listOf(Step.Pump(3_000))))
    add(Shot("feed-popped-desktop-1440x900", extraSeed = feedSeed, steps = openFeed() + listOf(Step.Click("在独立窗口播放"), Step.Pump(800))))
    addAll(tri("feed-immersive", extraSeed = feedSeed) { openFeed() + listOf(Step.Pump(2_000), Step.Click("隐藏控件"), Step.Pump(800)) })
    // 动画缩放为 0 时按住期间的「2 倍速」提示没有画出来（原因未查），按真实速度播才拍得到
    addAll(tri("feed-boost", extraSeed = feedSeed, motion = true) { openFeed() + listOf(Step.Pump(2_000), Step.LongPressAt(feedCenter(it), release = false)) })
    // 星爆约 450ms，要拍它的中途：动画按真实速度播，收尾不再多等
    addAll(tri("feed-star", extraSeed = feedSeed, motion = true, settleMs = 0) { openFeed() + listOf(Step.Pump(2_000), Step.ClickAt(feedCenter(it), double = true), Step.Pump(120)) })
    addAll(tri("feed-landscape-lock", forms = listOf(Form.TABLET), extraSeed = feedSeed) { openFeed() + listOf(Step.Pump(1_000), Step.Click("横屏"), Step.Pump(600)) })
    // 挂起：「在网盘中显示」之后，桌面「信息流」按钮带点，移动端网盘页底部的「继续刷」
    addAll(tri("feed-suspended", extraSeed = feedSeed) { openFeed() + listOf(Step.Pump(2_000), Step.Click("在网盘中显示"), Step.Pump(2_000)) })
    add(Shot("feed-suspended-phone-landscape-860x400", 860, 400, mobile = true, extraSeed = feedSeed,
        steps = openFeed() + listOf(Step.Pump(2_000), Step.Click("在网盘中显示"), Step.Pump(2_000))))

    // ===== 播放器 =====
    addAll(playerShots("player"))
    add(Shot("player-dark-desktop-1440x900", mode = ThemeMode.DARK, player = PlayerShot(), mobile = false, waitFor = null))
    addAll(playerShots("player-loading", PlayerShot(loading = true)))
    addAll(playerShots("player-error", PlayerShot(error = "原画与转码都无法播放：连接被重置")))
    addAll(playerShots("player-resume", PlayerShot(resumedFromMillis = 754_000)))
    addAll(playerShots("player-paused", PlayerShot(playing = false)))
    addAll(playerShots("player-fullscreen", PlayerShot(fullscreen = true)))
    addAll(playerShots("player-episodes", PlayerShot(playing = false)) { playerPanel("选集") })
    addAll(playerShots("player-settings", PlayerShot(playing = false)) { playerPanel("播放设置") })
    addAll(playerShots("player-tracks", PlayerShot(playing = false)) { playerPanel("播放设置", "字幕") })
    addAll(playerShots("player-drive-subtitles", PlayerShot(playing = false)) { playerPanel("播放设置", "字幕", "从网盘选择字幕") + listOf(Step.Pump(1_500)) })
    addAll(playerShots("player-stats", PlayerShot(playing = false)) { playerPanel("播放设置", "信息") })
    addAll(playerShots("player-speed", PlayerShot(playing = false)) { playerPanel("倍速") })
    addAll(playerShots("player-lock", PlayerShot(playing = false)) { playerPanel("锁定屏幕") })
    // HUD 只亮 800ms，按完键立刻存图
    addAll(playerShots("player-hud", PlayerShot(playing = false), settleMs = 0) { listOf(Step.Key("Up"), Step.Pump(100)) })
    addAll(playerShots("player-boost") { form -> listOf(Step.LongPressAt(Offset(if (form == Form.PHONE) 200f else form.width / 2f, 260f), release = false)) })
    addAll(playerShots("player-share", PlayerShot(playing = false)) { playerPanel("播放设置", "分享") + listOf(Step.Pump(1_000)) })
    addAll(playerShots("player-quality", PlayerShot(playing = false)) { playerPanel("播放设置", "下载") + listOf(Step.Pump(1_500)) })

    // ===== 任务占用与叠加 =====
    // 移动端独占：添加链接开着时查找重复、查找重复结果页上添加链接、离开查重目录树
    addAll(tri("task-claim-addlink-to-duplicates", forms = MOBILE_ONLY, initialLink = "magnet:?xt=urn:btih:" + "a".repeat(40)) {
        listOf(Step.Pump(2_000)) + collapseSheet(it) + fab("查找重复")
    })
    // 不加重复数据：扫完停在 sheet 里，收起后会话还在
    addAll(tri("task-claim-duplicates-to-addlink", forms = MOBILE_ONLY) { startDuplicates(it) + collapseSheet(it) + fab("添加链接") })
    // 结果页是一个位置，点顶栏返回即离开查重的目录树
    addAll(tri("task-leave-duplicates", forms = MOBILE_ONLY, extraSeed = { seedDuplicates() }) { startDuplicates(it) + listOf(Step.Click("返回"), Step.Pump(800)) })
    addAll(tri("task-claim-feed", forms = MOBILE_ONLY, extraSeed = feedSeed) {
        openFeed() + listOf(Step.Pump(2_000), Step.Click("在网盘中显示"), Step.Pump(2_000)) + fab("添加链接")
    })
    // 桌面放行：添加链接卡片、查重标签与解压卡片同时在
    add(Shot("task-coexist-desktop-1440x900", initialLink = "magnet:?xt=urn:btih:" + "a".repeat(40), extraSeed = { seedDuplicates() },
        steps = listOf(Step.Pump(2_000), collapseAddLink, Step.Pump(600),
            Step.Run { services.archiveExtractSession.extract(listOf(rootFile("Project Sekai OST Vol.3.zip"))) }, Step.Pump(800)) + startDuplicates(Form.DESKTOP)))
    // 根层浮层：定位遮罩、外部上传去向、解压密码
    // 已完成的云端任务「打开」即在网盘中定位，逐级查路径时盖遮罩；只留云端任务，手机上那一行才在首屏
    // 从传输页「打开」已完成的云端任务，落到网盘里的文件上。产出放在没列过的「动画/SPs」里，本想借逐级查上级拍定位遮罩，
    // 但取详情加了延迟也没盖上遮罩，路径不经那个接口得来（未查明），这里只拍落地的样子
    addAll(tri("task-reveal", localDownloads = false, extraSeed = {
        clearTasks()
        val sp = addFile("Frieren - SP01 [1080p].mkv", 300L shl 20, parentId = node("SPs").id)
        addTask("Arrival.2016.1080p.BluRay.x264.mkv", "PHASE_TYPE_COMPLETE", 100, 9L shl 30, created = hoursAgo(6), fileId = sp.id)
    }) { form ->
        toTransfers(form) + listOf(Step.Wait("Arrival")) +
            (if (form.desktop) listOf(Step.Click("Arrival", PointerButton.Secondary), Step.Pump(600), Step.Click("打开", topmost = true))
            else listOf(Step.Click("Arrival"))) + listOf(Step.Pump(800))
    })
    addAll(tri("task-upload-target") { listOf(Step.Pump(600), Step.Run { services.uploadManager.request(UploadSelection(files = listOf(File(dir, "vlog.mp4").path, File(dir, "notes.txt").path))) }, Step.Pump(1_000)) })
    addAll(tri("task-extract-password") { listOf(Step.Run { services.archiveExtractSession.extract(listOf(rootFile("android-sdk-backup.7z"))) }, Step.Pump(2_000)) })
}

/** 图片查看器要的本地图：Coil 不走假服务端的拦截器，网络地址取不到。 */
private fun shotImage(): String {
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(1600, 1000)
    surface.canvas.clear(0xFF2B4C6F.toInt())
    surface.canvas.drawCircle(1100f, 380f, 220f, org.jetbrains.skia.Paint().apply { color = 0xFFF2C46D.toInt() })
    surface.canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(0f, 700f, 1600f, 300f), org.jetbrains.skia.Paint().apply { color = 0xFF1C3324.toInt() })
    val file = File.createTempFile("piko-shot-image", ".png", ShotDirs.run)
    file.writeBytes(surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
    return file.toURI().toString()
}

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
        println(USAGE)
        return
    }
    val rest = args.drop(1)
    val outDir = File(option(rest, "-o") ?: "build/shots")
    ShotDirs.start()
    val code = try {
        when (args[0]) {
            "all" -> if (renderAll(rest, outDir)) 0 else 1
            "shot" -> {
                val name = rest.firstOrNull()?.takeUnless { it.startsWith("-") } ?: fail("shot 要一个名字")
                render(parseShot(name, rest.drop(1)), outDir)
                0
            }
            "texts" -> {
                printTexts(parseShot("texts", rest))
                0
            }
            else -> fail("未知命令：${args[0]}")
        }
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        2
    }
    // Compose 与 OkHttp 留下的非守护线程会让进程挂着不退；临时目录由退出时的钩子删
    exitProcess(code)
}

/**
 * `all`：带 `--shard` 时是子进程，只渲染自己那一片、逐张报给主进程；否则先把上一版挪作基线，
 * 在本进程或 `--jobs` 个子进程里渲染，最后写差异清单。返回是否全部成功。
 */
private fun renderAll(rest: List<String>, outDir: File): Boolean {
    check(standardSet.map { it.name }.toSet().size == standardSet.size) { "standardSet 里有重名的图" }
    val only = option(rest, "--only").orEmpty()
    val shots = standardSet.filter { it.name.startsWith(only) }
    option(rest, "--shard")?.let { spec ->
        renderBatch(Shard.parse(spec).pick(shots), outDir) { result -> println(result.toLine()) }
        return true
    }
    val jobs = option(rest, "--jobs")?.let { it.toIntOrNull()?.takeIf { n -> n > 0 } ?: fail("--jobs 要一个正整数") } ?: defaultJobs()
    val baseline = Baseline(outDir, only, keep = "--keep-baseline" in rest)
    baseline.roll()
    val names = shots.map { it.name }
    val started = System.currentTimeMillis()
    val results = if (jobs == 1 || shots.size <= 1) {
        val progress = Progress(shots.size)
        renderBatch(shots, outDir) { progress.report(it) }
    } else {
        renderInChildren(jobs, names, outDir, if (only.isEmpty()) emptyList() else listOf("--only", only))
    }
    val elapsedMs = System.currentTimeMillis() - started
    val failed = results.filter { !it.ok }
    println("${results.size} 张，失败 ${failed.size} 张，用时 ${elapsedMs / 1000} 秒")
    failed.forEach { println("  失败 ${it.name}：${it.error}") }
    val report = baseline.report(names, results, elapsedMs)
    println("差异清单：${report.absolutePath}")
    return failed.isEmpty()
}

/** 逐张渲染 [shots]，每张出完交给 [onResult]。一张找不到要点的节点不拖累其余几张。 */
private fun renderBatch(shots: List<Shot>, outDir: File, onResult: (ShotResult) -> Unit): List<ShotResult> = shots.map { shot ->
    val started = System.currentTimeMillis()
    val result = try {
        val settled = render(shot, outDir, quiet = true)
        ShotResult(shot.name, ok = true, ms = System.currentTimeMillis() - started, settled = settled)
    } catch (e: Exception) {
        // 界面在组合里抛的异常也只算这一张失败
        ShotResult(shot.name, ok = false, ms = System.currentTimeMillis() - started, error = "${e::class.simpleName}: ${e.message}")
    }
    onResult(result)
    result
}

private fun render(shot: Shot, outDir: File, quiet: Boolean = false): Boolean {
    val file = File(outDir, "${shot.name}.png")
    val settled = run(shot) { app -> app.save(file) }
    if (!quiet) {
        println(file.absolutePath)
        if (!settled) System.err.println("${shot.settleMs}ms 内画面没有停下，照当前画面出图")
    }
    return settled
}

private fun printTexts(shot: Shot) {
    run(shot) { app -> app.texts().forEach(::println) }
}

/** 走完 [shot] 的步骤后交给 [finish]；返回收尾时画面是否停了下来。 */
private fun run(shot: Shot, finish: (AppScene) -> Unit): Boolean {
    var settled = true
    ShotEnv(shot.name, shot.viewMode, shot.extraSeed, shot.login, shot.localDownloads, shot.prefs()).use { env ->
        val updater = shot.update?.let { spec -> ShotUpdater(ShotUpdate(spec.canInstall), spec.status, spec.onStartup) }
        val wrap: (dev.piko.ui.platform.PikoPlatform) -> dev.piko.ui.platform.PikoPlatform =
            { base -> if (updater != null) UpdateShotPlatform(base, updater) else base }
        AppScene.open(env, shot.width, shot.height, shot.mode, shot.player, shot.caption, shot.mobile, wrap, shot.waitFor, shot.motion).use { app ->
            shot.highlightName?.let { name ->
                val file = env.rootFile(name)
                edt { env.services.driveRepository.requestHighlight(setOf(file.id)) }
            }
            shot.initialLink?.let { link -> edt { env.services.instantSession.start(link) } }
            app.changed()
            var lastDragEnd: Offset? = null
            for (step in shot.steps) {
                when (step) {
                    is Step.Click -> app.click(step.text, step.button, step.topmost)
                    is Step.ClickAt -> if (step.double) app.doubleClick(step.at) else app.click(step.at)
                    is Step.ClickHighest -> app.clickHighest(step.text)
                    is Step.Hover -> app.hover(step.text)
                    is Step.MoveTo -> app.moveTo(step.at)
                    is Step.LongPress -> app.longPress(step.text)
                    is Step.LongPressAt -> app.longPress(step.at, releaseAfter = step.release)
                    is Step.Drag -> {
                        app.drag(step.from, step.to)
                        lastDragEnd = step.to
                    }
                    is Step.Swipe -> app.swipe(step.from, step.to)
                    is Step.Release -> app.release(lastDragEnd ?: fail("--release 前面要有 --drag"))
                    is Step.Key -> app.key(step.chord)
                    is Step.Type -> app.type(step.text)
                    is Step.Wait -> if (!app.pumpUntil { app.hasText(step.text) }) {
                        System.err.println("[${shot.name}] 等不到「${step.text}」，照当前画面出图")
                    }
                    is Step.Pump -> app.idle(step.ms)                    // rootFile 一类要等网络，放在 EDT 外跑；改状态的部分自己会切到主线程
                    is Step.Run -> {
                        step.action(env)
                        app.changed()
                    }
                }
            }
            if (shot.settleMs > 0) settled = app.idle(shot.settleMs)
            finish(app)
        }
    }
    return settled
}

private fun parseShot(name: String, args: List<String>): Shot {
    var width = 1440
    var height = 900
    var mode = ThemeMode.LIGHT
    var caption = false
    var mobile: Boolean? = null
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
            "--mobile" -> mobile = true
            "--desktop" -> mobile = false
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
    return Shot(name, width, height, mode, steps, caption = caption, mobile = mobile ?: (width < 840))
}

private fun option(args: List<String>, name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
