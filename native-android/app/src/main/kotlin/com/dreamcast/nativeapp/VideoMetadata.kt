package com.dreamcast.nativeapp

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.source.MediaSource
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Read stream formats before choosing SurfaceView versus dav1d's GL output. */
@Suppress("DEPRECATION")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
suspend fun videoFormats(context: Context, source: MediaSource.Factory, item: MediaItem): List<Format> =
    withTimeout(20_000) {
        val retriever = MetadataRetriever.Builder(context, item).setMediaSourceFactory(source).build()
        try {
            suspendCancellableCoroutine { continuation ->
                val future = retriever.retrieveTrackGroups()
                continuation.invokeOnCancellation { future.cancel(true) }
                future.addListener({
                    if (continuation.isActive) try {
                        val groups = future.get()
                        val formats = (0 until groups.length).flatMap { group ->
                            (0 until groups[group].length).map { groups[group].getFormat(it) }
                        }.filter { it.sampleMimeType?.startsWith("video/") == true }
                        continuation.resume(formats)
                    } catch (error: Exception) { continuation.resumeWithException(error) }
                }, Executor { it.run() })
            }
        } finally { retriever.close() }
    }
