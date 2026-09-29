package dev.piko.ui.screens.drive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.trackPointerSource
import dev.piko.ui.platform.LocalPikoPlatform
import io.github.nihildigit.pikpak.FileStat
import kotlin.math.abs
import kotlinx.coroutines.launch

/** 缩放上限。再往上原图也没有更多细节，只会把平移边界拉得很难收回来。 */
private const val VIEWER_MAX_SCALE = 4f
private const val VIEWER_DOUBLE_TAP_SCALE = 2.5f
private const val WHEEL_ZOOM_STEP = 1.2f
private const val KEY_ZOOM_STEP = 1.5f

/**
 * 原图解码的最长边。Coil 默认按控件尺寸解码，放大 4 倍看到的只是放大的屏幕分辨率位图；
 * 按原图解码又可能是上亿像素。4096 覆盖常见照片放到上限时的清晰度，位图不超过 64 MB。
 */
private const val FULL_IMAGE_MAX_SIDE = 4096

/**
 * 全屏图片查看器。
 *
 * 做成对话框而不是页面内的浮层：底部导航栏挂在 PikoMainScaffold 上，DriveScreen
 * 自己的 Box 盖不住它。Android 上黑底一路铺到系统栏下面，只有顶部的文件名一行按
 * safeDrawing 内缩。单击切换顶栏与系统栏的显隐。
 *
 * 触屏左右滑翻页、捏合缩放、下滑关闭；鼠标用两侧的翻页按钮，滚轮以指针为中心缩放。
 * 键盘：←→ PageUp PageDown 空格翻页，Home End 到首尾，+ − 缩放，0 回到适应窗口，
 * Esc 由对话框自己关闭。
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
        val pointers = LocalPointerSource.current
        val pagerState = rememberPagerState(initialPage = initialIndex) { images.size }
        // 下滑关闭的进度，0 到 1。背景跟着变透明，让下面的列表透出来，表明这是退出而不是切图。
        var dismissProgress by remember { mutableFloatStateOf(0f) }
        val scope = rememberCoroutineScope()
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        // 键盘缩放作用于眼前这一页，缩放状态却在各页里；各页组合时登记进来
        val zoomStates = remember { HashMap<Int, ZoomState>() }

        // 翻过去的页若仍留在组合里，回来时应当是适应窗口的样子
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collect { settled ->
                zoomStates.forEach { (page, zoom) -> if (page != settled) zoom.reset() }
            }
        }

        fun goTo(page: Int) {
            val target = page.coerceIn(0, images.lastIndex)
            if (target != pagerState.currentPage) scope.launch { pagerState.animateScrollToPage(target) }
        }

        fun zoomCurrent(targetScale: (ZoomState) -> Float) {
            val zoom = zoomStates[pagerState.currentPage] ?: return
            scope.launch { zoom.animateZoom(anchor = Offset.Zero, targetScale = targetScale(zoom)) }
        }

        fun handleKey(event: KeyEvent): Boolean {
            if (event.type != KeyEventType.KeyDown) return false
            val current = pagerState.currentPage
            when (event.key) {
                Key.DirectionLeft, Key.PageUp -> goTo(current - 1)
                Key.DirectionRight, Key.PageDown, Key.Spacebar -> goTo(current + 1)
                Key.MoveHome -> goTo(0)
                Key.MoveEnd -> goTo(images.lastIndex)
                // 美式键盘上 + 与 = 同键，不按 Shift 也认
                Key.Plus, Key.Equals, Key.NumPadAdd -> zoomCurrent { it.scale * KEY_ZOOM_STEP }
                Key.Minus, Key.NumPadSubtract -> zoomCurrent { it.scale / KEY_ZOOM_STEP }
                Key.Zero, Key.NumPad0 -> zoomCurrent { 1f }
                else -> return false
            }
            return true
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // 对话框的指针事件不经过窗口根部，要在这里另记一次输入来源
                .trackPointerSource(pointers)
                .background(Color.Black.copy(alpha = 1f - dismissProgress * 0.55f))
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent(::handleKey),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val file = images[page]
                val zoom = remember(file.id) { ZoomState() }
                DisposableEffect(page, zoom) {
                    zoomStates[page] = zoom
                    onDispose { if (zoomStates[page] === zoom) zoomStates.remove(page) }
                }
                ZoomableImagePage(
                    file = file,
                    zoom = zoom,
                    // 鼠标上下拖动多半是想挪图，不该把查看器关掉
                    swipeToDismiss = pointers.isTouchLike,
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

            // 按输入设备而不是窗口宽度决定：窄窗口里的鼠标同样需要按钮，平板上的手指滑动即可
            if (images.size > 1 && !pointers.isTouchLike) {
                PageButton(
                    visible = isChromeVisible && pagerState.currentPage > 0,
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    description = "上一张",
                    shortcut = "←",
                    onClick = { goTo(pagerState.currentPage - 1) },
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                PageButton(
                    visible = isChromeVisible && pagerState.currentPage < images.lastIndex,
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    description = "下一张",
                    shortcut = "→",
                    onClick = { goTo(pagerState.currentPage + 1) },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageButton(
    visible: Boolean,
    icon: ImageVector,
    description: String,
    shortcut: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier.padding(16.dp)) {
        // TooltipIconButton 没有底色，叠在浅色图片上看不见，这里自带半透明黑底
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = { PlainTooltip { Text("$description ($shortcut)") } },
            state = rememberTooltipState(),
        ) {
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
            TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "关闭", onClose, shortcut = "Esc", tint = Color.White)
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
 * 一页的缩放与平移。[offset] 是图片中心相对视口中心的位移，与 graphicsLayer 的默认变换原点一致；
 * 各处的 anchor 同样相对视口中心，缩放时它下面的那一点不动。
 */
@Stable
private class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var viewport = Size.Zero

    val isZoomed: Boolean get() = scale > 1f

    // 新的动画打断上一段：连按 + 时不会有两段动画抢着写
    private val animation = MutatorMutex()

    fun anchorOf(position: Offset): Offset = position - Offset(viewport.width / 2f, viewport.height / 2f)

    fun transform(anchor: Offset, zoomChange: Float, pan: Offset = Offset.Zero) {
        val next = (scale * zoomChange).coerceIn(1f, VIEWER_MAX_SCALE)
        offset = clamp(anchor - (anchor - offset) * (next / scale) + pan, next)
        scale = next
    }

    /** 捏回或滚回接近原大就干脆回正，免得停在 1.02 倍这种既不能翻页也看不出放大的状态。 */
    fun settle() {
        if (scale < 1.05f) reset()
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    /** 已贴着左右边缘还往外拖：图没有可挪的了，这一下该是翻页。 */
    fun pushesPastHorizontalEdge(pan: Offset): Boolean {
        if (abs(pan.x) <= abs(pan.y)) return false
        val limitX = horizontalLimit(scale)
        return if (pan.x > 0f) offset.x >= limitX - 1f else offset.x <= -limitX + 1f
    }

    suspend fun animateZoom(anchor: Offset, targetScale: Float) {
        val target = targetScale.coerceIn(1f, VIEWER_MAX_SCALE)
        animateTo(target, clamp(anchor - (anchor - offset) * (target / scale), target))
    }

    private suspend fun animateTo(targetScale: Float, targetOffset: Offset) = animation.mutate {
        val fromScale = scale
        val fromOffset = offset
        animate(0f, 1f, animationSpec = tween(durationMillis = 220)) { t, _ ->
            scale = fromScale + (targetScale - fromScale) * t
            offset = lerp(fromOffset, targetOffset, t)
        }
    }

    // 平移边界按整块视口算。图片按 Fit 摆放，留黑边的那一侧其实还能再收一点，
    // 但那要等图片真实尺寸，收益只有几十像素。
    private fun horizontalLimit(atScale: Float) = viewport.width * (atScale - 1f) / 2f

    private fun clamp(value: Offset, atScale: Float): Offset {
        val limitX = horizontalLimit(atScale)
        val limitY = viewport.height * (atScale - 1f) / 2f
        return Offset(value.x.coerceIn(-limitX, limitX), value.y.coerceIn(-limitY, limitY))
    }
}

private fun PointerEvent.consumeMoves() = changes.forEach { if (it.positionChanged()) it.consume() }

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
    zoom: ZoomState,
    swipeToDismiss: Boolean,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    onDismissProgress: (Float) -> Unit,
) {
    val driveRepo = LocalPikoServices.current.driveRepository
    val scope = rememberCoroutineScope()
    val platformContext = LocalPlatformContext.current

    var fullUrl by remember(file.id) { mutableStateOf<String?>(null) }
    var isFullReady by remember(file.id) { mutableStateOf(false) }
    // 全屏查看器里不再受防窥遮蔽拦一道：点进来本身就是「我要看这张」，
    // 再要求点一次「显示图片」只是多一步。遮蔽仍然作用在列表和海报墙的缩略图上。
    LaunchedEffect(file.id) {
        if (fullUrl == null) fullUrl = driveRepo.originalImageUrl(file.id)
    }

    var dragY by remember(file.id) { mutableFloatStateOf(0f) }
    // 只在跨过 1 倍时变，缩放过程中不必每帧重启下滑手势
    val atFit by remember(zoom) { derivedStateOf { !zoom.isZoomed } }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { zoom.viewport = it.toSize() }
                .pointerInput(zoom) {
                    detectTapGestures(
                        // 单击切换界面显隐，不再关闭：放大后点一下想收起顶栏的操作太常见，
                        // 原先 1 倍时单击即关，误触就得重新找回这张图。关闭走返回、顶栏按钮与下滑
                        onTap = { onTap() },
                        onDoubleTap = { tap ->
                            scope.launch {
                                val target = if (zoom.isZoomed) 1f else VIEWER_DOUBLE_TAP_SCALE
                                zoom.animateZoom(zoom.anchorOf(tap), target)
                            }
                        },
                    )
                }
                .pointerInput(zoom) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        // 放大后单指拖动先攒够 touch slop 再定去向：贴着边缘还往外拖就整段不消费，
                        // 让 Pager 翻页；否则是平移。一开头就消费的话 Pager 的拖动检测当场放弃，放大后再也翻不了页
                        var slop = Offset.Zero
                        var isPanning = false
                        var handedToPager = false
                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            if (zoomChange != 1f) {
                                zoom.transform(zoom.anchorOf(event.calculateCentroid()), zoomChange, event.calculatePan())
                                isPanning = true
                                event.consumeMoves()
                            } else if (zoom.isZoomed && !handedToPager) {
                                val pan = event.calculatePan()
                                if (isPanning) {
                                    zoom.transform(Offset.Zero, 1f, pan)
                                    event.consumeMoves()
                                } else {
                                    slop += pan
                                    if (slop.getDistance() > viewConfiguration.touchSlop) {
                                        handedToPager = zoom.pushesPastHorizontalEdge(slop)
                                        if (!handedToPager) {
                                            isPanning = true
                                            zoom.transform(Offset.Zero, 1f, slop)
                                            event.consumeMoves()
                                        }
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        zoom.settle()
                    }
                }
                .pointerInput(zoom) {
                    // 滚轮不加修饰键即缩放，照系统看图应用：查看器里滚轮没有别的用处
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.first()
                            val delta = change.scrollDelta.y
                            if (delta == 0f) continue
                            val factor = if (delta < 0f) WHEEL_ZOOM_STEP else 1f / WHEEL_ZOOM_STEP
                            zoom.transform(zoom.anchorOf(change.position), factor)
                            zoom.settle()
                            change.consume()
                        }
                    }
                }
                .pointerInput(zoom, swipeToDismiss && atFit) {
                    if (!swipeToDismiss || !atFit) return@pointerInput
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
                    scaleX = zoom.scale * dragShrink
                    scaleY = zoom.scale * dragShrink
                    translationX = zoom.offset.x
                    translationY = zoom.offset.y + dragY
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
                val request = remember(url, platformContext) {
                    ImageRequest.Builder(platformContext)
                        .data(url)
                        .size(FULL_IMAGE_MAX_SIDE)
                        // 显式给了尺寸时默认按精确尺寸解码，小图会被放大到 4096；INEXACT 只缩不放
                        .precision(Precision.INEXACT)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { isFullReady = true },
                )
            }
        }

        // 进度指示不进变换层，否则会跟着图一起放大
        if (!isFullReady) {
            // 底下已垫着缩略图，这里等的只是清晰度，用行内一档，不用整屏首载那个
            InlineLoadingIndicator(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 24.dp),
            )
        }
    }
}
