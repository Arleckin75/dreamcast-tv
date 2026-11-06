package com.dreamcast.nativeapp

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.decoder.av1.Libdav1dVideoRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.video.VideoDecoderOutputBufferRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/** Experimental: give the GPU enough lead time while retaining the media presentation clock. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class EarlyDav1dVideoRenderer(joiningMs: Long, eventHandler: Handler,
    eventListener: VideoRendererEventListener) : Libdav1dVideoRenderer(joiningMs,
    eventHandler, eventListener, 50, 0, 0, 12, 12, false) {
    private var realPositionUs = 0L
    private var positionNs = 0L
    private var speed = 1f
    private var aheadUs = 50_000L
    private var outputHandler: Handler? = null
    private val pending = LinkedHashMap<VideoDecoderOutputBuffer, Runnable>()
    private val presentationClock = FramePresentationClock()

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        realPositionUs = positionUs
        positionNs = System.nanoTime()
        aheadUs = (50_000 * speed).toLong()
        super.render(positionUs + aheadUs, elapsedRealtimeUs)
    }

    override fun setPlaybackSpeed(currentPlaybackSpeed: Float, targetPlaybackSpeed: Float) {
        clearPending()
        presentationClock.reset()
        speed = currentPlaybackSpeed
    }

    override fun shouldDropOutputBuffer(earlyUs: Long, elapsedRealtimeUs: Long) =
        super.shouldDropOutputBuffer(earlyUs + aheadUs, elapsedRealtimeUs)

    override fun shouldDropBuffersToKeyframe(earlyUs: Long, elapsedRealtimeUs: Long) =
        super.shouldDropBuffersToKeyframe(earlyUs + aheadUs, elapsedRealtimeUs)

    override fun shouldForceRenderOutputBuffer(earlyUs: Long, elapsedSinceLastRenderUs: Long) =
        super.shouldForceRenderOutputBuffer(earlyUs + aheadUs, elapsedSinceLastRenderUs)

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType == Renderer.MSG_SET_VIDEO_OUTPUT) {
            clearPending()
            if (message is ReusableTextureVideoView) {
                outputHandler = Handler(Looper.myLooper()!!)
                super.handleMessage(messageType, VideoDecoderOutputBufferRenderer { buffer ->
                    val now = System.nanoTime()
                    val suggestedNs = positionNs + ((buffer.timeUs - realPositionUs) * 1000 / speed).toLong()
                    val targetNs = presentationClock.adjust(buffer.timeUs, suggestedNs, speed)
                    val releaseNs = targetNs - 60_000_000
                    val presentationNs = if (state == Renderer.STATE_STARTED) targetNs else now
                    val delayNs = releaseNs - now
                    if (state != Renderer.STATE_STARTED || delayNs <= 0 || delayNs > 100_000_000) {
                        message.setOutputBuffer(buffer, presentationNs)
                    } else {
                        val task = Runnable {
                            pending.remove(buffer)
                            message.setOutputBuffer(buffer, presentationNs)
                        }
                        pending[buffer] = task
                        outputHandler!!.postAtTime(task,
                            SystemClock.uptimeMillis() + (delayNs + 999_999) / 1_000_000)
                    }
                })
                return
            }
        }
        super.handleMessage(messageType, message)
    }

    private fun clearPending() {
        presentationClock.reset()
        for ((buffer, task) in pending) {
            outputHandler?.removeCallbacks(task)
            buffer.release()
        }
        pending.clear()
    }

    override fun onStopped() {
        clearPending()
        super.onStopped()
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
        clearPending()
        super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
    }

    override fun onDisabled() {
        clearPending()
        super.onDisabled()
    }
}
