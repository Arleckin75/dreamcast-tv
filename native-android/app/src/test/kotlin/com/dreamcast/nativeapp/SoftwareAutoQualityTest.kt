package com.dreamcast.nativeapp

import com.dreamcast.nativeapp.SoftwareAutoQuality.State
import org.junit.Assert.*
import org.junit.Test

class SoftwareAutoQualityTest {
    private val stored = mutableListOf<State>()
    private fun quality(state: State = State(720)) = SoftwareAutoQuality(state) { stored += it }
        .also { it.newEpisode("e0") }

    /** Starts [count] further episodes; each counts down the wait by one. */
    private fun SoftwareAutoQuality.episodes(count: Int, from: Int = 1) =
        repeat(count) { newEpisode("e${from + it}") }

    @Test fun firstDropLowersAndWaitsFiveEpisodes() {
        val q = quality()
        assertEquals(480, q.overloaded(480))
        assertEquals(480, q.limit)
        assertEquals(State(480, failures = 1, waitEpisodes = 5), stored.last())
        // No attempt in the same episode or during the wait.
        assertNull(q.smooth(480, 720))
        q.episodes(4)
        assertEquals(480, q.limit)
        assertNull(q.smooth(480, 720))
        q.episodes(1, from = 5)
        assertEquals(720, q.smooth(480, 720))
    }

    @Test fun failuresInARowWaitLonger() {
        val q = quality()
        assertEquals(1080, q.smooth(720, 1080))
        assertEquals(State(720, failures = 1, waitEpisodes = 5), stored.last())
        assertEquals(720, q.overloaded(720))
        // The failed attempt is counted once.
        assertEquals(State(720, failures = 1, waitEpisodes = 5), stored.last())
        q.episodes(5)
        assertEquals(1080, q.smooth(720, 1080))
        q.overloaded(720)
        assertEquals(State(720, failures = 2, waitEpisodes = 20), stored.last())
        q.episodes(20, from = 6)
        q.smooth(720, 1080)
        q.overloaded(720)
        assertEquals(State(720, failures = 3, waitEpisodes = 50), stored.last())
        q.episodes(50, from = 26)
        q.smooth(720, 1080)
        q.overloaded(720)
        // The wait stops growing at 50 episodes.
        assertEquals(State(720, failures = 4, waitEpisodes = 50), stored.last())
    }

    @Test fun smoothAttemptIsKeptAndResetsFailures() {
        val q = quality(State(720, failures = 2, waitEpisodes = 0))
        assertEquals(1080, q.smooth(720, 1080))
        assertEquals(1080, q.limit)
        // Not switched yet: nothing is confirmed.
        assertNull(q.smooth(720, null))
        assertEquals(State(720, failures = 3, waitEpisodes = 50), stored.last())
        assertNull(q.smooth(1080, null))
        assertEquals(State(1080, failures = 0, waitEpisodes = 5), stored.last())
        q.episodes(1)
        assertEquals(1080, q.limit)
    }

    @Test fun oneAttemptPerEpisode() {
        val q = quality()
        q.smooth(720, 1080)
        q.smooth(1080, null)
        // Confirmed at 1080p; a higher step waits for later episodes, not this one.
        assertNull(q.smooth(1080, 1440))
        q.episodes(5)
        assertEquals(1440, q.smooth(1080, 1440))
    }

    @Test fun restartingTheSameEpisodeDoesNotCountDown() {
        val q = quality()
        q.overloaded(480)
        repeat(10) { q.newEpisode("e0") }
        assertEquals(5, stored.last().waitEpisodes)
        q.newEpisode("e1")
        assertEquals(4, stored.last().waitEpisodes)
    }

    @Test fun cutShortAttemptCountsAsAFailure() {
        val q = quality()
        q.smooth(720, 1080)
        q.episodes(1)
        assertEquals(720, q.limit)
        assertNull(q.smooth(720, 1080))
        assertEquals(State(720, failures = 1, waitEpisodes = 4), stored.last())
    }

    @Test fun slowNetworkBelowTheLimitDoesNotUseAnAttempt() {
        val q = quality()
        // The connection keeps playback at 480p under a 720p limit.
        assertNull(q.smooth(480, 1080))
        assertTrue(stored.isEmpty())
        // Once 720p plays smoothly, the attempt happens.
        assertEquals(1080, q.smooth(720, 1080))
    }

    @Test fun attemptHeldBackByTheNetworkIsNotConfirmed() {
        val q = quality()
        q.smooth(720, 1080)
        // The limit is 1080p but the network keeps 720p: nothing is learned.
        assertNull(q.smooth(720, null))
        assertEquals(State(720, failures = 1, waitEpisodes = 5), stored.last())
    }

    @Test fun tryingLastsUntilConfirmedOrRolledBack() {
        val q = quality()
        assertFalse(q.trying)
        q.smooth(720, 1080)
        assertTrue(q.trying)
        q.overloaded(720)
        assertFalse(q.trying)
        val confirmed = quality(State(720))
        confirmed.smooth(720, 1080)
        confirmed.smooth(1080, null)
        assertFalse(confirmed.trying)
    }

    @Test fun nothingHigherMeansNoAttempt() {
        val q = quality(State(1080))
        assertNull(q.smooth(1080, null))
        assertTrue(stored.isEmpty())
    }

    @Test fun returningToAutoRestoresTheLearnedHeight() {
        val q = quality()
        q.smooth(720, 1080)
        q.restoreLearned()
        assertEquals(720, q.limit)
    }
}
