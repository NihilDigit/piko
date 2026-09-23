package dev.piko.ui.screens.player

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
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
            factory = { context -> SurfaceView(context).apply { holder.addCallback(SurfaceBridge(backend)) } },
            modifier = modifier,
        )
    }
}

private class SurfaceBridge(private val backend: MpvPlaybackBackend) : SurfaceHolder.Callback {
    override fun surfaceCreated(holder: SurfaceHolder) = backend.attachSurface(holder.surface)

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
        backend.setSurfaceSize(width, height)

    override fun surfaceDestroyed(holder: SurfaceHolder) = backend.detachSurface()
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

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        backend.detachSurface()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}
