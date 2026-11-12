package com.dreamcast.nativeapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import kotlin.math.abs

/**
 * Keep visible cards stationary; scroll only far enough to reveal a clipped focus target.
 *
 * A focused button is drawn slightly larger than the bounds it reports, so a target scrolled
 * flush with the edge would have its enlarged outline clipped. [margin] (pixels) keeps that
 * much space between the target and the viewport edges; it shrinks when the target barely fits.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class VisibleItemScrollSpec(private val margin: Float = 0f) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (containerSize <= 0f) return 0f
        val space = margin.coerceAtMost(((containerSize - size) / 2f).coerceAtLeast(0f))
        val start = offset - space
        val end = offset + size + space
        if (start >= -0.5f && end <= containerSize + 0.5f) return 0f
        // A target larger than the viewport must not oscillate between its two edges.
        if (start <= 0f && end >= containerSize) return 0f
        val toEnd = end - containerSize
        return if (abs(start) < abs(toEnd)) start else toEnd
    }
}
