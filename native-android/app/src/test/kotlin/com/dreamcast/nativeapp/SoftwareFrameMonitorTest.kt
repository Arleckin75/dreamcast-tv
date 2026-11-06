package com.dreamcast.nativeapp

import org.junit.Assert.*
import org.junit.Test

class SoftwareFrameMonitorTest {
    @Test fun sustainedDropsRequireTwoFullWindows() {
        val monitor = SoftwareFrameMonitor()
        assertFalse(monitor.sample(0, 0, 0))
        assertFalse(monitor.sample(4_000, 80, 20))
        assertFalse(monitor.sample(5_000, 100, 20))
        assertTrue(monitor.sample(10_000, 200, 40))
    }

    @Test fun isolatedStallDoesNotLowerQuality() {
        val monitor = SoftwareFrameMonitor()
        monitor.sample(0, 0, 0)
        assertFalse(monitor.sample(5_000, 100, 20))
        assertFalse(monitor.sample(10_000, 220, 20))
        assertFalse(monitor.sample(15_000, 320, 40))
    }

    @Test fun smallLossesAndLowFrameCountsDoNotTrigger() {
        val monitor = SoftwareFrameMonitor()
        monitor.sample(0, 0, 0)
        assertFalse(monitor.sample(5_000, 120, 5))
        assertFalse(monitor.sample(10_000, 240, 10))
        assertFalse(monitor.sample(15_000, 250, 20))
        assertFalse(monitor.sample(20_000, 260, 30))
    }

    @Test fun pauseSeekAndDecoderReplacementDiscardOldSamples() {
        val monitor = SoftwareFrameMonitor()
        monitor.sample(0, 0, 0)
        monitor.sample(5_000, 100, 20)
        monitor.reset()
        assertFalse(monitor.sample(10_000, 200, 40))
        assertFalse(monitor.sample(15_000, 300, 60))
        assertFalse(monitor.sample(20_000, 0, 0))
        assertFalse(monitor.sample(25_000, 100, 20))
        assertFalse(monitor.sample(40_000, 200, 40))
    }
}
