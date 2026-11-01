package com.dreamcast.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/** Detects whether a stream exposes a hardware-supported video track. */
@OptIn(UnstableApi::class)
object CosmosMedia3Probe {
    private const val TAG = "DCTV_TRACKS"
    private val handler = Handler(Looper.getMainLooper())
    private var active: ExoPlayer? = null
    private var generation = 0

    /** Inspect each supplied URL without playing it. Return the first source with a
     * supported video track; return -1 when no compatible video source exists. */
    @JvmStatic
    fun selectCompatible(context: Context, candidates: List<Map<String, Any>>, result: MethodChannel.Result) {
        handler.post {
            generation += 1
            val token = generation
            active?.release()
            active = null
            if (candidates.isEmpty()) { result.success(-1); return@post }
            var finished = false
            fun finish(index: Int) {
                if (finished || generation != token) return
                finished = true
                active?.release()
                active = null
                Log.i(TAG, "selection finished index=$index total=${candidates.size}")
                result.success(index)
            }
            var index = 0
            lateinit var next: () -> Unit
            next = {
                if (!finished && generation == token) {
                    if (index >= candidates.size || index >= 12) {
                        finish(-1)
                    } else {
                        val currentIndex = index++
                        val candidate = candidates[currentIndex]
                        val url = candidate["url"] as? String ?: ""
                        @Suppress("UNCHECKED_CAST")
                        val headers = candidate["headers"] as? Map<String, String> ?: emptyMap()
                        Log.i(TAG, "source=$currentIndex checking scheme=${url.substringBefore(':')} host=${runCatching { android.net.Uri.parse(url).host }.getOrNull()} (path redacted)")
                        active?.release()
                        active = null
                        if (!url.startsWith("http")) {
                            next()
                        } else {
                            try {
                                val httpFactory = DefaultHttpDataSource.Factory()
                                    .setDefaultRequestProperties(headers)
                                    .setAllowCrossProtocolRedirects(true)
                                val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory))
                                val player = ExoPlayer.Builder(context).setMediaSourceFactory(mediaSourceFactory).build()
                                active = player
                                var decided = false
                                fun advance(supported: Boolean) {
                                    if (decided || finished || generation != token) return
                                    decided = true
                                    Log.i(TAG, "source=$currentIndex supportedVideo=$supported")
                                    if (supported) finish(currentIndex) else {
                                        player.release()
                                        if (active === player) active = null
                                        next()
                                    }
                                }
                                player.addListener(object : Player.Listener {
                                    override fun onTracksChanged(tracks: Tracks) {
                                        val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                                        if (video.isNotEmpty()) {
                                            video.forEach { group ->
                                                for (t in 0 until group.length) {
                                                    val f = group.getTrackFormat(t)
                                                    Log.i(TAG, "source=$currentIndex video mime=${f.sampleMimeType} codecs=${f.codecs} size=${f.width}x${f.height} supported=${group.isTrackSupported(t)}")
                                                }
                                            }
                                            advance(video.any { group -> (0 until group.length).any { group.isTrackSupported(it) } })
                                        } else if (player.playbackState == Player.STATE_READY) advance(false)
                                    }
                                    override fun onPlaybackStateChanged(state: Int) {
                                        if (state == Player.STATE_READY && !player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO) &&
                                            player.currentTracks.groups.none { it.type == C.TRACK_TYPE_VIDEO }) advance(false)
                                    }
                                    override fun onPlayerError(error: PlaybackException) {
                                        Log.w(TAG, "source=$currentIndex error=${error.errorCodeName}")
                                        advance(false)
                                    }
                                })
                                val mime = when {
                                    url.substringBefore('?').endsWith(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
                                    url.substringBefore('?').endsWith(".mpd", true) -> MimeTypes.APPLICATION_MPD
                                    else -> null
                                }
                                val item = MediaItem.Builder().setUri(url).apply { if (mime != null) setMimeType(mime) }.build()
                                player.setMediaItem(item)
                                player.prepare()
                                handler.postDelayed({ advance(false) }, 6000L)
                            } catch (e: Exception) {
                                Log.w(TAG, "source=$currentIndex probe exception=${e.javaClass.simpleName}")
                                next()
                            }
                        }
                    }
                }
            }
            next()
        }
    }

}
