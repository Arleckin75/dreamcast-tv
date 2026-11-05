package com.dreamcast.nativeapp.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterBackdropTest {
    @Test
    fun aLateImageFromThePreviousPosterCannotReplaceTheFocusedCover() {
        val state = PosterBackdropState()
        state.select("red", null)
        state.select("blue", null)
        state.loaded("red", ColorDrawable(0xffff0000.toInt()))
        assertEquals("blue", state.selected!!.url)
        assertNull(state.selected!!.drawable)
        val blue = ColorDrawable(0xff0000ff.toInt())
        state.loaded("blue", blue)
        state.failed("red")
        assertSame(blue, state.selected!!.drawable)
        state.failed("blue")
        assertTrue(state.selected!!.failed)
        assertNull(state.selected!!.drawable)
        state.select("", null)
        assertNull(state.selected)
    }

    @Test
    fun extractingColoursDoesNotRecycleTheImageUsedByCoil() {
        val bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888)
        val colour = 0xffca3040.toInt()
        bitmap.eraseColor(colour)
        val drawable = BitmapDrawable(RuntimeEnvironment.getApplication().resources, bitmap)
        assertEquals(PosterPalette(colour, colour), extractPosterPalette(drawable))
        assertFalse(bitmap.isRecycled)
        assertEquals(colour, bitmap.getPixel(5, 5))
        bitmap.recycle()
    }

    @Test
    fun samplingADrawableDoesNotChangeItsVisibleBounds() {
        val drawable = ColorDrawable(0xff30a060.toInt())
        drawable.setBounds(10, 20, 70, 100)
        val before = android.graphics.Rect(drawable.bounds)
        assertNotNull(extractPosterPalette(drawable))
        assertEquals(before, drawable.bounds)
    }
}
