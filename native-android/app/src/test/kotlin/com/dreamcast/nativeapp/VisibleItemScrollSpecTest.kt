package com.dreamcast.nativeapp

import androidx.compose.foundation.ExperimentalFoundationApi
import com.dreamcast.nativeapp.ui.VisibleItemScrollSpec
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class VisibleItemScrollSpecTest {
    private val flush = VisibleItemScrollSpec()
    private val spaced = VisibleItemScrollSpec(margin = 6f)

    @Test
    fun `fully visible cards do not move the list when focus travels horizontally`() {
        for (offset in listOf(0f, 10f, 25f, 50f)) {
            assertEquals(0f, flush.calculateScrollDistance(offset, 50f, 100f), 0.001f)
        }
        assertEquals(0f, flush.calculateScrollDistance(-0.1f, 50f, 100f), 0.001f)
        for (offset in listOf(6f, 25f, 44f)) {
            assertEquals(0f, spaced.calculateScrollDistance(offset, 50f, 100f), 0.001f)
        }
    }

    @Test
    fun `clipped targets scroll the minimum distance in either direction`() {
        assertEquals(-10f, flush.calculateScrollDistance(-10f, 50f, 100f), 0.001f)
        assertEquals(15f, flush.calculateScrollDistance(65f, 50f, 100f), 0.001f)
    }

    @Test
    fun `margin keeps the enlarged focus outline away from the edges`() {
        // Scrolling back up used to stop with the target flush at the top edge.
        assertEquals(-6f, spaced.calculateScrollDistance(0f, 50f, 100f), 0.001f)
        assertEquals(-16f, spaced.calculateScrollDistance(-10f, 50f, 100f), 0.001f)
        assertEquals(6f, spaced.calculateScrollDistance(50f, 50f, 100f), 0.001f)
        assertEquals(21f, spaced.calculateScrollDistance(65f, 50f, 100f), 0.001f)
    }

    @Test
    fun `margin shrinks when the target barely fits`() {
        assertEquals(-2f, spaced.calculateScrollDistance(0f, 96f, 100f), 0.001f)
        assertEquals(0f, spaced.calculateScrollDistance(2f, 96f, 100f), 0.001f)
        assertEquals(0f, spaced.calculateScrollDistance(0f, 100f, 100f), 0.001f)
    }

    @Test
    fun `oversized targets covering the viewport do not oscillate between edges`() {
        for (spec in listOf(flush, spaced)) {
            assertEquals(0f, spec.calculateScrollDistance(-25f, 150f, 100f), 0.001f)
            assertEquals(0f, spec.calculateScrollDistance(0f, 150f, 100f), 0.001f)
            assertEquals(0f, spec.calculateScrollDistance(0f, 50f, 0f), 0.001f)
        }
    }
}
