package com.dreamcast.nativeapp

/**
 * Judges dav1d playback in five-second windows. Two overloaded windows in a row (about 10 s
 * of drops) report [Signal.OVERLOADED]; six windows in a row without a single drop (30 s)
 * report [Signal.SMOOTH]. Requiring a run of windows avoids reacting to a seek or a single
 * complex scene. While trying a higher step ([sample] with `trial`), one overloaded window
 * is enough: the lower step is known to play, so there is no reason to keep stuttering.
 */
internal class SoftwareFrameMonitor {
    enum class Signal { NONE, OVERLOADED, SMOOTH }

    private var previousTime = -1L
    private var previousRendered = 0
    private var previousDropped = 0
    private var overloadedWindows = 0
    private var smoothWindows = 0

    fun reset() {
        previousTime = -1
        overloadedWindows = 0
        smoothWindows = 0
    }

    fun sample(nowMs: Long, rendered: Int, dropped: Int, trial: Boolean = false): Signal {
        if (previousTime < 0 || rendered < previousRendered || dropped < previousDropped ||
            nowMs - previousTime > 10_000) {
            previousTime = nowMs
            previousRendered = rendered
            previousDropped = dropped
            overloadedWindows = 0
            smoothWindows = 0
            return Signal.NONE
        }
        if (nowMs - previousTime < WINDOW_MS) return Signal.NONE
        val lost = dropped - previousDropped
        val total = rendered - previousRendered + lost
        previousTime = nowMs
        previousRendered = rendered
        previousDropped = dropped
        if (total < MIN_FRAMES) {
            overloadedWindows = 0
            smoothWindows = 0
            return Signal.NONE
        }
        val overloaded = lost >= 6 && lost.toLong() * 100 >= total * 5L
        overloadedWindows = if (overloaded) overloadedWindows + 1 else 0
        smoothWindows = if (lost == 0) smoothWindows + 1 else 0
        if (overloadedWindows >= (if (trial) 1 else OVERLOADED_WINDOWS)) {
            overloadedWindows = 0
            return Signal.OVERLOADED
        }
        if (smoothWindows >= SMOOTH_WINDOWS) {
            smoothWindows = 0
            return Signal.SMOOTH
        }
        return Signal.NONE
    }

    private companion object {
        const val WINDOW_MS = 5_000L
        const val MIN_FRAMES = 50
        const val OVERLOADED_WINDOWS = 2
        const val SMOOTH_WINDOWS = 6
    }
}
