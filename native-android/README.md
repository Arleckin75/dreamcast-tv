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

## Проверки

JUnit/Robolectric проверяют парсеры PlayerJS, запросы, кэш, историю, библиотеку,
прогресс и открытие реальной схемы Drift v3. `flutter_schema_v3.json` выгружен из
исходного `AppDatabase`, а не переписан по памяти.

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
