package com.dreamcast.nativeapp

/**
 * "Pick first, then seek": left/right move a target on the timeline while the video keeps
 * playing; the player seeks once, on OK or after [COMMIT_DELAY_MS] without presses.
 */
internal class SeekScrubber {
    /** Where the player will seek to; null when not scrubbing. */
    var target: Long? = null
        private set

    val active: Boolean
        get() = target != null

    /**
     * Moves the target one step in [direction] (-1 or 1). The first step starts from
     * [positionMs]. [repeatCount] is the key repeat count of a held button and speeds the
     * target up. Returns the new target, kept within 0..[durationMs].
     */
    fun step(direction: Int, positionMs: Long, durationMs: Long, repeatCount: Int = 0): Long {
        val from = target ?: positionMs.coerceIn(0, durationMs)
        val next = (from + direction.coerceIn(-1, 1) * scrubStepMs(repeatCount)).coerceIn(0, durationMs)
        target = next
        return next
    }

    /** Ends scrubbing and returns the target to seek to, or null when not scrubbing. */
    fun commit(): Long? = target.also { target = null }

    /** Ends scrubbing without seeking. */
    fun cancel() {
        target = null
    }

    companion object {
        /** Seek is applied this long after the last press. */
        const val COMMIT_DELAY_MS = 1_000L

        /**
         * Step for a key press: 10 s, then 30 s and 60 s while the button is held. Thumbnail
         * frames are 10 s apart, so single presses show every frame.
         */
        fun scrubStepMs(repeatCount: Int): Long =
            when {
                repeatCount < 5 -> 10_000L
                repeatCount < 15 -> 30_000L
                else -> 60_000L
            }
    }
}
