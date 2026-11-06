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
            out.add(Libdav1dVideoRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener, 50))
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

fun canFallbackToDav1d(mode: DecoderMode, output: VideoOutput, attempted: Boolean,
    videoError: Boolean, supported: Boolean): Boolean =
    mode == DecoderMode.AUTO && output == VideoOutput.HARDWARE && !attempted && videoError && supported
