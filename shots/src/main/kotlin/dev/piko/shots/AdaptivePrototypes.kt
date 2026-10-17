@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.piko.shots

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.PikoTheme
import dev.piko.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

private data class DemoWork(val id: Int, val title: String, val subtitle: String, val detail: String, val icon: ImageVector)
private val demoWorks = listOf(
    DemoWork(0, "旅行影像", "文件 · 我的文件 / 影像", "8 个项目", Icons.Outlined.Folder),
    DemoWork(1, "待整理", "文件 · 我的文件 / 收件箱", "12 个项目", Icons.Outlined.Folder),
    DemoWork(2, "山间的一年", "导入草稿 · 保存到旅行影像", "已选 6 个文件 · 2.4 GB", Icons.Outlined.AddLink),
    DemoWork(3, "收藏中的重复文件", "查重 · 我的文件 / 收藏", "发现 8 组重复", Icons.Outlined.ContentCopy),
)
private val demoFiles = listOf(
    "2026 · 山野之间" to "文件夹 · 12 个项目",
    "沿海公路" to "文件夹 · 8 个项目",
    "清晨的山谷.mp4" to "视频 · 3840 × 2160 · 1.28 GB",
    "雨后森林.mp4" to "视频 · 1920 × 1080 · 846 MB",
    "海边的最后一天.mp4" to "视频 · 3840 × 2160 · 2.06 GB",
    "旅途笔记.pdf" to "文档 · 24 页 · 3.2 MB",
    "路线与拍摄清单.md" to "文档 · 18 KB",
    "封面照片.jpg" to "图片 · 6000 × 4000 · 8.4 MB",
)
private class DemoState {
    var showTasks by mutableStateOf(false)
    val tasks = TaskPrototypeState()
    val import = AddLinkPrototypeState()
    var manager by mutableStateOf(false)
    var active by mutableIntStateOf(0)
    var works by mutableStateOf(demoWorks)
    var previews: Map<Int, ImageBitmap> = emptyMap()
    private val selections = mutableStateMapOf<Int, Set<Int>>()
    var selection: Set<Int>
        get() = selections[active].orEmpty()
        set(value) { selections[active] = value }
    private val selectionModes = mutableStateMapOf<Int, Boolean>()
    var selecting: Boolean
        get() = selectionModes[active] ?: false
        set(value) { selectionModes[active] = value }
    var hover by mutableIntStateOf(-1)
    var focus by mutableIntStateOf(-1)
    var menu by mutableIntStateOf(-1)
    var feed by mutableStateOf(true)
    var fullFeed by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var pointer by mutableStateOf(PointerType.Touch)
    fun toggle(index: Int) { selection = if (index in selection) selection - index else selection + index }
    fun activate(id: Int) { active = id; manager = false; menu = -1; focus = -1 }
    fun close(id: Int) {
        if (works.size == 1) return
        works = works.filterNot { it.id == id }
        if (active == id) active = works.first().id
    }
}

internal fun renderAdaptivePrototypes(outDir: File) {
    val directory = File(outDir, "adaptive-prototypes").apply { mkdirs() }
    ShotEnv("adaptive-prototypes", localDownloads = false).use { env ->
        val previews = mutableMapOf<Int, ImageBitmap>()
        val previewImages = mutableListOf<org.jetbrains.skia.Image>()
        fun render(name: String, width: Int, height: Int, dark: Boolean = false, setup: DemoState.() -> Unit = {}) {
            val state = DemoState().apply { this.previews = previews.toMap() }.apply(setup)
            kotlinx.coroutines.runBlocking {
                env.services.preferences.setSnailMode(dev.piko.data.auth.SnailMode(enabled = state.tasks.snail, downloadKiBps = 1024, uploadKiBps = 512))
            }
            val scene = edt {
                ImageComposeScene(width, height, Density(1f), coroutineContext = Dispatchers.Main + object : MotionDurationScale { override val scaleFactor = 0f }) {
                    CompositionLocalProvider(LocalPikoPlatform provides env.platform, dev.piko.ui.LocalPikoServices provides env.services) {
                        PikoTheme(Appearance(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
                            if (state.showTasks) TaskPrototype(state.tasks) else DemoWorkspace(state)
                        }
                    }
                }
            }
            fun pump() { repeat(8) { edt { scene.render(it * 100_000_000L).close() }; Thread.sleep(20) } }
            fun click(label: String) {
                edt {
                    fun find(node: SemanticsNode): SemanticsNode? {
                        val labels = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
                        val action = node.config.getOrNull(SemanticsActions.OnClick)
                        if (label in labels || action?.label == label) return node
                        return node.children.firstNotNullOfOrNull(::find)
                    }
                    var node: SemanticsNode? = scene.semanticsOwners.firstNotNullOfOrNull { find(it.unmergedRootSemanticsNode) }
                        ?: error("Missing prototype action: $label")
                    while (node != null && node.config.getOrNull(SemanticsActions.OnClick)?.action == null) node = node.parent
                    check(node?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
                }
                pump()
            }
            try {
                pump()
                if (name == "01-phone-files") {
                    click("标签页，4 个")
                    check(state.manager)
                    click("切换到待整理")
                    check(state.active == 1 && !state.manager)
                    click("标签页，4 个")
                    click("切换到旅行影像")
                    check(state.active == 0)
                }
                if (name == "13-import-phone-collapsed") {
                    val selected = state.import.selected
                    click("展开导入管理")
                    check(state.import.sheet == ImportSheetLevel.Half)
                    click("完全展开导入管理")
                    check(state.import.sheet == ImportSheetLevel.Expanded)
                    click("预览夏 · 林间的雨")
                    check(state.import.previewIndex == 1 && state.import.selected == selected)
                    click("收起导入管理")
                    check(state.import.sheet == ImportSheetLevel.Collapsed)
                    edt { state.import.previewIndex = 0 }
                    pump()
                }
                if (name == "17-import-phone-unselected") {
                    val original = state.import.selected
                    click("选择更多资源请访问.url")
                    check(8 in state.import.selected && original.all { it in state.import.selected })
                    edt { state.import.selected = original }
                    pump()
                }
                if (name == "19-tasks-phone") {
                    click("旅行影像")
                    check(state.tasks.selected == "archive")
                    click("返回任务")
                    check(state.tasks.selected == null)
                }
                if (name == "22-tasks-phone-attention") {
                    click("重新核对")
                    check(state.tasks.checked && state.tasks.selected == "uncertain")
                    edt { state.tasks.checked = false }
                    pump()
                }
                if (name == "25-tasks-phone-snail") {
                    click("限速 1.0 MB/s")
                    check(!kotlinx.coroutines.runBlocking { env.services.preferences.snailModeFlow.first() }.enabled)
                    click("蜗牛模式")
                    val limits = kotlinx.coroutines.runBlocking { env.services.preferences.snailModeFlow.first() }
                    check(limits.enabled && limits.downloadKiBps == 1024 && limits.uploadKiBps == 512)
                }
                if (name == "26-tasks-phone-limits") click("设置上限")
                edt { scene.render(1_000_000_000L).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use {
                        File(directory, "$name.png").writeBytes(it.bytes)
                        if (name.startsWith("preview-")) {
                            val snapshot = org.jetbrains.skia.Image.makeFromEncoded(it.bytes)
                            previewImages += snapshot
                            previews[state.active] = snapshot.toComposeImageBitmap()
                        }
                    }
                } }
                println("prototype: $name ($width x $height)")
            } finally { edt { scene.close() } }
        }
        demoWorks.forEach { work -> render("preview-${work.id}", 412, 740) { active = work.id } }
        render("01-phone-files", 412, 892)
        render("02-phone-tabs", 412, 892) { manager = true }
        render("03-phone-selection-touch", 412, 892) { selecting = true; selection = setOf(2, 3) }
        render("04-phone-selection-mouse", 412, 892) { selection = setOf(2, 3); hover = 3; focus = 3; pointer = PointerType.Mouse }
        render("05-wide-touch", 1280, 820)
        render("06-wide-mouse", 1280, 820) { hover = 2; focus = 2; pointer = PointerType.Mouse }
        render("08-phone-import", 412, 892) { active = 2 }
        render("09-wide-import", 1280, 820) { active = 2 }
        render("10-wide-dark", 1280, 820, dark = true)
        render("11-low-window", 860, 420)
        render("12-phone-feed", 412, 892) { fullFeed = true }
        render("13-import-phone-collapsed", 412, 892) { active = 2; import.sheet = ImportSheetLevel.Collapsed }
        render("14-import-phone-half", 412, 892) { active = 2; import.sheet = ImportSheetLevel.Half }
        render("15-import-phone-expanded", 412, 892) { active = 2; import.sheet = ImportSheetLevel.Expanded }
        render("16-import-wide", 1440, 960) { active = 2 }
        render("17-import-phone-unselected", 412, 892) { active = 2; import.sheet = ImportSheetLevel.Expanded; import.excludedOnly = true }
        render("18-import-wide-reference", 1440, 960) { active = 2; import.referenceOnly = true; import.selected = (0..6).toSet() }
        render("19-tasks-phone", 412, 892) { showTasks = true }
        render("20-tasks-wide", 1440, 960) { showTasks = true; tasks.selected = "archive" }
        render("21-tasks-phone-detail", 412, 892) { showTasks = true; tasks.selected = "archive" }
        render("22-tasks-phone-attention", 412, 892) { showTasks = true; tasks.selected = "uncertain" }
        render("23-tasks-phone-completed", 412, 892) { showTasks = true; tasks.filter = "已完成" }
        render("24-tasks-wide-dark", 1440, 960, dark = true) { showTasks = true; tasks.selected = "password" }
        render("25-tasks-phone-snail", 412, 892) { showTasks = true; tasks.snail = true }
        render("26-tasks-phone-limits", 412, 892) { showTasks = true; tasks.snail = true }
        val sections = listOf(
            Triple("任务：执行与结果", "沿用外框与内容岛，用色阶和间距分区。速度旁复用蜗牛模式开关和上限设置。", listOf("20-tasks-wide", "19-tasks-phone", "25-tasks-phone-snail", "26-tasks-phone-limits", "21-tasks-phone-detail", "22-tasks-phone-attention", "23-tasks-phone-completed", "24-tasks-wide-dark")),
            Triple("添加链接：结果树与来源树", "主区展示实际保存结果；来源树保留原始目录、选择框、预览和下载。", listOf("16-import-wide", "13-import-phone-collapsed", "14-import-phone-half", "15-import-phone-expanded")),
            Triple("补选与引用归档", "未选筛选保留祖先目录；广告标注排除原因，可手动选回。引用保存使用同一份选择。", listOf("17-import-phone-unselected", "18-import-wide-reference")),
            Triple("手机：文件页与标签管理", "方框数字入口，标签总览使用实际页面缩略图；当前标签以描边标记。", listOf("01-phone-files", "02-phone-tabs")),
            Triple("输入：触摸选择与键鼠焦点", "相同尺寸和选择集合，位置不变；触摸显示选择模式，键鼠强调焦点。", listOf("03-phone-selection-touch", "04-phone-selection-mouse")),
            Triple("宽窗口：触摸与键鼠", "同一布局使用标签栏及信息流辅助区，鼠标增加悬停与焦点反馈。", listOf("05-wide-touch", "06-wide-mouse")),
            Triple("导入：同一草稿的窄宽组织", "窄窗口上下排列，宽窗口文件与目标并列；提交区保持明确。", listOf("08-phone-import", "09-wide-import")),
            Triple("补充场景", "深色、低高度窗口、窄窗口信息流。", listOf("10-wide-dark", "11-low-window", "12-phone-feed")),
        )
        File(directory, "index.html").writeText("""
            <!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Piko · 自适应工作区设计</title>
            <style>body{margin:0;background:#edf0f7;color:#273044;font:16px/1.6 system-ui,'Microsoft YaHei',sans-serif}main{max-width:1440px;margin:auto;padding:40px 28px}h1{font-size:32px;margin:0}h2{font-size:23px;margin:0}p{color:#5a6476}section{margin:48px 0}.images{display:flex;gap:24px;align-items:flex-start;flex-wrap:wrap}figure{margin:0;max-width:100%}img{display:block;max-width:100%;height:auto;border-radius:16px;box-shadow:0 8px 32px #26375612}.phone img{width:330px}.wide img{width:1100px}figcaption{font-size:13px;margin-top:10px;color:#5a6476}a{color:inherit}header{border-bottom:1px solid #d0d6e0;padding-bottom:24px}</style>
            <main><header><h1>Piko · 自适应工作区</h1><p>Material 3 Expressive · 共用工作模型 / 按空间布局 / 按输入反馈</p><p>假数据 Compose 原型，点击图片可查看原尺寸。交互状态为设计样例；业务按钮尚未接入正式流程。</p></header>
            ${sections.joinToString("\n") { (title, description, names) -> "<section><h2>$title</h2><p>$description</p><div class='images'>" + names.joinToString("") { name -> "<figure class='${if (name.contains("phone")) "phone" else "wide"}'><a href='$name.png'><img src='$name.png' alt='$name'></a><figcaption>$name</figcaption></figure>" } + "</div></section>" }}
            </main></html>
        """.trimIndent())
        File(directory, "README.md").writeText("""
            # 自适应工作区原型

            使用 PikoTheme 与 Compose Material 3，固定假数据，无网络媒体。
            运行：`./gradlew :shots:run --args="adaptive-prototypes"`

            - 01 → 02：手机工作页与标签总览；总览可切换、关闭、新建文件标签。
            - 03 / 04：同尺寸、同选择集合；触摸显式选择，鼠标焦点与悬停。
            - 05 / 06：同尺寸并列布局；输入变化只影响控件反馈。
            - 宽窗口直接使用标签栏。
            - 08 / 09：同一导入草稿的窄宽组织。
            - 10：深色；11：低高度窗口；12：窄窗口信息流。
            - 13 / 14 / 15 / 16：添加链接的收起、半展开、完全展开与宽窗口。
            - index.html：整组对照页。

            原型在 shots 内独立组合，按钮用于本地状态演示。正式业务、媒体播放、持久化与平台输入仍需接入。
            渲染时执行了手机标签入口 → 待整理 → 总览 → 旅行影像的语义点击验证。
            同时验证管理面板三档切换，以及改变预览对象保持保存选择。
        """.trimIndent())
        previewImages.forEach { it.close() }
    }
}

@Composable private fun DemoWorkspace(state: DemoState) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        val wide = maxWidth >= 1000.dp
        val rail = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (rail) DemoRail()
            Column(Modifier.weight(1f).fillMaxHeight().padding(if (rail) 12.dp else 0.dp)) {
                Surface(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(if (rail) 28.dp else 0.dp), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        if (state.manager) DemoManager(state, wide) else if (state.fullFeed) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                DemoIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回文件") { state.fullFeed = false }
                                Text("旅行影像", style = MaterialTheme.typography.titleMedium)
                            }
                            DemoFeed(state, Modifier.fillMaxSize().padding(12.dp))
                        } else {
                            if (wide) DemoTabStrip(state)
                            DemoHeader(state, wide)
                            when (state.active) {
                                2 -> TreeImportPrototype(state.import, wide)
                                3 -> DemoReport()
                                else -> Row(Modifier.weight(1f)) {
                                    DemoFiles(state, wide, Modifier.weight(1f))
                                    if (wide && state.feed) DemoFeed(state, Modifier.width(342.dp).fillMaxHeight().padding(end = 16.dp, bottom = 16.dp))
                                }
                            }
                        }
                    }
                }
                if (!rail) DemoBottomNav()
            }
        }
    }
}

@Composable internal fun DemoRail(selected: Int = 0, snail: Boolean = false) {
    Column(Modifier.width(88.dp).fillMaxHeight().padding(top = 26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("piko", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        listOf(Icons.Outlined.Folder to "文件", Icons.Outlined.SwapVert to "任务", Icons.Outlined.PersonOutline to "我的").forEachIndexed { i, (icon, name) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = RoundedCornerShape(22.dp), color = if (i == 1 && snail) MaterialTheme.colorScheme.tertiaryContainer else if (i == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent) {
                    Icon(icon, name, Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                }
                Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable internal fun DemoBottomNav(selected: Int = 0, snail: Boolean = false) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.height(80.dp)) {
        listOf(Icons.Outlined.Folder to "文件", Icons.Outlined.SwapVert to "任务", Icons.Outlined.PersonOutline to "我的").forEachIndexed { i, (icon, label) ->
            NavigationBarItem(selected = i == selected, onClick = {}, icon = { Icon(icon, label) }, label = { Text(label) }, colors = NavigationBarItemDefaults.colors(indicatorColor = if (i == 1 && snail) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer))
        }
    }
}

@Composable private fun DemoTabStrip(state: DemoState) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        state.works.forEach { work ->
            Surface(modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp), color = if (state.active == work.id) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, onClick = { state.activate(work.id) }) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(work.icon, null, Modifier.size(18.dp))
                    Text(work.title, Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                    DemoIcon(Icons.Outlined.Close, "关闭${work.title}") { state.close(work.id) }
                }
            }
        }
        DemoIcon(Icons.Outlined.Add, "新建标签") {
            val id = (state.works.maxOfOrNull { it.id } ?: 3) + 1
            state.works = state.works + DemoWork(id, "新文件页", "文件 · 我的文件", "从根目录开始", Icons.Outlined.Folder)
            state.activate(id)
        }
    }
}

@Composable private fun DemoHeader(state: DemoState, wide: Boolean) {
    val work = state.works.first { it.id == state.active }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(work.title, style = if (wide) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(work.subtitle.substringAfter(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        if (state.active < 2) DemoIcon(Icons.Outlined.Search, "搜索") {}
        if (!wide) DemoTabCounter(state.works.size) { state.manager = true }
        DemoIcon(Icons.Outlined.MoreVert, "页面操作") {}
    }
}

@Composable private fun DemoManager(state: DemoState, wide: Boolean) {
    Column(Modifier.fillMaxSize().padding(if (wide) 28.dp else 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DemoIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回当前标签") { state.manager = false }
            Text("标签页", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Text("${state.works.size} 个已打开", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DemoIcon(Icons.Outlined.Add, "新建文件标签") {
                val id = (state.works.maxOfOrNull { it.id } ?: 3) + 1
                state.works = state.works + DemoWork(id, "新文件页", "文件 · 我的文件", "从根目录开始", Icons.Outlined.Folder)
                state.activate(id)
            }
        }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val columns = if (wide) 4 else 2
            state.works.chunked(columns).forEach { group ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    group.forEach { work -> DemoWorkCard(work, state, Modifier.weight(1f)) }
                    repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable private fun DemoWorkCard(work: DemoWork, state: DemoState, modifier: Modifier) {
    val active = state.active == work.id
    val shape = RoundedCornerShape(22.dp)
    Surface(modifier.aspectRatio(.76f).then(if (active) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier), shape = shape,
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(4.dp)) {
            Row(Modifier.height(48.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(work.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Box(Modifier.weight(1f).fillMaxHeight().clickable(onClickLabel = "切换到${work.title}") { state.activate(work.id) }, contentAlignment = Alignment.CenterStart) {
                    Text(work.title, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DemoIcon(Icons.Outlined.Close, "关闭${work.title}") { state.close(work.id) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
                .clickable(onClickLabel = "切换到${work.title}") { state.activate(work.id) }) {
                val preview = state.previews[work.id]
                if (preview != null) Image(preview, "${work.title}的页面预览", Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                else Icon(work.icon, null, Modifier.size(48.dp).align(Alignment.Center), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable private fun DemoTabCounter(count: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).semantics { contentDescription = "标签页，$count 个" }) {
        Box(Modifier.size(24.dp).border(2.dp, LocalContentColor.current, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            Text(if (count > 99) "99+" else count.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun DemoFiles(state: DemoState, wide: Boolean, modifier: Modifier) {
    Column(modifier.fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.selection.isNotEmpty() || state.selecting) {
                DemoIcon(Icons.Outlined.Close, "取消选择") { state.selection = emptySet(); state.selecting = false }
                Text("已选 ${state.selection.size} 项", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                DemoIcon(Icons.AutoMirrored.Outlined.DriveFileMove, "移动") {}
                DemoIcon(Icons.Outlined.ContentCopy, "复制") {}
                DemoIcon(Icons.Outlined.MoreHoriz, "所选项目操作") {}
            } else {
                TextButton(onClick = {}) { Text("名称"); Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(16.dp)) }
                Spacer(Modifier.weight(1f))
                DemoIcon(Icons.Outlined.Checklist, "选择文件") { state.selecting = true }
                DemoIcon(Icons.Outlined.ViewModule, "切换视图") {}
                DemoIcon(Icons.Outlined.SmartDisplay, "信息流") { if (wide) state.feed = !state.feed else state.fullFeed = true }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            demoFiles.forEachIndexed { i, (name, detail) ->
                val selected = i in state.selection
                val hovered = state.hover == i
                val color = when { selected -> MaterialTheme.colorScheme.secondaryContainer; hovered -> MaterialTheme.colorScheme.surfaceContainerHigh; else -> MaterialTheme.colorScheme.surfaceContainerLow }
                Row(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(16.dp))
                    .background(color)
                    .then(if (state.focus == i) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier)
                    .onPointerEvent(PointerEventType.Enter) { state.hover = i }
                    .onPointerEvent(PointerEventType.Exit) { if (state.hover == i) state.hover = -1 }
                    .onPointerEvent(PointerEventType.Press) { state.pointer = it.changes.first().type }
                    .combinedClickable(onClick = { if (state.selecting) state.toggle(i) else if (state.pointer == PointerType.Mouse) { state.selection = setOf(i); state.focus = i } else state.active = 1 }, onLongClick = { state.selecting = true; state.toggle(i) }, onDoubleClick = { state.active = 1 })
                    .padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.selecting || selected) Checkbox(selected, { state.toggle(i) }, Modifier.size(44.dp))
                    else Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(if (i < 2) Icons.Outlined.Folder else if (i < 5) Icons.Outlined.Movie else Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.primary) }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    }
                    Box {
                        DemoIcon(Icons.Outlined.MoreVert, "$name 的操作") { state.menu = i }
                        DropdownMenu(expanded = state.menu == i, onDismissRequest = { state.menu = -1 }) {
                            listOf("打开", "在新标签页打开", "移动到…", "属性").forEach { action -> DropdownMenuItem(text = { Text(action) }, onClick = { state.menu = -1 }) }
                        }
                    }
                }
            }
            Text("8 个项目", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.selection.isEmpty() && !state.selecting) Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(onClick = { state.activate(2) }, modifier = Modifier.height(52.dp)) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("添加链接") }
        }
    }
}

@Composable private fun DemoFeed(state: DemoState, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(Modifier.padding(start = 16.dp, end = 4.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("信息流", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (!state.fullFeed) DemoIcon(Icons.Outlined.OpenInFull, "全屏观看") { state.fullFeed = true }
                DemoIcon(Icons.Outlined.Close, "关闭信息流") { state.feed = false; state.fullFeed = false }
            }
            Box(Modifier.weight(1f).padding(horizontal = 12.dp).clip(RoundedCornerShape(20.dp))) {
                LandscapeArtwork(Modifier.fillMaxSize())
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC102B31)))).padding(20.dp)) {
                    Text("山野之间", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    Text("清晨的山谷 · 00:42 / 03:18", color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(20.dp))
                    LinearProgressIndicator(progress = { .22f }, modifier = Modifier.fillMaxWidth(), color = Color(0xFFB8EBDA), trackColor = Color.White.copy(alpha = .3f))
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                DemoIcon(Icons.Outlined.FavoriteBorder, "收藏") {}
                FilledIconButton(onClick = { state.playing = !state.playing }, modifier = Modifier.size(56.dp)) { Icon(if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, "播放或暂停") }
                DemoIcon(Icons.Outlined.SkipNext, "下一段") {}
                DemoIcon(Icons.Outlined.MyLocation, "在文件中定位") { state.focus = 2; state.playing = false }
            }
        }
    }
}

@Composable internal fun LandscapeArtwork(modifier: Modifier) {
    Canvas(modifier.background(Brush.verticalGradient(listOf(Color(0xFFBCD9CF), Color(0xFF578B83), Color(0xFF173B43))))) {
        drawCircle(Color(0xFFF7E6BA), radius = size.width * .13f, center = Offset(size.width * .73f, size.height * .2f))
        listOf(.44f to Color(0xFF7AA79B), .61f to Color(0xFF477F79), .79f to Color(0xFF245C60)).forEach { (y, color) ->
            val path = Path().apply {
                moveTo(0f, size.height * y)
                cubicTo(size.width * .24f, size.height * (y - .28f), size.width * .32f, size.height * (y + .15f), size.width * .6f, size.height * (y - .06f))
                cubicTo(size.width * .8f, size.height * (y - .25f), size.width, size.height * (y - .12f), size.width, size.height * y)
                lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            drawPath(path, color)
        }
    }
}

@Composable private fun DemoImport(wide: Boolean) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("选择要保存的内容", style = MaterialTheme.typography.headlineSmall)
        Text("山间的一年 · 6 个视频", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
        if (wide) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ImportFiles(Modifier.weight(1f)); ImportTarget(Modifier.width(320.dp))
        } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ImportTarget(Modifier.fillMaxWidth()); ImportFiles(Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("6 项 · 2.4 GB", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Button(onClick = {}, modifier = Modifier.height(52.dp)) { Icon(Icons.Outlined.SaveAlt, null); Spacer(Modifier.width(8.dp)); Text("保存到网盘") }
        }
    }
}

@Composable private fun ImportTarget(modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(20.dp)) {
            Text("保存位置", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, null); Text("旅行影像", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
            }
            Text("我的文件 / 影像", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            TextButton(onClick = {}, contentPadding = PaddingValues(0.dp)) { Text("更改位置") }
        }
    }
}

@Composable private fun ImportFiles(modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("01 春 · 山谷的第一束光", "02 夏 · 林间的雨", "03 秋 · 沿着溪流", "04 冬 · 风雪中的小屋", "05 清晨的鸟鸣", "06 回到山里").forEach { title ->
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(true, {}); Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyMedium); Text("MP4 · 约 400 MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable private fun DemoReport() {
    Column(Modifier.padding(24.dp)) {
        Text("发现 8 组重复文件", style = MaterialTheme.typography.headlineSmall)
        Text("可释放约 3.6 GB", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 12.dp))
        FilledTonalButton(onClick = {}) { Text("查看重复组") }
    }
}

@Composable private fun DemoIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { Icon(icon, label, Modifier.size(22.dp)) }
}
