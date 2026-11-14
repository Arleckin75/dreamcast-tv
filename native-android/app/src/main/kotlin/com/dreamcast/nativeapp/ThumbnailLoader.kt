package com.dreamcast.nativeapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads a thumbnails track and its sprite images for seek previews.
 *
 * Sprites are decoded as RGB_565 and kept in a small LRU cache (an 800×450 sprite takes about
 * 700 KB). Frames are drawn straight from the sprite, so scrubbing allocates no pixel copies.
 * Failures return null: a missing preview must never interrupt playback.
 */
internal class ThumbnailLoader(
    private val headers: Map<String, String>,
    private val client: OkHttpClient = defaultClient,
    maxSprites: Int = 6,
) {
    private val sprites = LruCache<String, Bitmap>(maxSprites)
    private val tracks = mutableMapOf<String, ThumbnailTrack>()
    /** One lock per sprite: a download blocks only requests for the same sprite. */
    private val spriteLocks = ConcurrentHashMap<String, Mutex>()

    /** The parsed track at [url], cached for the player's lifetime. */
    suspend fun track(url: String): ThumbnailTrack? {
        if (url.isBlank()) return null
        synchronized(tracks) { tracks[url] }?.let { return it }
        val track =
            fetch(url) { body -> ThumbnailTrack.parse(body.string(), url) }
                ?.takeIf { it.cues.isNotEmpty() } ?: return null
        synchronized(tracks) { tracks[url] = track }
        return track
    }

    /** The cached frame for [cue], without any network access. */
    fun cachedFrame(cue: ThumbnailCue): Drawable? = sprites.get(cue.imageUrl)?.let { crop(it, cue) }

    /** The frame for [cue], downloading its sprite when needed. */
    suspend fun frame(cue: ThumbnailCue): Drawable? {
        cachedFrame(cue)?.let { return it }
        // One download per sprite even when many scrub steps ask for it at once.
        val sprite =
            spriteLocks.getOrPut(cue.imageUrl) { Mutex() }.withLock {
                sprites.get(cue.imageUrl)
                    ?: fetch(cue.imageUrl) { body ->
                            BitmapFactory.decodeStream(
                                body.byteStream(),
                                null,
                                BitmapFactory.Options().apply {
                                    inPreferredConfig = Bitmap.Config.RGB_565
                                },
                            )
                        }
                        ?.also { sprites.put(cue.imageUrl, it) }
            } ?: return null
        return crop(sprite, cue)
    }

    fun clear() {
        sprites.evictAll()
        spriteLocks.clear()
        synchronized(tracks) { tracks.clear() }
    }

    private suspend fun <T> fetch(url: String, read: (okhttp3.ResponseBody) -> T?): T? =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    Request.Builder()
                        .url(url)
                        .apply { headers.forEach { (key, value) -> header(key, value) } }
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    read(response.body)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                android.util.Log.w("DCTV_THUMBNAILS", "Could not load $url: ${error.message}")
                null
            }
        }

    companion object {
        private val defaultClient: OkHttpClient by lazy {
            com.dreamcast.nativeapp.data.SharedHttp.client(connect = 10, read = 15)
        }

        /** The [cue] region of [sprite]; the whole sprite when the cue has no region. */
        internal fun region(sprite: Bitmap, cue: ThumbnailCue): Rect? {
            if (cue.width <= 0 || cue.height <= 0) return Rect(0, 0, sprite.width, sprite.height)
            val x = cue.x.coerceIn(0, sprite.width)
            val y = cue.y.coerceIn(0, sprite.height)
            val width = cue.width.coerceAtMost(sprite.width - x)
            val height = cue.height.coerceAtMost(sprite.height - y)
            if (width <= 0 || height <= 0) return null
            return Rect(x, y, x + width, y + height)
        }

        /** A drawable showing the [cue] region of [sprite], without copying pixels. */
        internal fun crop(sprite: Bitmap, cue: ThumbnailCue): Drawable? =
            region(sprite, cue)?.let { SpriteFrameDrawable(sprite, it) }
    }
}

/** Draws [source] of a shared sprite into its bounds. The sprite stays owned by the cache. */
internal class SpriteFrameDrawable(val sprite: Bitmap, val source: Rect) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun draw(canvas: Canvas) {
        if (!sprite.isRecycled) canvas.drawBitmap(sprite, source, bounds, paint)
    }

    override fun getIntrinsicWidth() = source.width()

    override fun getIntrinsicHeight() = source.height()

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
