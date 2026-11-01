# Dream Cast TV — единый Debug workflow

Этот патч заменяет только `.github/workflows/build-tv-apk.yml`. Другие файлы проекта не меняются.

## Как применять

1. В GitHub Desktop выберите `develop`, скопируйте файл из архива по пути `.github/workflows/build-tv-apk.yml` с заменой, Commit → Push.
2. Переключитесь на `main`, выполните Fetch/Pull origin, замените **тот же** файл и сделайте отдельный Commit → Push в `main`. Не переносите остальные файлы или исходники v0.7.1.
3. Откройте Actions → Build Dream Cast TV APK → Run workflow. В **Use workflow from** выберите `main`. В поле **Source branch to build** выберите `develop` для двух APK v0.7.1 или `main` для одного v0.7.0.

Примечание: GitHub показывает доступные workflow и их `workflow_dispatch` inputs из default branch (`main`). Поэтому файл обязательно должен оказаться в `main`; достаточно одного YAML, без слияния кода `develop`.

При выборе `develop` будут созданы artifacts `dreamcast-tv-v0.7.1-software_AV1-debug` и `dreamcast-tv-v0.7.1-hardware_AV1-debug`. В них лежат APK и SHA256. Если выбран `main`, появится `dreamcast-tv-v0.7.0-main-debug`.

Подпись Release, существующий Release workflow и опубликованная v0.7.0 не затрагиваются. Это только автоматизация Debug. Проверка фактической компиляции требует запуска в GitHub Actions.

Старый `build-tv-v071-debug.yml`, присутствующий только в develop, можно удалить **после** успешного запуска нового workflow, чтобы не сохранять дубликаты.
