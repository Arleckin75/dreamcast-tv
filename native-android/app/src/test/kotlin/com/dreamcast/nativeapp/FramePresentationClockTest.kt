package com.dreamcast.nativeapp

import org.junit.Assert.*
import org.junit.Test

class FramePresentationClockTest {
    @Test fun audioPositionJitterDoesNotBecomeFrameCadenceJitter() {
        val clock = FramePresentationClock()
        val base = 1_000_000_000L
        var previous = clock.adjust(0, base, 1f)
        for (frame in 1..240) {
            val pts = frame * 1_000_000L / 24
            val jitter = if (frame % 2 == 0) 8_000_000 else -8_000_000
            val result = clock.adjust(pts, base + pts * 1000 + jitter, 1f)
            assertTrue("Output cadence changed with clock jitter", result - previous in 41_500_000..41_850_000)
            previous = result
        }
    }

    @Test fun speedChangesAndSeeksUseTheNewClock() {
        val clock = FramePresentationClock()
        clock.adjust(1_000_000, 5_000_000_000, 1f)
        assertEquals(6_000_000_000, clock.adjust(1_100_000, 6_000_000_000, 2f))
        assertEquals(6_500_000_000, clock.adjust(2_100_000, 6_500_000_000, 2f))
        assertEquals(7_000_000_000, clock.adjust(100_000, 7_000_000_000, 2f))
        clock.reset()
        assertEquals(9_000_000_000, clock.adjust(200_000, 9_000_000_000, 1f))
    }

    @Test fun slowClockDriftIsFollowedWithoutAbruptSteps() {
        val clock = FramePresentationClock()
        val base = 1_000_000_000L
        var result = base
        var suggested = base
        for (frame in 0..2400) {
            val pts = frame * 1_000_000L / 24
            suggested = base + pts * 1000 + frame * 1_000L
            result = clock.adjust(pts, suggested, 1f)
        }
        assertTrue("Audio/video clocks drifted apart", abs(suggested - result) < 200_000)
    }

    private fun abs(value: Long) = kotlin.math.abs(value)
}
