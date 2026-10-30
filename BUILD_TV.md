# Dream Cast TV — сборка APK через GitHub Actions

1. Создайте приватный пустой репозиторий GitHub.
2. Загрузите **содержимое** папки `dreamcast-app-main`, а не саму папку. Файл `.github/workflows/build-tv-apk.yml` должен находиться в корне репозитория в указанном пути. Для скрытой папки `.github` удобнее использовать Git или GitHub Desktop.
3. В GitHub откройте Actions -> Build Dream Cast TV APK -> Run workflow. Автосборка также запускается при push в ветки main/master.
4. После успешной сборки откройте запуск -> Artifacts -> dreamcast-tv-debug-apk и скачайте zip с `app-debug.apk`.
5. Установите debug APK на Android TV через ADB или приложение передачи файлов. При использовании ADB: `adb install -r app-debug.apk`. На TV необходимо разрешить установку из выбранного источника.

Это тестовая debug-сборка, не релиз. GitHub-сборка пока не проверена, возможны ошибки зависимостей или исходников. Для выпуска приложения потребуется отдельная release-подпись и проверка D-pad, фокуса, воспроизведения и сетевых запросов.
