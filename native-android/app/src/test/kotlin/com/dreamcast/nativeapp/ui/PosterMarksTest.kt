package com.dreamcast.nativeapp.ui

import com.dreamcast.nativeapp.UiNotification
import com.dreamcast.nativeapp.data.Release
import com.dreamcast.nativeapp.data.WatchProgress
import org.junit.Assert.*
import org.junit.Test

class PosterMarksTest {
    private fun progress(id: Int, position: Long, duration: Long, updated: Long, watched: Boolean = false) =
        WatchProgress(Release(id, "R$id"), "e$updated", "", 1, position, duration, watched, updated)

    private fun notification(id: Int?, unread: Boolean) =
        UiNotification("k$id$unread", id?.let { Release(it, "R$it") }, "", "", unread, "")

    @Test
    fun progressShowsTheLatestUnfinishedEpisodePerRelease() {
        val marks =
            PosterMarks.from(
                listOf(
                    progress(1, 300, 1_000, updated = 1),
                    progress(1, 750, 1_000, updated = 5),
                    progress(2, 500, 1_000, updated = 2, watched = true),
                    progress(3, 0, 1_000, updated = 3),
                    progress(4, 100, 0, updated = 4),
                ),
                emptyList(),
            )
        assertEquals(0.75f, marks.progress(1)!!, 0.0001f)
        assertNull(marks.progress(2)) // finished
        assertNull(marks.progress(3)) // not started
        assertNull(marks.progress(4)) // unknown duration
        assertNull(marks.progress(5))
    }

    @Test
    fun onlyUnreadNotificationsMarkReleasesAsNew() {
        val marks =
            PosterMarks.from(
                emptyList(),
                listOf(notification(7, unread = true), notification(8, unread = false), notification(null, true)),
            )
        assertTrue(marks.isNew(7))
        assertFalse(marks.isNew(8))
        assertFalse(marks.isNew(9))
    }

    @Test
    fun homeShowsTodaysScheduleOrTheNextDayWithReleases() {
        fun day(title: String, vararg ids: Int) =
            com.dreamcast.nativeapp.data.ScheduleDay(title, ids.map { Release(it, "R$it") })
        val week =
            listOf(day("Понедельник", 1), day("Вторник"), day("Среда", 3), day("Суббота"), day("Воскресенье"))
        assertEquals(
            "Среда" to true,
            nearestScheduleDay(week, java.time.DayOfWeek.WEDNESDAY)!!.let { it.first.title to it.second },
        )
        // Empty today: the next day with releases, wrapping around to Monday.
        assertEquals(
            "Понедельник" to false,
            nearestScheduleDay(week, java.time.DayOfWeek.SATURDAY)!!.let { it.first.title to it.second },
        )
        // No day named like today: the first day with releases.
        assertEquals(
            "Понедельник" to false,
            nearestScheduleDay(week, java.time.DayOfWeek.THURSDAY)!!.let { it.first.title to it.second },
        )
        assertNull(nearestScheduleDay(listOf(day("Среда")), java.time.DayOfWeek.WEDNESDAY))
    }
}
