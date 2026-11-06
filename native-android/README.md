# Dream Cast TV — единый Kotlin-проект

Kotlin, Compose / TV Material, Media3/ExoPlayer, dav1d, SQLite, WorkManager.
Flutter SDK и отдельные flavor для декодирования не используются.

После [подготовки SDK и dav1d](../BUILD_TV.md):

```sh
bash native-android/gradlew -p native-android :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

В PowerShell: `scripts/build_native.ps1`; Release — `-Configuration release`.
Подпись читается из `native-android/key.properties`, относительный `storeFile`
разрешается относительно `native-android`. Для обновления нужен прежний ключ.

## Плеер

`PlayerActivity` и `PlayerLauncher` основаны на прежней hardware-реализации.
Один плеер обслуживает HLS/DASH, выбор качества/скорости/потока, прогресс,
следующую серию и PiP. `VideoDecoderCapabilities` проверяет аппаратный флаг
MediaCodec и поддержку текущего формата; системный программный декодер
не включает пункт «Аппаратное».

В «Авто» предпочтителен аппаратный путь; для AV1 без него применяется dav1d.
При ошибке аппаратного видеорендерера в «Авто» выполняется однократный переход
на dav1d с сохранением позиции. Сетевые и звуковые ошибки не маскируются этим
переходом. Принудительный режим dav1d доступен только для поддерживаемого AV1.
Параметры потока читаются до выбора SurfaceView или GL-вывода.

## Проверки

```sh
bash native-android/gradlew -p native-android :app:connectedDebugAndroidTest
bash native-android/gradlew -p native-android :app:dependencies --configuration debugRuntimeClasspath > dependencies.txt
python scripts/audit_av1_apk.py --apk native-android/app/build/outputs/apk/debug/app-debug.apk --dependencies dependencies.txt
```

`UnifiedAv1PlaybackTest` проверяет AV1 + AAC, реальную доступность аппаратного
декодера, отключённый пункт на неподдерживаемом устройстве, смену режима в паузе,
позицию и возврат из фона. `PlayerPlaybackTest` сохраняет прежние проверки
качества, скорости, перемотки и записи прогресса. Тесты используют локальный сервер.

JUnit/Robolectric проверяют парсеры, кэш, историю, библиотеку и совместимость
с реальной схемой Drift v3 (`flutter_schema_v3.json`). Runtime JAR для тестов
готовит `prepareRobolectricAndroid` через кэш Gradle.

## Данные и лицензии

Сохранены SQLite `app_flutter/dream_cast.sqlite`, чтение FlutterSharedPreferences,
история и позиции прежних APK. Старое промежуточное хранилище software-позиций
по-прежнему читается для совместимости, но новый плеер пишет через ProgressWriter.

Все лицензии компонентов доступны в едином APK из «О приложении».
См. [проверку лицензий](../docs/PLAYER_LICENSES_RU.md) и
[выбор декодера](../docs/MEDIA3_DAV1D_RU.md).
