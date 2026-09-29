package dev.piko.ui.screens.clips

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import dev.piko.shared.naming.FileKind
import dev.piko.shared.naming.parseMediaName
import dev.piko.shared.state.Clip
import dev.piko.ui.components.formatTimeMs
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.windowDragArea
import dev.piko.ui.platform.rememberCaptionSlot
import dev.piko.ui.screens.player.PlayerSeekBar
import dev.piko.ui.screens.player.handCursor
import kotlin.math.roundToInt

/**
 * 顶栏：正中是在刷的文件夹，右侧是静音、在窗口间挪动与关闭。侧栏里的信息流也用这一条，
 * 不另压一条栏名：整张卡是一块黑底的竖屏画面。文件夹名只是标明范围，不能点：范围就是打开时网盘页所在的文件夹。
 *
 * [onPopOut] 弹出到独立窗口，[onDock] 从独立窗口收回主窗口，只在桌面端、各在它该出现的形态里给出。
 *
 * 桌面上片段窗口没有标题栏，文件夹名两侧的空白兼做拖动区。拖动区盖住按钮的话它们就点不动了，
 * 所以按钮两边各登记一块，不把整条顶栏报成一块。
 */
@Composable
internal fun ClipFeedTopBar(
    title: String,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onClose: (() -> Unit)?,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onPopOut: (() -> Unit)? = null,
    onDock: (() -> Unit)? = null,
) {
    val buttonSize = if (compact) 40.dp else 48.dp
    // 在主窗口里全屏或停在右侧一栏时，这一行贴着窗口右上角，窗口按钮接在关闭后面
    val caption = rememberCaptionSlot()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(caption.modifier)
            .background(Brush.verticalGradient(TopScrim))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .padding(horizontal = if (compact) 4.dp else 8.dp, vertical = if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 侧栏里窄，文件夹名靠左、按钮靠右，才有位置写得下；全屏与独立窗口里照短视频应用居中，
        // 左侧空白兼做窗口的拖动区
        if (compact) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { ScopeTitle(title, compact) }
        } else {
            Spacer(Modifier.weight(1f).height(buttonSize).windowDragArea())
            ScopeTitle(title, compact)
        }
        Row(
            modifier = if (compact) Modifier else Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 文件夹名与按钮之间的空白也能拖窗口，与左边那段一起，整条顶栏除了按钮都是拖动区
            if (!compact) Spacer(Modifier.weight(1f).height(buttonSize).windowDragArea())
            ChromeIconButton(
                icon = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                label = if (muted) "取消静音" else "静音",
                onClick = onToggleMute,
                size = buttonSize,
                tooltip = true,
                shortcut = "M",
            )
            if (onDock != null) {
                // 独立窗口与普通播放窗口一样，右上角只有一个 ×：关窗即是收回主窗口，信息流回到侧栏接着刷。
                // 关掉信息流在主窗口里做，这里不再并排一个关闭与一个收回
                ChromeIconButton(Icons.Filled.Close, "收回到主窗口", onDock, buttonSize, tooltip = true)
            } else {
                if (onPopOut != null) ChromeIconButton(Icons.AutoMirrored.Outlined.OpenInNew, "在独立窗口播放", onPopOut, buttonSize, tooltip = true)
                if (onClose != null) ChromeIconButton(Icons.Filled.Close, "关闭信息流", onClose, buttonSize, tooltip = true)
            }
            caption.buttons?.invoke()
        }
    }
}

@Composable
private fun ScopeTitle(title: String, compact: Boolean) {
    Surface(
        shape = CircleShape,
        color = chromeContainer(),
        contentColor = Color.White,
        modifier = Modifier.widthIn(max = if (compact) 180.dp else 260.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = title,
                style = (if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
                    .copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 右侧的操作栏，自上而下：收藏、看完整、在网盘中显示。图标在上、短标签在下，照短视频应用的样子常驻。
 * 分享与下载不在这里，在完整播放器的顶栏：刷的时候只管收藏与去看，真要留下或发给别人时多半已点进来看完整了。
 */
@Composable
internal fun ClipActionRail(
    starred: Boolean,
    onToggleStar: () -> Unit,
    onPlayFull: () -> Unit,
    onLocate: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RailAction(
            icon = if (starred) Icons.Filled.Star else Icons.Outlined.StarOutline,
            label = if (starred) "已收藏" else "收藏",
            onClick = onToggleStar,
            compact = compact,
            tint = if (starred) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
        )
        RailAction(Icons.Outlined.OpenInFull, "看完整", onPlayFull, compact)
        RailAction(Icons.Outlined.FolderOpen, "在网盘中显示", onLocate, compact)
    }
}

@Composable
private fun RailAction(icon: ImageVector, label: String, onClick: () -> Unit, compact: Boolean, tint: Color = Color.Unspecified) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ChromeIconButton(
            icon = icon,
            label = label,
            onClick = onClick,
            size = if (compact) 40.dp else 48.dp,
            iconSize = if (compact) 22.dp else 26.dp,
            tint = tint,
        )
        Text(
            text = label,
            style = (if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium).withShadow(),
            color = Color.White,
            maxLines = 1,
            // 按钮已带同名的说明，读屏不必再念一遍
            modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {},
        )
    }
}

/**
 * 画面上的圆形按钮。[tooltip] 为 true 时鼠标停上去显示 [label]（带上 [shortcut]）：顶栏的几个只有图标，
 * 右侧操作栏的下面已写着字，不必再弹。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChromeIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    size: Dp,
    iconSize: Dp = 24.dp,
    tint: Color = Color.Unspecified,
    tooltip: Boolean = false,
    shortcut: String? = null,
) {
    if (tooltip) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = { PlainTooltip { Text(if (shortcut != null) "$label ($shortcut)" else label) } },
            state = rememberTooltipState(),
        ) {
            ChromeIconButton(icon, label, onClick, size, iconSize, tint)
        }
        return
    }
    FilledTonalIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = chromeContainer(), contentColor = Color.White),
        modifier = Modifier.size(size).handCursor(),
    ) {
        if (tint == Color.Unspecified) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(iconSize))
        } else {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}

/**
 * 左下的说明：所在目录、解析出的作品名与集号、片段在全片中的实际起点 [startMs]。解析不出作品名时退回原名。
 * 只按这一个名字解析，不看同目录的其他文件，网盘页按整个目录折叠出的作品名这里拿不到。
 */
@Composable
internal fun ClipCaption(clip: Clip, startMs: Long, folderName: String?, compact: Boolean, modifier: Modifier = Modifier) {
    val caption = remember(clip.name) { captionOf(clip.name) }
    val typography = MaterialTheme.typography
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!folderName.isNullOrBlank()) {
            Text(
                text = folderName,
                style = (if (compact) typography.titleSmall else typography.titleMedium).copy(fontWeight = FontWeight.Bold).withShadow(),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = caption.heading,
            style = (if (compact) typography.bodyMedium else typography.bodyLarge).withShadow(),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        caption.episode?.let {
            Text(
                text = it,
                style = (if (compact) typography.bodySmall else typography.bodyMedium).withShadow(),
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "片中 ${formatTimeMs(startMs)} 起",
            style = typography.labelMedium.copy(fontFeatureSettings = "tnum").withShadow(),
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private class Caption(val heading: String, val episode: String?)

private fun captionOf(name: String): Caption {
    // 进信息流的都是视频，没有扩展名的也照视频解析
    val parsed = parseMediaName(name, FileKind.VIDEO)
    val title = parsed.title?.takeIf { it.isNotBlank() }
    val label = parsed.label?.takeIf { it.isNotBlank() && it != title }
    // 只有纯集号的标签补成「第 N 集」；「SP 01」「OVA」这类原样显示
    val episode = label?.let { if (it == parsed.episode?.shortText) "第 $it 集" else it }
    return when {
        !parsed.recognized || title == null && episode == null -> Caption(name.substringBeforeLast('.').ifBlank { name }, null)
        title == null -> Caption(episode!!, null)
        else -> Caption(title, episode)
    }
}

/**
 * 底边的进度条，只管这一段本身，不是整条流：切片比一段长，后面那截是留的余量，拖到那里的画面放不了多久就被自动翻页带走。
 * 平时是一条细线，手指按下或鼠标悬停时变粗、出现手柄，由 PlayerSeekBar 负责。
 */
@Composable
internal fun ClipProgressBar(
    positionMillis: Long,
    lengthMillis: Long,
    bufferedPositionMillis: Long,
    onSeek: (Long) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrubbing by remember { mutableStateOf(false) }
    PlayerSeekBar(
        positionMillis = positionMillis,
        durationMillis = lengthMillis,
        bufferedPositionMillis = bufferedPositionMillis,
        onSeek = onSeek,
        // 触屏也只在按下时出现手柄：常驻的圆点在短视频的画面上太抢眼，按下即跳到该处，本来就看得出能拖
        thumbOnHoverOnly = true,
        onScrub = { target ->
            if (scrubbing != (target != null)) {
                scrubbing = target != null
                onScrubbingChange(scrubbing)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal))
            // 轨道画在触控区的正中，往下挪半个触控区，细线贴着底边；露在外面的那半截照样接得住手指
            .offset(y = 10.dp),
    )
}

/**
 * 画面没有铺满时垫在底下的背景：这一个视频的缩略图，放大、模糊再压暗，代替上下的黑边。
 *
 * 不用正在播放的画面：两端的画面表面都只能挂一个播放器，同一个播放器出不了第二份画面，
 * 再开一个播放器解同一路流又要多一倍流量。缩略图是列目录时带回来的小图，
 * 按很小的尺寸解码，放大后本来就是糊的；系统不支持模糊的旧版 Android 上靠的就是这一点。
 * 没有缩略图时（存盘恢复的段还没重新列到）用深色渐变。
 */
@Composable
internal fun ClipBackdrop(thumbnail: String?, modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(modifier.clipToBounds().background(Brush.verticalGradient(listOf(base, Color.Black)))) {
        if (!thumbnail.isNullOrEmpty()) {
            val context = LocalPlatformContext.current
            val request = remember(thumbnail) {
                ImageRequest.Builder(context)
                    .data(thumbnail)
                    .size(BACKDROP_DECODE_PX)
                    .precision(Precision.EXACT)
                    .memoryCacheKey("clip-backdrop:$thumbnail")
                    .build()
            }
            val blurred = if (LocalPikoPlatform.current.supportsBlur) {
                Modifier.blur(BackdropBlur, edgeTreatment = BlurredEdgeTreatment.Rectangle)
            } else {
                Modifier
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(blurred),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = BACKDROP_DIM)))
        }
    }
}

/** 暂停时画面正中的播放图标，继续播放时淡出。只是提示，不接点击：点画面任何一处都是切换暂停。 */
@Composable
internal fun PausedMark(compact: Boolean, modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.PlayArrow,
        contentDescription = "已暂停",
        tint = Color.White.copy(alpha = 0.85f),
        modifier = modifier.size(if (compact) 64.dp else 88.dp),
    )
}

/** 长按期间顶部的倍速提示。 */
@Composable
internal fun BoostPill(modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = chromeContainer(), contentColor = Color.White, modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.FastForward, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("2 倍速", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 双击处冒出的一颗星，放大、上浮、淡出。 */
internal class StarBurst(val id: Long, val at: Offset, val tilt: Float)

/**
 * 双击收藏的动效。用星标而不是心形：这里的收藏就是网盘的星标，右侧按钮也是星，两处要对得上。
 */
@Composable
internal fun BoxScope.StarBursts(bursts: List<StarBurst>, onFinished: (StarBurst) -> Unit) {
    val tint = MaterialTheme.colorScheme.tertiary
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    bursts.forEach { burst ->
        key(burst.id) {
            val pop = remember { Animatable(0.4f) }
            val fade = remember { Animatable(0f) }
            val finished by rememberUpdatedState(onFinished)
            LaunchedEffect(Unit) {
                pop.animateTo(1f, spatial)
                fade.animateTo(1f, tween(BURST_FADE_MS))
                finished(burst)
            }
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        val half = BurstSize.toPx()
                        IntOffset((burst.at.x - half).roundToInt(), (burst.at.y - half).roundToInt())
                    }
                    .size(BurstSize * 2)
                    .graphicsLayer {
                        val scale = pop.value * (1f + 0.3f * fade.value)
                        scaleX = scale
                        scaleY = scale
                        rotationZ = burst.tilt
                        translationY = -BurstRise.toPx() * fade.value
                        alpha = 1f - fade.value
                    },
            )
        }
    }
}

@Composable
private fun chromeContainer(): Color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = CHROME_CONTAINER_ALPHA)

/** 文字直接压在画面上，加一层淡投影，亮画面上也读得出。 */
private fun TextStyle.withShadow(): TextStyle = copy(shadow = TextShadow)

private val TextShadow = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(0f, 1f), blurRadius = 6f)

// 顶栏与底部说明区的遮罩，与播放器相同的深浅
private val TopScrim = listOf(
    Color.Black.copy(alpha = 0.55f),
    Color.Black.copy(alpha = 0.2f),
    Color.Transparent,
)
internal val BottomScrim = listOf(
    Color.Transparent,
    Color.Black.copy(alpha = 0.35f),
    Color.Black.copy(alpha = 0.7f),
)

/** 浮在画面上的按钮比播放器的更透一些：它们常驻，不能整块挡住画面。 */
private const val CHROME_CONTAINER_ALPHA = 0.45f

private const val BACKDROP_DECODE_PX = 48
private val BackdropBlur = 32.dp
private const val BACKDROP_DIM = 0.35f

private val BurstSize = 48.dp
private val BurstRise = 72.dp
private const val BURST_FADE_MS = 450
