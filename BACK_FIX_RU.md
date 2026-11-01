# v0.7.1: Cosmos Back fix (develop)

Изменяется только `android/app/src/softwareAv1/kotlin/com/dreamcast/app/CosmosAv1PlayerActivity.kt`.

Причина: прежний код выполнял `finish()` в `onKeyDown(KEYCODE_BACK)` до события отпускания кнопки. После закрытия видео оставшийся `KEYCODE_BACK ACTION_UP` потенциально попадал в `MainActivity`, где обрабатывается навигация Flutter. Это вероятное объяснение случайного выхода из приложения; без logcat точная причина не доказана.

Исправление: оба события DOWN/UP перехватываются на уровне `dispatchKeyEvent`; закрытие плеера происходит по UP. Добавлен единичный `exitToEpisodes()`, резервная обработка `onBackPressed()` и защита от повторного `finish()`. Качество, прогресс и LibVLC не меняются. `hardwareAv1` не меняется.

## Установка
1. В GitHub Desktop выбрать `develop`.
2. Скопировать `android/` из ZIP в корень проекта с заменой **одного** Kotlin-файла. Остальные файлы проекта не удалять.
3. Commit + Push.
4. Build Dream Cast TV APK, `source_branch: develop`; проверить Debug APK на Cosmos.

## Проверка на Cosmos
Запустить видео и 10–15 раз выполнить сценарий: открыть эпизод → нажать/отпустить Back → убедиться, что открывается экран эпизодов, а не рабочий стол. Проверить прогресс и повторный запуск эпизода.

Если сбой останется, снять logcat с фильтрами `DCTV_AV1`, `DCTV_PROGRESS`, `AndroidRuntime`, `libc` во время воспроизведения: другая вероятная причина — аварийное завершение native LibVLC при stop/release во время уничтожения Surface.
