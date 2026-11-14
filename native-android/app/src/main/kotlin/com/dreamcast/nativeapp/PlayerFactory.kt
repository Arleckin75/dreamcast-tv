package com.dreamcast.nativeapp

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Media source factory whose HTTP requests carry the source's [headers]. Segments load over the
 * app's shared OkHttp connections (SharedHttp), the same pool as pages, posters and previews,
 * with the timeouts DefaultHttpDataSource used (8 s).
 */
@OptIn(UnstableApi::class)
internal fun mediaSourceFactory(context: Context, headers: Map<String, String>) =
    DefaultMediaSourceFactory(
        DefaultDataSource.Factory(
            context,
            OkHttpDataSource.Factory(mediaHttpClient).setDefaultRequestProperties(headers),
        )
    )

private val mediaHttpClient by lazy {
    com.dreamcast.nativeapp.data.SharedHttp.client(connect = 8, read = 8)
}

/** ExoPlayer with the renderers for [output] that never exceeds renderer capabilities. */
@OptIn(UnstableApi::class)
internal fun createPlayer(
    context: Context,
    output: VideoOutput,
    source: DefaultMediaSourceFactory,
): ExoPlayer =
    ExoPlayer.Builder(context, UnifiedRenderersFactory(context, output))
        .setTrackSelector(
            DefaultTrackSelector(context).apply {
                setParameters(buildUponParameters().setExceedRendererCapabilitiesIfNecessary(false))
            }
        )
        .apply { if (isLowMemoryDevice(context)) setLoadControl(lowMemoryLoadControl()) }
        .setSeekBackIncrementMs(SEEK_STEP_MS)
        .setSeekForwardIncrementMs(SEEK_STEP_MS)
        .setMediaSourceFactory(source)
        .build()
        .apply { setSeekParameters(seekParametersFor(output)) }

/**
 * The dav1d software decoder seeks to the nearest keyframe. Dream Cast streams have a keyframe
 * only at the start of each 10 s segment, and an exact seek decodes everything from there to the
 * target before showing a frame: on an ARMv7 TV with dav1d that took up to 3.5 s. The nearest keyframe
 * is at most 5 s away, and seek previews are 10 s apart anyway. Hardware and system decoders
 * catch up fast, so they keep exact seeks.
 */
@OptIn(UnstableApi::class)
internal fun seekParametersFor(output: VideoOutput): SeekParameters =
    if (output == VideoOutput.DAV1D) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT

/**
 * Keyframe positions (ms) of the playing HLS stream: the start of each segment. Empty for other
 * streams. Media3 snaps an HLS seek only back to the start of the segment it lands in (the next
 * segment counts only with #EXT-X-INDEPENDENT-SEGMENTS, which Dream Cast playlists lack), so
 * the app picks the keyframe itself.
 */
@OptIn(UnstableApi::class)
internal fun hlsKeyframesMs(player: androidx.media3.common.Player): List<Long> {
    val manifest = player.currentManifest as? androidx.media3.exoplayer.hls.HlsManifest
        ?: return emptyList()
    // Round up: a position below the segment start would snap to the previous segment.
    return manifest.mediaPlaylist.segments.map { (it.relativeStartTimeUs + 999) / 1000 }
}

/**
 * The keyframe nearest to [targetMs] on the far side of [fromMs], so a seek always moves in its
 * direction. [targetMs] itself when there are no keyframes there.
 */
internal fun keyframeSeekTarget(targetMs: Long, fromMs: Long, keyframesMs: List<Long>): Long {
    val ahead = targetMs > fromMs
    return keyframesMs
        .filter { if (ahead) it > fromMs else it < fromMs }
        .minByOrNull { kotlin.math.abs(it - targetMs) } ?: targetMs
}

/** Buffer limits for TVs with little RAM, where software AV1 already needs most of the heap. */
internal object LowMemoryBuffer {
    const val MIN_MS = 15_000
    const val MAX_MS = 30_000
    const val FOR_PLAYBACK_MS = 1_000
    const val AFTER_REBUFFER_MS = 2_000
    /** About 30 s of 720p video; Media3's default video target is ~130 MB. */
    const val TARGET_BYTES = 32 * 1024 * 1024

    /** Devices up to this much total RAM use the smaller buffer. */
    const val DEVICE_RAM_BYTES = 2_560L * 1024 * 1024
}

internal fun isLowMemoryDevice(context: Context): Boolean {
    val manager = context.getSystemService(android.app.ActivityManager::class.java) ?: return false
    if (manager.isLowRamDevice) return true
    val info = android.app.ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    return info.totalMem in 1..LowMemoryBuffer.DEVICE_RAM_BYTES
}

@OptIn(UnstableApi::class)
internal fun lowMemoryLoadControl(): DefaultLoadControl =
    DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            LowMemoryBuffer.MIN_MS,
            LowMemoryBuffer.MAX_MS,
            LowMemoryBuffer.FOR_PLAYBACK_MS,
            LowMemoryBuffer.AFTER_REBUFFER_MS,
        )
        .setTargetBufferBytes(LowMemoryBuffer.TARGET_BYTES)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
