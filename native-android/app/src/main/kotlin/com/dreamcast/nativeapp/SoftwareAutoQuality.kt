package com.dreamcast.nativeapp

/**
 * The automatic height limit for dav1d on this device, learned from playback.
 *
 * Every episode starts at the remembered height. Sustained frame drops lower the limit by
 * one step at once and remember it. After 30 s without drops the player tries one step
 * higher, at most once per episode; the higher step is remembered after another 30 s
 * without drops there.
 *
 * Every lowering and every attempt that does not prove smooth counts as a failure, and the
 * next attempt waits a number of episodes that grows with consecutive failures
 * ([WAIT_EPISODES]): a device that really cannot play higher soon stops trying, while a
 * lowering caused by something passing (a background update, heat) is retried soon. The
 * failure is counted when the attempt starts, so an attempt cut short also waits.
 */
internal class SoftwareAutoQuality(private val state: State, private val persist: (State) -> Unit) {
    /** What is stored per device: the learned height, consecutive failures, episodes to wait. */
    data class State(val height: Int, val failures: Int = 0, val waitEpisodes: Int = 0)

    private var stored = state
    private var probeFrom = 0
    private var probedThisEpisode = false
    private var loweredThisEpisode = false
    private var episodeKey: String? = null

    /** The current limit; changes are applied by the caller. */
    var limit = state.height
        private set

    /** An attempt at a higher step is running and not yet confirmed. */
    val trying get() = probeFrom > 0

    /**
     * A stream starts. A new [key] (another episode) counts down the wait; restarting the
     * same episode (another stream, recovery) does not.
     */
    fun newEpisode(key: String) {
        limit = stored.height
        probeFrom = 0
        if (key == episodeKey) return
        episodeKey = key
        probedThisEpisode = false
        loweredThisEpisode = false
        if (stored.waitEpisodes > 0) save(stored.copy(waitEpisodes = stored.waitEpisodes - 1))
    }

    /** The viewer switched back to Auto: return to the remembered height without a new attempt. */
    fun restoreLearned() {
        limit = stored.height
        probeFrom = 0
    }

    /** Sustained drops; [lower] is the next lower height dav1d can play. Returns the new limit. */
    fun overloaded(lower: Int?): Int? {
        lower ?: return null
        // A failed attempt was already counted when it started.
        val failures = if (probeFrom > 0) stored.failures else stored.failures + 1
        limit = lower
        probeFrom = 0
        loweredThisEpisode = true
        save(State(lower, failures, waitFor(failures)))
        return lower
    }

    /**
     * 30 s without drops at [currentHeight]; [higher] is the next height above the limit.
     * An attempt starts only while playing at the limit, so a slow connection that keeps
     * the quality lower does not use one up. Returns the new limit when an attempt starts,
     * otherwise null.
     */
    fun smooth(currentHeight: Int, higher: Int?): Int? {
        if (probeFrom > 0) {
            // Confirmed only at the new limit: below it the network, not the device, decided.
            if (currentHeight >= limit) {
                probeFrom = 0
                save(State(limit, 0, WAIT_AFTER_SUCCESS))
            }
            return null
        }
        // Below the limit the network chose a lower quality: the limit itself is untested.
        if (currentHeight < limit || probedThisEpisode || loweredThisEpisode ||
            stored.waitEpisodes > 0 || higher == null) return null
        probeFrom = limit
        limit = higher
        probedThisEpisode = true
        val failures = stored.failures + 1
        save(stored.copy(failures = failures, waitEpisodes = waitFor(failures)))
        return higher
    }

    private fun save(next: State) {
        stored = next
        persist(next)
    }

    companion object {
        /** Episodes to wait after the 1st, 2nd and 3rd (and later) failure in a row. */
        val WAIT_EPISODES = listOf(5, 20, 50)
        /** After a successful step up, wait a little before trying the next one. */
        const val WAIT_AFTER_SUCCESS = 5

        fun waitFor(failures: Int) = WAIT_EPISODES[(failures - 1).coerceIn(0, WAIT_EPISODES.lastIndex)]
    }
}
