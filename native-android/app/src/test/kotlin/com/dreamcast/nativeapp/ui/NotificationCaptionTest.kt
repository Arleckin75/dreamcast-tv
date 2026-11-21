package com.dreamcast.nativeapp.ui

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationCaptionTest {
    private val utc = ZoneOffset.UTC

    @Test
    fun episodeCountBecomesShortCaptionWithDate() {
        assertEquals(
            "Серий: 6 → 7 · 21 нояб.",
            notificationCaption("Доступно серий: 7 (было 6)", "2026-11-21T20:15:00Z", utc),
        )
    }

    @Test
    fun otherMessageIsKept() {
        assertEquals("Вышла новая серия · 1 дек.", notificationCaption("Вышла новая серия", "2026-12-01T00:00:00Z", utc))
    }

    @Test
    fun missingDateIsLeftOut() {
        assertEquals("Серий: 1 → 3", notificationCaption("Доступно серий: 3 (было 1)", "", utc))
    }
}
