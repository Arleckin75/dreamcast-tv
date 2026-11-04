package com.dreamcast.nativeapp.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import com.dreamcast.nativeapp.R
import com.dreamcast.nativeapp.data.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

private data class Load<T>(
    val value: T? = null,
    val error: String? = null,
    val loading: Boolean = true,
)

@Composable
private fun <T> resource(key: Any, loader: suspend () -> T): Load<T> {
    val state by
        produceState(Load<T>(), key) {
            value = Load()
            value =
                try {
                    Load(value = withContext(Dispatchers.IO) { loader() }, loading = false)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Load(error = error.message ?: "Ошибка загрузки", loading = false)
                }
        }
    return state
}

@Composable
fun DreamApp(model: AppViewModel, activity: MainActivity) {
    if (!model.uiReady) {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF101114)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }
    val theme = model.ui.theme
    val dark =
        when (theme) {
            "light" -> false
            "system" -> isSystemInDarkTheme()
            else -> true
        }
    val accent =
        when (model.ui.accent) {
            "green" -> Color(0xFF89CB6A)
            "violet" -> Color(0xFFD0BCFF)
            "rose" -> Color(0xFFFFB1C8)
            else -> Color(0xFFAEC6FF)
        }
    val context = LocalContext.current
    val colors =
        if (Build.VERSION.SDK_INT >= 31 && model.ui.dynamicColor && model.ui.accent == "system") {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (dark)
            darkColorScheme(
                primary = accent,
                background = Color(0xFF101114),
                surface = Color(0xFF191B20),
                onBackground = Color(0xFFF1F1F3),
                onSurface = Color(0xFFF1F1F3),
            )
        else
            lightColorScheme(
                primary =
                    accent.copy(
                        red = accent.red * 0.55f,
                        green = accent.green * 0.55f,
                        blue = accent.blue * 0.55f,
                    )
            )
    MaterialTheme(colorScheme = colors) {
        androidx.tv.material3.MaterialTheme(
            colorScheme =
                if (dark)
                    androidx.tv.material3.darkColorScheme(
                        primary = colors.primary,
                        onPrimary = colors.onPrimary,
                        surface = colors.surface,
                        onSurface = colors.onSurface,
                    )
                else
                    androidx.tv.material3.lightColorScheme(
                        primary = colors.primary,
                        onPrimary = colors.onPrimary,
                        surface = colors.surface,
                        onSurface = colors.onSurface,
                    )
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
                val route = model.route
                val saved = rememberSaveableStateHolder()
                BackHandler(model.routes.size > 1) { model.back() }
                Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
                    TopNavigation(model)
                    Spacer(Modifier.height(22.dp))
                    if (route.name !in listOf("home", "catalog", "search", "library", "schedule")) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            Text(
                                when (route.name) {
                                    "settings" -> "Настройки"
                                    "profile" -> "Профиль"
                                    "cache" -> "Кэш"
                                    "about" -> "О приложении"
                                    "notifications" -> "Уведомления"
                                    "episodes" -> "Серии"
                                    else -> route.release?.title.orEmpty()
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                    saved.SaveableStateProvider(route.key) {
                        when (route.name) {
                            "home" -> Home(model)
                            "catalog" -> Catalog(model)
                            "search" -> Search(model)
                            "library" -> Library(model)
                            "schedule" -> Schedule(model)
                            "detail" -> ReleaseDetail(model, route.release!!)
                            "episodes" -> Episodes(model, activity, route.release!!)
                            "settings" -> Settings(model)
                            "profile" -> Profile(model)
                            "cache" -> Cache(model)
                            "about" -> About()
                            "notifications" -> Notifications(model)
                        }
                    }
                }
                if (model.preparing)
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                model.playbackError?.let { error ->
                    AlertDialog(
                        onDismissRequest = { model.playbackError = null },
                        title = { Text("Не удалось открыть серию") },
                        text = { Text(error) },
                        confirmButton = {},
                    )
                }
                model.pendingStreams?.let { pending ->
                    val (pair, streams) = pending
                    ChoiceDialog(
                        "Поток видео",
                        streams.map { "${it.type.uppercase()} • ${it.quality}p" },
                        onDismiss = { model.pendingStreams = null },
                    ) { index ->
                        activity.launchPlayback(pair.first, pair.second, streams[index])
                    }
                }
            }
        }
    }
}

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
private fun GuiButton(
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
private fun Action(
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
private fun ErrorState(message: String, retry: () -> Unit) {
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
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun Stale() {
    Text(
        "Нет свежих данных. Показана сохранённая версия.",
        modifier =
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(12.dp),
    )
}

@Composable
private fun TopNavigation(model: AppViewModel) {
    val unread = model.ui.unreadNotifications
    val tabs =
        listOf(
            Triple("catalog", "Каталог", R.drawable.ic_nav_catalog),
            Triple("search", "Поиск", R.drawable.ic_nav_search),
            Triple(
                "notifications",
                if (unread > 0) "Уведомления ($unread)" else "Уведомления",
                R.drawable.ic_nav_notifications,
            ),
            Triple("library", "Библиотека", R.drawable.ic_nav_library),
            Triple("schedule", "Расписание", R.drawable.ic_nav_schedule),
            Triple("settings", "Настройки", R.drawable.ic_nav_settings),
        )
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        tabs.forEach { (route, label, icon) ->
            val requester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                if (route == "search" && model.route.name == "home") requester.requestFocus()
            }
            GuiButton(
                onClick = {
                    if (route in listOf("notifications", "settings")) model.navigate(Route(route))
                    else model.selectSection(route)
                },
                modifier =
                    Modifier.size(44.dp).focusRequester(requester).semantics {
                        contentDescription = label
                    },
                selected =
                    model.route.name == route ||
                        (route == "settings" &&
                            model.route.name in listOf("profile", "cache", "about")),
                iconOnly = true,
                contentPadding = PaddingValues(0.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(icon),
                        contentDescription = null,
                        tint = androidx.tv.material3.LocalContentColor.current,
                        modifier = Modifier.size(24.dp),
                    )
                    if (route == "notifications" && unread > 0) {
                        Box(
                            Modifier.align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(16.dp)
                                .background(Color(0xFFBF223C), RoundedCornerShape(50)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (unread > 9) "9+" else unread.toString(),
                                color = Color.White,
                                fontSize = 9.sp,
                                lineHeight = 10.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Home(model: AppViewModel) {
    var retry by remember { mutableIntStateOf(0) }
    val data =
        resource(retry) { model.repository.releases("", CatalogFilter(), 1, refresh = retry > 0) }
    val releases = data.value?.items.orEmpty()
    var featuredId by rememberSaveable { mutableIntStateOf(-1) }
    val featured = releases.firstOrNull { it.id == featuredId } ?: releases.firstOrNull()
    val recent = model.ui.continueWatching
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            if (featured != null)
                FeaturedRelease(featured) { model.navigate(Route("detail", featured)) }
            else if (data.loading) Loading() else data.error?.let { ErrorState(it) { retry++ } }
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
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Последние обновления", style = MaterialTheme.typography.titleLarge)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(6.dp),
                    ) {
                        items(releases, key = { it.id }) { release ->
                            Box(Modifier.width(200.dp)) {
                                Poster(
                                    release,
                                    onFocused = { featuredId = release.id },
                                    landscape = true,
                                ) {
                                    model.navigate(Route("detail", release))
                                }
                            }
                        }
                    }
                    Action("Все релизы  ›") { model.selectSection("catalog") }
                }
            }
    }
}

@Composable
private fun FeaturedRelease(release: Release, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .height(270.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        AsyncImage(
            release.posterUrl,
            null,
            Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.56f).fillMaxHeight(),
            contentScale = ContentScale.Crop,
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
                "DREAM CAST TV",
                fontSize = 11.sp,
                letterSpacing = 3.sp,
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
            Action("Подробнее", onClick = onClick)
        }
    }
}

@Composable
private fun Catalog(model: AppViewModel) {
    var year by rememberSaveable { mutableStateOf(0) }
    var genres by rememberSaveable { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    val options = resource(retry) { model.repository.options() }
    var yearDialog by remember { mutableStateOf(false) }
    var genreDialog by remember { mutableStateOf(false) }
    val selected = genres.split(',').filter(String::isNotBlank).toSet()
    val names =
        options.value
            ?.value
            ?.genres
            ?.filter { it.id in selected }
            ?.joinToString(", ") { it.name }
            .orEmpty()
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Action(
            "Год: ${if(year==0)"любой" else year}",
            enabled = options.value != null,
            autofocus = true,
        ) {
            yearDialog = true
        }
        Action(
            "Жанры: ${if(selected.isEmpty())"все" else names.take(55)}",
            enabled = options.value != null,
        ) {
            genreDialog = true
        }
        Action("Показать все") {
            year = 0
            genres = ""
        }
    }
    if (options.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    options.error?.let { ErrorState("Не удалось загрузить фильтры: $it") { retry++ } }
    if (options.value?.stale == true) Stale()
    Spacer(Modifier.height(12.dp))
    ReleaseFeed(model, CatalogFilter(year.takeIf { it != 0 }, selected), "")
    if (yearDialog)
        options.value?.value?.let { data ->
            ChoiceDialog(
                "Год выпуска",
                listOf("Любой год") + data.years.map(Int::toString),
                { yearDialog = false },
            ) {
                year = if (it == 0) 0 else data.years[it - 1]
                yearDialog = false
            }
        }
    if (genreDialog)
        options.value?.value?.let { data ->
            var draft by remember { mutableStateOf(selected) }
            AlertDialog(
                onDismissRequest = { genreDialog = false },
                title = { Text("Жанры") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(data.genres, key = { it.id }) { genre ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        draft =
                                            if (genre.id in draft) draft - genre.id
                                            else draft + genre.id
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = genre.id in draft,
                                    onCheckedChange = { checked ->
                                        draft = if (checked) draft + genre.id else draft - genre.id
                                    },
                                )
                                Text(genre.name)
                            }
                        }
                    }
                },
                confirmButton = {
                    Action("Применить") {
                        genres = draft.sorted().joinToString(",")
                        genreDialog = false
                    }
                },
                dismissButton = { Action("Сбросить") { draft = emptySet() } },
            )
        }
}

@Composable
private fun Search(model: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(420)
        submitted = query.trim().takeIf { it.length >= 2 }.orEmpty()
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            query,
            { query = it },
            modifier = Modifier.weight(1f),
            label = { Text("Название") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions =
                KeyboardActions(
                    onSearch = {
                        val search = query.trim()
                        submitted = search
                        if (search.isNotBlank()) model.mutateLocal { saveSearch(search) }
                    }
                ),
        )
        Action("Найти") {
            val search = query.trim()
            submitted = search
            if (search.isNotBlank()) model.mutateLocal { saveSearch(search) }
        }
    }
    Spacer(Modifier.height(12.dp))
    if (submitted.isBlank()) {
        val history = model.ui.searchHistory
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("История поиска", style = MaterialTheme.typography.titleLarge) }
            items(history, key = { it }) { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Action(item, Modifier.weight(1f)) {
                        query = item
                        submitted = item
                    }
                    Action("Удалить") { model.mutateLocal { removeSearch(item) } }
                }
            }
        }
    } else ReleaseFeed(model, CatalogFilter(), submitted)
}

@Composable
private fun ReleaseFeed(model: AppViewModel, filter: CatalogFilter, query: String) {
    val key = "${filter.cacheKey}:$query"
    val feed = remember(key) { model.feeds.getOrPut(key) { FeedState() } }
    var page by feed::page
    var retry by remember(key) { mutableIntStateOf(0) }
    var all by feed::items
    var total by feed::total
    var stale by feed::stale
    var exhausted by feed::exhausted
    val data =
        resource("$key:$page:$retry") {
            model.repository.releases(query, filter, page, refresh = retry > 0)
        }
    LaunchedEffect(data.value) {
        data.value?.let { response ->
            if (response.page <= feed.loadedPage) return@LaunchedEffect
            val added =
                if (page == 1) response.items else (all + response.items).distinctBy { it.id }
            exhausted = page > 1 && added.size == all.size
            all = added
            total = response.total
            stale = stale || response.stale
            feed.loadedPage = response.page
        }
    }
    val grid = rememberLazyGridState()
    var focused by rememberSaveable(key) { mutableIntStateOf(-1) }
    val hasMore = !exhausted && all.size < total
    LaunchedEffect(grid, key, hasMore, data.loading, data.error) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                if (
                    last >= all.size - 8 &&
                        all.isNotEmpty() &&
                        hasMore &&
                        !data.loading &&
                        data.error == null
                )
                    page++
            }
    }
    Column(Modifier.fillMaxSize()) {
        if (stale) Stale()
        if (data.loading && all.isEmpty()) Loading()
        if (!data.loading && all.isEmpty() && data.error == null)
            Text("Релизы не найдены", modifier = Modifier.padding(24.dp))
        if (all.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Найдено: $total", modifier = Modifier.padding(vertical = 8.dp))
                if (query.isBlank())
                    Action("Обновить") {
                        feed.loadedPage = 0
                        all = emptyList()
                        stale = false
                        exhausted = false
                        page = 1
                        retry++
                    }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                state = grid,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(all, key = { it.id }) { release ->
                    Poster(release, focused == release.id, { focused = release.id }) {
                        model.navigate(Route("detail", release))
                    }
                }
                item {
                    if (data.loading) CircularProgressIndicator()
                    else if (data.error != null) Action("Повторить") { retry++ }
                    else if (hasMore) Action("Загрузить ещё") { page++ }
                }
            }
        }
        data.error?.let { if (all.isEmpty()) ErrorState(it) { retry++ } }
    }
}

@Composable
private fun Poster(
    release: Release,
    restoreFocus: Boolean = false,
    onFocused: () -> Unit = {},
    landscape: Boolean = false,
    onClick: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.035f else 1f, label = "posterFocus")
    LaunchedEffect(Unit) { if (restoreFocus) requester.requestFocus() }
    Column(
        Modifier.fillMaxWidth()
            .reserveFocusScale(1.035f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
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

@Composable
private fun Library(model: AppViewModel) {
    var status by rememberSaveable { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Action("Все") { status = "" }
        LibraryStatus.entries.forEach { item -> Action(item.label) { status = item.name } }
    }
    Spacer(Modifier.height(16.dp))
    val entries =
        remember(model.ui.bookmarks, status) {
            model.ui.bookmarks.filter { status.isEmpty() || it.status.name == status }
        }
    if (entries.isEmpty()) Text("В этой категории пока нет релизов")
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(entries, key = { it.release.id }) { item ->
            Poster(item.release) { model.navigate(Route("detail", item.release)) }
        }
    }
}

@Composable
private fun ReleaseDetail(model: AppViewModel, seed: Release) {
    val context = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    val loaded = resource("${seed.id}:$retry") { model.repository.detail(seed) }
    val release = loaded.value?.value?.release ?: seed
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
                Action("Серии") { model.navigate(Route("episodes", release)) }
                Action(status?.label ?: "В библиотеку") { bookmarkDialog = true }
                Action(if (subscribed) "Отписаться" else "Новые серии") {
                    model.mutateLocal(
                        after = { if (!subscribed) EpisodeScheduler.checkSoon(context) }
                    ) {
                        if (subscribed) remove("notifications.release.item.${release.id}")
                        else subscribe(release, release.currentEpisodes)
                    }
                }
            }
            if (loaded.loading) Loading()
            loaded.error?.let { ErrorState(it) { retry++ } }
            Text(
                release.description.ifBlank { "Описание отсутствует" },
                fontSize = 18.sp,
                modifier = Modifier.focusable(),
            )
        }
    }
    if (bookmarkDialog)
        ChoiceDialog(
            "Библиотека",
            LibraryStatus.entries.map { it.label } + "Убрать из библиотеки",
            { bookmarkDialog = false },
        ) { index ->
            model.mutateLocal { bookmark(release, LibraryStatus.entries.getOrNull(index)) }
            bookmarkDialog = false
        }
}

@Composable
private fun EpisodeAction(
    text: String,
    modifier: Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    GuiButton(
        onClick = onClick,
        modifier = modifier.height(44.dp * 1.05f),
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
private fun Episodes(model: AppViewModel, activity: MainActivity, release: Release) {
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
    if (data.loading) Loading()
    data.error?.let { ErrorState(it) { retry++ } }
    if (data.value?.stale == true) Stale()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Preserve the previous 2:1 button proportions, enlarging each width by 20%.
        val previousUnit = ((maxWidth / 3f - 20.dp) / 4f).coerceAtLeast(1.dp)
        val seriesWidth = previousUnit * 2f * 1.2f
        val restartWidth = previousUnit * 1.2f
        LazyColumn(
            modifier = Modifier.width(seriesWidth + restartWidth + 14.dp),
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
                        Modifier.width(seriesWidth),
                        primary = true,
                    ) {
                        model.prepare(activity, release, episode.id)
                    }
                    if (watched != null && watched.positionMs > 0)
                        EpisodeAction("С начала", Modifier.width(restartWidth)) {
                            model.mutateLocal(
                                after = { model.prepare(activity, release, episode.id) }
                            ) {
                                saveProgress(release, episode, 0, watched.durationMs)
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun Schedule(model: AppViewModel) {
    var retry by remember { mutableIntStateOf(0) }
    val data = resource(retry) { model.repository.schedule() }
    if (data.loading) Loading()
    data.error?.let { ErrorState(it) { retry++ } }
    if (data.value?.stale == true) Stale()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(data.value?.value ?: emptyList(), key = { it.title }) { day ->
            Column {
                Text(day.title, style = MaterialTheme.typography.titleLarge)
                if (day.releases.isEmpty()) Text("Нет релизов")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(day.releases, key = { it.id }) { release ->
                        Box(Modifier.width(170.dp)) {
                            Poster(release) { model.navigate(Route("detail", release)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    items: List<String>,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                Modifier.heightIn(max = 330.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items.size) { index ->
                    Action(items[index], Modifier.fillMaxWidth(), autofocus = index == 0) {
                        onSelect(index)
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun Settings(model: AppViewModel) {
    var dialog by remember { mutableStateOf("") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Action("Профиль: ${model.ui.profile.ifBlank{"Не задан"}}", Modifier.fillMaxWidth()) {
                model.navigate(Route("profile"))
            }
        }
        item { Action("Тема", Modifier.fillMaxWidth()) { dialog = "theme" } }
        item { Action("Акцент", Modifier.fillMaxWidth()) { dialog = "accent" } }
        item {
            Action(
                "Цвета системы: ${if(model.ui.dynamicColor)"включены" else "выключены"}",
                Modifier.fillMaxWidth(),
            ) {
                model.mutateLocal {
                    put("settings.dynamic_color", !boolean("settings.dynamic_color", true))
                }
            }
        }
        item {
            Action(
                "Проигрыватель: ${model.ui.streamTechnology.uppercase()}",
                Modifier.fillMaxWidth(),
            ) {
                dialog = "player"
            }
        }
        item { Action("Кэш", Modifier.fillMaxWidth()) { model.navigate(Route("cache")) } }
        item { Action("О приложении", Modifier.fillMaxWidth()) { model.navigate(Route("about")) } }
    }
    when (dialog) {
        "theme" ->
            ChoiceDialog("Тема", listOf("Системная", "Светлая", "Тёмная"), { dialog = "" }) { index
                ->
                model.mutateLocal {
                    put("settings.theme_mode", listOf("system", "light", "dark")[index])
                }
                dialog = ""
            }
        "accent" ->
            ChoiceDialog(
                "Акцент",
                listOf("Системный", "Синий", "Зелёный", "Фиолетовый", "Розовый"),
                { dialog = "" },
            ) { index ->
                model.mutateLocal {
                    put(
                        "settings.accent",
                        listOf("system", "blue", "green", "violet", "rose")[index],
                    )
                }
                dialog = ""
            }
        "player" ->
            ChoiceDialog(
                "Технология потока",
                listOf("Выбирать вручную", "HLS", "DASH"),
                { dialog = "" },
            ) { index ->
                model.mutateLocal {
                    put("player.preferred_stream_technology", listOf("none", "hls", "dash")[index])
                }
                dialog = ""
            }
    }
}

@Composable
private fun Profile(model: AppViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var edited by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(model.ui.profile) { if (!edited) name = model.ui.profile }
    var saved by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Локальный профиль")
        OutlinedTextField(
            name,
            {
                if (it.length <= 32) {
                    name = it
                    edited = true
                    saved = false
                }
            },
            label = { Text("Имя") },
            singleLine = true,
        )
        Action("Сохранить", enabled = name.trim().isNotEmpty()) {
            val updatedName = name
            model.mutateLocal(after = { if (name == updatedName) saved = true }) {
                saveProfile(updatedName)
            }
        }
        if (saved) Text("Профиль сохранён")
    }
}

@Composable
private fun Cache(model: AppViewModel) {
    var retentionDialog by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    val stats by
        produceState<Pair<Long, Long>?>(null, model.revision) {
            value = withContext(Dispatchers.IO) { model.storage.cacheStats() }
        }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stats?.let { "Записей: ${it.first} • ${it.second/1024} КБ" } ?: "Подсчёт кэша…")
        Text("Срок хранения: ${model.ui.retentionHours.let{if(it==0L)"бессрочно" else "$it ч."}}")
        Action("Срок хранения") { retentionDialog = true }
        Action("Очистить просроченные") { model.mutateLocal { clearExpired() } }
        Action("Очистить весь кэш") { clearDialog = true }
    }
    if (retentionDialog)
        ChoiceDialog(
            "Срок хранения",
            listOf("8 часов", "12 часов", "1 день", "3 дня", "7 дней", "30 дней", "Бессрочно"),
            { retentionDialog = false },
        ) { index ->
            model.mutateLocal { retention(listOf(8L, 12L, 24L, 72L, 168L, 720L, 0L)[index]) }
            retentionDialog = false
        }
    if (clearDialog)
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text("Очистить кэш?") },
            text = { Text("Библиотека и история просмотра сохранятся.") },
            confirmButton = {
                Action(
                    "Очистить",
                    onClick = {
                        model.mutateLocal { clearCache() }
                        clearDialog = false
                    },
                )
            },
        )
}

@Composable
private fun Notifications(model: AppViewModel) {
    val entries = model.ui.notifications
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Action("Прочитать все") {
            model.mutateLocal {
                entries.forEach { entry ->
                    put(
                        entry.key,
                        org.json
                            .JSONObject(entry.raw)
                            .put("readAt", Instant.now().toString())
                            .toString(),
                    )
                }
            }
        }
        Action("Очистить историю") { model.mutateLocal { entries.forEach { remove(it.key) } } }
    }
    Spacer(Modifier.height(16.dp))
    if (entries.isEmpty()) Text("Уведомлений пока нет")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(entries, key = { it.key }) { entry ->
            entry.release?.let { release ->
                Action(
                    "${if(entry.unread)"● " else ""}${release.title} • ${entry.message}",
                    Modifier.fillMaxWidth(),
                ) {
                    model.mutateLocal {
                        put(
                            entry.key,
                            org.json
                                .JSONObject(entry.raw)
                                .put("readAt", Instant.now().toString())
                                .toString(),
                        )
                    }
                    model.navigate(Route("detail", release))
                }
            }
        }
    }
}

@Composable
private fun About() {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(
                "Dream Cast TV ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.titleLarge,
            )
        }
        item {
            Text(
                "Нативный клиент для Android TV. Лицензия GNU GPL v3. Оригинальное приложение разработано rurin1x. Версия для Android TV является модификацией оригинального проекта."
            )
        }
        val links =
            listOf(
                "Сайт" to BASE_URL,
                "Telegram" to "https://t.me/dreamercast",
                "ВКонтакте" to "https://vk.com/dreamerscast",
                "YouTube" to "https://www.youtube.com/@AdminDreamCast",
                "Оригинальные исходники" to "https://github.com/rurin1x/dreamcast-app",
                "Исходники Android TV" to "https://github.com/Arleckin75/dreamcast-tv",
            )
        items(links) { (label, url) -> Action(label) { text = url } }
        item {
            Action("Текст лицензии GPLv3") {
                text =
                    context.assets.open("licenses/GPL-3.0.txt").bufferedReader().use {
                        it.readText()
                    }
            }
        }
    }
    text?.let { content ->
        AlertDialog(
            onDismissRequest = { text = null },
            title = { Text(if (content.startsWith("http")) "Ссылка" else "GNU GPL v3") },
            text = {
                Text(
                    content,
                    modifier =
                        Modifier.heightIn(max = 330.dp)
                            .verticalScroll(rememberScrollState())
                            .focusable(),
                )
            },
            confirmButton = {},
        )
    }
}
