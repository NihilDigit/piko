package dev.piko.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidHwdecFallbackTest {
    @Test
    fun legacyAviContainersProactivelyUseSoftwareDecodeWhileModernVideosKeepHwdec() {
        val state = AndroidHwdecFallbackState(userHardwareDecoding = true)

        assertEquals("no", state.onOpen("http://127.0.0.1:45678/abc123/media.avi"))
        assertTrue(state.forcedSoftwareForCurrentFile)
        assertEquals("legacy_container:.avi", state.proactiveReason)
        assertEquals("no", state.effectiveHwdec())

        // Opening a subsequent modern MP4/MKV restores hardware decode cleanly.
        assertEquals(ANDROID_HWDEC_VALUE, state.onOpen("http://127.0.0.1:45678/def456/media.mp4"))
        assertFalse(state.forcedSoftwareForCurrentFile)
        assertNull(state.proactiveReason)
        assertEquals(ANDROID_HWDEC_VALUE, state.effectiveHwdec())

        // Query strings and uppercase extensions are handled properly.
        assertEquals("no", state.onOpen("https://cdn.example.com/old_movie.AVI?token=xyz#t=10"))
        assertEquals("no", state.onOpen("/storage/emulated/0/Movies/clip.RMVB"))
        assertEquals(ANDROID_HWDEC_VALUE, state.onOpen("/storage/emulated/0/Movies/anime.mkv"))
    }

    @Test
    fun userExplicitSoftwareDecodePreferenceIsAlwaysRespected() {
        val state = AndroidHwdecFallbackState(userHardwareDecoding = false)

        assertEquals("no", state.onOpen("http://127.0.0.1:45678/tok/media.mp4"))
        assertTrue(state.forcedSoftwareForCurrentFile)
        assertEquals("no", state.effectiveHwdec())
        assertFalse(state.shouldRetryWithSoftwareOnEndFileError(headless = false, audioOnly = false))
    }

    @Test
    fun reactiveFallbackRetriesOnceOnMediaCodecFailureAndNeverInfiniteLoops() {
        val state = AndroidHwdecFallbackState(userHardwareDecoding = true)

        // Extensionless content:// URI starts with hardware decode.
        assertEquals(ANDROID_HWDEC_VALUE, state.onOpen("content://com.android.externalstorage.documents/document/42"))
        assertFalse(state.forcedSoftwareForCurrentFile)

        // MediaCodec error reported before ready -> retries once with hwdec=no.
        state.onLogMessage("ffmpeg", isWarningOrError = true, text = "mediacodec: Failed to initialize decoder")
        assertTrue(state.shouldRetryWithSoftwareOnEndFileError(headless = false, audioOnly = false))
        assertTrue(state.forcedSoftwareForCurrentFile)
        assertEquals("no", state.effectiveHwdec())

        // If software decode also fails, must NOT retry again.
        assertFalse(state.shouldRetryWithSoftwareOnEndFileError(headless = false, audioOnly = false))
    }

    @Test
    fun legacyCodecDetectedAtFileLoadedSwitchesToSoftwareDecodeAndPreservesAcrossSurfaceReattach() {
        val state = AndroidHwdecFallbackState(userHardwareDecoding = true)
        assertEquals(ANDROID_HWDEC_VALUE, state.onOpen("http://127.0.0.1:1234/tok/media.mkv"))

        // H.264 / HEVC / AV1 / VP9 stay on MediaCodec hardware decode.
        assertFalse(state.shouldSwitchToSoftwareOnCodecLoaded("h264", "mediacodec"))
        assertFalse(state.shouldSwitchToSoftwareOnCodecLoaded("hevc", "mediacodec"))
        assertFalse(state.shouldSwitchToSoftwareOnCodecLoaded("av1", "mediacodec-copy"))
        assertFalse(state.shouldSwitchToSoftwareOnCodecLoaded("vp9", "mediacodec"))
        assertEquals(ANDROID_HWDEC_VALUE, state.effectiveHwdec())

        // Legacy MPEG-4 ASP / DivX / XviD / MSMPEG4v3 triggers immediate software decode switch.
        assertTrue(state.shouldSwitchToSoftwareOnCodecLoaded("mpeg4", "mediacodec"))
        assertEquals("no", state.effectiveHwdec())
    }

    @Test
    fun networkOrDemuxFailureBeforeFileLoadedDoesNotTriggerFalseSoftwareRetry() {
        val state = AndroidHwdecFallbackState(userHardwareDecoding = true)
        assertEquals(ANDROID_HWDEC_VALUE, state.onOpen("http://127.0.0.1:1234/tok/media.mp4"))
        state.onLogMessage("ffmpeg", isWarningOrError = true, text = "http: HTTP error 404 Not Found")

        // Pure HTTP/demux failure before FILE_LOADED without hwdec error must NOT retry with hwdec=no.
        assertFalse(state.shouldRetryWithSoftwareOnEndFileError(headless = false, audioOnly = false))
        assertFalse(state.forcedSoftwareForCurrentFile)
        assertEquals(ANDROID_HWDEC_VALUE, state.effectiveHwdec())
    }

    @Test
    fun silentDecoderFailureAfterFileLoadedBeforeReadyTriggersOneShotSoftwareRetry() {
        val state = AndroidHwdecFallbackState(userHardwareDecoding = true)
        assertEquals(ANDROID_HWDEC_VALUE, state.onOpen("content://com.android.externalstorage.documents/document/99"))
        state.onFileLoaded()

        // Demux succeeded (FILE_LOADED) but decoder failed before first frame (PLAYBACK_RESTART).
        assertTrue(state.shouldRetryWithSoftwareOnEndFileError(headless = false, audioOnly = false))
        assertTrue(state.forcedSoftwareForCurrentFile)
        assertEquals("no", state.effectiveHwdec())
        assertFalse(state.shouldRetryWithSoftwareOnEndFileError(headless = false, audioOnly = false))
    }

    @Test
    fun midPlaybackNetworkErrorWithoutHwdecErrorDoesNotTriggerSpuriousSoftwareRetry() {
        val state = AndroidHwdecFallbackState(userHardwareDecoding = true)
        assertEquals(ANDROID_HWDEC_VALUE, state.onOpen("http://127.0.0.1:1234/tok/media.mp4"))
        state.onFileLoaded()
        state.onPlaybackReady()
        state.onLogMessage("ffmpeg", isWarningOrError = true, text = "tcp: Connection reset by peer")

        assertFalse(state.shouldRetryWithSoftwareOnEndFileError(headless = false, audioOnly = false))
    }

    @Test
    fun androidHwdecCodecsIncludeModernCodecsAndExcludeLegacyMpeg4() {
        val codecs = ANDROID_HWDEC_CODECS.split(',').toSet()
        assertTrue("h264" in codecs)
        assertTrue("hevc" in codecs)
        assertTrue("vp8" in codecs)
        assertTrue("vp9" in codecs)
        assertTrue("av1" in codecs)
        assertFalse("mpeg4" in codecs)
    }
}
