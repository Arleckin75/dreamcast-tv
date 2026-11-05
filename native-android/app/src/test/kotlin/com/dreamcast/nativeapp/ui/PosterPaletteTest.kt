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
    fun darkRedCoverDoesNotUsePaleLetteringAsItsSecondaryColour() {
        val red = 0xff531d21.toInt()
        val lettering = 0xffe4e2dc.toInt()
        val result = PosterPalette.fromPixels(IntArray(650) { red } + IntArray(350) { lettering })!!
        assertEquals(PosterPalette(red, red), result)
        val tint = backdropTint(result.primary)
        assertEquals(150, (tint ushr 16) and 255)
        assertTrue(((tint ushr 16) and 255) > ((tint ushr 8) and 255) * 2)
    }

    @Test
    fun neutralCoversStayNeutralAndBrightCoversAreBounded() {
        assertEquals(0xff000000.toInt(), backdropTint(0xff000000.toInt()))
        assertEquals(0xff969696.toInt(), backdropTint(0xff202020.toInt()))
        assertEquals(0xffd2d2d2.toInt(), backdropTint(0xffffffff.toInt()))
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
