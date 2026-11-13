package com.dreamcast.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dreamcast.nativeapp.*
import com.dreamcast.nativeapp.data.*

@Composable
internal fun Home(model: AppViewModel) {
    var retry by remember { mutableIntStateOf(0) }
    val data =
        resource(retry) { publish ->
            model.repository.releases(
                "",
                CatalogFilter(),
                1,
                refresh = retry > 0,
                onStale = publish,
            )
        }
    val releases = data.value?.items.orEmpty()
    val started = remember(retry) { NativePerformance.now() }
    LaunchedEffect(data.loading) {
        if (!data.loading && data.value != null) {
            withFrameNanos {}
            NativePerformance.event("home.native.catalogue_framework_frame", started)
        }
    }
    var featuredId by rememberSaveable { mutableIntStateOf(-1) }
    val featuredIndex = releases.indexOfFirst { it.id == featuredId }.coerceAtLeast(0)
    val featured = releases.getOrNull(featuredIndex)
    val recent = model.ui.continueWatching
    val listState = rememberLazyListState()
    var bannerFocused by remember { mutableStateOf(false) }
    var initialBannerFocus by remember { mutableStateOf(true) }
    LaunchedEffect(bannerFocused) {
        if (bannerFocused) {
            // Let the focused child request visibility, then reveal its entire banner.
            withFrameNanos {}
            listState.scrollToItem(0, 0)
        }
    }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            if (featured != null)
                FeaturedRelease(
                    featured,
                    index = featuredIndex,
                    count = releases.size,
                    autofocus = initialBannerFocus,
                    onBannerFocusChange = {
                        bannerFocused = it
                        if (it) initialBannerFocus = false
                    },
                    onPrevious = {
                        if (featuredIndex > 0) featuredId = releases[featuredIndex - 1].id
                    },
                    onNext = {
                        if (featuredIndex < releases.lastIndex)
                            featuredId = releases[featuredIndex + 1].id
                    },
                ) {
                    model.navigate(Route("detail", featured))
                }
            else if (data.loading) BannerSkeleton() else data.error?.let { ErrorState(it) { retry++ } }
        }
        if (data.value?.stale == true) item { Stale() }
        if (recent.isNotEmpty())
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Продолжить просмотр", style = MaterialTheme.typography.titleLarge)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(6.dp),
                    ) {
                        items(recent, key = { it.release.id }) { item ->
                            Column(Modifier.width(220.dp)) {
                                Poster(item.release, landscape = true) {
                                    model.navigate(Route("episodes", item.release, item.episodeId))
                                }
                                LinearProgressIndicator(
                                    progress = {
                                        if (item.durationMs > 0)
                                            (item.positionMs.toFloat() / item.durationMs).coerceIn(
                                                0f,
                                                1f,
                                            )
                                        else 0f
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                )
                                Text(
                                    "Серия ${item.ordinal}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        if (releases.isNotEmpty())
            item { Action("Все релизы  ›") { model.selectSection("catalog") } }
    }
}

@Composable
private fun FeaturedRelease(
    release: Release,
    index: Int,
    count: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBannerFocusChange: (Boolean) -> Unit,
    autofocus: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val artwork = rememberPosterArtwork(release.posterUrl)
    LaunchedEffect(artwork) { if (focused || autofocus) artwork.focus() }
    Box(
        Modifier.fillMaxWidth()
            .height(270.dp)
            .onFocusChanged {
                focused = it.hasFocus
                if (it.hasFocus) artwork.focus()
                onBannerFocusChange(it.hasFocus)
            }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
                    if (event.type == KeyEventType.KeyDown) {
                        if (event.key == Key.DirectionLeft) onPrevious() else onNext()
                    }
                    true
                } else false
            }
            .border(
                2.dp,
                if (focused) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                else Color.Transparent,
                RoundedCornerShape(22.dp),
            )
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        AsyncImage(
            release.posterUrl,
            null,
            Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.56f).fillMaxHeight(),
            contentScale = ContentScale.Crop,
            onSuccess = { artwork.loaded(it.result.drawable) },
            onError = { artwork.failed() },
        )
        Box(
            Modifier.fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            Color.Transparent,
                        )
                    )
                )
        )
        Column(
            Modifier.fillMaxWidth(0.62f).align(Alignment.CenterStart).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "ПОСЛЕДНИЕ ОБНОВЛЕНИЯ",
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                release.title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(
                        release.year.takeIf { it > 0 }?.toString(),
                        release.type.takeIf { it.isNotBlank() },
                        release.genres.takeIf { it.isNotBlank() },
                    )
                    .filterNotNull()
                    .joinToString("  •  "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Action("Подробнее", autofocus = autofocus, onClick = onClick)
        }
        Text(
            "${if (index > 0) "‹   " else ""}${index + 1} / $count${if (index < count - 1) "   ›" else ""}",
            modifier =
                Modifier.align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            color = Color.White,
            fontSize = 13.sp,
        )
    }
}
