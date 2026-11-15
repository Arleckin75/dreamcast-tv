package com.dreamcast.nativeapp

import com.dreamcast.nativeapp.SoftwareFrameMonitor.Signal
import org.junit.Assert.*
import org.junit.Test

class SoftwareFrameMonitorTest {
    @Test fun sustainedDropsRequireTwoFullWindows() {
        val monitor = SoftwareFrameMonitor()
        assertEquals(Signal.NONE, monitor.sample(0, 0, 0))
        assertEquals(Signal.NONE, monitor.sample(4_000, 80, 20))
        assertEquals(Signal.NONE, monitor.sample(5_000, 100, 20))
        assertEquals(Signal.OVERLOADED, monitor.sample(10_000, 200, 40))
    }

    @Test fun isolatedStallDoesNotLowerQuality() {
        val monitor = SoftwareFrameMonitor()
        monitor.sample(0, 0, 0)
        assertEquals(Signal.NONE, monitor.sample(5_000, 100, 20))
        assertEquals(Signal.NONE, monitor.sample(10_000, 220, 20))
        assertEquals(Signal.NONE, monitor.sample(15_000, 320, 40))
    }

    @Test fun smallLossesAndLowFrameCountsDoNotTrigger() {
        val monitor = SoftwareFrameMonitor()
        monitor.sample(0, 0, 0)
        assertEquals(Signal.NONE, monitor.sample(5_000, 120, 5))
        assertEquals(Signal.NONE, monitor.sample(10_000, 240, 10))
        assertEquals(Signal.NONE, monitor.sample(15_000, 250, 20))
        assertEquals(Signal.NONE, monitor.sample(20_000, 260, 30))
    }

    @Test fun pauseSeekAndDecoderReplacementDiscardOldSamples() {
        val monitor = SoftwareFrameMonitor()
        monitor.sample(0, 0, 0)
        monitor.sample(5_000, 100, 20)
        monitor.reset()
        assertEquals(Signal.NONE, monitor.sample(10_000, 200, 40))
        assertEquals(Signal.NONE, monitor.sample(15_000, 300, 60))
        assertEquals(Signal.NONE, monitor.sample(20_000, 0, 0))
        assertEquals(Signal.NONE, monitor.sample(25_000, 100, 20))
        assertEquals(Signal.NONE, monitor.sample(40_000, 200, 40))
    }

    @Test fun duringATrialOneBadWindowIsEnough() {
        val monitor = SoftwareFrameMonitor()
        monitor.sample(0, 0, 0, trial = true)
        assertEquals(Signal.OVERLOADED, monitor.sample(5_000, 100, 20, trial = true))
        // Without a trial the same window alone does not lower the quality.
        val normal = SoftwareFrameMonitor()
        normal.sample(0, 0, 0)
        assertEquals(Signal.NONE, normal.sample(5_000, 100, 20))
    }

    @Test fun thirtySecondsWithoutDropsIsSmooth() {
        val monitor = SoftwareFrameMonitor()
        monitor.sample(0, 0, 0)
        for (window in 1..5) assertEquals(Signal.NONE, monitor.sample(window * 5_000L, window * 120, 0))
        assertEquals(Signal.SMOOTH, monitor.sample(30_000, 720, 0))
        // The run starts over: the next report comes after another 30 s.
        for (window in 7..11) assertEquals(Signal.NONE, monitor.sample(window * 5_000L, window * 120, 0))
        assertEquals(Signal.SMOOTH, monitor.sample(60_000, 1_440, 0))
    }

    @Test fun anyDropRestartsTheSmoothRun() {
        val monitor = SoftwareFrameMonitor()
        monitor.sample(0, 0, 0)
        for (window in 1..5) monitor.sample(window * 5_000L, window * 120, 0)
        assertEquals(Signal.NONE, monitor.sample(30_000, 719, 1))
        for (window in 7..11) assertEquals(Signal.NONE, monitor.sample(window * 5_000L, window * 120, 1))
        assertEquals(Signal.SMOOTH, monitor.sample(60_000, 1_440, 1))
    }

    @Test fun pausedOrStarvedWindowsAreNotSmooth() {
        val monitor = SoftwareFrameMonitor()
        monitor.sample(0, 0, 0)
        for (window in 1..8) assertEquals(Signal.NONE, monitor.sample(window * 5_000L, window * 10, 0))
    }
}
