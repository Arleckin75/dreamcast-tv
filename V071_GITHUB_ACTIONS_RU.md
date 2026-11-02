# Dream Cast TV v0.7.1 — GitHub Actions Debug

Пакет содержит только `.github/workflows/build-tv-v071-debug.yml` и эту инструкцию.

1. Выбрать `develop` в GitHub Desktop.
2. Распаковать ZIP в корень локального репозитория, сохранив каталог `.github`.
3. Commit + Push в `develop`.
4. GitHub → Actions → **Build Dream Cast TV v0.7.1 Debug (software + hardware AV1)** → Run workflow → `develop`.
5. В результате матрица создаст два отдельных artifacts: `software_AV1` и `hardware_AV1`.

Выполнение команд Flutter на Windows не требуется. Подписанный Release и `main` не меняются.

Внимание: это тестовый workflow. Он проверяет компиляцию и не доказывает, что в `software_AV1` физически отсутствует зависимость ExoPlayer из плагина `video_player_android`. Изоляция зависимостей и все четыре режима качества — ещё не завершённые пункты v0.7.1.
