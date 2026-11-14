package com.dreamcast.nativeapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.dreamcast.nativeapp.*
import com.dreamcast.nativeapp.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun Catalog(model: AppViewModel) {
    var year by rememberSaveable { mutableStateOf(0) }
    var genres by rememberSaveable { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    val options = resource(retry) { publish -> model.repository.options(onStale = publish) }
    var yearDialog by remember { mutableStateOf(false) }
    var genreDialog by remember { mutableStateOf(false) }
    val selected = genres.split(',').filter(String::isNotBlank).toSet()
    // Releases per genre, counted in the background and cached for a day; empty until known.
    val allGenres = options.value?.value?.genres.orEmpty()
    val genreCounts =
        resource(allGenres.map { it.id }) {
            if (allGenres.isEmpty()) emptyMap() else model.repository.genreCounts(allGenres).value
        }.value.orEmpty()
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
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        LazyColumn(
                            Modifier.weight(1f).heightIn(max = 320.dp),
                            contentPadding = PaddingValues(vertical = 6.dp),
                        ) {
                            // Genres without releases are hidden unless already chosen.
                            val shown =
                                data.genres.filter { (genreCounts[it.id] ?: 1) > 0 || it.id in draft }
                            items(shown, key = { it.id }) { genre ->
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
                                            draft =
                                                if (checked) draft + genre.id else draft - genre.id
                                        },
                                    )
                                    Text(
                                        genreCounts[genre.id]?.let { "${genre.name} ($it)" }
                                            ?: genre.name
                                    )
                                }
                            }
                        }
                        Column(
                            Modifier.width(132.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Action("Применить", Modifier.fillMaxWidth()) {
                                genres = draft.sorted().joinToString(",")
                                genreDialog = false
                            }
                            Action("Сбросить", Modifier.fillMaxWidth()) { draft = emptySet() }
                        }
                    }
                },
                confirmButton = {},
            )
        }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Search(model: AppViewModel) {
    val inputFocus = remember { FocusRequester() }
    val resultsFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val moveToResults: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
            // Read current window insets: Compose's observed visibility may lag after IME dismissal.
            val keyboardVisible = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            if (keyboardVisible) false
            else resultsFocus.requestFocus().also { moved -> if (moved) keyboard?.hide() }
        } else false
    }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        inputFocus.requestFocus()
    }
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(420)
        submitted = query.trim().takeIf { it.length >= 2 }.orEmpty()
    }
    Row(
        // IMEs can consume D-pad keys even while the keyboard is hidden.
        modifier = Modifier.onPreInterceptKeyBeforeSoftKeyboard(moveToResults)
            .onPreviewKeyEvent(moveToResults),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            query,
            { query = it },
            modifier = Modifier.weight(1f).focusRequester(inputFocus),
            label = { Text("Название") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions =
                KeyboardActions(
                    onSearch = {
                        val search = query.trim()
                        submitted = search
                        if (search.isNotBlank())
                            model.mutateLocal(UiSection.SEARCH) { saveSearch(search) }
                    }
                ),
        )
        Action("Найти") {
            val search = query.trim()
            submitted = search
            if (search.isNotBlank()) model.mutateLocal(UiSection.SEARCH) { saveSearch(search) }
        }
    }
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxSize().focusRequester(resultsFocus).focusGroup()) {
        if (submitted.isBlank()) {
            val history = model.ui.searchHistory
            LazyColumn(
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Text("История поиска", style = MaterialTheme.typography.titleLarge) }
                items(history, key = { it }) { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Action(item, Modifier.weight(1f)) {
                            query = item
                            submitted = item
                        }
                        Action("Удалить") { model.mutateLocal(UiSection.SEARCH) { removeSearch(item) } }
                    }
                }
            }
        } else ReleaseFeed(model, CatalogFilter(), submitted)
    }
}

@Composable
private fun ReleaseFeed(model: AppViewModel, filter: CatalogFilter, query: String) {
    val key = "${filter.cacheKey}:$query"
    val feed = remember(key) { model.feed(key) }
    var page by feed::page
    var retry by remember(key) { mutableIntStateOf(0) }
    var all by feed::items
    var total by feed::total
    var stale by feed::stale
    var exhausted by feed::exhausted
    val data =
        resource("$key:$page:$retry") { publish ->
            feed to
                model.repository.releases(
                    query,
                    filter,
                    page,
                    refresh = retry > 0,
                    onStale = { publish(feed to it) },
                )
        }
    val response = data.value?.takeIf { it.first === feed }?.second
    // Do not request another page until the current response has been merged and published.
    val loading = data.loading || (data.error == null && page > feed.loadedPage)
    LaunchedEffect(feed, response, data.loading) {
        if (data.loading) return@LaunchedEffect
        response?.let { response ->
            if (response.page <= feed.loadedPage) return@LaunchedEffect
            val merged = mergeFeedPage(all, response)
            exhausted = merged.exhausted
            all = merged.items
            total = response.total
            stale = stale || response.stale
            feed.loadedPage = response.page
            model.trimFeeds(key)
        }
    }
    val grid = rememberLazyGridState()
    var focused by rememberSaveable(key) { mutableIntStateOf(-1) }
    val hasMore = !exhausted && all.size < total
    val displayed = if (all.isEmpty()) response?.items.orEmpty() else all
    LaunchedEffect(grid, key, hasMore, loading, data.error) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                if (
                    last >= all.size - 8 &&
                        all.isNotEmpty() &&
                        hasMore &&
                        !loading &&
                        data.error == null
                )
                    feed.requestNextPage()
            }
    }
    Column(Modifier.fillMaxSize()) {
        if (stale || response?.stale == true) Stale()
        if (loading && displayed.isEmpty()) PosterGridSkeleton()
        if (!loading && all.isEmpty() && data.error == null)
            Text("Релизы не найдены", modifier = Modifier.padding(24.dp))
        if (displayed.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Найдено: $total", modifier = Modifier.padding(vertical = 8.dp))
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                state = grid,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(displayed, key = { it.id }) { release ->
                    Poster(release, focused == release.id, { focused = release.id }) {
                        model.navigate(Route("detail", release))
                    }
                }
                item {
                    if (loading) CircularProgressIndicator()
                    else if (data.error != null) Action("Повторить") { retry++ }
                    else if (hasMore) Action("Загрузить ещё") { feed.requestNextPage() }
                }
            }
        }
        data.error?.let { if (all.isEmpty()) ErrorState(it) { retry++ } }
    }
}

@Composable
internal fun Library(model: AppViewModel) {
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
