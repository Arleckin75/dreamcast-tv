package com.dreamcast.nativeapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamcast.nativeapp.*
import com.dreamcast.nativeapp.R
import com.dreamcast.nativeapp.data.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AboutPanel(
    title: String? = null,
    readOnly: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val reveal = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Surface(
        modifier =
            Modifier.fillMaxWidth()
                .bringIntoViewRequester(reveal)
                .onFocusChanged { if (it.hasFocus) scope.launch { reveal.bringIntoView() } }
                .focusGroup()
                .then(
                    if (readOnly)
                        Modifier.semantics(mergeDescendants = true) {}
                            .onFocusChanged { focused = it.isFocused }
                            .focusable()
                    else Modifier
                ),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
        border =
            androidx.compose.foundation.BorderStroke(
                if (focused) 2.dp else 1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = if (focused) 0.65f else 0.12f),
            ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (title != null)
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
            content()
        }
    }
}

@Composable
internal fun AboutAction(label: String, icon: Int, onClick: () -> Unit) {
    GuiButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(
            painterResource(icon),
            null,
            tint = androidx.tv.material3.LocalContentColor.current,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        androidx.tv.material3.Text(label, fontSize = 14.sp, maxLines = 2)
    }
}

/** Read-only documents retain focus and scroll with the D-pad, including long GPL text. */
@Composable
private fun AboutDocument(text: String) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val requester = remember { FocusRequester() }
    val step = with(androidx.compose.ui.platform.LocalDensity.current) { 160.dp.roundToPx() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    Text(
        text,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(max = 320.dp)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    RoundedCornerShape(12.dp),
                )
                .verticalScroll(scroll)
                .focusRequester(requester)
                .onPreviewKeyEvent { event ->
                    val direction =
                        when (event.key) {
                            Key.DirectionUp -> -1
                            Key.DirectionDown -> 1
                            else -> 0
                        }
                    if (direction == 0) false
                    else {
                        if (event.type == KeyEventType.KeyDown)
                            scope.launch {
                                scroll.animateScrollTo(
                                    (scroll.value + direction * step).coerceIn(0, scroll.maxValue)
                                )
                            }
                        true
                    }
                }
                .focusable()
                .padding(14.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun About() {
    val context = LocalContext.current
    var address by remember { mutableStateOf<String?>(null) }
    var licenseDocument by remember { mutableStateOf<OpenSourceLicense?>(null) }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
    ) {
        item {
            AboutPanel(readOnly = true) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Image(
                        painterResource(R.drawable.tv_banner),
                        "Dream Cast TV",
                        modifier =
                            Modifier.width(150.dp).height(88.dp).clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Dream Cast TV",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Приложение для просмотра релизов Dream Cast на Android TV.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Версия ${BuildConfig.VERSION_NAME} • сборка ${BuildConfig.VERSION_CODE} • Media3 + dav1d",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Исходники: ${BuildConfig.SOURCE_REVISION}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            AboutPanel("Открытый исходный код и лицензия") {
                Text(
                    "Dream Cast распространяется по лицензии GNU GPLv3. Оригинальное приложение разработано rurin1x. Версия для Android TV является модификацией оригинального проекта.",
                    lineHeight = 23.sp,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AboutAction("Текст лицензии GPLv3", R.drawable.ic_about_document) {
                        licenseDocument = applicationLicense
                    }
                    AboutAction("Оригинальные исходники", R.drawable.ic_about_code) {
                        address = "https://github.com/rurin1x/dreamcast-app"
                    }
                    AboutAction("Исходники Android TV", R.drawable.ic_about_tv) {
                        address = "https://github.com/Arleckin75/dreamcast-tv"
                    }
                }
            }
        }
        item {
            AboutPanel("Лицензии компонентов плеера") {
                Text("Сторонние компоненты сохраняют собственные лицензии. Полные тексты и уведомления доступны без интернета.")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    playerLicenses().forEach { notice ->
                        AboutAction(notice.title, R.drawable.ic_about_document) {
                            licenseDocument = notice
                        }
                    }
                }
            }
        }
        item {
            AboutPanel("Dream Cast") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AboutAction("Telegram", R.drawable.ic_about_send) {
                        address = "https://t.me/dreamercast"
                    }
                    AboutAction("Группа в VK", R.drawable.ic_about_groups) {
                        address = "https://vk.com/dreamerscast"
                    }
                    AboutAction("Сайт", R.drawable.ic_about_language) { address = BASE_URL + "/" }
                    AboutAction("YouTube", R.drawable.ic_about_play) {
                        address = "https://www.youtube.com/@AdminDreamCast"
                    }
                }
            }
        }
        item {
            AboutPanel(readOnly = true) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_about_heart),
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        "Спасибо open-source сообществу и людям, чьи проекты и идеи помогли при разработке приложения.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 23.sp,
                    )
                }
            }
        }
    }
    address?.let { link ->
        AlertDialog(
            onDismissRequest = { address = null },
            title = { Text("Ссылка") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Откройте этот адрес на телефоне или компьютере:")
                    AboutDocument(link)
                }
            },
            confirmButton = {},
        )
    }
    licenseDocument?.let { document ->
        val license =
            resource(document) {
                document.assets.joinToString("\n\n") { asset ->
                    context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            }
        AlertDialog(
            onDismissRequest = { licenseDocument = null },
            title = { Text(document.title) },
            text = {
                when {
                    license.loading -> Loading()
                    license.error != null -> Text("Не удалось открыть лицензию: ${license.error}")
                    else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(document.summary, style = MaterialTheme.typography.bodySmall)
                        AboutDocument(license.value.orEmpty())
                    }
                }
            },
            confirmButton = {},
        )
    }
}
