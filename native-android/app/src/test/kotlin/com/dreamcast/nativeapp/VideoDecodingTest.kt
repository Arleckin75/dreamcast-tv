package com.dreamcast.nativeapp

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
}
