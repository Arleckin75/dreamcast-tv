package com.dreamcast.nativeapp.ui

import com.dreamcast.nativeapp.data.Episode
import com.dreamcast.nativeapp.data.Release
import com.dreamcast.nativeapp.data.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartEpisodeTest {
    private val release = Release(7, "Тайтл")
    private val episodes = (1..4).map { Episode("e$it", 7, it, "Серия $it", "") }

    private fun progress(id: String, watched: Boolean, updatedAt: Long) =
        id to WatchProgress(release, id, id, 0, 60_000, 1_200_000, watched, updatedAt)

    @Test
    fun `nothing watched starts on the first episode`() {
        assertEquals("e1", startEpisode(episodes, emptyMap()))
    }

    @Test
    fun `an unfinished episode watched last is the start`() {
        val watched = mapOf(progress("e1", true, 1), progress("e3", false, 3), progress("e2", true, 2))
        assertEquals("e3", startEpisode(episodes, watched))
    }

    @Test
    fun `after a finished episode the next one is the start`() {
        val watched = mapOf(progress("e1", true, 1), progress("e2", true, 5), progress("e3", false, 2))
        assertEquals("e3", startEpisode(episodes, watched))
    }

    @Test
    fun `the last episode finished stays the start`() {
        assertEquals("e4", startEpisode(episodes, mapOf(progress("e4", true, 9))))
    }

    @Test
    fun `progress of episodes no longer listed is ignored`() {
        assertEquals("e1", startEpisode(episodes, mapOf(progress("old", false, 9))))
        assertNull(startEpisode(emptyList(), emptyMap()))
    }
}
