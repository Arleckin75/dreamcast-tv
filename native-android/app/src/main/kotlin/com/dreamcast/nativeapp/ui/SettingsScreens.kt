package com.dreamcast.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import com.dreamcast.nativeapp.*
import com.dreamcast.nativeapp.R
import com.dreamcast.nativeapp.data.*
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Settings(model: AppViewModel) {
    var dialog by remember { mutableStateOf("") }
    val themeLabel =
        when (model.ui.theme) {
            "system" -> "Системная"
            "light" -> "Светлая"
            else -> "Тёмная"
        }
    val accentLabel =
        when (model.ui.accent) {
            "blue" -> "Синий"
            "green" -> "Зелёный"
            "violet" -> "Фиолетовый"
            "rose" -> "Розовый"
            else -> "Системный"
        }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
    ) {
        item {
            AboutPanel("Профиль") {
                AboutAction(
                    "Профиль: ${model.ui.profile.ifBlank { "Не задан" }}",
                    R.drawable.ic_about_groups,
                ) {
                    model.navigate(Route("profile"))
                }
            }
        }
        item {
            AboutPanel("Оформление") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AboutAction("Тема: $themeLabel", R.drawable.ic_nav_settings) {
                        dialog = "theme"
                    }
                    AboutAction("Акцент: $accentLabel", R.drawable.ic_about_heart) {
                        dialog = "accent"
                    }
                    AboutAction(
                        "Цвета системы: ${if (model.ui.dynamicColor) "включены" else "выключены"}",
                        R.drawable.ic_about_tv,
                    ) {
                        model.mutateLocal(UiSection.SETTINGS) {
                            put("settings.dynamic_color", !boolean("settings.dynamic_color", true))
                        }
                    }
                }
            }
        }
        item {
            AboutPanel("Воспроизведение") {
                AboutAction(
                    "Поток по умолчанию: ${if (model.ui.streamTechnology == "none") "выбирать вручную" else model.ui.streamTechnology.uppercase()}",
                    R.drawable.ic_about_play,
                ) {
                    dialog = "player"
                }
            }
        }
        item {
            AboutPanel("Приложение") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AboutAction("Кэш", R.drawable.ic_about_document) {
                        model.navigate(Route("cache"))
                    }
                    AboutAction("О приложении", R.drawable.ic_about_tv) {
                        model.navigate(Route("about"))
                    }
                    AboutAction("Проверить обновления", R.drawable.ic_about_send) {
                        model.updates.checkManually()
                    }
                    AboutAction(
                        "Автопроверка обновлений: ${if (model.updates.autoCheck) "включена" else "выключена"}",
                        R.drawable.ic_nav_notifications,
                    ) {
                        model.updates.setAutoCheckEnabled(!model.updates.autoCheck)
                    }
                }
            }
        }
    }
    when (dialog) {
        "theme" ->
            ChoiceDialog("Тема", listOf("Системная", "Светлая", "Тёмная"), { dialog = "" }) { index
                ->
                model.mutateLocal(UiSection.SETTINGS) {
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
                model.mutateLocal(UiSection.SETTINGS) {
                    put(
                        "settings.accent",
                        listOf("system", "blue", "green", "violet", "rose")[index],
                    )
                }
                dialog = ""
            }
        "player" ->
            ChoiceDialog(
                "Поток по умолчанию",
                listOf("Выбирать вручную", "HLS", "DASH"),
                { dialog = "" },
            ) { index ->
                model.mutateLocal(UiSection.SETTINGS) {
                    put("player.preferred_stream_technology", listOf("none", "hls", "dash")[index])
                }
                dialog = ""
            }
    }
}

@Composable
internal fun Profile(model: AppViewModel) {
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
            model.mutateLocal(
                UiSection.PROFILE,
                after = { if (name == updatedName) saved = true },
            ) {
                saveProfile(updatedName)
            }
        }
        if (saved) Text("Профиль сохранён")
    }
}

@Composable
internal fun Cache(model: AppViewModel) {
    var retentionDialog by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    val stats by
        produceState<Pair<Long, Long>?>(null, model.revision) {
            value = withContext(Dispatchers.IO) { model.storage.cacheStats() }
        }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stats?.let { "Записей: ${it.first} • ${it.second/1024} КБ" } ?: "Подсчёт кэша…")
        Text("Срок хранения: ${model.ui.retentionHours.let{if(it==0L)"бессрочно" else "$it ч."}}")
        Text(
            "Кэш очищается автоматически при заполнении. Библиотека и история просмотра сохраняются."
        )
        Action("Срок хранения") { retentionDialog = true }
        Action("Очистить просроченные") { model.mutateLocal(UiSection.CACHE) { clearExpired() } }
        Action("Очистить весь кэш") { clearDialog = true }
    }
    if (retentionDialog)
        ChoiceDialog(
            "Срок хранения",
            listOf("8 часов", "12 часов", "1 день", "3 дня", "7 дней", "30 дней", "Бессрочно"),
            { retentionDialog = false },
        ) { index ->
            model.mutateLocal(UiSection.CACHE) {
                retention(listOf(8L, 12L, 24L, 72L, 168L, 720L, 0L)[index])
            }
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
                        model.mutateLocal(UiSection.CACHE) { clearCache() }
                        clearDialog = false
                    },
                )
            },
        )
}

@Composable
internal fun Notifications(model: AppViewModel) {
    val entries = model.ui.notifications
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Action("Прочитать все") {
            model.mutateLocal(UiSection.NOTIFICATIONS) {
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
        Action("Очистить историю") {
            model.mutateLocal(UiSection.NOTIFICATIONS) { entries.forEach { remove(it.key) } }
        }
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
                    model.mutateLocal(UiSection.NOTIFICATIONS) {
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
