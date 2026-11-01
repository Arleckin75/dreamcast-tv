# Автоматическая проверка состава APK (v0.7.1)

Патч применять **только к develop**. Из ZIP копировать `.github/workflows/build-tv-apk.yml` и `scripts/audit_av1_apk.py` с сохранением каталогов. Затем Commit → Push origin; GitHub Actions → Build Dream Cast TV APK → Run workflow → source_branch: develop.

Каждый variant после компиляции проверяет Gradle runtimeClasspath, `.so` в APK и сигнатуры классов в `classes*.dex`. Отчёт скачивается как artifact `...-audit`.

Ожидания:
- software: LibVLC есть, Media3 ExoPlayer отсутствует;
- hardware: ExoPlayer есть, LibVLC отсутствует.

**Важно:** ранее было отмечено, что `video_player_android` общая зависимость Flutter, поэтому software-ветка может завершиться ошибкой `forbidden ExoPlayer runtime dependency PRESENT`. Это будет корректная диагностика — не ошибка самого скрипта и не повод отключать проверку. Тогда следующим шагом потребуется удалить ExoPlayer из software-варианта через переработку зависимостей/регистрации плагина.

Проверка DEX основывается на именах классов Debug (без обфускации). Для будущего Release понадобится дополнительный контроль зависимостей и обфускации. Workflow не удаляет и не правит main.
