package com.dreamcast.nativeapp

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import com.dreamcast.nativeapp.data.VideoStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// ExoPlaybackException builds its message with TextUtils, so the tests need Robolectric.
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], manifest = Config.NONE)
class PlayerRecoveryTest {
    private val hls = VideoStream("https://example.test/a.m3u8", "hls", 1080)
    private val dash = VideoStream("https://example.test/a.mpd", "dash", 1080)

    @Test
    fun `dav1d fallback wins over every other recovery`() {
        assertEquals(
            PlaybackRecovery.Dav1dFallback,
            planRecovery(true, true, hls, listOf(hls, dash), setOf(hls.url), "ERROR"),
        )
    }

    @Test
    fun `first failure reloads the page`() {
        assertEquals(
            PlaybackRecovery.ReloadPage,
            planRecovery(false, true, hls, listOf(hls, dash), setOf(hls.url), "ERROR"),
        )
    }

    @Test
    fun `later failures try a stream that has not failed, then give up`() {
        assertEquals(
            PlaybackRecovery.OtherStream(dash),
            planRecovery(false, false, hls, listOf(hls, dash), setOf(hls.url), "ERROR"),
        )
        assertEquals(
            PlaybackRecovery.Fail("Не удалось воспроизвести DASH: ERROR_CODE_IO"),
            planRecovery(
                false,
                false,
                dash,
                listOf(hls, dash),
                setOf(hls.url, dash.url),
                "ERROR_CODE_IO",
            ),
        )
    }

    @Test
    fun `only video renderer failures count as decoder errors`() {
        val video = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).build()
        val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        fun renderer(name: String, format: Format) =
            ExoPlaybackException.createForRenderer(
                RuntimeException(),
                name,
                0,
                format,
                C_FORMAT_HANDLED,
                false,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
            )
        assertTrue(isVideoRendererError(renderer("MediaCodecRenderer", video)))
        assertTrue(isVideoRendererError(renderer("MediaCodecVideoRenderer", audio)))
        assertFalse(isVideoRendererError(renderer("MediaCodecAudioRenderer", audio)))
        assertFalse(
            isVideoRendererError(
                PlaybackException("io", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            )
        )
    }

    @Test
    fun `software downgrade picks the next lower dav1d height`() {
        val formats = listOf(1080, 720, 540, 360).map { av1(it) } + h264(480)
        val dav1d = { f: Format -> f.sampleMimeType == MimeTypes.VIDEO_AV1 }
        assertEquals(540, lowerSoftwareHeight(formats, 720, 720, dav1d))
        assertEquals(360, lowerSoftwareHeight(formats, 1080, 540, dav1d))
        assertNull(lowerSoftwareHeight(formats, 360, 720, dav1d))
    }

    private fun av1(height: Int) =
        Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).setHeight(height).build()

    private fun h264(height: Int) =
        Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setHeight(height).build()

    private companion object {
        const val C_FORMAT_HANDLED = androidx.media3.common.C.FORMAT_HANDLED
    }
}
