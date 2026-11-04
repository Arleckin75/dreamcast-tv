package com.dreamcast.nativeapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import kotlin.math.abs

/** Keep visible cards stationary; scroll only far enough to reveal a clipped focus target. */
@OptIn(ExperimentalFoundationApi::class)
internal object VisibleItemScrollSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (containerSize <= 0f) return 0f
        val end = offset + size
        if (offset >= -0.5f && end <= containerSize + 0.5f) return 0f
        // A target larger than the viewport must not oscillate between its two edges.
        if (offset <= 0f && end >= containerSize) return 0f
        val toEnd = end - containerSize
        return if (abs(offset) < abs(toEnd)) offset else toEnd
    }
}
