package com.dreamcast.nativeapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dreamcast.nativeapp.BuildConfig
import com.dreamcast.nativeapp.update.UpdateController
import com.dreamcast.nativeapp.update.UpdateState
import com.dreamcast.nativeapp.update.shortNotes

/** Dialog for every step of the update flow; nothing is shown when there is no update state. */
@Composable
internal fun UpdateDialog(updates: UpdateController) {
    when (val state = updates.state ?: return) {
        UpdateState.Checking ->
            UpdateAlert("Проверка обновлений", onDismiss = updates::dismiss, text = {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }) {
                Action("Отмена", autofocus = true, onClick = updates::dismiss)
            }
        UpdateState.UpToDate ->
            UpdateAlert("Обновлений нет", onDismiss = updates::dismiss, text = {
                Text("Установлена последняя версия ${BuildConfig.VERSION_NAME}.")
            }) {
                Action("Закрыть", autofocus = true, onClick = updates::dismiss)
            }
        is UpdateState.Available ->
            UpdateAlert("Доступна версия ${state.release.version}", onDismiss = updates::dismiss, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Установлена версия ${BuildConfig.VERSION_NAME}.")
                    shortNotes(state.release.notes).takeIf { it.isNotBlank() }?.let {
                        Text(it, maxLines = 12, overflow = TextOverflow.Ellipsis)
                    }
                }
            }) {
                Action("Установить", autofocus = true) { updates.install(state.release) }
                Action("Позже", onClick = updates::dismiss)
                Action("Пропустить версию") { updates.skip(state.release) }
            }
        is UpdateState.Downloading ->
            UpdateAlert("Загрузка версии ${state.release.version}", onDismiss = {}, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${(state.progress * 100).toInt()}%")
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }) {
                Action("Отмена", autofocus = true, onClick = updates::dismiss)
            }
        is UpdateState.NeedsPermission ->
            UpdateAlert("Разрешите установку", onDismiss = updates::dismiss, text = {
                Text(
                    "Версия ${state.release.version} скачана и проверена. Чтобы установить её, " +
                        "разрешите Dream Cast TV устанавливать приложения и вернитесь назад."
                )
            }) {
                Action("Открыть настройки", autofocus = true, onClick = updates::openInstallPermission)
                Action("Отмена", onClick = updates::dismiss)
            }
        is UpdateState.Failed ->
            UpdateAlert("Обновление не удалось", onDismiss = updates::dismiss, text = {
                Text(state.message)
            }) {
                state.release?.let { release ->
                    Action("Повторить", autofocus = true) { updates.install(release) }
                }
                Action("Закрыть", autofocus = state.release == null, onClick = updates::dismiss)
            }
    }
}

@Composable
private fun UpdateAlert(
    title: String,
    onDismiss: () -> Unit,
    text: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text,
        confirmButton = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() } },
    )
}
