package com.dreamcast.nativeapp

import android.os.Handler
import android.view.KeyEvent
import androidx.media3.exoplayer.ExoPlayer
import com.dreamcast.nativeapp.data.Episode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Seeking with previews in the player: left/right move a target along the timeline with a frame
 * above it, and the video seeks on OK or [SeekScrubber.COMMIT_DELAY_MS] after the last press.
 *
 * Owns the episode's preview track and its sprite downloads. [seekTo] performs the seek the
 * viewer picked; [seekBy] is the immediate seek used while the duration is unknown.
 */
internal class SeekPreview(
    private val scope: CoroutineScope,
    private val handler: Handler,
    private val controls: PlayerControls,
    streamHeaders: () -> Map<String, String>,
    private val player: () -> ExoPlayer?,
    /** True once the current stream is prepared and its duration can be trusted. */
    private val ready: () -> Boolean,
    private val seekTo: (ExoPlayer, Long) -> Unit,
    private val seekBy: (Long) -> Unit,
    private val reveal: () -> Unit,
) {
    private val scrubber = SeekScrubber()
    private val thumbnails by lazy { ThumbnailLoader(streamHeaders()) }
    private var track: ThumbnailTrack? = null
    private var trackJob: Job? = null
    private var frameJob: Job? = null
    private var frameSprite = ""
    private var prefetchJob: Job? = null
    private var prefetchSprites = emptyList<String>()
    private var swallowKeyUp = KeyEvent.KEYCODE_UNKNOWN
    private val commitTask = Runnable { commit() }

    /** Loads [episode]'s preview track in the background; scrubbing works without it. */
    fun load(episode: Episode) {
        cancel()
        trackJob?.cancel()
        track = null
        if (episode.thumbnails.isBlank()) return
        trackJob = scope.launch {
            track = thumbnails.track(episode.thumbnails)
            prefetch()
        }
    }

    /**
     * Loads the preview sprites around [positionMs] (the playback position by default) in the
     * background, so the first left/right press shows a frame instead of waiting for its sprite.
     */
    fun prefetch(positionMs: Long? = null) {
        val track = track ?: return
        val position = positionMs ?: player()?.currentPosition ?: return
        val cues = track.spritesAround(position)
        val sprites = cues.map { it.imageUrl }
        if (prefetchJob?.isActive == true && sprites == prefetchSprites) return
        prefetchJob?.cancel()
        prefetchSprites = sprites
        prefetchJob = scope.launch { cues.forEach { thumbnails.frame(it) } }
    }

    /**
     * Moves the seek target one step in [direction] and shows its preview. Without a known
     * duration it falls back to an immediate seek.
     */
    fun step(direction: Int, repeatCount: Int) {
        val exo = player() ?: return
        val duration = exo.duration
        if (!ready() || duration <= 0 || !exo.isCurrentMediaItemSeekable) {
            seekBy(direction * SEEK_STEP_MS)
            return
        }
        val target = scrubber.step(direction, exo.currentPosition, duration, repeatCount)
        controls.show()
        reveal()
        showFrame(target)
        handler.removeCallbacks(commitTask)
        handler.postDelayed(commitTask, SeekScrubber.COMMIT_DELAY_MS)
    }

    private fun showFrame(target: Long) {
        val track = track
        val cue = track?.frameAt(target)
        val frame = cue?.let(thumbnails::cachedFrame)
        controls.showScrub(target, frame, hasPreviews = track != null)
        if (cue == null || frame != null) return
        // Keep one download per sprite while the target moves within it.
        if (frameJob?.isActive == true && frameSprite == cue.imageUrl) return
        frameJob?.cancel()
        frameSprite = cue.imageUrl
        frameJob = scope.launch {
            thumbnails.frame(cue)
            val current = scrubber.target?.let(track::frameAt) ?: return@launch
            thumbnails.cachedFrame(current)?.let(controls::setScrubFrame)
        }
    }

    /** Seeks to the picked target now. */
    fun commit() {
        handler.removeCallbacks(commitTask)
        val target = scrubber.commit() ?: return
        controls.hideScrub()
        player()?.let { seekTo(it, target) }
        if (controls.visible) reveal()
    }

    /** Leaves scrubbing without seeking; true when it was active. */
    fun cancel(): Boolean {
        handler.removeCallbacks(commitTask)
        if (!scrubber.active) return false
        scrubber.cancel()
        controls.hideScrub()
        return true
    }

    /** While scrubbing, OK seeks now; the key up is consumed too. Back is the caller's. */
    fun handleKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == swallowKeyUp) {
            swallowKeyUp = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || !scrubber.active) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> commit()
            else -> return false
        }
        swallowKeyUp = event.keyCode
        return true
    }

    /** Drops the cached sprites; pending jobs end with [scope]. */
    fun dispose() {
        handler.removeCallbacks(commitTask)
        thumbnails.clear()
    }
}
