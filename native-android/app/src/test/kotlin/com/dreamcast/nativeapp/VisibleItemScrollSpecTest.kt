package com.dreamcast.nativeapp

import androidx.compose.foundation.ExperimentalFoundationApi
import com.dreamcast.nativeapp.ui.VisibleItemScrollSpec
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class VisibleItemScrollSpecTest {
    @Test
    fun `fully visible cards do not move the list when focus travels horizontally`() {
        for (offset in listOf(0f, 10f, 25f, 50f)) {
            assertEquals(
                0f,
                VisibleItemScrollSpec.calculateScrollDistance(offset, 50f, 100f),
                0.001f,
            )
        }
        assertEquals(0f, VisibleItemScrollSpec.calculateScrollDistance(-0.1f, 50f, 100f), 0.001f)
    }

    @Test
    fun `clipped targets scroll the minimum distance in either direction`() {
        assertEquals(-10f, VisibleItemScrollSpec.calculateScrollDistance(-10f, 50f, 100f), 0.001f)
        assertEquals(15f, VisibleItemScrollSpec.calculateScrollDistance(65f, 50f, 100f), 0.001f)
    }

    @Test
    fun `oversized targets covering the viewport do not oscillate between edges`() {
        assertEquals(0f, VisibleItemScrollSpec.calculateScrollDistance(-25f, 150f, 100f), 0.001f)
        assertEquals(0f, VisibleItemScrollSpec.calculateScrollDistance(0f, 150f, 100f), 0.001f)
        assertEquals(0f, VisibleItemScrollSpec.calculateScrollDistance(0f, 50f, 0f), 0.001f)
    }
}
