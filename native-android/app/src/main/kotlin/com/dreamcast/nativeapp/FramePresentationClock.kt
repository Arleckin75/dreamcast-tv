package com.dreamcast.nativeapp

import kotlin.math.abs

/** Keep frame cadence stable while gently following the audio clock's position estimates. */
internal class FramePresentationClock {
    private var anchorPtsUs = Long.MIN_VALUE
    private var anchorNs = 0L
    private var lastPtsUs = Long.MIN_VALUE
    private var playbackSpeed = 1f

    fun reset() {
        anchorPtsUs = Long.MIN_VALUE
        lastPtsUs = Long.MIN_VALUE
    }

    fun adjust(ptsUs: Long, suggestedNs: Long, speed: Float): Long {
        val predicted = if (anchorPtsUs == Long.MIN_VALUE) suggestedNs
            else anchorNs + ((ptsUs - anchorPtsUs) * 1000 / playbackSpeed.toDouble()).toLong()
        if (anchorPtsUs == Long.MIN_VALUE || ptsUs <= lastPtsUs || speed != playbackSpeed ||
            abs(suggestedNs - predicted) > 100_000_000) {
            anchorPtsUs = ptsUs
            anchorNs = suggestedNs
            playbackSpeed = speed
            lastPtsUs = ptsUs
            return suggestedNs
        }
        val correction = ((suggestedNs - predicted) / 100).coerceIn(-100_000, 100_000)
        anchorNs += correction
        lastPtsUs = ptsUs
        return predicted + correction
    }
}
