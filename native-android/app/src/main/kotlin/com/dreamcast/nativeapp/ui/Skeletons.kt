package com.dreamcast.nativeapp.ui

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Loading skeletons: grey shapes in the layout of the screen that is loading, shown on the
 * first load instead of a spinner. They are not focusable; the whole skeleton fades in and out
 * as one layer, which is cheap on any TV GPU, and stays still when animations are off.
 */

/** One grey shape of a skeleton. */
@Composable
private fun Bone(modifier: Modifier, shape: Shape = RoundedCornerShape(8.dp)) =
    Box(modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), shape))

@Composable
private fun Modifier.skeleton(): Modifier {
    val context = LocalContext.current
    val animationsOff = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val base = semantics { contentDescription = "Загрузка" }
    if (animationsOff) return this.then(base)
    val alpha by
        rememberInfiniteTransition(label = "skeleton")
            .animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), "alpha")
    return this.then(base).graphicsLayer { this.alpha = alpha }
}

@Composable
private fun TextLines(widths: List<Float>, height: Dp = 18.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        widths.forEach { Bone(Modifier.fillMaxWidth(it).height(height)) }
    }
}

/** A poster card: cover (0.7 aspect), title and metadata lines, as in [Poster]. */
@Composable
private fun PosterBone(modifier: Modifier = Modifier) {
    Column(modifier.padding(5.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Bone(Modifier.fillMaxWidth().aspectRatio(0.70f))
        Bone(Modifier.fillMaxWidth(0.85f).height(16.dp))
        Bone(Modifier.fillMaxWidth(0.5f).height(12.dp))
    }
}

/** The home banner while the latest releases load. */
@Composable
internal fun BannerSkeleton() {
    Box(
        Modifier.fillMaxWidth()
            .height(270.dp)
            .skeleton()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp))
    ) {
        Bone(
            Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.44f).fillMaxHeight(),
            RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp),
        )
        Column(
            Modifier.fillMaxWidth(0.62f).align(Alignment.CenterStart).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Bone(Modifier.width(150.dp).height(10.dp))
            Bone(Modifier.fillMaxWidth(0.85f).height(30.dp))
            Bone(Modifier.fillMaxWidth(0.55f).height(30.dp))
            Bone(Modifier.fillMaxWidth(0.6f).height(16.dp))
            Bone(Modifier.width(150.dp).height(44.dp), RoundedCornerShape(50))
        }
    }
}

/** A grid of poster cards laid out like the catalog grid (160 dp minimum columns, 16 dp gaps). */
@Composable
internal fun PosterGridSkeleton(rows: Int = 2) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + 16.dp) / (160.dp + 16.dp)).toInt().coerceAtLeast(1)
        Column(Modifier.fillMaxWidth().skeleton(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(rows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(columns) { PosterBone(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Days of the schedule: a day title and a row of cards each. */
@Composable
internal fun ScheduleSkeleton(days: Int = 2) {
    Column(Modifier.fillMaxSize().skeleton(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(days) {
            Bone(Modifier.width(160.dp).height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(6) { PosterBone(Modifier.width(170.dp)) }
            }
        }
    }
}

/** Description lines of a release page while its details load. */
@Composable
internal fun DescriptionSkeleton() {
    Box(Modifier.fillMaxWidth().skeleton()) { TextLines(listOf(1f, 0.95f, 0.9f, 0.6f)) }
}

/** Episode buttons of the episode list dialog. */
@Composable
internal fun EpisodeListSkeleton(count: Int = 6) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp).skeleton(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(count) { Bone(Modifier.fillMaxWidth().height(46.dp), RoundedCornerShape(50)) }
    }
}
