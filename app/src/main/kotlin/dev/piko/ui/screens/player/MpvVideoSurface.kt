package dev.piko.ui.screens.player

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 把 mpv 的画面挂到一个 Android 视图上。
 *
 * 全屏播放用 SurfaceView，合成开销最小。放在可滚动、带圆角的底部面板里的小预览用
 * TextureView：SurfaceView 是在窗口上挖洞，不跟随父布局的裁剪与位移动画。
 */
@Composable
internal fun MpvVideoSurface(
    backend: MpvPlaybackBackend,
    modifier: Modifier = Modifier,
    useTextureView: Boolean = false,
) {
    if (useTextureView) {
        AndroidView(
            factory = { context -> TextureView(context).apply { surfaceTextureListener = TextureBridge(backend) } },
            modifier = modifier,
        )
    } else {
        AndroidView(
            factory = { context ->
                val cover = View(context).apply {
                    setBackgroundColor(Color.BLACK)
                    visibility = View.INVISIBLE
                }
                val surfaceView = SurfaceView(context).apply { holder.addCallback(SurfaceBridge(backend, cover)) }
                FrameLayout(context).apply {
                    addView(surfaceView, MATCH_PARENT, MATCH_PARENT)
                    addView(cover, MATCH_PARENT, MATCH_PARENT)
                }
            },
            modifier = modifier,
        )
    }
}

/**
 * 尺寸变化时盖住画面，直到 mpv 按新尺寸交出一帧：在此之前屏幕上是按旧尺寸画的 buffer，会被非等比拉伸，
 * 见 SurfaceRedrawGate。盖板是窗口里的一个普通 View，与 SurfaceView 的新几何同一帧上屏。
 *
 * surfaceChanged 在布局过程中回调，盖板只切 VISIBLE 与 INVISIBLE：改成 GONE 会请求重新布局，要到下一帧才画出来。
 */
private class SurfaceBridge(private val backend: MpvPlaybackBackend, private val cover: View) : SurfaceHolder.Callback {
    private var surface: Surface? = null
    private var width = 0
    private var height = 0

    // 连着改了两次尺寸时，只有最后一次的帧能揭开盖板
    private var resizes = 0

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        // 新建的 Surface 里没有旧画面，第一次 surfaceChanged 不必盖
        width = 0
        height = 0
        backend.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val resized = this.width != 0 && (width != this.width || height != this.height)
        this.width = width
        this.height = height
        backend.setSurfaceSize(width, height)
        if (!resized) return
        val resize = ++resizes
        cover.visibility = View.VISIBLE
        backend.awaitFrameAtNewSize { if (resize == resizes) cover.visibility = View.INVISIBLE }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface?.let { backend.detachSurface(it) }
        surface = null
    }
}

private class TextureBridge(private val backend: MpvPlaybackBackend) : TextureView.SurfaceTextureListener {
    private var surface: Surface? = null

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val created = Surface(texture)
        surface = created
        backend.attachSurface(created)
        backend.setSurfaceSize(width, height)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) =
        backend.setSurfaceSize(width, height)

    // 返回 false 是由我们来释放：mpv 在它自己的线程上放开画面之后才释放，界面线程不等它，
    // 信息流每翻一页都有一个预览的画面拆掉重建，在主线程上等要一两百毫秒（2026-09-28）
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val released = surface
        surface = null
        if (released == null) {
            texture.release()
            return false
        }
        backend.detachSurface(released) {
            released.release()
            texture.release()
        }
        return false
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}
