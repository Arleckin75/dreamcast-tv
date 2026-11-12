package com.dreamcast.nativeapp.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamcast.nativeapp.*
import com.dreamcast.nativeapp.R
import com.dreamcast.nativeapp.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class Load<T>(
    val value: T? = null,
    val error: String? = null,
    val loading: Boolean = true,
)

@Composable
internal fun <T> resource(key: Any, loader: suspend ((T) -> Unit) -> T): Load<T> {
    val state by
        produceState(Load<T>(), key) {
            value = Load()
            value =
                try {
                    val owner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                    Load(
                        value =
                            withContext(Dispatchers.IO) {
                                loader { cached ->
                                    if (owner?.isActive == true) value = Load(value = cached)
                                }
                            },
                        loading = false,
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Load(error = error.message ?: "Ошибка загрузки", loading = false)
                }
        }
    return state
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DreamApp(model: AppViewModel, activity: MainActivity) {
    // Keep focused items stationary until their reserved bounds leave the viewport.
    // The margin covers the focus scale (4%) and the antialiasing edge of focused buttons.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scrollSpec = remember(density) { VisibleItemScrollSpec(with(density) { 6.dp.toPx() }) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides scrollSpec) {
        DreamAppContent(model, activity)
    }
}

@Composable
private fun DreamAppContent(model: AppViewModel, activity: MainActivity) {
    LaunchedEffect(Unit) {
        withFrameNanos {}
        NativePerformance.firstFrame()
        model.application.maintainCacheAfterFirstFrame()
        // Debug builds use another application ID, so a release APK would not update them.
        if (!BuildConfig.DEBUG) model.updates.checkAutomatically()
    }
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
                val contentRoute =
                    if (route.name == "episodes")
                        model.routes.dropLast(1).lastOrNull() ?: Route("detail", route.release)
                    else route
                val backdrop = remember(contentRoute.key) { PosterBackdropState() }
                PosterBackdrop(backdrop, colors.background, dark)
                val saved = rememberSaveableStateHolder()
                val retainedRoutes =
                    model.routes.map { it.key }.toSet() +
                        setOf("home", "catalog", "search", "library", "schedule").map {
                            Route(it).key
                        }
                val previousRoutes = remember { mutableSetOf<String>() }
                DisposableEffect(route.key) {
                    previousRoutes += route.key
                    onDispose {}
                }
                LaunchedEffect(retainedRoutes) {
                    val removed = previousRoutes.filter { it !in retainedRoutes }
                    removed.forEach {
                        saved.removeState(it)
                        previousRoutes.remove(it)
                    }
                }
                BackHandler(model.routes.size > 1) { model.back() }
                Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
                    TopNavigation(model)
                    Spacer(Modifier.height(22.dp))
                    if (
                        contentRoute.name !in
                            listOf("home", "catalog", "search", "library", "schedule")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            Text(
                                when (contentRoute.name) {
                                    "settings" -> "Настройки"
                                    "profile" -> "Профиль"
                                    "cache" -> "Кэш"
                                    "about" -> "О приложении"
                                    "notifications" -> "Уведомления"
                                    "episodes" -> "Серии"
                                    else -> contentRoute.release?.title.orEmpty()
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                    saved.SaveableStateProvider(contentRoute.key) {
                        CompositionLocalProvider(LocalPosterBackdrop provides backdrop) {
                            when (contentRoute.name) {
                                "home" -> Home(model)
                                "catalog" -> Catalog(model)
                                "search" -> Search(model)
                                "library" -> Library(model)
                                "schedule" -> Schedule(model)
                                "detail" -> ReleaseDetail(model, contentRoute.release!!)
                                "settings" -> Settings(model)
                                "profile" -> Profile(model)
                                "cache" -> Cache(model)
                                "about" -> About()
                                "notifications" -> Notifications(model)
                            }
                        }
                    }
                }
                if (route.name == "episodes") {
                    AlertDialog(
                        onDismissRequest = { model.back() },
                        title = { Text("Серии") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    route.release!!.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Episodes(model, activity, route.release)
                            }
                        },
                        confirmButton = { Action("Закрыть") { model.back() } },
                    )
                }
                if (model.preparing)
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                UpdateDialog(model.updates)
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
                    var rememberChoice by rememberSaveable(pair.second.id) { mutableStateOf(false) }
                    ChoiceDialog(
                        "Поток видео",
                        streams.map { "${it.type.uppercase()} • ${it.quality}p" },
                        onDismiss = { model.pendingStreams = null },
                        header = {
                            GuiButton(
                                onClick = { rememberChoice = !rememberChoice },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Checkbox(checked = rememberChoice, onCheckedChange = null)
                                androidx.tv.material3.Text(
                                    "Запомнить выбор по умолчанию",
                                    fontSize = 14.sp,
                                    maxLines = 2,
                                )
                            }
                        },
                    ) { index ->
                        val stream = streams[index]
                        if (rememberChoice && stream.type in listOf("hls", "dash")) {
                            model.pendingStreams = null
                            model.mutateLocal(
                                UiSection.SETTINGS,
                                after = { activity.launchPlayback(pair.first, pair.second, stream) },
                            ) {
                                put("player.preferred_stream_technology", stream.type)
                            }
                        } else activity.launchPlayback(pair.first, pair.second, stream)
                    }
                }
            }
        }
    }
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
            GuiButton(
                onClick = {
                    if (route in listOf("notifications", "settings")) model.navigate(Route(route))
                    else model.selectSection(route)
                },
                modifier = Modifier.size(44.dp).semantics { contentDescription = label },
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
