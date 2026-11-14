package com.dreamcast.nativeapp

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/** Media source factory whose HTTP requests carry the source's [headers]. */
@OptIn(UnstableApi::class)
internal fun mediaSourceFactory(context: Context, headers: Map<String, String>) =
    DefaultMediaSourceFactory(
        DefaultDataSource.Factory(
            context,
            DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers),
        )
    )

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
