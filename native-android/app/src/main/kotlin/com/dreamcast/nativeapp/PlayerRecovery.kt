package com.dreamcast.nativeapp

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import com.dreamcast.nativeapp.data.VideoStream

/** What the player does after a playback error. */
internal sealed interface PlaybackRecovery {
    /** Restart the same stream with the dav1d software decoder. */
    data object Dav1dFallback : PlaybackRecovery

    /** Reload the release page: stream URLs are signed and may have expired. */
    data object ReloadPage : PlaybackRecovery

    data class OtherStream(val stream: VideoStream) : PlaybackRecovery

    data class Fail(val message: String) : PlaybackRecovery
}

/**
 * Chooses the recovery after a playback error of [stream].
 *
 * Order: dav1d fallback for a hardware video decoder failure, then one page reload, then any
 * stream not in [failedStreams] (which must already include [stream]), then an error.
 */
internal fun planRecovery(
    dav1dFallback: Boolean,
    firstFailure: Boolean,
    stream: VideoStream,
    streams: List<VideoStream>,
    failedStreams: Set<String>,
    errorCodeName: String,
): PlaybackRecovery =
    when {
        dav1dFallback -> PlaybackRecovery.Dav1dFallback
        firstFailure -> PlaybackRecovery.ReloadPage
        else ->
            streams.firstOrNull { it.url !in failedStreams }?.let(PlaybackRecovery::OtherStream)
                ?: PlaybackRecovery.Fail(
                    "Не удалось воспроизвести ${stream.type.uppercase()}: $errorCodeName"
                )
    }

/** True when a video renderer, not the network or audio, failed. */
@OptIn(UnstableApi::class)
internal fun isVideoRendererError(error: PlaybackException): Boolean =
    error is ExoPlaybackException &&
        error.type == ExoPlaybackException.TYPE_RENDERER &&
        (error.rendererFormat?.sampleMimeType?.startsWith("video/") == true ||
            error.rendererName?.contains("Video") == true)
