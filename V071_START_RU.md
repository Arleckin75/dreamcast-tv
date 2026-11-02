# Dream Cast TV v0.7.1 — первый этап

Варианты `softwareAv1` и `hardwareAv1` разводятся на уровне Gradle flavor и Kotlin source sets.

- `softwareAv1`: вызывает только LibVLC, использует прямой HLS fMP4, Auto=720p; 480/720/1080 выбираются явно.
- `hardwareAv1`: Flutter video_player/ExoPlayer, без LibVLC и без переключения движка. Качества в интерфейсе пока не гарантируют выбор конкретного Media3 track в hardware APK — требуется отдельная интеграция track selector.
- Прежний Debug APK имеет свой package ID `.diagnostic`.
- Разделение ExoPlayer на уровне APK для software варианта пока НЕ завершено: Flutter plugin `video_player_android` остаётся транзитивной зависимостью `video_player`.
- Прогресс LibVLC по-прежнему передаётся в Drift при возврате из Activity.

## Команды сборки

`flutter build apk --debug --flavor softwareAv1 --dart-define=AV1_PLAYER=software`
`flutter build apk --debug --flavor hardwareAv1 --dart-define=AV1_PLAYER=hardware`

`flutter build apk --release --flavor softwareAv1 --dart-define=AV1_PLAYER=software`
`flutter build apk --release --flavor hardwareAv1 --dart-define=AV1_PLAYER=hardware`

НЕ публиковать как v0.7.1 Release без проверки на реальных устройствах, корректного Media3 выбора качества и проверки отсутствия ExoPlayer в software APK. Перед заменой локальных исходников сохранить текущие `.github/workflows`, особенно настроенный `build-tv-release.yml` и секреты `release-signing`.
