package com.dreamcast.nativeapp

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.decoder.av1.Libdav1dVideoRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.dreamcast.nativeapp.data.PlayerJs
import com.dreamcast.nativeapp.data.Release
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, real-stream comparison; never uses the user's installed app or storage. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Dav1dPerformanceTest {
    @Test fun compareDecoderQueues() {
        val args = InstrumentationRegistry.getArguments()
        val releaseId = args.getString("performanceReleaseId")?.toIntOrNull()
        assumeTrue("Requires an explicitly selected real stream", releaseId != null)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DreamApplication
        val release = Release(releaseId!!, "Performance test")
        val episodes = runBlocking { app.repository.playback(release.copy(
            url = args.getString("performanceReleaseUrl") ?: release.url)).second }
        val episode = episodes.first { it.id == args.getString("performanceEpisodeId") }
        val stream = PlayerJs.streams(episode).first { it.type == "hls" }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (tuned in listOf(false, true, false, true)) {
                var player: ExoPlayer? = null
                var view: PlayerView? = null
                scenario.onActivity { activity ->
                    val factory = object : DefaultRenderersFactory(activity) {
                        override fun buildVideoRenderers(context: Context, extensionRendererMode: Int,
                            mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
                            eventHandler: Handler, eventListener: VideoRendererEventListener,
                            allowedVideoJoiningTimeMs: Long, out: java.util.ArrayList<Renderer>) {
                            out.add(if (tuned) Libdav1dVideoRenderer(allowedVideoJoiningTimeMs,
                                eventHandler, eventListener, 50, 0, 0, 12, 12, false)
                            else Libdav1dVideoRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener, 50))
                        }
                    }
                    val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(app.repository.site.headers)
                    player = ExoPlayer.Builder(activity, factory).setMediaSourceFactory(
                        DefaultMediaSourceFactory(DefaultDataSource.Factory(activity, http))).build()
                    view = activity.layoutInflater.inflate(com.dreamcast.nativeapp.R.layout.dav1d_player, null) as PlayerView
                    view!!.keepScreenOn = true
                    activity.setContentView(view)
                    view!!.onResume()
                    view!!.player = player
                    player!!.trackSelectionParameters = player!!.trackSelectionParameters.buildUpon()
                        .setMinVideoSize(1280, 720).setMaxVideoSize(1280, 720).build()
                    player!!.setMediaItem(MediaItem.fromUri(stream.url), 130_000)
                    player!!.prepare()
                    player!!.play()
                }
                try {
                    var started = false
                    repeat(400) {
                        if (!started) {
                            scenario.onActivity { started = player!!.isPlaying && player!!.videoFormat != null }
                            if (!started) Thread.sleep(100)
                        }
                    }
                    assertTrue("Playback did not start", started)
                    Thread.sleep(30_000)
                    scenario.onActivity {
                        val p = player!!
                        val c = p.videoDecoderCounters!!
                        c.ensureUpdated()
                        val f = p.videoFormat!!
                        Log.i("DCTV_BENCH", "tuned=$tuned format=${f.width}x${f.height} fps=${f.frameRate} rendered=${c.renderedOutputBufferCount} dropped=${c.droppedBufferCount} skipped=${c.skippedOutputBufferCount} maxDrop=${c.maxConsecutiveDroppedBufferCount} bufferedMs=${p.totalBufferedDuration} position=${p.currentPosition}")
                        assertTrue("Playback failed", p.playerError == null)
                    }
                } finally {
                    scenario.onActivity { view!!.player = null; view!!.onPause(); player!!.release() }
                }
            }
        }
    }
}
