package com.dreamcast.nativeapp

import android.os.Handler
import androidx.media3.exoplayer.ExoPlayer

/**
 * Runs [tick] every [intervalMs] while playback is going on: it starts with [schedule], repeats
 * while [playing], and does nothing once the player screen is neither visible nor in
 * picture-in-picture ([active]).
 */
internal class PlaybackTicker(
    private val handler: Handler,
    private val intervalMs: Long,
    private val active: () -> Boolean,
    private val playing: () -> Boolean,
    private val tick: () -> Unit,
) {
    private val task =
        object : Runnable {
            override fun run() {
                if (!active()) return
                tick()
                if (playing()) handler.postDelayed(this, intervalMs)
            }
        }

    /** Restarts the interval; the first tick comes [intervalMs] from now. */
    fun schedule() {
        handler.removeCallbacks(task)
        if (active() && playing()) handler.postDelayed(task, intervalMs)
    }

    fun stop() = handler.removeCallbacks(task)
}

/**
 * Shows the player controls on interaction, keeps their timeline current while they are
 * visible, and hides them [HIDE_DELAY_MS] after the last interaction during playback.
 */
internal class ControlsTimer(
    private val handler: Handler,
    private val controls: PlayerControls,
    private val player: () -> ExoPlayer?,
    /** True while the player screen is started and not in picture-in-picture. */
    private val onScreen: () -> Boolean,
    private val hasWindowFocus: () -> Boolean,
    private val onReveal: () -> Unit,
) {
    private val hide = Runnable {
        if (hasWindowFocus() && !controls.modalVisible && player()?.isPlaying == true)
            controls.hide()
    }
    private val refresh =
        object : Runnable {
            override fun run() {
                if (!onScreen() || !controls.visible) return
                player()?.let {
                    controls.update(
                        it.currentPosition,
                        it.duration,
                        it.isPlaying,
                        it.isCurrentMediaItemSeekable,
                    )
                }
                handler.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }

    /** Shows the controls and restarts the hide countdown. */
    fun reveal() {
        if (!onScreen()) return
        controls.show()
        onReveal()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, HIDE_DELAY_MS)
    }

    fun stop() {
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(hide)
    }

    private companion object {
        const val HIDE_DELAY_MS = 5000L
        const val REFRESH_INTERVAL_MS = 500L
    }
}
