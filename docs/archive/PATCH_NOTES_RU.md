# v0.7.1 — изоляция ExoPlayer в software_AV1

В workflow **software_AV1** перед `flutter pub get` выполняется `scripts/prepare_software_av1.py`. Он временно добавляет в локальный CI `pubspec.yaml` `dependency_overrides` на пакет `third_party/video_player_android_noop`. Этот пакет реализует Flutter plugin registration без запуска Android-видеоплеера и не содержит Media3/ExoPlayer. Общий Dart `video_player` сохраняется для hardware-плеера, но в software flavor его вызовы запрещены архитектурой приложения.

В **hardware_AV1** ничего не подменяется: используется официальный `video_player_android` с ExoPlayer. Аудит по-прежнему должен остановить workflow, если ExoPlayer найден в software APK.

## Применение

Распаковать поверх рабочего `develop` с сохранением папок, затем Commit/Push. **Не переносить в main.** Запустить `Build Dream Cast TV APK` с `source_branch=develop`.

## Проверка

Предварительно проверена структура исходников и Python-синтаксис. Flutter/Kotlin компиляция и поведение runtime не подтверждены: первый запуск обеих сборок обязателен. Если Flutter выдаст ошибку о реализации плагина, передать полный лог. Ни в коем случае не отключать аудит при ошибке.

`software_AV1` с native LibVLC и его результатом прогресса должен остаться без изменений; Dart VideoPlayer не создаётся в этом flavor. После обновления APK проверить запуск видео и «Продолжить просмотр» на Cosmos.
