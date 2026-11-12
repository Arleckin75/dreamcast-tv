package com.dreamcast.nativeapp

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
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
        .setSeekBackIncrementMs(SEEK_STEP_MS)
        .setSeekForwardIncrementMs(SEEK_STEP_MS)
        .setMediaSourceFactory(source)
        .build()
