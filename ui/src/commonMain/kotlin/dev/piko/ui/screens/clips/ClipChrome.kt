package dev.piko.ui.screens.clips

import dev.piko.shared.i18n.tr

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.SwipeVertical
import dev.piko.shared.media.player.PlayerControlId
import dev.piko.shared.media.player.PlayerFormFactorLayout
import dev.piko.shared.media.player.PlayerPanelItem
import dev.piko.ui.screens.player.LocalPlayerLayoutPrefs
import dev.piko.ui.screens.player.PlayerSystemStatusCapsule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import dev.piko.shared.naming.FileKind
import dev.piko.shared.naming.parseMediaName
import dev.piko.shared.state.Clip
import dev.piko.ui.components.formatTimeMs
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalFramelessWindow
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.resolveCoilModel
import dev.piko.ui.platform.windowDragArea
import dev.piko.ui.platform.rememberCaptionSlot
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.player_next_clip
import dev.piko.ui.resources.player_previous_clip
import dev.piko.ui.screens.player.PlayerSeekBar
import dev.piko.ui.screens.player.formatSpeedPreset
import dev.piko.ui.screens.player.handCursor
import dev.piko.ui.theme.FixedColors
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.tabular
import kotlin.math.roundToInt

/**
 * 顶栏：正中是在刷的文件夹，右侧是静音、在窗口间挪动与关闭。侧栏里的信息流也用这一条，
 * 不另压一条栏名：整张卡是一块黑底的竖屏画面。文件夹名只是标明范围，不能点：范围就是打开时网盘页所在的文件夹。
 *
 * [onPopOut] 弹出到独立窗口，[onDock] 从独立窗口收回主窗口，只在桌面端、各在它该出现的形态里给出。
 * [onHideChrome] 进入沉浸，只在翻页器出来之后给出：此前藏起控件就没有可点的画面来叫回。
 * [landscapeLocked] 为 null 时不给横屏按钮（平台转不了方向，或在侧栏里）；为 true 时也不给，× 改作回到竖屏，
 * 关闭信息流要先回到竖屏再按一次：横屏是看片的一种姿势，按 × 的人多半是想退出横屏，不是想丢掉整个队列。
 *
 * 桌面上片段窗口没有标题栏，文件夹名与按钮之间的空白兼做拖动区。拖动区盖住按钮的话它们就点不动了，
 * 所以只登记这一段，不把整条顶栏报成一块。
 */
@Composable
internal fun ClipFeedTopBar(
    title: String,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onClose: (() -> Unit)?,
    compact: Boolean,
    modifier: Modifier = Modifier,
    autoAdvance: Boolean = false,
    onToggleAutoAdvance: (() -> Unit)? = null,
    onPopOut: (() -> Unit)? = null,
    onDock: (() -> Unit)? = null,
    onToggleFullscreen: (() -> Unit)? = null,
    onHideChrome: (() -> Unit)? = null,
    landscapeLocked: Boolean? = null,
    onToggleLandscape: () -> Unit = {},
    landscapeLayout: Boolean = landscapeLocked == true,
    layout: PlayerFormFactorLayout? = null,
    paused: Boolean = false,
    onTogglePause: (() -> Unit)? = null,
) {
    val buttonSize = if (compact) 40.dp else 48.dp
    val caption = rememberCaptionSlot()
    val resolvedLayout = layout ?: LocalPlayerLayoutPrefs.current.clipsLayout(isLandscape = landscapeLayout)
    val items = resolvedLayout.topBar
    val hasSpacer = items.any { it == PlayerPanelItem.Spacer }
    val framelessWindow = LocalFramelessWindow.current
    val pinInLayout = items.any { it is PlayerPanelItem.Control && it.id == PlayerControlId.PinOnTop }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(caption.modifier)
            .background(Brush.verticalGradient(TopScrim))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .padding(horizontal = if (compact) 4.dp else 8.dp, vertical = if (compact) 4.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            when (item) {
                PlayerPanelItem.Spacer -> Spacer(
                    Modifier.weight(1f).height(buttonSize).windowDragArea(),
                )

                is PlayerPanelItem.Control -> when (item.id) {
                    PlayerControlId.Title -> {
                        ScopeTitle(title, compact, showBackground = item.showBackground)
                        if (!hasSpacer) {
                            Spacer(Modifier.weight(1f).height(buttonSize).windowDragArea())
                        }
                    }

                    PlayerControlId.SystemStatus -> if (landscapeLayout) {
                        PlayerSystemStatusCapsule(showBackground = item.showBackground)
                    }

                    PlayerControlId.Rotate -> if (landscapeLocked == false) {
                        ChromeIconButton(
                            icon = Icons.Filled.StayCurrentLandscape,
                            label = tr("横屏", "Landscape", "横向き", "가로 화면", "橫向螢幕"),
                            onClick = onToggleLandscape,
                            size = buttonSize,
                            tooltip = true,
                            showBackground = item.showBackground,
                        )
                    }

                    PlayerControlId.HideChrome -> if (onHideChrome != null) {
                        ChromeIconButton(
                            icon = Icons.Filled.VisibilityOff,
                            label = tr("隐藏控件", "Hide controls", "操作ボタンを隠す", "컨트롤 숨기기", "隱藏控制項"),
                            onClick = onHideChrome,
                            size = buttonSize,
                            tooltip = true,
                            showBackground = item.showBackground,
                        )
                    }

                    PlayerControlId.AutoAdvance -> if (onToggleAutoAdvance != null) {
                        ChromeIconButton(
                            icon = if (autoAdvance) Icons.Filled.SwipeVertical else Icons.Outlined.SwipeVertical,
                            label = if (autoAdvance) tr("关闭自动连刷", "Disable auto-advance", "自動送りをオフ", "자동 넘김 끄기", "關閉自動連刷") else tr("自动连刷", "Auto-advance", "自動送り", "자동 넘김", "自動連刷"),
                            onClick = onToggleAutoAdvance,
                            size = buttonSize,
                            selected = autoAdvance,
                            tooltip = true,
                            shortcut = "A",
                            showBackground = item.showBackground,
                        )
                    }

                    PlayerControlId.Volume -> ChromeIconButton(
                        icon = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        label = if (muted) tr("取消静音", "Unmute", "ミュートを解除", "음소거 해제", "取消靜音") else tr("静音", "Mute", "ミュート", "음소거", "靜音"),
                        onClick = onToggleMute,
                        size = buttonSize,
                        tooltip = true,
                        shortcut = "M",
                        showBackground = item.showBackground,
                    )

                    PlayerControlId.PinOnTop -> if (onDock != null && framelessWindow != null) {
                        val onTop = framelessWindow.isAlwaysOnTop
                        ChromeIconButton(
                            icon = if (onTop) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            label = if (onTop) tr("取消置顶", "Unpin", "最前面表示を解除", "고정 해제", "取消置頂") else tr("置顶", "Pin on top", "最前面に表示", "항상 위에 표시", "置頂"),
                            onClick = { framelessWindow.setAlwaysOnTop(!onTop) },
                            size = buttonSize,
                            tooltip = true,
                            showBackground = item.showBackground,
                        )
                    }

                    PlayerControlId.Fullscreen -> if (onDock == null && onToggleFullscreen != null) {
                        ChromeIconButton(
                            icon = Icons.Filled.Fullscreen,
                            label = if (compact) tr("全屏信息流", "Fullscreen feed", "フィードを全画面表示", "전체 화면 피드", "全螢幕資訊流") else tr("返回文件与信息流", "Back to files and feed", "ファイルとフィードに戻る", "파일 및 피드로 돌아가기", "返回檔案與資訊流"),
                            onClick = onToggleFullscreen,
                            size = buttonSize,
                            tooltip = true,
                            showBackground = item.showBackground,
                        )
                    }

                    PlayerControlId.PlayPause -> if (onTogglePause != null) {
                        ChromeIconButton(
                            icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            label = if (paused) tr("播放", "Play", "再生", "재생", "播放") else tr("暂停", "Pause", "一時停止", "일시 중지", "暫停"),
                            onClick = onTogglePause,
                            size = buttonSize,
                            tooltip = true,
                            shortcut = "Space",
                            showBackground = item.showBackground,
                        )
                    }

                    PlayerControlId.Back -> {
                        if (onDock != null) {
                            if (!pinInLayout && framelessWindow != null) {
                                val onTop = framelessWindow.isAlwaysOnTop
                                ChromeIconButton(
                                    icon = if (onTop) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                    label = if (onTop) tr("取消置顶", "Unpin", "最前面表示を解除", "고정 해제", "取消置頂") else tr("置顶", "Pin on top", "最前面に表示", "항상 위에 표시", "置頂"),
                                    onClick = { framelessWindow.setAlwaysOnTop(!onTop) },
                                    size = buttonSize,
                                    tooltip = true,
                                    showBackground = item.showBackground,
                                )
                            }
                            ChromeIconButton(
                                icon = Icons.Filled.Close,
                                label = tr("收回主窗口", "Dock to main window", "メインウィンドウに格納", "메인 창으로 복귀", "收回主視窗"),
                                onClick = onDock,
                                size = buttonSize,
                                tooltip = true,
                                minTouchTarget = true,
                                showBackground = item.showBackground,
                            )
                        } else {
                            if (onPopOut != null) {
                                ChromeIconButton(
                                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                                    label = tr("在独立窗口播放", "Play in separate window", "別ウィンドウで再生", "별도 창에서 재생", "在獨立視窗播放"),
                                    onClick = onPopOut,
                                    size = buttonSize,
                                    tooltip = true,
                                    showBackground = item.showBackground,
                                )
                            }
                            if (onClose != null) {
                                val label = if (landscapeLocked == true) tr("返回竖屏", "Return to portrait", "縦向きに戻る", "세로 화면으로 돌아가기", "返回直向螢幕") else tr("关闭信息流", "Close feed", "フィードを閉じる", "피드 닫기", "關閉資訊流")
                                ChromeIconButton(
                                    icon = Icons.Filled.Close,
                                    label = label,
                                    onClick = onClose,
                                    size = buttonSize,
                                    tooltip = true,
                                    minTouchTarget = true,
                                    showBackground = item.showBackground,
                                )
                            }
                        }
                    }

                    else -> Unit
                }
            }
        }
        caption.buttons?.invoke()
    }
}

@Composable
internal fun ScopeTitle(
    title: String,
    compact: Boolean,
    showBackground: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val fixed = LocalFixedColors.current
    Surface(
        shape = CircleShape,
        color = if (showBackground) chromeContainer() else Color.Transparent,
        contentColor = fixed.OnMedia,
        border = if (showBackground) BorderStroke(1.dp, fixed.HudSpecularBorder) else null,
        modifier = modifier.widthIn(max = if (compact) 180.dp else 260.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (showBackground) 14.dp else 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = title,
                style = (if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
                    .copy(fontWeight = FontWeight.SemiBold)
                    .let { if (showBackground) it else it.withShadow() },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 底部的说明与操作，属于一段：控件层换段时整块淡出淡入。
 *
 * 竖屏时说明在左下、操作栏在右侧竖排；[landscape] 时操作栏改为右下横排，
 * 操作栏按钮顺序与底色容器均遵循四态 `PlayerLayoutPrefs`（`ClipsLandscape` / `ClipsPortrait`）。
 */
@Composable
internal fun ClipControls(
    clip: Clip,
    startMs: Long,
    folderName: String?,
    starred: Boolean,
    onToggleStar: () -> Unit,
    onPlayFull: () -> Unit,
    onLocate: () -> Unit,
    compact: Boolean,
    landscape: Boolean,
    modifier: Modifier = Modifier,
    layout: PlayerFormFactorLayout? = null,
    paused: Boolean = false,
    onTogglePause: (() -> Unit)? = null,
    canPrevious: Boolean = false,
    canNext: Boolean = false,
    onPreviousClip: (() -> Unit)? = null,
    onNextClip: (() -> Unit)? = null,
    seekStepMillis: Long = 10_000L,
    onSeekByMillis: ((Long) -> Unit)? = null,
) {
    val resolvedLayout = layout ?: LocalPlayerLayoutPrefs.current.clipsLayout(isLandscape = landscape)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(
                start = if (compact) 12.dp else if (landscape) 24.dp else 16.dp,
                end = if (compact) 6.dp else if (landscape) 16.dp else 10.dp,
                bottom = if (compact) 18.dp else 24.dp,
            ),
        // 横屏时说明不铺满，余下的空白在说明与操作栏之间，操作栏贴右
        horizontalArrangement = if (landscape) Arrangement.SpaceBetween else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        ClipCaption(
            clip = clip,
            startMs = startMs,
            folderName = folderName,
            compact = compact,
            modifier = if (landscape) {
                Modifier.weight(1f, fill = false).widthIn(max = LandscapeCaptionMaxWidth).padding(end = 24.dp)
            } else {
                Modifier.weight(1f).padding(end = 12.dp)
            },
        )
        ClipActionRail(
            items = resolvedLayout.bottomBar,
            starred = starred,
            onToggleStar = onToggleStar,
            onPlayFull = onPlayFull,
            onLocate = onLocate,
            compact = compact,
            horizontal = landscape,
            paused = paused,
            onTogglePause = onTogglePause,
            canPrevious = canPrevious,
            canNext = canNext,
            onPreviousClip = onPreviousClip,
            onNextClip = onNextClip,
            seekStepMillis = seekStepMillis,
            onSeekByMillis = onSeekByMillis,
        )
    }
}

@Composable
private fun ClipActionRail(
    items: List<PlayerPanelItem>,
    starred: Boolean,
    onToggleStar: () -> Unit,
    onPlayFull: () -> Unit,
    onLocate: () -> Unit,
    compact: Boolean,
    horizontal: Boolean,
    paused: Boolean,
    onTogglePause: (() -> Unit)?,
    canPrevious: Boolean,
    canNext: Boolean,
    onPreviousClip: (() -> Unit)?,
    onNextClip: (() -> Unit)?,
    seekStepMillis: Long,
    onSeekByMillis: ((Long) -> Unit)?,
) {
    val actions: @Composable () -> Unit = {
        items.forEach { item ->
            val control = item as? PlayerPanelItem.Control ?: return@forEach
            val renderAction: @Composable (ImageVector, String, () -> Unit, Color) -> Unit = { icon, label, onClick, tint ->
                if (horizontal) {
                    // 横屏右下横排与视频播放器底栏一致：仅圆形图标 + 悬停 Tooltip，不叠放下方文字挤占标题宽度
                    ChromeIconButton(
                        icon = icon,
                        label = label,
                        onClick = onClick,
                        size = if (compact) 40.dp else 48.dp,
                        iconSize = if (compact) 22.dp else 26.dp,
                        tint = tint,
                        tooltip = true,
                        showBackground = control.showBackground,
                    )
                } else {
                    RailAction(
                        icon = icon,
                        label = label,
                        onClick = onClick,
                        compact = compact,
                        tint = tint,
                        showBackground = control.showBackground,
                    )
                }
            }
            when (control.id) {
                PlayerControlId.Star -> renderAction(
                    if (starred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    if (starred) tr("已收藏", "Favorited", "お気に入り登録済み", "즐겨찾기 추가됨", "已收藏") else tr("收藏", "Favorite", "お気に入り", "즐겨찾기", "收藏"),
                    onToggleStar,
                    if (starred) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
                )

                PlayerControlId.OpenFull -> renderAction(
                    Icons.Outlined.OpenInFull,
                    tr("播放全片", "Play full video", "本編を再生", "전체 재생", "播放全片"),
                    onPlayFull,
                    Color.Unspecified,
                )

                PlayerControlId.OpenFolder -> renderAction(
                    Icons.Outlined.FolderOpen,
                    tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"),
                    onLocate,
                    Color.Unspecified,
                )

                PlayerControlId.PlayPause -> if (onTogglePause != null) {
                    renderAction(
                        if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        if (paused) tr("播放", "Play", "再生", "재생", "播放") else tr("暂停", "Pause", "一時停止", "일시 중지", "暫停"),
                        onTogglePause,
                        Color.Unspecified,
                    )
                }

                PlayerControlId.Previous -> if (onPreviousClip != null && canPrevious) {
                    renderAction(
                        Icons.Filled.SkipPrevious,
                        stringResource(Res.string.player_previous_clip),
                        onPreviousClip,
                        Color.Unspecified,
                    )
                }

                PlayerControlId.Next -> if (onNextClip != null && canNext) {
                    renderAction(
                        Icons.Filled.SkipNext,
                        stringResource(Res.string.player_next_clip),
                        onNextClip,
                        Color.Unspecified,
                    )
                }

                PlayerControlId.SeekBackward -> if (onSeekByMillis != null) {
                    renderAction(
                        Icons.Filled.FastRewind,
                        tr("快退", "Rewind", "巻き戻し", "되감기", "倒轉"),
                        { onSeekByMillis(-seekStepMillis) },
                        Color.Unspecified,
                    )
                }

                PlayerControlId.SeekForward -> if (onSeekByMillis != null) {
                    renderAction(
                        Icons.Filled.FastForward,
                        tr("快进", "Forward", "早送り", "앞으로", "快轉"),
                        { onSeekByMillis(seekStepMillis) },
                        Color.Unspecified,
                    )
                }

                else -> Unit
            }
        }
    }
    if (horizontal) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) { actions() }
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { actions() }
    }
}

@Composable
private fun RailAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    compact: Boolean,
    tint: Color = Color.Unspecified,
    showBackground: Boolean = true,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ChromeIconButton(
            icon = icon,
            label = label,
            onClick = onClick,
            size = if (compact) 40.dp else 48.dp,
            iconSize = if (compact) 22.dp else 26.dp,
            tint = tint,
            showBackground = showBackground,
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
    selected: Boolean = false,
    tooltip: Boolean = false,
    shortcut: String? = null,
    minTouchTarget: Boolean = false,
    showBackground: Boolean = true,
) {
    if (tooltip) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = { PlainTooltip { Text(if (shortcut != null) "$label ($shortcut)" else label) } },
            state = rememberTooltipState(),
        ) {
            ChromeIconButton(icon, label, onClick, size, iconSize, tint, selected = selected, minTouchTarget = minTouchTarget, showBackground = showBackground)
        }
        return
    }
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.primary
        showBackground -> chromeContainer()
        else -> Color.Transparent
    }
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White
    FilledTonalIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = containerColor, contentColor = contentColor),
        // 占位放大到最小触控尺寸、画的仍是 size，触控范围随占位放大
        modifier = (if (minTouchTarget) Modifier.minimumInteractiveComponentSize() else Modifier).size(size).handCursor(),
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
            text = tr("自 ${formatTimeMs(startMs)} 起", "From ${formatTimeMs(startMs)}", "${formatTimeMs(startMs)} から", "${formatTimeMs(startMs)}부터", "自 ${formatTimeMs(startMs)} 起"),
            style = typography.labelMedium.tabular.withShadow(),
            color = Color.White.copy(alpha = 0.78f),
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
    val episode = label?.let { if (it == parsed.episode?.shortText) tr("第 $it 集", "Episode $it", "第 $it 話", "에피소드 $it", "第 $it 集") else it }
    return when {
        !parsed.recognized || title == null && episode == null -> Caption(name.substringBeforeLast('.').ifBlank { name }, null)
        title == null -> Caption(episode!!, null)
        else -> Caption(title, episode)
    }
}

/**
 * 桌面鼠标悬停在信息流右侧时浮现的上一条 / 下一条切换胶囊：高光描边半透明容器，
 * 仅用于非触控输入，避免在手机上与上下滑动手势重复。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClipNavButtons(
    canPrevious: Boolean,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val fixed = LocalFixedColors.current
    val buttonSize = if (compact) 36.dp else 40.dp
    Surface(
        shape = CircleShape,
        color = fixed.ScrimOnMedia,
        contentColor = fixed.OnMedia,
        border = BorderStroke(1.dp, fixed.HudSpecularBorder),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ClipNavIconButton(
                icon = Icons.Filled.KeyboardArrowUp,
                label = stringResource(Res.string.player_previous_clip),
                shortcut = "↑",
                enabled = canPrevious,
                size = buttonSize,
                onClick = onPrevious,
            )
            ClipNavIconButton(
                icon = Icons.Filled.KeyboardArrowDown,
                label = stringResource(Res.string.player_next_clip),
                shortcut = "↓",
                enabled = canNext,
                size = buttonSize,
                onClick = onNext,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipNavIconButton(
    icon: ImageVector,
    label: String,
    shortcut: String,
    enabled: Boolean,
    size: Dp,
    onClick: () -> Unit,
) {
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered && enabled) Color.White.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
    )
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Left),
        tooltip = { PlainTooltip { Text("$label ($shortcut)") } },
        state = rememberTooltipState(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(bg)
                .hoverable(hoverSource, enabled = enabled)
                .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
                .handCursor(enabled),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) LocalFixedColors.current.OnMedia else LocalFixedColors.current.OnMedia.copy(alpha = 0.35f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * 底边的进度条，只管这一段本身，不是整条流：切片比一段长，后面那截是留的余量，拖到那里的画面放不了多久就被自动翻页带走。
 * 平时是一条细线，手指按下或鼠标悬停时变粗、出现手柄，由 PlayerSeekBar 负责；高频进度读数通过 provider 推迟到绘制阶段读取。
 */
@Composable
internal fun ClipProgressBar(
    positionMillisProvider: () -> Long,
    lengthMillis: Long,
    bufferedPositionMillisProvider: () -> Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrubbing by remember { mutableStateOf(false) }
    val isTouchLike = dev.piko.ui.components.LocalPointerSource.current.isTouchLike
    PlayerSeekBar(
        positionMillisProvider = positionMillisProvider,
        durationMillis = lengthMillis,
        bufferedPositionMillisProvider = bufferedPositionMillisProvider,
        isPlaying = isPlaying,
        wavy = false,
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
            // 触屏往下挪半个触控区贴底；桌面键鼠上移避开窗口底边 6~8px 的系统拉伸边框（HTBOTTOM），避免点击被窗口缩放拦截
            .offset(y = if (isTouchLike) 10.dp else 2.dp),
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
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier
            .onSizeChanged {
                // 量化到 64px 桶，避免窗口拖拽缩放时每像素触发重组与预模糊查询
                val bucketed = IntSize(
                    ((it.width + 63) / 64 * 64).coerceAtLeast(64),
                    ((it.height + 63) / 64 * 64).coerceAtLeast(64),
                )
                if (containerSize != bucketed) containerSize = bucketed
            }
            .clipToBounds()
            .background(Brush.verticalGradient(listOf(base, Color.Black))),
    ) {
        if (!thumbnail.isNullOrEmpty()) {
            val platform = LocalPikoPlatform.current
            val resolvedModel = remember(platform, thumbnail) { platform.resolveCoilModel(thumbnail) }
            val targetBlurPx = with(LocalDensity.current) { BackdropBlur.toPx() }
            val cw = containerSize.width.takeIf { it > 0 } ?: 1080
            val ch = containerSize.height.takeIf { it > 0 } ?: 1920
            val cacheKey = remember(resolvedModel, cw, ch, targetBlurPx) {
                PreblurredBackdropCache.keyOf(resolvedModel, cw, ch, targetBlurPx)
            }
            var preblurred by remember(cacheKey) { mutableStateOf(PreblurredBackdropCache.get(cacheKey)) }
            var fallbackToRealtimeBlur by remember(cacheKey) { mutableStateOf(false) }

            LaunchedEffect(cacheKey) {
                if (preblurred != null) return@LaunchedEffect
                val loaded = platform.loadPreblurredBackdrop(resolvedModel, cw, ch, targetBlurPx)
                if (loaded != null) {
                    PreblurredBackdropCache.put(cacheKey, loaded)
                    preblurred = loaded
                } else {
                    fallbackToRealtimeBlur = true
                }
            }

            val readyBitmap = preblurred
            if (readyBitmap != null) {
                Image(
                    bitmap = readyBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (fallbackToRealtimeBlur) {
                val context = LocalPlatformContext.current
                val request = remember(resolvedModel) {
                    ImageRequest.Builder(context)
                        .data(resolvedModel)
                        .size(BACKDROP_DECODE_PX)
                        .precision(Precision.EXACT)
                        .memoryCacheKey("clip-backdrop:$thumbnail")
                        .build()
                }
                val blurred = if (platform.supportsBlur) {
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
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = BACKDROP_DIM)))
        }
    }
}

/** 暂停时画面正中的播放图标，继续播放时淡出。只是提示，不接点击：点画面任何一处都是切换暂停。 */
@Composable
internal fun PausedMark(compact: Boolean, modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.PlayArrow,
        contentDescription = tr("已暂停", "Paused", "一時停止中", "일시 중지됨", "已暫停"),
        tint = Color.White.copy(alpha = 0.85f),
        modifier = modifier.size(if (compact) 64.dp else 88.dp),
    )
}

/** 长按期间顶部的倍速提示。 */
@Composable
internal fun BoostPill(speed: Float, modifier: Modifier = Modifier) {
    val fixed = LocalFixedColors.current
    Surface(
        shape = CircleShape,
        color = fixed.ScrimOnMedia,
        contentColor = fixed.OnMedia,
        border = BorderStroke(1.dp, fixed.HudSpecularBorder),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.FastForward, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                tr("${formatSpeedPreset(speed)} 倍速", "${formatSpeedPreset(speed)} speed", "${formatSpeedPreset(speed)} 倍速", "${formatSpeedPreset(speed)}배속", "${formatSpeedPreset(speed)} 倍速"),
                style = MaterialTheme.typography.labelLarge.tabular,
            )
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
internal fun chromeContainer(): Color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = CHROME_CONTAINER_ALPHA)

/** 文字直接压在画面上，加一层淡投影，亮画面上也读得出。 */
private fun TextStyle.withShadow(): TextStyle = copy(shadow = TextShadow)

private val TextShadow = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(0f, 1f), blurRadius = 6f)

// 顶栏与底部说明区的平滑渐变遮罩：底部采用四段过渡至 FixedColors.ScrimOnMediaStrong，过亮底画面仍保持高对比度
internal val TopScrim = listOf(
    FixedColors.ScrimOnMedia,
    Color.Black.copy(alpha = 0.2f),
    Color.Transparent,
)
internal val BottomScrim = listOf(
    Color.Transparent,
    Color.Black.copy(alpha = 0.16f),
    FixedColors.ScrimOnMedia,
    FixedColors.ScrimOnMediaStrong,
)

/** 浮在画面上的按钮比播放器的更透一些：它们常驻，不能整块挡住画面。 */
private const val CHROME_CONTAINER_ALPHA = 0.45f

private val LandscapeCaptionMaxWidth = 560.dp

private const val BACKDROP_DECODE_PX = 48
private val BackdropBlur = 32.dp
private const val BACKDROP_DIM = 0.35f

private val BurstSize = 48.dp
private val BurstRise = 72.dp
private const val BURST_FADE_MS = 450
