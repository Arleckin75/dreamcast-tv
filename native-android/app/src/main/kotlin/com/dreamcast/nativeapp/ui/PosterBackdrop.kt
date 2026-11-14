package com.dreamcast.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class SelectedPoster(
    val url: String,
    val drawable: Drawable?,
    val failed: Boolean = false,
)

internal class PosterBackdropState {
    var selected by mutableStateOf<SelectedPoster?>(null)
        private set

    fun select(url: String, drawable: Drawable?, failed: Boolean = false) {
        selected =
            if (url.isBlank()) null
            else
                SelectedPoster(
                    url,
                    if (failed) null else drawable ?: selected?.takeIf { it.url == url }?.drawable,
                    failed,
                )
    }

    fun loaded(url: String, drawable: Drawable) {
        if (selected?.url == url) select(url, drawable)
    }

    fun failed(url: String) {
        if (selected?.url == url) select(url, null, failed = true)
    }
}

internal val LocalPosterBackdrop = staticCompositionLocalOf<PosterBackdropState?> { null }
private val palettes = PosterPaletteCache()

internal class PosterArtwork(private val url: String, private val backdrop: PosterBackdropState?) {
    private var drawable: Drawable? = null
    private var failed = false

    fun focus() {
        backdrop?.select(url, drawable, failed)
    }

    fun loaded(value: Drawable) {
        drawable = value
        failed = false
        backdrop?.loaded(url, value)
    }

    fun failed() {
        failed = true
        drawable = null
        backdrop?.failed(url)
    }
}

@Composable
internal fun rememberPosterArtwork(url: String): PosterArtwork {
    val backdrop = LocalPosterBackdrop.current
    return remember(url, backdrop) { PosterArtwork(url, backdrop) }
}

@Composable
internal fun PosterBackdrop(state: PosterBackdropState, base: Color, dark: Boolean) {
    var palette by remember(state) { mutableStateOf<PosterPalette?>(null) }
    val context = LocalContext.current.applicationContext
    val selected = state.selected
    LaunchedEffect(selected) {
        when {
            selected == null || selected.failed -> palette = null
            else -> {
                val cached = palettes.get(selected.url)
                if (cached != null) palette = cached
                else if (selected.drawable != null) {
                    // Let fast D-pad repeats settle before touching image pixels.
                    delay(120)
                    val source =
                        if (isHardwareBitmap(selected.drawable))
                            loadPaletteSample(context, selected.url) ?: selected.drawable
                        else selected.drawable
                    palette =
                        withContext(Dispatchers.Default) {
                            runCatching { extractPosterPalette(source) }
                                .getOrNull()
                                ?.also { palettes.put(selected.url, it) }
                        }
                }
            }
        }
    }
    val first =
        animateColorAsState(
            palette?.let { lerp(base, Color(backdropTint(it.primary)), if (dark) 0.55f else 0.24f) }
                ?: base,
            tween(450),
            label = "posterBackgroundPrimary",
        )
    val second =
        animateColorAsState(
            palette?.let {
                lerp(base, Color(backdropTint(it.secondary)), if (dark) 0.32f else 0.14f)
            } ?: base,
            tween(450),
            label = "posterBackgroundSecondary",
        )
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(
                Brush.linearGradient(
                    0f to first.value,
                    0.6f to second.value,
                    1f to base,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
            )
        }
    )
}

private fun isHardwareBitmap(drawable: Drawable): Boolean =
    Build.VERSION.SDK_INT >= 26 &&
        (drawable as? BitmapDrawable)?.bitmap?.config == Bitmap.Config.HARDWARE

/**
 * A small software copy of the poster for colour sampling. Pixels of a HARDWARE bitmap cannot be
 * read directly, and copying the full poster costs far more than decoding a tiny sample from
 * Coil's disk cache. No network access and no memory-cache entry; null when the poster is not in
 * the disk cache, so the caller falls back to copying the shown image.
 */
private suspend fun loadPaletteSample(context: android.content.Context, url: String): Drawable? {
    val request =
        ImageRequest.Builder(context)
            .data(url)
            .size(PALETTE_SAMPLE)
            .allowHardware(false)
            .networkCachePolicy(CachePolicy.DISABLED)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
    return (context.imageLoader.execute(request) as? SuccessResult)?.drawable
}

private const val PALETTE_SAMPLE = 48

/** Reuse Coil's rendered image. Only a 48x48 sample is analysed; no second network request. */
internal fun extractPosterPalette(drawable: Drawable): PosterPalette? {
    val original = (drawable as? BitmapDrawable)?.bitmap
    val readable =
        if (Build.VERSION.SDK_INT >= 26 && original?.config == Bitmap.Config.HARDWARE)
            original.copy(Bitmap.Config.ARGB_8888, false)
        else original
    val sample = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(sample)
        if (readable != null)
            canvas.drawBitmap(readable, null, Rect(0, 0, 48, 48), Paint(Paint.FILTER_BITMAP_FLAG))
        else {
            val copy = drawable.constantState?.newDrawable()?.mutate() ?: return null
            copy.setBounds(0, 0, 48, 48)
            copy.draw(canvas)
        }
        val pixels = IntArray(48 * 48)
        sample.getPixels(pixels, 0, 48, 0, 0, 48, 48)
        return PosterPalette.fromPixels(pixels)
    } finally {
        sample.recycle()
        if (readable !== original) readable?.recycle()
    }
}
