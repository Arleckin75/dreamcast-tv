package com.dreamcast.nativeapp

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.*
import org.junit.Test

class VideoDecodingTest {
    @Test fun autoPrefersHardwareAndFallsBackToDav1d() {
        assertEquals(VideoOutput.HARDWARE, decoderOutput(DecoderMode.AUTO, true, true))
        assertEquals(VideoOutput.DAV1D, decoderOutput(DecoderMode.AUTO, false, true))
    }
    @Test fun forcedSoftwareDoesNotUseAnAvailableHardwareDecoder() {
        assertEquals(VideoOutput.DAV1D, decoderOutput(DecoderMode.SOFTWARE, true, true))
    }
    @Test fun unavailableHardwareDoesNotSelectHardware() {
        assertEquals(VideoOutput.DAV1D, decoderOutput(DecoderMode.HARDWARE, false, true))
    }
    @Test fun otherCodecsStillHaveTheSystemPlaybackPath() {
        assertEquals(VideoOutput.SYSTEM, decoderOutput(DecoderMode.AUTO, false, false))
    }
    @Test fun unknownStoredModeIsSafeAuto() {
        assertEquals(DecoderMode.AUTO, DecoderMode.from("unsupported"))
    }
    @Test fun videoFailureFallsBackOnlyOnceAndOnlyInAuto() {
        assertTrue(canFallbackToDav1d(DecoderMode.AUTO, VideoOutput.HARDWARE, false, true, true))
        assertFalse(canFallbackToDav1d(DecoderMode.AUTO, VideoOutput.HARDWARE, true, true, true))
        assertFalse(canFallbackToDav1d(DecoderMode.HARDWARE, VideoOutput.HARDWARE, false, true, true))
        assertFalse(canFallbackToDav1d(DecoderMode.AUTO, VideoOutput.DAV1D, false, true, true))
    }
    @Test fun networkAudioAndUnsupportedFormatsDoNotTriggerVideoFallback() {
        assertFalse(canFallbackToDav1d(DecoderMode.AUTO, VideoOutput.HARDWARE, false, false, true))
        assertFalse(canFallbackToDav1d(DecoderMode.AUTO, VideoOutput.HARDWARE, false, true, false))
    }

    private fun av1(height: Int) = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).setHeight(height).build()
    private val ladder = listOf(av1(480), av1(1080), av1(720))
    private val none: (Format) -> Boolean = { false }
    private val all: (Format) -> Boolean = { true }

    @Test fun autoPicksHighestHardwareFormat() {
        val choice = chooseVideo(ladder, 0, DecoderMode.AUTO, hardware = all, software = all)
        assertEquals(1080, choice.format!!.height)
        assertEquals(VideoOutput.HARDWARE, choice.output)
        assertEquals(DecoderMode.AUTO, choice.mode)
    }
    @Test fun dav1dPrefersAtMost720pInAuto() {
        val choice = chooseVideo(ladder, 0, DecoderMode.AUTO, hardware = none, software = all)
        assertEquals(720, choice.format!!.height)
        assertEquals(VideoOutput.DAV1D, choice.output)
    }
    @Test fun qualityLimitCapsTheChosenHeight() {
        val choice = chooseVideo(ladder, 480, DecoderMode.AUTO, hardware = all, software = all)
        assertEquals(480, choice.format!!.height)
    }
    @Test fun qualityBelowEveryFormatKeepsAllFormats() {
        val choice = chooseVideo(ladder, 360, DecoderMode.AUTO, hardware = all, software = all)
        assertEquals(1080, choice.format!!.height)
    }
    @Test fun forcedSoftwareUsesDav1dEvenWithHardware() {
        val choice = chooseVideo(ladder, 0, DecoderMode.SOFTWARE, hardware = all, software = all)
        assertEquals(DecoderMode.SOFTWARE, choice.mode)
        assertEquals(VideoOutput.DAV1D, choice.output)
        assertEquals(720, choice.format!!.height)
    }
    @Test fun unavailableForcedModeFallsBackToAuto() {
        val hardwareOnly = chooseVideo(ladder, 0, DecoderMode.HARDWARE, hardware = none, software = all)
        assertEquals(DecoderMode.AUTO, hardwareOnly.mode)
        assertEquals(VideoOutput.DAV1D, hardwareOnly.output)
        val softwareOnly = chooseVideo(ladder, 0, DecoderMode.SOFTWARE, hardware = all, software = none)
        assertEquals(DecoderMode.AUTO, softwareOnly.mode)
        assertEquals(VideoOutput.HARDWARE, softwareOnly.output)
    }
    @Test fun noCapableDecoderUsesSystemPathWithHighestFormat() {
        val choice = chooseVideo(ladder, 0, DecoderMode.AUTO, hardware = none, software = none)
        assertEquals(1080, choice.format!!.height)
        assertEquals(VideoOutput.SYSTEM, choice.output)
        assertNull(chooseVideo(emptyList(), 0, DecoderMode.AUTO, all, all).format)
    }
    @Test fun dav1dAutoQualityUsesTheSoftwareLimit() {
        assertEquals(540, videoHeightLimit(VideoOutput.DAV1D, 0, 540))
        assertEquals(1080, videoHeightLimit(VideoOutput.DAV1D, 1080, 540))
        assertEquals(0, videoHeightLimit(VideoOutput.HARDWARE, 0, 540))
    }
}
