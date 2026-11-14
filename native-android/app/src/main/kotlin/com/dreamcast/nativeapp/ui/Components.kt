package com.dreamcast.nativeapp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ButtonDefaults as TvButtonDefaults
import coil.compose.AsyncImage
import com.dreamcast.nativeapp.*
import com.dreamcast.nativeapp.data.*

/** Reserve the maximum focused bounds even while unfocused, including an antialiasing margin. */
private fun Modifier.reserveFocusScale(scale: Float): Modifier = layout { measurable, constraints ->
    val edge = 1.dp.roundToPx()
    fun shrink(value: Int) =
        if (value == Constraints.Infinity) value
        else ((value - edge * 2).coerceAtLeast(0) / scale).toInt()
    val child =
        measurable.measure(
            Constraints(
                minWidth = shrink(constraints.minWidth),
                maxWidth = shrink(constraints.maxWidth),
                minHeight = shrink(constraints.minHeight),
                maxHeight = shrink(constraints.maxHeight),
            )
        )
    val width = constraints.constrainWidth(kotlin.math.ceil(child.width * scale).toInt() + edge * 2)
    val height =
        constraints.constrainHeight(kotlin.math.ceil(child.height * scale).toInt() + edge * 2)
    layout(width, height) {
        child.placeRelative((width - child.width) / 2, (height - child.height) / 2)
    }
}

/** Shared shape, palette and focus treatment for every text action in the TV UI. */
@Composable
internal fun GuiButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    iconOnly: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val foreground = MaterialTheme.colorScheme.onSurface
    TvButton(
        onClick = onClick,
        modifier = modifier.reserveFocusScale(1.04f).heightIn(min = 44.dp),
        enabled = enabled,
        contentPadding = contentPadding,
        shape = TvButtonDefaults.shape(shape = RoundedCornerShape(50)),
        colors =
            TvButtonDefaults.colors(
                containerColor =
                    foreground.copy(alpha = if (selected) 0.22f else if (iconOnly) 0f else 0.12f),
                contentColor = foreground,
                focusedContainerColor = foreground,
                focusedContentColor = MaterialTheme.colorScheme.surface,
            ),
        scale =
            TvButtonDefaults.scale(
                focusedScale = 1.04f,
                pressedScale = 1.04f,
                focusedDisabledScale = 1.04f,
            ),
        content = content,
    )
}

@Composable
internal fun Action(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    autofocus: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(enabled) { if (autofocus && enabled) focus.requestFocus() }
    GuiButton(
        onClick = onClick,
        modifier = modifier.focusRequester(focus),
        enabled = enabled,
        selected = selected,
    ) {
        androidx.tv.material3.Text(
            text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ErrorState(message: String, retry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message)
        Action("Повторить", onClick = retry)
    }
}

@Composable
internal fun Loading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun Stale() {
    Text(
        "Нет свежих данных. Показана сохранённая версия.",
        modifier =
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(12.dp),
    )
}

@Composable
internal fun ChoiceDialog(
    title: String,
    items: List<String>,
    onDismiss: () -> Unit,
    header: (@Composable () -> Unit)? = null,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                header?.invoke()
                LazyColumn(
                    Modifier.heightIn(max = 330.dp),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items.size) { index ->
                        Action(items[index], Modifier.fillMaxWidth(), autofocus = index == 0) {
                            onSelect(index)
                        }
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
internal fun Poster(
    release: Release,
    restoreFocus: Boolean = false,
    onFocused: () -> Unit = {},
    landscape: Boolean = false,
    onClick: () -> Unit,
) {
    val artwork = rememberPosterArtwork(release.posterUrl)
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val scale by
        animateFloatAsState(
            if (focused) 1.035f else 1f,
            animationSpec = tween(140),
            label = "posterFocus",
        )
    val focusColor = MaterialTheme.colorScheme.onSurface
    LaunchedEffect(Unit) { if (restoreFocus) requester.requestFocus() }
    Column(
        Modifier.fillMaxWidth()
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    artwork.focus()
                    onFocused()
                }
            }
            // Focus and bring-into-view track the fixed reserved slot, not the animated layer.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .reserveFocusScale(1.035f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // Only the focused card draws a frame; the others need no extra border or clip layer.
            .drawWithContent {
                drawContent()
                if (focused) {
                    val stroke = 3.dp.toPx()
                    drawRoundRect(
                        focusColor,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(12.dp.toPx() - stroke / 2),
                        style = Stroke(stroke),
                    )
                }
            }
            .padding(5.dp)
    ) {
        AsyncImage(
            release.posterUrl,
            release.title,
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(if (landscape) 16f / 9f else 0.70f)
                    .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
            onSuccess = { artwork.loaded(it.result.drawable) },
            onError = { artwork.failed() },
        )
        Text(
            release.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.height(44.dp).padding(top = 6.dp),
        )
        Text(
            listOfNotNull(
                    release.type.takeIf(String::isNotEmpty),
                    release.year.takeIf { it > 0 }?.toString(),
                    release.currentEpisodes.takeIf { it > 0 }?.let { "$it сер." },
                )
                .joinToString(" • "),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
