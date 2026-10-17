package dev.piko.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class ImportSheetLevel { Collapsed, Half, Expanded }

internal class AddLinkPrototypeState {
    var sheet by mutableStateOf(ImportSheetLevel.Half)
    var previewIndex by mutableIntStateOf(0)
    var selected by mutableStateOf((0..5).toSet())
    var playing by mutableStateOf(false)
    var keepNames by mutableStateOf(true)
    var referenceOnly by mutableStateOf(false)
    var query by mutableStateOf("")
    var excludedOnly by mutableStateOf(false)
    var mediaPreview by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    val expanded = mutableStateMapOf("season" to true, "extras" to true, "subtitles" to true)
    fun toggle(index: Int) { selected = if (index in selected) selected - index else selected + index }
}

private val importTitles = listOf("春 · 山谷的第一束光", "夏 · 林间的雨", "秋 · 沿着溪流", "冬 · 风雪中的小屋", "清晨的鸟鸣", "回到山里")

@Composable internal fun AddLinkPrototype(state: AddLinkPrototypeState, wide: Boolean) {
    if (wide) Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        ImportPreview(state, Modifier.weight(1f).fillMaxHeight(), compact = false)
        ImportManagement(state, Modifier.width(360.dp).fillMaxHeight(), sheet = false)
    } else BoxWithConstraints(Modifier.fillMaxSize()) {
        ImportPreview(state, Modifier.fillMaxSize().padding(horizontal = 16.dp), compact = true)
        val height = when (state.sheet) {
            ImportSheetLevel.Collapsed -> 88.dp
            ImportSheetLevel.Half -> (maxHeight * .53f).coerceAtLeast(300.dp)
            ImportSheetLevel.Expanded -> maxHeight - 8.dp
        }
        ImportManagement(state, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height), sheet = true)
    }
}

@Composable private fun ImportPreview(state: AddLinkPrototypeState, modifier: Modifier, compact: Boolean) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("内容预览", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("${state.previewIndex + 1} / 6", style = MaterialTheme.typography.labelLarge)
        }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(24.dp))) {
            LandscapeArtwork(Modifier.fillMaxSize())
            Surface(Modifier.align(Alignment.TopStart).padding(16.dp), shape = RoundedCornerShape(8.dp), color = Color(0x99152D31)) {
                Text("4K · 03:18", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
            FilledIconButton(onClick = { state.playing = !state.playing }, modifier = Modifier.align(Alignment.Center).size(64.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xDFFFFFFF), contentColor = Color(0xFF234D51))) {
                Icon(if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, "播放预览", Modifier.size(32.dp))
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x77152D31)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("00:42 / 03:18", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, "音量", Modifier.size(20.dp), tint = Color.White)
                    Spacer(Modifier.width(16.dp)); Icon(Icons.Outlined.OpenInFull, "全屏", Modifier.size(20.dp), tint = Color.White)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { .22f }, modifier = Modifier.fillMaxWidth(), color = Color(0xFFD3F2DC), trackColor = Color.White.copy(alpha = .3f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(importTitles[state.previewIndex], style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("MP4 · 3840 × 2160 · 400 MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            ImportIcon(Icons.Outlined.SkipPrevious, "预览上一个") { state.previewIndex = (state.previewIndex + 5) % 6 }
            ImportIcon(Icons.Outlined.SkipNext, "预览下一个") { state.previewIndex = (state.previewIndex + 1) % 6 }
        }
        if (!compact) {
            Spacer(Modifier.height(28.dp))
            Text("山间的一年", style = MaterialTheme.typography.titleMedium)
            Text("6 段影像，记录四季的山林与日常。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable private fun ImportManagement(state: AddLinkPrototypeState, modifier: Modifier, sheet: Boolean) {
    val collapsed = sheet && state.sheet == ImportSheetLevel.Collapsed
    val shape = if (sheet) RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) else RoundedCornerShape(24.dp)
    Surface(modifier, shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = if (sheet) 6.dp else 0.dp) {
        Column {
            if (sheet) Box(Modifier.fillMaxWidth().height(20.dp).clickable(onClickLabel = "切换管理面板高度") {
                state.sheet = if (state.sheet == ImportSheetLevel.Expanded) ImportSheetLevel.Half else ImportSheetLevel.Expanded
            }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(32.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outlineVariant))
            }
            Row(Modifier.fillMaxWidth().height(if (collapsed) 60.dp else 56.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).then(if (collapsed) Modifier.clickable(onClickLabel = "展开导入管理") { state.sheet = ImportSheetLevel.Half } else Modifier)) {
                    Text("导入管理", style = MaterialTheme.typography.titleMedium)
                    if (collapsed) Text("已选 ${state.selected.size} 项 · 保存到旅行影像", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                if (sheet) {
                    if (collapsed) ImportIcon(Icons.Outlined.KeyboardArrowUp, "展开导入管理") { state.sheet = ImportSheetLevel.Half }
                    else {
                        if (state.sheet == ImportSheetLevel.Half) ImportIcon(Icons.Outlined.OpenInFull, "完全展开导入管理") { state.sheet = ImportSheetLevel.Expanded }
                        ImportIcon(Icons.Outlined.KeyboardArrowDown, "收起导入管理") { state.sheet = ImportSheetLevel.Collapsed }
                    }
                } else ImportIcon(Icons.Outlined.MoreHoriz, "导入选项") {}
            }
            if (!collapsed) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    if (!sheet || state.sheet == ImportSheetLevel.Expanded) {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Link, null, Modifier.size(20.dp))
                                Column(Modifier.weight(1f).padding(10.dp)) {
                                    Text("magnet:?xt=urn:btih:7a9f…", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    Text("解析完成 · 6 个视频", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                ImportIcon(Icons.Outlined.Edit, "编辑链接") {}
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("保存内容", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { state.selected = if (state.selected.size == 6) emptySet() else (0..5).toSet() }) { Text(if (state.selected.size == 6) "取消全选" else "全选") }
                    }
                    importTitles.forEachIndexed { i, title ->
                        val previewing = state.previewIndex == i
                        Surface(Modifier.fillMaxWidth().padding(bottom = 4.dp), shape = RoundedCornerShape(12.dp), color = if (previewing) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                            Row(Modifier.height(58.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(i in state.selected, { state.toggle(i) }, Modifier.semantics { contentDescription = "保存$title" })
                                Column(Modifier.weight(1f)) {
                                    Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (previewing) "正在预览 · 400 MB" else "MP4 · 400 MB", style = MaterialTheme.typography.labelSmall, color = if (previewing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                ImportIcon(if (previewing) Icons.Outlined.Visibility else Icons.Outlined.PlayCircleOutline, "预览$title") { state.previewIndex = i }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("保留原文件名", style = MaterialTheme.typography.bodyMedium)
                            Text("使用链接中提供的文件名称", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(state.keepNames, { state.keepNames = it })
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Folder, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("旅行影像", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = {}) { Text("更改") }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${state.selected.size} 项 · ${state.selected.size * 400} MB", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            Button(onClick = {}, enabled = state.selected.isNotEmpty(), modifier = Modifier.height(48.dp)) {
                                Icon(Icons.Outlined.SaveAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("保存到网盘")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ImportIcon(icon: ImageVector, title: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(48.dp)) { Icon(icon, title, Modifier.size(22.dp)) }
}
