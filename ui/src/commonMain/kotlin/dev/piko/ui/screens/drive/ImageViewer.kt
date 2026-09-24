package dev.piko.ui.screens.drive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.components.PikoLoadingIndicator
import dev.piko.ui.platform.LocalPikoPlatform
import io.github.nihildigit.pikpak.FileStat
import kotlin.math.abs
import kotlinx.coroutines.launch

/** 缩放上限。再往上原图也没有更多细节，只会把平移边界拉得很难收回来。 */
private const val VIEWER_MAX_SCALE = 4f
private const val VIEWER_DOUBLE_TAP_SCALE = 2.5f
private const val WHEEL_ZOOM_STEP = 1.2f

/**
 * 全屏图片查看器。
 *
 * 做成对话框而不是页面内的浮层：底部导航栏挂在 PikoMainScaffold 上，DriveScreen
 * 自己的 Box 盖不住它。Android 上黑底一路铺到系统栏下面，只有顶部的文件名一行按
 * safeDrawing 内缩。单击切换顶栏与系统栏的显隐。
 *
 * 桌面端没有触摸手势可用：左右方向键翻页，滚轮以指针为中心缩放，Esc 关闭；
 * 两侧另有翻页按钮给只用鼠标的人。
 */
@Composable
internal fun ImageViewer(
    images: List<FileStat>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    var isChromeVisible by remember { mutableStateOf(true) }
    LocalPikoPlatform.current.FullscreenDialog(
        onDismiss = onDismiss,
        immersive = true,
        systemBarsVisible = isChromeVisible,
    ) {
        val pagerState = rememberPagerState(initialPage = initialIndex) { images.size }
        // 下滑关闭的进度，0 到 1。背景跟着变透明，让下面的列表透出来，表明这是退出而不是切图。
        var dismissProgress by remember { mutableFloatStateOf(0f) }
        val scope = rememberCoroutineScope()
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        fun turnPage(delta: Int) {
            val target = (pagerState.currentPage + delta).coerceIn(0, images.lastIndex)
            if (target != pagerState.currentPage) scope.launch { pagerState.animateScrollToPage(target) }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 1f - dismissProgress * 0.55f))
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft, Key.PageUp -> turnPage(-1)
                        Key.DirectionRight, Key.PageDown, Key.Spacebar -> turnPage(1)
                        else -> return@onKeyEvent false
                    }
                    true
                },
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val file = images[page]
                ZoomableImagePage(
                    file = file,
                    onTap = { isChromeVisible = !isChromeVisible },
                    onDismiss = onDismiss,
                    onDismissProgress = { dismissProgress = it },
                )
            }

            AnimatedVisibility(
                visible = isChromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer { alpha = 1f - dismissProgress },
            ) {
                ViewerTopBar(
                    title = images[pagerState.currentPage].name,
                    position = if (images.size > 1) "${pagerState.currentPage + 1} / ${images.size}" else null,
                    onClose = onDismiss,
                )
            }

            // 触屏上左右滑即可翻页，按钮只在宽窗口出现，给鼠标用
            if (images.size > 1 && currentWidthClass() != WidthClass.Compact) {
                PageButton(
                    visible = isChromeVisible && pagerState.currentPage > 0,
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    description = "上一张",
                    onClick = { turnPage(-1) },
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                PageButton(
                    visible = isChromeVisible && pagerState.currentPage < images.lastIndex,
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    description = "下一张",
                    onClick = { turnPage(1) },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

@Composable
private fun PageButton(
    visible: Boolean,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier.padding(16.dp)) {
        FilledTonalIconButton(
            onClick = onClick,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.45f),
                contentColor = Color.White,
            ),
        ) {
            Icon(icon, contentDescription = description)
        }
    }
}

/**
 * 顶栏压在一段由黑到透明的渐变上。原先文字直接叠在图上，浅色图片的顶部一片白，
 * 文件名与关闭按钮都看不清。
 */
@Composable
private fun ViewerTopBar(title: String, position: String?, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "关闭", tint = Color.White)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (position != null) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = position,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/**
 * 查看器里的一页：捏合缩放、双击切换倍率、放大后拖动、1 倍时下滑关闭。
 *
 * 手势没有用 transformable + detectTransformGestures 的现成组合：它们一旦越过
 * touch slop 就把事件全部消费掉，HorizontalPager 再也收不到横向拖动，放大之后能平移，
 * 代价是 1 倍下也翻不了页。这里自己跑事件循环，只在「双指有缩放」或「已经放大」时消费，
 * 1 倍的单指拖动原样留给 Pager。
 */
@Composable
private fun ZoomableImagePage(
    file: FileStat,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    onDismissProgress: (Float) -> Unit,
) {
    val driveRepo = LocalPikoServices.current.driveRepository
    val scope = rememberCoroutineScope()

    var fullUrl by remember(file.id) { mutableStateOf<String?>(null) }
    var isFullReady by remember(file.id) { mutableStateOf(false) }
    // 全屏查看器里不再受防窥遮蔽拦一道：点进来本身就是「我要看这张」，
    // 再要求点一次「显示图片」只是多一步。遮蔽仍然作用在列表和瀑布流的缩略图上。
    LaunchedEffect(file.id) {
        if (fullUrl == null) fullUrl = driveRepo.originalImageUrl(file.id)
    }

    var scale by remember(file.id) { mutableFloatStateOf(1f) }
    var offset by remember(file.id) { mutableStateOf(Offset.Zero) }
    var dragY by remember(file.id) { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val viewWidth = with(density) { maxWidth.toPx() }
        val viewHeight = with(density) { maxHeight.toPx() }

        // 平移边界按整块视口算。图片按 Fit 摆放，留黑边的那一侧其实还能再收一点，
        // 但那要等图片真实尺寸，收益只有几十像素。
        fun clampOffset(value: Offset, atScale: Float): Offset {
            val limitX = viewWidth * (atScale - 1f) / 2f
            val limitY = viewHeight * (atScale - 1f) / 2f
            return Offset(value.x.coerceIn(-limitX, limitX), value.y.coerceIn(-limitY, limitY))
        }

        suspend fun animateTo(targetScale: Float, targetOffset: Offset) {
            val fromScale = scale
            val fromOffset = offset
            animate(0f, 1f, animationSpec = tween(durationMillis = 220)) { t, _ ->
                scale = fromScale + (targetScale - fromScale) * t
                offset = Offset(
                    fromOffset.x + (targetOffset.x - fromOffset.x) * t,
                    fromOffset.y + (targetOffset.y - fromOffset.y) * t,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(file.id) {
                    detectTapGestures(
                        // 单击切换界面显隐，不再关闭：放大后点一下想收起顶栏的操作太常见，
                        // 原先 1 倍时单击即关，误触就得重新找回这张图。关闭走返回、顶栏按钮与下滑
                        onTap = { onTap() },
                        onDoubleTap = { tap ->
                            scope.launch {
                                if (scale > 1f) {
                                    animateTo(1f, Offset.Zero)
                                } else {
                                    // 以双击点为中心放大：该点到中心的位移放大同样的倍数
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    val target = (center - tap) * (VIEWER_DOUBLE_TAP_SCALE - 1f)
                                    animateTo(
                                        VIEWER_DOUBLE_TAP_SCALE,
                                        clampOffset(target, VIEWER_DOUBLE_TAP_SCALE),
                                    )
                                }
                            }
                        },
                    )
                }
                .pointerInput(file.id) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var event: PointerEvent
                        do {
                            event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            if (zoomChange != 1f || scale > 1f) {
                                val next = (scale * zoomChange).coerceIn(1f, VIEWER_MAX_SCALE)
                                offset = clampOffset(offset + event.calculatePan(), next)
                                scale = next
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        // 捏回到接近原大就干脆回正，免得停在 1.02 倍这种既不能翻页也看不出放大的状态
                        if (scale < 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        }
                    }
                }
                .pointerInput(file.id) {
                    // 滚轮缩放，以指针所在点为中心：该点到中心的位移随倍率同比变化
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.first()
                            val delta = change.scrollDelta.y
                            if (delta == 0f) continue
                            val factor = if (delta < 0f) WHEEL_ZOOM_STEP else 1f / WHEEL_ZOOM_STEP
                            val next = (scale * factor).coerceIn(1f, VIEWER_MAX_SCALE)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val anchor = change.position - center
                            offset = clampOffset(anchor - (anchor - offset) * (next / scale), next)
                            scale = next
                            if (scale < 1.05f) {
                                scale = 1f
                                offset = Offset.Zero
                            }
                            change.consume()
                        }
                    }
                }
                .pointerInput(file.id, scale == 1f) {
                    if (scale != 1f) return@pointerInput
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (abs(dragY) > size.height * 0.16f) {
                                onDismiss()
                            } else {
                                dragY = 0f
                                onDismissProgress(0f)
                            }
                        },
                        onDragCancel = {
                            dragY = 0f
                            onDismissProgress(0f)
                        },
                    ) { _, delta ->
                        dragY += delta
                        onDismissProgress((abs(dragY) / (size.height * 0.4f)).coerceIn(0f, 1f))
                    }
                }
                .graphicsLayer {
                    // 下滑时图随之缩小，像是被收回列表里，与左右翻页的平移区分开
                    val dragShrink = 1f - (abs(dragY) / (size.height * 0.4f)).coerceIn(0f, 1f) * 0.2f
                    scaleX = scale * dragShrink
                    scaleY = scale * dragShrink
                    translationX = offset.x
                    translationY = offset.y + dragY
                },
            contentAlignment = Alignment.Center,
        ) {
            // 缩略图垫底：它多半还在 Coil 的内存缓存里，原图到位前不会先闪一片白
            AsyncImage(
                model = file.thumbnailLink,
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            fullUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { isFullReady = true },
                )
            }
        }

        // 进度指示不进变换层，否则会跟着图一起放大
        if (!isFullReady) {
            PikoLoadingIndicator(
                size = 24.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 24.dp),
            )
        }
    }
}
