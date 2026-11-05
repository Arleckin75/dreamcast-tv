package com.dreamcast.nativeapp.ui

import org.junit.Assert.*
import org.junit.Test

class PosterPaletteTest {
    @Test
    fun transparentImagesHaveNoPalette() {
        assertNull(PosterPalette.fromPixels(intArrayOf()))
        assertNull(PosterPalette.fromPixels(IntArray(200) { 0x00ff0000 }))
    }

    @Test
    fun bordersAndWhiteLetteringDoNotHideTheCoverColours() {
        val red = 0xffdc2828.toInt()
        val blue = 0xff285adc.toInt()
        val pixels =
            IntArray(800) { 0xff000000.toInt() } +
                IntArray(500) { 0xffffffff.toInt() } +
                IntArray(400) { red } +
                IntArray(200) { blue }
        assertEquals(PosterPalette(red, blue), PosterPalette.fromPixels(pixels))
    }

    @Test
    fun monochromeAndNearWhiteCoversStillHaveAStableFallback() {
        for (colour in listOf(0xff000000.toInt(), 0xffeeeeee.toInt(), 0xff858585.toInt())) {
            assertEquals(
                PosterPalette(colour, colour),
                PosterPalette.fromPixels(IntArray(100) { colour }),
            )
        }
    }

    @Test
    fun mutedBackgroundDoesNotDrownOutSaturatedArtwork() {
        val grey = 0xff888888.toInt()
        val green = 0xff28c850.toInt()
        val result = PosterPalette.fromPixels(IntArray(600) { grey } + IntArray(400) { green })!!
        assertEquals(green, result.primary)
    }

    @Test
    fun recentlyUsedPalettesSurviveEviction() {
        val cache = PosterPaletteCache(2)
        val a = PosterPalette(1, 2)
        val b = PosterPalette(3, 4)
        cache.put("a", a)
        cache.put("b", b)
        assertSame(a, cache.get("a"))
        cache.put("c", PosterPalette(5, 6))
        assertNull(cache.get("b"))
        assertSame(a, cache.get("a"))
    }
}
