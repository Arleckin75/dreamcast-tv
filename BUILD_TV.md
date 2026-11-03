# Сборка Dream Cast TV

## GitHub Actions

1. Откройте **Actions → Build Dream Cast TV APK → Run workflow**.
2. Для тестовой сборки выберите `develop` и режим `debug`.
3. После успешного запуска скачайте нужный ZIP из **Artifacts**:
   `dreamcast-tv-v0.8.4-software_AV1-debug` или
   `dreamcast-tv-v0.8.4-hardware_AV1-debug`.
4. Распакуйте APK и `.sha256`, проверьте контрольную сумму и установите на TV.

Основной workflow находится в `.github/workflows/build-tv-apk.yml`. Он собирает
оба flavor из выбранной ревизии, запускает проверки и проверяет состав APK.
Перед software-сборкой вызывается reusable workflow `build-libvlc-tv.yml`, который
собирает либо восстанавливает проверенный сокращённый LibVLC.

Release разрешён только из `main` при настроенной подписи. Параметр `publish`
отдельно разрешает публикацию. Ручная точка **Build Dream Cast TV Release APK**
запускает тот же workflow с `publish: false`.

## Локально

Нужны Flutter **3.41.5**, JDK **17**, Android SDK и NDK из
`.android-ndk-version`. Используйте Dart из закреплённого Flutter SDK.

```sh
flutter pub get --enforce-lockfile
dart run scripts/build_tv.dart hardware debug --check
```

Для software сначала скачайте артефакт **libvlc-tv-maven** из успешного запуска
**Build reduced TV LibVLC** или основного процесса сборки. Распакуйте так, чтобы
Maven-репозиторий находился в `.tools/libvlc-tv/maven`:

```sh
dart run scripts/build_tv.dart software debug --check
```

Helper выбирает видеоплагин и Android flavor, запускает проверки с `--check`,
собирает ARM APK и восстанавливает overrides/lock-файл. Собирайте по одному flavor.
Для Release замените `debug` на `release` и настройте
[подпись](RELEASE_SIGNING_RU.md). Без ключа Release-сборка завершается ошибкой.

Готовые APK находятся в `build/app/outputs/flutter-apk/`:

- `app-softwareav1-debug.apk`;
- `app-hardwareav1-debug.apk`.

## Установка и проверка

```sh
adb install -r path/to/application.apk
```

Обновление требует совместимой подписи. Debug APK разных запусков или вариантов
могут иметь разные сертификаты. Проверить их можно через
`apksigner verify --print-certs path/to/application.apk`.
Debug использует `com.dreamcast.app.diagnostic`, Release — `com.dreamcast.app`.

После установки проверьте поиск с системной клавиатурой, навигацию пультом,
воспроизведение, перемотку, сохранение позиции и переход между сериями. Отдельно
проверьте паузу, заставку TV и выход Back. Для old ATV используйте 720p;
проверка 1080p на этом устройстве исключена из текущего плана.

Подробнее о движке: [профиль LibVLC](third_party/libvlc_tv/README.md).
