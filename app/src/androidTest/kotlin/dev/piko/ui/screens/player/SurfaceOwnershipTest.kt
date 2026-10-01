package dev.piko.ui.screens.player

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test

class SurfaceOwnershipTest {
    @Test fun destroyingAnOldViewDoesNotDetachTheReplacementSurface() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val textures = listOf(SurfaceTexture(false), SurfaceTexture(false))
        val surfaces = textures.map(::Surface)
        val backend = withContext(Dispatchers.Main) { MpvPlaybackBackend(context, headless = true) }
        try {
            withContext(Dispatchers.Main) {
                backend.attachSurface(surfaces[0])
                backend.attachSurface(surfaces[1])
                backend.detachSurface(surfaces[0])
                backend.setSurfaceSize(640, 360)
            }
            withTimeout(5_000) {
                while (backend.readStringProperty("android-surface-size") != "640x360") delay(50)
            }
            withContext(Dispatchers.Main) { backend.detachSurface(surfaces[1]) }
        } finally {
            backend.release()
            surfaces.forEach { it.release() }
            textures.forEach { it.release() }
        }
    }
}
