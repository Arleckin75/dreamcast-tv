package com.dreamcast.nativeapp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.launch

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
    // The AniList banner, drawn across the whole screen by DreamApp. Looked up only when the
    // setting asks for banners: otherwise nothing is sent to AniList.
    val bannersOn = model.ui.releaseBanners
    val banner by produceState<String?>(null, release.id, release.originalTitle, bannersOn) {
        value = if (bannersOn) AniListBanners.find(model.storage, release) else null
    }
    val bannerSlot = LocalReleaseBanner.current
    DisposableEffect(banner) {
        bannerSlot?.value = banner
        onDispose { bannerSlot?.value = null }
    }
    // The content keeps its place whether or not a banner arrives; the banner only fades in
    // behind it, so nothing jumps under the focus.
    Row(
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
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
            selected = status?.let { LibraryStatus.entries.indexOf(it) } ?: -1,
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
    // Keyed on autofocus: the start episode is known only once the progress has loaded.
    LaunchedEffect(autofocus) { if (autofocus) focus.requestFocus() }
    GuiButton(
        onClick = onClick,
        modifier = modifier.focusRequester(focus).height(44.dp * 1.05f),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    ) {
        // 14 sp: smaller text is hard to read from the sofa.
        androidx.tv.material3.Text(
            text,
            modifier = Modifier.fillMaxWidth(),
            fontSize = 14.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign =
                if (primary) androidx.compose.ui.text.style.TextAlign.Start
                else androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
internal fun Episodes(model: AppViewModel, release: Release) {
    var retry by remember { mutableIntStateOf(0) }
    val data =
        resource("${release.id}:$retry") {
            val detail = model.repository.detail(release).value
            model.repository.episodes(detail)
        }
    val progress = model.ui.progressByRelease[release.id].orEmpty()
    val episodes = data.value?.value.orEmpty()
    // Focus starts on the episode to watch next, scrolled into view, not on the first one.
    val start = remember(episodes, progress) { startEpisode(episodes, progress) }
    val listState = rememberLazyListState()
    LaunchedEffect(start) {
        val index = episodes.indexOfFirst { it.id == start }
        if (index > 0) listState.scrollToItem(index)
    }
    if (data.loading && data.value == null) EpisodeListSkeleton()
    data.error?.let { ErrorState(it) { retry++ } }
    if (data.value?.stale == true) Stale()
    LazyColumn(
        state = listState,
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
                    autofocus = episode.id == start,
                ) {
                    model.prepare(release, episode.id)
                }
                if (watched != null && watched.positionMs > 0)
                    EpisodeAction("С начала", Modifier.width(100.dp)) {
                        model.mutateLocal(
                            UiSection.PROGRESS,
                            after = { model.prepare(release, episode.id) },
                        ) {
                            saveProgress(release, episode, 0, watched.durationMs)
                        }
                    }
            }
        }
    }
}

/**
 * The episode the list starts on: the one watched last if it is not finished, else the one after
 * it; the first episode when nothing has been watched yet.
 */
internal fun startEpisode(episodes: List<Episode>, progress: Map<String, WatchProgress>): String? {
    val (index, watched) =
        episodes
            .mapIndexedNotNull { i, episode -> progress[episode.id]?.let { i to it } }
            .maxByOrNull { it.second.updatedAt } ?: return episodes.firstOrNull()?.id
    if (!watched.watched) return episodes[index].id
    return (episodes.getOrNull(index + 1) ?: episodes[index]).id
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Schedule(model: AppViewModel) {
    var retry by remember { mutableIntStateOf(0) }
    val data = resource(retry) { publish -> model.repository.schedule(onStale = publish) }
    val days = data.value?.value.orEmpty()
    // Start on today (or the next day with releases), as the home screen does.
    val firstDayIndex =
        remember(days) {
            nearestScheduleDay(days, java.time.LocalDate.now().dayOfWeek)?.let { days.indexOf(it.first) }
                ?: -1
        }
    val firstRelease = days.getOrNull(firstDayIndex)?.releases?.firstOrNull()
    val listState = rememberLazyListState()
    // The first visit focuses the first release of the first day with releases; after that the
    // last focused poster ("<day>/<release id>") gets focus back when returning from a release.
    var initialFocus by rememberSaveable { mutableStateOf(true) }
    var focusedCard by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(firstDayIndex, initialFocus) {
        if (initialFocus && firstDayIndex >= 0) listState.scrollToItem(firstDayIndex)
    }
    // First load only: a refresh keeps showing the saved schedule instead.
    if (data.loading && data.value == null) ScheduleSkeleton()
    data.error?.let { ErrorState(it) { retry++ } }
    if (data.value?.stale == true) Stale()
    val marks = rememberPosterMarks(model.ui)
    val scope = rememberCoroutineScope()
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(days, key = { it.title }) { day ->
            // Focus scrolls only far enough to show the poster, which leaves the day title above
            // it off screen when moving up; bring the whole day into view instead.
            val reveal = remember { BringIntoViewRequester() }
            Column(
                Modifier.bringIntoViewRequester(reveal).onFocusChanged {
                    if (it.hasFocus) scope.launch { reveal.bringIntoView() }
                }
            ) {
                Text(day.title, style = MaterialTheme.typography.titleLarge)
                if (day.releases.isEmpty()) Text("Нет релизов")
                // Five cards fit the width exactly, as on the home screen: none is cut at the edge.
                FittedRow(day.releases, perScreen = 5, key = { it.id }) { release ->
                    val card = "${day.title}/${release.id}"
                    Poster(
                        release,
                        restoreFocus =
                            focusedCard == card ||
                                initialFocus &&
                                    day.title == days.getOrNull(firstDayIndex)?.title &&
                                    release.id == firstRelease?.id,
                        onFocused = {
                            initialFocus = false
                            focusedCard = card
                        },
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

/** The banner URL the release screen wants shown behind the whole screen. */
internal val LocalReleaseBanner = staticCompositionLocalOf<MutableState<String?>?> { null }

/**
 * A banner from edge to edge at the top of the screen, fading out below. It fades in once the
 * image has loaded instead of popping up, and the content never moves for it.
 */
@Composable
internal fun ReleaseBanner(url: String?) {
    if (url == null) return
    var loaded by remember(url) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (loaded) 1f else 0f, tween(600), label = "banner")
    // Surface stretches its children to the whole screen; a Box keeps the banner's own height.
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            url,
            null,
            modifier =
                Modifier.fillMaxWidth()
                    .height(330.dp)
                    .graphicsLayer(
                        alpha = alpha,
                        compositingStrategy = CompositingStrategy.Offscreen,
                    )
                    .drawWithContent {
                        drawContent()
                        // Pale enough for the title and buttons that sit on its middle.
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.7f),
                                0.55f to Color.Black.copy(alpha = 0.35f),
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            contentScale = ContentScale.Crop,
            onSuccess = { loaded = true },
        )
    }
}
