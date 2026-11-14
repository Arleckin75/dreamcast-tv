package com.dreamcast.nativeapp.ui

import androidx.compose.foundation.background
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
    val marks = rememberPosterMarks(model.ui)
    val schedule = resource("home-schedule") { publish -> model.repository.schedule(onStale = publish) }
    val nextDay =
        remember(schedule.value) {
            nearestScheduleDay(schedule.value?.value.orEmpty(), java.time.LocalDate.now().dayOfWeek)
        }
    val fresh =
        remember(model.ui.notifications) {
            model.ui.notifications.filter { it.unread }.mapNotNull { it.release }.distinctBy { it.id }
        }
    val watching =
        remember(model.ui.bookmarks, recent) {
            val inProgress = recent.map { it.release.id }.toSet()
            model.ui.bookmarks
                .filter { it.status == LibraryStatus.watching && it.release.id !in inProgress }
                .map { it.release }
        }
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
        // Right under the banner: Down from the banner always reaches the catalog first.
        if (releases.isNotEmpty())
            item { Action("Все релизы  ›") { model.selectSection("catalog") } }
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
        if (fresh.isNotEmpty())
            item { PosterRow("Новые серии", fresh, marks) { model.navigate(Route("detail", it)) } }
        if (watching.isNotEmpty())
            item { PosterRow("Смотрю", watching, marks) { model.navigate(Route("detail", it)) } }
        nextDay?.let { (day, today) ->
            item {
                PosterRow(if (today) "Сегодня по расписанию" else "Расписание · ${day.title}", day.releases, marks) {
                    model.navigate(Route("detail", it))
                }
            }
        }
    }
}

private val WEEKDAYS =
    mapOf(
        java.time.DayOfWeek.MONDAY to "Понедельник",
        java.time.DayOfWeek.TUESDAY to "Вторник",
        java.time.DayOfWeek.WEDNESDAY to "Среда",
        java.time.DayOfWeek.THURSDAY to "Четверг",
        java.time.DayOfWeek.FRIDAY to "Пятница",
        java.time.DayOfWeek.SATURDAY to "Суббота",
        java.time.DayOfWeek.SUNDAY to "Воскресенье",
    )

/**
 * The schedule day for the home screen and whether it is [today]: today's day when it has
 * releases, else the next day with releases (the site lists the week from Monday, so this wraps
 * around). Without a day named like today, the first day with releases.
 */
internal fun nearestScheduleDay(days: List<ScheduleDay>, today: java.time.DayOfWeek): Pair<ScheduleDay, Boolean>? {
    val name = WEEKDAYS.getValue(today)
    val start = days.indexOfFirst { it.title.trim().startsWith(name, ignoreCase = true) }
    if (start < 0) return days.firstOrNull { it.releases.isNotEmpty() }?.let { it to false }
    for (offset in days.indices) {
        val day = days[(start + offset) % days.size]
        if (day.releases.isNotEmpty()) return day to (offset == 0)
    }
    return null
}

/** A titled row of portrait posters on the home screen. */
@Composable
private fun PosterRow(
    title: String,
    releases: List<Release>,
    marks: PosterMarks,
    onOpen: (Release) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(6.dp),
        ) {
            items(releases, key = { it.id }) { release ->
                Box(Modifier.width(150.dp)) {
                    Poster(
                        release,
                        progress = marks.progress(release.id),
                        isNew = marks.isNew(release.id),
                    ) {
                        onOpen(release)
                    }
                }
            }
        }
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
            .focusFrame(focused, MaterialTheme.colorScheme.onSurface, 22.dp)
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
