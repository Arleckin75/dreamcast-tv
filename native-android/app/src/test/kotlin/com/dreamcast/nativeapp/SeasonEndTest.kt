package com.dreamcast.nativeapp

import com.dreamcast.nativeapp.SeasonEndAction.CLOSE
import com.dreamcast.nativeapp.SeasonEndAction.MARK_WATCHED
import com.dreamcast.nativeapp.SeasonEndAction.REWATCH
import com.dreamcast.nativeapp.data.LibraryStatus
import com.dreamcast.nativeapp.data.Release
import org.junit.Assert.*
import org.junit.Test

class SeasonEndTest {
    private fun release(total: Int, status: String = "") =
        Release(1, "Релиз", totalEpisodes = total, currentEpisodes = 12, status = status)

    @Test
    fun `finished season offers rewatch, mark as watched and close`() {
        val menu = seasonEndMenu(release(12), 12, LibraryStatus.watching)
        assertEquals("Сезон просмотрен", menu.title)
        assertEquals(listOf(REWATCH, MARK_WATCHED, CLOSE), menu.actions)
        assertEquals(listOf(REWATCH, MARK_WATCHED, CLOSE), seasonEndMenu(release(12), 12, null).actions)
    }

    @Test
    fun `already watched release is not marked again`() {
        assertEquals(
            listOf(REWATCH, CLOSE),
            seasonEndMenu(release(12), 12, LibraryStatus.completed).actions,
        )
    }

    @Test
    fun `airing season says no new episodes and cannot be marked watched`() {
        val partial = seasonEndMenu(release(24), 12, LibraryStatus.watching)
        assertEquals("Новых серий пока нет", partial.title)
        assertEquals(listOf(REWATCH, CLOSE), partial.actions)
        // A status from the site wins even when the total equals the episodes out so far.
        assertFalse(seasonComplete(release(12, "Онгоинг"), 12))
        assertFalse(seasonComplete(release(12, "Выходит"), 12))
        assertTrue(seasonComplete(release(12, "Вышел"), 12))
    }
}
