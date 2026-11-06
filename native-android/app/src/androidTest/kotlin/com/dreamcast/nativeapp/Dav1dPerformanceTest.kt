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
import org.junit.Assert.assertEquals
import android.os.SystemClock
import android.os.Process
import android.opengl.GLSurfaceView
import androidx.media3.exoplayer.video.VideoDecoderGLSurfaceView
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
        val compareQuality = args.getString("performanceCompareQuality") == "true"
        val compareScheduling = args.getString("performanceCompareScheduling") == "true"
        val compareLead = args.getString("performanceCompareLead") == "true"
        val compareTextures = args.getString("performanceCompareTextures") == "true" || compareLead
        val warmup = compareQuality || compareScheduling || compareTextures
        data class Profile(val height: Int, val expanded: Boolean, val fast: Boolean = false, val reuseTextures: Boolean = false, val early: Boolean = false)
        val heights = args.getString("performanceHeights")?.split(',')?.map { it.toInt() }
            ?: listOf(720, 480, 480, 720, 720, 480)
        val outputSequence = if (args.getString("performanceOptimizedOnly") == "true") listOf(true) else listOf(false, true, true, false)
        val profiles = if (compareLead) outputSequence.map { Profile(720, true, reuseTextures = it, early = it) }
            else if (compareScheduling) listOf(false, true, true, false).map { Profile(720, true, it) }
            else if (compareTextures) listOf(false, true, true, false).map { Profile(720, true, reuseTextures = it) }
            else if (compareQuality) heights.map { Profile(it, true) }
            else listOf(false, true, false, true).map { Profile(720, it) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for ((sample, profile) in profiles.withIndex()) {
                    val tuned = profile.expanded
                    val layerToken = "${app.packageName}/DCTV-BENCH-${SystemClock.elapsedRealtime()}-$sample"
                    val collectPts = java.util.concurrent.atomic.AtomicBoolean(false)
                    val ptsIntervals = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger>()
                    var previousPtsUs = -1L
                    var player: ExoPlayer? = null
                    var view: PlayerView? = null
                    var glView: GLSurfaceView? = null
                    scenario.onActivity { activity ->
                        activity.window.attributes = activity.window.attributes.apply { setTitle(layerToken) }
                        val factory = object : DefaultRenderersFactory(activity) {
                            override fun buildVideoRenderers(context: Context, extensionRendererMode: Int,
                                mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
                                eventHandler: Handler, eventListener: VideoRendererEventListener,
                                allowedVideoJoiningTimeMs: Long, out: java.util.ArrayList<Renderer>) {
                                out.add(if (profile.early) EarlyDav1dVideoRenderer(allowedVideoJoiningTimeMs,
                                    eventHandler, eventListener) else if (profile.fast) object : Libdav1dVideoRenderer(allowedVideoJoiningTimeMs,
                                    eventHandler, eventListener, 50, 0, 0, 12, 12, false) {
                                    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
                                        if (state == Renderer.STATE_STARTED) 2_000 else super.getDurationToProgressUs(positionUs, elapsedRealtimeUs)
                                } else if (tuned) Libdav1dVideoRenderer(allowedVideoJoiningTimeMs,
                                    eventHandler, eventListener, 50, 0, 0, 12, 12, false)
                                else Libdav1dVideoRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener, 50))
                            }
                        }
                        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(app.repository.site.headers)
                        player = ExoPlayer.Builder(activity, factory).experimentalSetDynamicSchedulingEnabled(profile.fast).setMediaSourceFactory(
                            DefaultMediaSourceFactory(DefaultDataSource.Factory(activity, http))).build()
                        player!!.setVideoFrameMetadataListener { ptsUs, _, _, _ ->
                            if (collectPts.get()) {
                                if (previousPtsUs >= 0) ptsIntervals.computeIfAbsent(ptsUs - previousPtsUs) {
                                    java.util.concurrent.atomic.AtomicInteger()
                                }.incrementAndGet()
                                previousPtsUs = ptsUs
                            } else previousPtsUs = -1
                        }
                        if (compareTextures) {
                            glView = if (profile.reuseTextures) ReusableTextureVideoView(activity)
                                else VideoDecoderGLSurfaceView(activity)
                            glView!!.keepScreenOn = true
                            activity.setContentView(glView)
                            glView!!.onResume()
                            player!!.setVideoSurfaceView(glView)
                        } else {
                            view = activity.layoutInflater.inflate(com.dreamcast.nativeapp.R.layout.dav1d_player, null) as PlayerView
                            view!!.keepScreenOn = true
                            activity.setContentView(view)
                            view!!.onResume()
                            view!!.player = player
                        }
                        player!!.trackSelectionParameters = player!!.trackSelectionParameters.buildUpon()
                            .setMinVideoSize(1, profile.height).setMaxVideoSize(Int.MAX_VALUE, profile.height).build()
                        player!!.setMediaItem(MediaItem.fromUri(stream.url), if (warmup) 120_000 else 130_000)
                        player!!.prepare()
                        player!!.play()
                    }
                    try {
                        var started = false
                        repeat(400) {
                            if (!started) {
                                scenario.onActivity { started = player!!.isPlaying && player!!.videoFormat != null &&
                                    (!warmup || player!!.currentPosition >= 130_000) }
                                if (!started) Thread.sleep(100)
                            }
                        }
                        assertTrue("Playback did not start", started)
                        var initialRendered = 0
                        var initialDropped = 0
                        var initialSkipped = 0
                        var initialDuplicates = 0
                        var initialReplaced = 0
                        var initialTimestampFailures = 0
                        scenario.onActivity {
                            assertEquals("Requested rendition was not selected", profile.height, player!!.videoFormat!!.height)
                            val c = player!!.videoDecoderCounters!!
                            c.ensureUpdated()
                            initialRendered = c.renderedOutputBufferCount
                            initialDropped = c.droppedBufferCount
                            initialSkipped = c.skippedOutputBufferCount
                        }
                        shell("dumpsys SurfaceFlinger --timestats -clear")
                        shell("dumpsys SurfaceFlinger --timestats -enable")
                        (glView as? ReusableTextureVideoView)?.let {
                            initialDuplicates = it.duplicateDraws
                            initialReplaced = it.replacedFrames
                            initialTimestampFailures = it.timestampFailures
                        }
                        val cpuStart = Process.getElapsedCpuTime()
                        val timeStart = SystemClock.elapsedRealtime()
                        collectPts.set(true)
                        Thread.sleep(30_000)
                        collectPts.set(false)
                        val elapsedMs = SystemClock.elapsedRealtime() - timeStart
                        val cpuMs = Process.getElapsedCpuTime() - cpuStart
                        (glView as? ReusableTextureVideoView)?.let {
                            Log.i("DCTV_BENCH", "duplicateDraws=${it.duplicateDraws - initialDuplicates} replacedFrames=${it.replacedFrames - initialReplaced} timestampFailures=${it.timestampFailures - initialTimestampFailures}")
                        }
                        val pts = ptsIntervals.toSortedMap().entries.joinToString(" ") { "${it.key}us=${it.value.get()}" }
                        Log.i("DCTV_BENCH", "sample=$sample early=${profile.early} fast=${profile.fast} reuse=${profile.reuseTextures} height=${profile.height} ptsIntervals=$pts")
                        val stats = shell("dumpsys SurfaceFlinger --timestats -dump")
                        val layer = stats.split("layerName = ").singleOrNull {
                            it.startsWith("SurfaceView") && it.substringBefore('\n').contains(layerToken)
                        }.orEmpty()
                        val histogram = layer.substringAfter("present2present histogram is as below:", "")
                            .lineSequence().firstOrNull { it.contains("20ms=") }.orEmpty()
                        assertTrue("Surface presentation histogram missing", histogram.isNotBlank())
                        val bins = Regex("""(\d+)ms=(\d+)""").findAll(histogram)
                            .associate { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                        val intervals = bins.filterValues { it > 0 }.entries.joinToString(" ") { "${it.key}ms=${it.value}" }
                        val short = bins.filterKeys { it <= 25 }.values.sum()
                        val long = bins.filterKeys { it >= 75 }.values.sum()
                        Log.i("DCTV_BENCH", "sample=$sample early=${profile.early} fast=${profile.fast} reuse=${profile.reuseTextures} height=${profile.height} queuesExpanded=$tuned intervals=$intervals")
                        scenario.onActivity {
                            val p = player!!
                            val c = p.videoDecoderCounters!!
                            c.ensureUpdated()
                            val f = p.videoFormat!!
                            assertEquals("Rendition changed during measurement", profile.height, f.height)
                            Log.i("DCTV_BENCH", "sample=$sample early=${profile.early} fast=${profile.fast} reuse=${profile.reuseTextures} height=${profile.height} format=${f.width}x${f.height} fps=${f.frameRate} rendered=${c.renderedOutputBufferCount - initialRendered} dropped=${c.droppedBufferCount - initialDropped} skipped=${c.skippedOutputBufferCount - initialSkipped} shortIntervals=$short longIntervals=$long cpuMs=$cpuMs elapsedMs=$elapsedMs bufferedMs=${p.totalBufferedDuration} position=${p.currentPosition}")
                            assertTrue("Playback failed", p.playerError == null)
                        }
                    } finally {
                        scenario.onActivity {
                            if (glView != null) {
                                player!!.clearVideoSurfaceView(glView)
                                glView!!.onPause()
                            } else {
                                view!!.player = null
                                view!!.onPause()
                            }
                            player!!.release()
                        }
                    }
                }
            }
        } finally {
            shell("dumpsys SurfaceFlinger --timestats -disable")
        }
    }

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText()
        }
}
