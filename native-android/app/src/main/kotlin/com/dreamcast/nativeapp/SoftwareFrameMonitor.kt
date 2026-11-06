package com.dreamcast.nativeapp

/** Two consecutive five-second windows avoid reacting to a seek or a single complex frame. */
internal class SoftwareFrameMonitor {
    private var previousTime = -1L
    private var previousRendered = 0
    private var previousDropped = 0
    private var overloadedWindows = 0

    fun reset() {
        previousTime = -1
        overloadedWindows = 0
    }

    fun sample(nowMs: Long, rendered: Int, dropped: Int): Boolean {
        if (previousTime < 0 || rendered < previousRendered || dropped < previousDropped ||
            nowMs - previousTime > 10_000) {
            previousTime = nowMs
            previousRendered = rendered
            previousDropped = dropped
            overloadedWindows = 0
            return false
        }
        if (nowMs - previousTime < 5_000) return false
        val lost = dropped - previousDropped
        val total = rendered - previousRendered + lost
        previousTime = nowMs
        previousRendered = rendered
        previousDropped = dropped
        overloadedWindows = if (total >= 50 && lost >= 6 && lost.toLong() * 100 >= total * 5L)
            overloadedWindows + 1 else 0
        if (overloadedWindows < 2) return false
        overloadedWindows = 0
        return true
    }
}
