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
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
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
        } else out.add(
            BitstreamColorVideoRenderer(
                MediaCodecVideoRenderer.Builder(context)
                    .setCodecAdapterFactory(codecAdapterFactory)
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
            )
        )
    }
}

/**
 * A MediaCodec video renderer that leaves the colour of SDR AV1 to the bitstream. ExoPlayer
 * normally copies the stream's colour info (BT.709, limited range, SDR) into the codec's
 * KEY_COLOR_* settings; on a Candy Google TV the hardware AV1 decoder then shows the picture
 * with a yellow cast, while the same stream through dav1d, and through mpv on the same hardware
 * decoder (FFmpeg does not set these keys), looks right. The bitstream carries the same values,
 * so other decoders are unaffected; HDR keeps its colour info.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class BitstreamColorVideoRenderer(builder: MediaCodecVideoRenderer.Builder) :
    MediaCodecVideoRenderer(builder) {
    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: MediaCodecVideoRenderer.CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int,
    ): android.media.MediaFormat =
        super.getMediaFormat(
            if (leaveColorToBitstream(format)) format.buildUpon().setColorInfo(null).build()
            else format,
            codecMimeType, codecMaxValues, codecOperatingRate,
            deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId,
        )
}

/** SDR AV1 (or AV1 without a transfer) takes its colour from the bitstream, not KEY_COLOR_*. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun leaveColorToBitstream(format: Format): Boolean =
    format.sampleMimeType == MimeTypes.VIDEO_AV1 &&
        format.colorInfo?.colorTransfer.let {
            it == null || it == Format.NO_VALUE || it == C.COLOR_TRANSFER_SDR
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
 * The height dav1d starts with in Auto on a device that has not played anything yet. Each
 * device then learns its own limit from frame drops (see [SoftwareAutoQuality]).
 */
const val SOFTWARE_AUTO_START_HEIGHT = 720

/**
 * Picks the video format and renderer for [formats]. [maxHeight] is the user's quality
 * limit (0 = Auto); in Auto dav1d prefers at most [softwareAutoHeight]. [hardware] and
 * [software] are the capability checks, so the policy itself is independent of the device.
 */
fun chooseVideo(
    formats: List<Format>,
    maxHeight: Int,
    mode: DecoderMode,
    hardware: (Format) -> Boolean,
    software: (Format) -> Boolean,
    softwareAutoHeight: Int = SOFTWARE_AUTO_START_HEIGHT,
): VideoChoice {
    val ordered = formats.filter { maxHeight == 0 || it.height <= maxHeight }
        .ifEmpty { formats }
        .sortedByDescending { it.height }
    val hardwareFormat = ordered.firstOrNull(hardware)
    val softwareFormat = ordered.filter(software).let { candidates ->
        candidates.firstOrNull { it.height <= (maxHeight.takeIf { h -> h > 0 } ?: softwareAutoHeight) }
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

/**
 * The next lower height dav1d can decode, below both the playing [currentHeight] and the
 * current automatic [limit]; null when there is none.
 */
fun lowerSoftwareHeight(
    formats: List<Format>,
    currentHeight: Int,
    limit: Int,
    software: (Format) -> Boolean,
): Int? =
    formats
        .filter { software(it) && it.height in 1 until currentHeight && it.height < limit }
        .maxOfOrNull { it.height }

/** The next height dav1d can decode above the automatic [limit]; null when there is none. */
fun higherSoftwareHeight(formats: List<Format>, limit: Int, software: (Format) -> Boolean): Int? =
    formats.filter { software(it) && it.height > limit }.minOfOrNull { it.height }

/**
 * The decoding item of the player menu. In Auto it also names the decoder actually running,
 * so "Аппаратное" is not chosen in the hope of a change when Auto already uses it.
 */
fun decoderMenuLabel(mode: DecoderMode, output: VideoOutput?): String =
    if (mode != DecoderMode.AUTO || output == null) mode.label
    else "${mode.label} (${when (output) {
        VideoOutput.HARDWARE -> "аппаратное"
        VideoOutput.DAV1D -> "dav1d"
        VideoOutput.SYSTEM -> "системное"
    }})"

/**
 * Pause between releasing a video decoder and creating a different one. Some TV firmware
 * (seen on a Candy Google TV) hangs the whole system when a hardware decoder is torn down and
 * a new one is started at once.
 */
const val DECODER_SWITCH_PAUSE_MS = 1_000L

fun canFallbackToDav1d(mode: DecoderMode, output: VideoOutput, attempted: Boolean,
    videoError: Boolean, supported: Boolean): Boolean =
    mode == DecoderMode.AUTO && output == VideoOutput.HARDWARE && !attempted && videoError && supported
