# Сборка Dream Cast TV

## GitHub Actions

1. Откройте **Actions → Build Dream Cast TV APK → Run workflow**.
2. Для тестовой сборки выберите `develop`. Этот workflow вручную собирает только Debug из develop.
3. После успешного запуска скачайте нужный ZIP из **Artifacts**:
   `dreamcast-tv-software-debug` или `dreamcast-tv-hardware-debug`.
4. Распакуйте APK и `.sha256`, проверьте контрольную сумму и установите на TV.

Основной workflow находится в `.github/workflows/build-tv-apk.yml`. Он собирает
оба нативных flavor из выбранной ревизии, запускает unit-тесты, lint и аудит APK.
Отчёты доступны в отдельных артефактах с суффиксом `-reports`.
Перед software-сборкой вызывается reusable workflow `build-libvlc-tv.yml`, который
собирает либо восстанавливает проверенный сокращённый LibVLC.

Для выпуска откройте **Build Dream Cast TV Release APK** и выберите `main`.
Параметр **Publish v0.9.1 on GitHub Releases (main only)** включён по умолчанию:
после сборки и проверок будет опубликован релиз. Снимите флажок, если нужны
только подписанные APK без публикации. Используются прежние секреты подписи
и окружение `release-signing`.

Ручных точек запуска две: **Build Dream Cast TV APK** для develop и
**Build Dream Cast TV Release APK** для main. **Build reduced TV LibVLC** —
внутренняя зависимость software-сборки, отдельно не запускается.
Проверки входят в сборочные workflow; отдельных `Verify` и
`Verify native Kotlin TV` больше нет. Автоматические сборки при push отключены.

## Локально

Для нативной версии в `develop` нужны JDK **17**, Android SDK Platform **36**
и Build Tools **36.0.0**. Настройте `JAVA_HOME` и `ANDROID_HOME`.

```sh
bash android/gradlew -p native-android :app:testHardwareAv1DebugUnitTest :app:assembleHardwareAv1Debug
```

Для software сначала скачайте артефакт **libvlc-tv-maven** из успешного запуска
сборки APK. Распакуйте так, чтобы
Maven-репозиторий находился в `.tools/libvlc-tv/maven`:

```sh
bash android/gradlew -p native-android :app:testSoftwareAv1DebugUnitTest :app:assembleSoftwareAv1Debug
```

Flutter SDK не требуется. Готовые APK находятся в
`native-android/app/build/outputs/apk/{hardwareAv1,softwareAv1}/debug/`.
Команды PowerShell и сведения о тестах — в [нативном проекте](native-android/README.md).
Нативный релиз v0.9.1 собирается отдельно из `main`; порядок выпуска и настройки
ключа описаны в [инструкции подписи](RELEASE_SIGNING_RU.md).

## Установка и проверка

```sh
adb install -r path/to/application.apk
```

Обновление требует совместимой подписи. Debug APK разных запусков или вариантов
могут иметь разные сертификаты. Проверить их можно через
`apksigner verify --print-certs path/to/application.apk`.
Нативный Debug использует `com.dreamcast.app.kotlin`, Release — `com.dreamcast.app`.

После установки проверьте поиск с системной клавиатурой, навигацию пультом,
воспроизведение, перемотку, сохранение позиции и переход между сериями. Отдельно
проверьте паузу, заставку TV и выход Back. Для old ATV используйте 720p;
проверка 1080p на этом устройстве исключена из текущего плана.

Подробнее о движке: [профиль LibVLC](third_party/libvlc_tv/README.md).
