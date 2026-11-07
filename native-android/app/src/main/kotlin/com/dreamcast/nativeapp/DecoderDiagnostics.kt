package com.dreamcast.nativeapp

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * Logs video decoder events under `DCTV_DECODER` and reports the input format and
 * decoder name. Events are ignored once [exo] is no longer the current player.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class DecoderDiagnostics(
    private val exo: ExoPlayer,
    private val isCurrent: () -> Boolean,
    private val requested: () -> String,
    private val onFormat: (Format) -> Unit,
    private val onDecoder: (String) -> Unit,
) : AnalyticsListener {
    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        if (!isCurrent()) return
        onFormat(format)
        Log.i(TAG, "format=${format.width}x${format.height} fps=${format.frameRate} bitrate=${format.bitrate}")
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        if (isCurrent())
            Log.i(TAG, "dropped=$droppedFrames elapsedMs=$elapsedMs bufferedMs=${exo.totalBufferedDuration}")
    }

    override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, counters: DecoderCounters) {
        counters.ensureUpdated()
        Log.i(TAG, "rendered=${counters.renderedOutputBufferCount} dropped=${counters.droppedBufferCount} skipped=${counters.skippedOutputBufferCount} maxConsecutiveDropped=${counters.maxConsecutiveDroppedBufferCount}")
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        if (!isCurrent()) return
        onDecoder(decoderName)
        Log.i(TAG, "${requested()} decoder=$decoderName")
    }

    private companion object {
        const val TAG = "DCTV_DECODER"
    }
}
