package com.dreamcast.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dreamcast.nativeapp.*
import com.dreamcast.nativeapp.data.*

@Composable
internal fun ReleaseDetail(model: AppViewModel, seed: Release) {
    val context = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    val loaded =
        resource("${seed.id}:$retry") { publish ->
            model.repository.detail(seed, refresh = retry > 0, onStale = publish)
        }
    val release = loaded.value?.value?.release ?: seed
    val artwork = rememberPosterArtwork(release.posterUrl)
    LaunchedEffect(artwork) { artwork.focus() }
    var bookmarkDialog by remember { mutableStateOf(false) }
    val status = model.ui.bookmarksById[release.id]?.status
    val subscribed = release.id in model.ui.subscriptions
    if (loaded.value?.stale == true) Stale()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        AsyncImage(
            release.posterUrl,
            release.title,
            modifier = Modifier.width(210.dp).aspectRatio(0.7f),
            contentScale = ContentScale.Crop,
            onSuccess = { artwork.loaded(it.result.drawable) },
            onError = { artwork.failed() },
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(release.title, style = MaterialTheme.typography.headlineSmall)
            if (release.originalTitle.isNotBlank()) Text(release.originalTitle)
            Text(
                listOf(
                        release.year.takeIf { it > 0 }?.toString().orEmpty(),
                        release.type,
                        release.season,
                        release.studio,
                        release.status,
                        release.rating.takeIf { it.isNotBlank() }?.let { "★ $it" }.orEmpty(),
                    )
                    .filter(String::isNotEmpty)
                    .joinToString(" • ")
            )
            if (release.genres.isNotBlank()) Text(release.genres)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Action("Серии", autofocus = true) { model.navigate(Route("episodes", release)) }
                Action(status?.label ?: "В библиотеку") { bookmarkDialog = true }
                Action(if (subscribed) "Отписаться" else "Новые серии") {
                    model.mutateLocal(
                        UiSection.SUBSCRIPTIONS,
                        after = { if (!subscribed) EpisodeScheduler.checkSoon(context) },
                    ) {
                        if (subscribed) remove("notifications.release.item.${release.id}")
                        else subscribe(release, release.currentEpisodes)
                    }
                }
            }
            loaded.error?.let { ErrorState(it) { retry++ } }
            // The list item has no description; skeleton lines until the page arrives.
            if (loaded.loading && release.description.isBlank()) DescriptionSkeleton()
            else Description(release.description.ifBlank { "Описание отсутствует" })
        }
    }
    if (bookmarkDialog)
        ChoiceDialog(
            "Библиотека",
            LibraryStatus.entries.map { it.label } + "Убрать из библиотеки",
            { bookmarkDialog = false },
        ) { index ->
            model.mutateLocal(UiSection.BOOKMARKS) {
                bookmark(release, LibraryStatus.entries.getOrNull(index))
            }
            bookmarkDialog = false
        }
}

/**
 * The release description: five lines with an ellipsis, the whole text while it has focus, so a
 * long description never runs off the screen and Down from the buttons opens it.
 */
@Composable
private fun Description(text: String) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Text(
        text,
        fontSize = 18.sp,
        maxLines = if (focused) Int.MAX_VALUE else 5,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier.fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .background(
                    if (focused) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    else Color.Transparent,
                    shape,
                )
                .focusFrame(focused, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), 12.dp)
                .padding(12.dp),
    )
}

@Composable
private fun EpisodeAction(
    text: String,
    modifier: Modifier,
    primary: Boolean = false,
    autofocus: Boolean = false,
    onClick: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autofocus) focus.requestFocus() }
    GuiButton(
        onClick = onClick,
        modifier = modifier.focusRequester(focus).height(44.dp * 1.05f),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    ) {
        androidx.tv.material3.Text(
            text,
            modifier = Modifier.fillMaxWidth(),
            fontSize = if (primary) 12.sp else 10.sp,
            lineHeight = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign =
                if (primary) androidx.compose.ui.text.style.TextAlign.Start
                else androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
internal fun Episodes(model: AppViewModel, activity: MainActivity, release: Release) {
    var retry by remember { mutableIntStateOf(0) }
    val data =
        resource("${release.id}:$retry") {
            val detail = model.repository.detail(release).value
            model.repository.episodes(detail)
        }
    val resumeEpisode = model.route.argument
    LaunchedEffect(data.value) {
        if (
            resumeEpisode.isNotBlank() && data.value?.value?.any { it.id == resumeEpisode } == true
        ) {
            model.prepare(activity, release, resumeEpisode)
        }
    }
    val progress = model.ui.progressByRelease[release.id].orEmpty()
    if (data.loading && data.value == null) EpisodeListSkeleton()
    data.error?.let { ErrorState(it) { retry++ } }
    if (data.value?.stale == true) Stale()
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 330.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(data.value?.value ?: emptyList(), key = { it.id }) { episode ->
            val watched = progress[episode.id]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EpisodeAction(
                    (if (watched?.watched == true) "✓ " else "") +
                        episode.title +
                        if (watched != null && !watched.watched)
                            " • ${watched.positionMs/60000} мин."
                        else "",
                    Modifier.weight(1f),
                    primary = true,
                    autofocus = episode.id == data.value?.value?.firstOrNull()?.id,
                ) {
                    model.prepare(activity, release, episode.id)
                }
                if (watched != null && watched.positionMs > 0)
                    EpisodeAction("С начала", Modifier.width(100.dp)) {
                        model.mutateLocal(
                            UiSection.PROGRESS,
                            after = { model.prepare(activity, release, episode.id) },
                        ) {
                            saveProgress(release, episode, 0, watched.durationMs)
                        }
                    }
            }
        }
    }
}

@Composable
internal fun Schedule(model: AppViewModel) {
    var retry by remember { mutableIntStateOf(0) }
    val data = resource(retry) { publish -> model.repository.schedule(onStale = publish) }
    val days = data.value?.value.orEmpty()
    val firstDayIndex = days.indexOfFirst { it.releases.isNotEmpty() }
    val firstRelease = days.getOrNull(firstDayIndex)?.releases?.firstOrNull()
    val listState = rememberLazyListState()
    var initialFocus by remember { mutableStateOf(true) }
    LaunchedEffect(firstDayIndex, initialFocus) {
        if (initialFocus && firstDayIndex >= 0) listState.scrollToItem(firstDayIndex)
    }
    // First load only: a refresh keeps showing the saved schedule instead.
    if (data.loading && data.value == null) ScheduleSkeleton()
    data.error?.let { ErrorState(it) { retry++ } }
    if (data.value?.stale == true) Stale()
    val marks = rememberPosterMarks(model.ui)
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(days, key = { it.title }) { day ->
            Column {
                Text(day.title, style = MaterialTheme.typography.titleLarge)
                if (day.releases.isEmpty()) Text("Нет релизов")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(day.releases, key = { it.id }) { release ->
                        Box(Modifier.width(170.dp)) {
                            Poster(
                                release,
                                restoreFocus =
                                    initialFocus &&
                                        day.title == days.getOrNull(firstDayIndex)?.title &&
                                        release.id == firstRelease?.id,
                                onFocused = { initialFocus = false },
                                progress = marks.progress(release.id),
                                isNew = marks.isNew(release.id),
                            ) {
                                model.navigate(Route("detail", release))
                            }
                        }
                    }
                }
            }
        }
    }
}
