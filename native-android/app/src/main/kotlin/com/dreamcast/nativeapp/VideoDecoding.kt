package com.dreamcast.nativeapp

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.av1.Dav1dLibrary
import androidx.media3.decoder.av1.Libdav1dVideoRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.VideoRendererEventListener

enum class DecoderMode(val label: String) {
    AUTO("Авто"), HARDWARE("Аппаратное"), SOFTWARE("Программное AV1 — dav1d");
    companion object {
        fun from(value: String) = entries.firstOrNull { it.name == value } ?: AUTO
    }
}

enum class VideoOutput { HARDWARE, DAV1D, SYSTEM }

/** The same capability predicate drives the disabled UI and the actual renderer selector. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoDecoderCapabilities(private val context: Context) {
    private val hardwareCache = mutableMapOf<Format, Boolean>()

    fun hardware(format: Format): Boolean = hardwareCache.getOrPut(format) {
        val mime = format.sampleMimeType ?: return@getOrPut false
        runCatching {
            MediaCodecUtil.getDecoderInfos(mime, format.cryptoType != C.CRYPTO_TYPE_NONE, false)
                .any { it.hardwareAccelerated && !it.softwareOnly && it.isFormatSupported(context, format) }
        }.getOrDefault(false)
    }

    fun software(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.VIDEO_AV1 && format.cryptoType == C.CRYPTO_TYPE_NONE && Dav1dLibrary.isAvailable()
}

/** Strict hardware means platform software codecs cannot make the Hardware option appear usable. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class UnifiedRenderersFactory(context: Context, private val output: VideoOutput) : DefaultRenderersFactory(context) {
    init {
        setEnableDecoderFallback(true)
        if (output == VideoOutput.HARDWARE) setMediaCodecSelector { mime, secure, tunneling ->
            val candidates = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            if (mime.startsWith("video/")) candidates.filter { it.hardwareAccelerated && !it.softwareOnly }
            else candidates
        }
    }

    override fun buildVideoRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean, eventHandler: Handler, eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long, out: java.util.ArrayList<Renderer>,
    ) {
        if (output == VideoOutput.DAV1D) {
            // Queue enough frames to absorb decode-time spikes; preserve dav1d's automatic thread count.
            out.add(Libdav1dVideoRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener,
                50, Libdav1dVideoRenderer.THREAD_COUNT_DECODER_DEFAULT, 0, 12, 12, false))
        } else super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector,
            enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
    }
}

/** Deterministic policy, independent of Android's codec enumeration. */
fun decoderOutput(mode: DecoderMode, hardware: Boolean, dav1d: Boolean): VideoOutput = when {
    mode == DecoderMode.SOFTWARE && dav1d -> VideoOutput.DAV1D
    hardware -> VideoOutput.HARDWARE
    dav1d -> VideoOutput.DAV1D
    else -> VideoOutput.SYSTEM
}

/** Decoder mode actually used (a forced mode falls back to AUTO), chosen format and renderer. */
data class VideoChoice(val mode: DecoderMode, val format: Format?, val output: VideoOutput)

/**
 * Picks the video format and renderer for [formats]. [maxHeight] is the user's quality
 * limit (0 = Auto); dav1d prefers at most 720p in Auto. [hardware] and [software] are the
 * capability checks, so the policy itself is independent of the device.
 */
fun chooseVideo(
    formats: List<Format>,
    maxHeight: Int,
    mode: DecoderMode,
    hardware: (Format) -> Boolean,
    software: (Format) -> Boolean,
): VideoChoice {
    val ordered = formats.filter { maxHeight == 0 || it.height <= maxHeight }
        .ifEmpty { formats }
        .sortedByDescending { it.height }
    val hardwareFormat = ordered.firstOrNull(hardware)
    val softwareFormat = ordered.filter(software).let { candidates ->
        candidates.firstOrNull { it.height <= (maxHeight.takeIf { h -> h > 0 } ?: 720) }
            ?: candidates.lastOrNull()
    }
    val effective = if ((mode == DecoderMode.HARDWARE && hardwareFormat == null) ||
        (mode == DecoderMode.SOFTWARE && softwareFormat == null)) DecoderMode.AUTO else mode
    val chosen = if (effective == DecoderMode.SOFTWARE) softwareFormat
        else hardwareFormat ?: softwareFormat ?: ordered.firstOrNull()
    val output = decoderOutput(effective, chosen != null && hardware(chosen),
        chosen != null && software(chosen))
    return VideoChoice(effective, chosen, output)
}

/** Maximum video height for track selection; 0 means no limit. */
fun videoHeightLimit(output: VideoOutput, selectedQuality: Int, softwareAutoMaxHeight: Int): Int =
    if (output == VideoOutput.DAV1D && selectedQuality == 0) softwareAutoMaxHeight else selectedQuality

fun canFallbackToDav1d(mode: DecoderMode, output: VideoOutput, attempted: Boolean,
    videoError: Boolean, supported: Boolean): Boolean =
    mode == DecoderMode.AUTO && output == VideoOutput.HARDWARE && !attempted && videoError && supported
