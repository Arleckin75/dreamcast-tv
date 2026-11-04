# Dream Cast TV — Kotlin

Нативное Android-приложение: Kotlin, Jetpack Compose / TV Material, coroutines,
OkHttp, Jsoup, SQLite, WorkManager. Flutter SDK для сборки не требуется.

## Сборка

Требуются JDK 17 и Android SDK Platform 36. Укажите `JAVA_HOME` и `ANDROID_HOME`.
Из корня репозитория:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build_native.ps1 -Player hardware
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build_native.ps1 -Player software
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build_native.ps1 -Player hardware -Test
```

Для software сначала распакуйте проверенный артефакт `libvlc-tv-maven` существующего
workflow в `.tools/libvlc-tv/maven`; см. `third_party/libvlc_tv/README.md`.
Автоматической подмены сокращённого LibVLC полной библиотекой нет.

Linux/macOS используют тот же Gradle Wrapper:

```sh
bash android/gradlew -p native-android :app:assembleHardwareAv1Debug
bash android/gradlew -p native-android :app:testHardwareAv1DebugUnitTest
```

APK: `native-android/app/build/outputs/apk/{hardwareAv1,softwareAv1}/debug/`.
Debug имеет отдельный applicationId `com.dreamcast.app.kotlin` и устанавливается
рядом с основной версией. Release использует прежний `com.dreamcast.app` и ключ
из `android/key.properties`; подпись тем же ключом необходима для обновления.
Не подписанная сборка не является готовым обновлением.

В GitHub Actions выберите `Verify native Kotlin TV` и ветку `Kotlin`.
Ручной запуск `Build Dream Cast TV APK` в этой ветке также вызывает нативную
debug-сборку обоих вариантов; Flutter debug остаётся привязан к `develop`,
а Flutter release — к `main`. Готовые APK находятся в артефактах
`kotlin-hardware-debug` и `kotlin-software-debug`.

## Проверки

JUnit/Robolectric проверяют парсеры PlayerJS, запросы, кэш, историю, библиотеку,
прогресс и открытие реальной схемы Drift v3. `flutter_schema_v3.json` выгружен из
исходного `AppDatabase`, а не переписан по памяти.

Android runtime для Robolectric скачивается задачей `prepareRobolectricAndroid`
через Gradle и его кэш. Тесты читают JAR из `app/build/robolectric-android`,
без отдельного скачивания через `repo1.maven.org`. При изменении SDK в `@Config`
или версии Robolectric нужно обновить зависимости `robolectricAndroid` и
`robolectricResourceAndroid` в Gradle (сейчас API 28 для тестов и API 35 для ресурсов).

На подключённом эмуляторе:

```sh
bash android/gradlew -p native-android :app:connectedHardwareAv1DebugAndroidTest
```

UI-тест использует локальный сервер. Разрешение HTTP в debug ограничено
localhost; release продолжает использовать HTTPS. Тест запускается в отдельном
debug-приложении и очищает его сетевой кэш.

Проверка разделения движков: `scripts/audit_av1_apk.py`, тот же аудит,
который используется для Flutter APK. В software отсутствует Media3/ExoPlayer,
в hardware отсутствует LibVLC; в обоих отсутствует Flutter engine.

## Данные и сравнение

Данные меню готовятся в фоне в `UiSnapshot` и хранятся в `AppViewModel`.
Отрисовка и смена фокуса не перечитывают SQLite или SharedPreferences. Снимок
обновляется после действий пользователя, возврата из плеера и изменений настроек,
библиотеки или уведомлений. Записи выполняются последовательно в фоне; «С начала»
сначала сохраняет сброшенную позицию и затем запускает плеер. Разбор сетевого
и сохранённого HTML/JSON также выполняется вне главного потока.

Это изменение проверено сборкой и тестами. Время кадров на телевизоре после
оптимизации отдельно не измерялось; для сравнения плавности нужна release-сборка.

При подписанном обновлении используется прежняя SQLite `app_flutter/dream_cast.sqlite`.
Профиль, библиотека, подписки, история уведомлений, позиции и настройки читаются
из прежних форматов. Flutter SharedPreferences не удаляются. Новые настройки
сохраняются отдельно с приоритетом над старыми.

Состояние переноса и ещё не подтверждённые проверки: `docs/KOTLIN_MIGRATION.md`.
Исходники Flutter пока сохранены для сравнения поведения и проверки миграции.

Документация используемого UI:
- https://developer.android.com/training/tv/playback/compose
- https://developer.android.com/develop/ui/compose/bom
- https://kotlinlang.org/docs/compose-compiler-migration-guide.html
