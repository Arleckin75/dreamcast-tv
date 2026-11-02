# v0.7.1 — исправление ошибок компиляции Kotlin

Ошибка `:app:compileHardwareAv1DebugKotlin` возникает из-за сохранённых в Git-репозитории файлов из прежней v0.7 (`android/app/src/main/kotlin/com/dreamcast/app/`), тогда как v0.7.1 использует отдельные source sets для `softwareAv1` и `hardwareAv1`.

Удаляем **только** четыре legacy-файла из `src/main` и `src/debug`, если они остались: `MainActivity.kt`, `CosmosAv1PlayerActivity.kt`, `CosmosFmp4Resolver.kt`, `CosmosMedia3Probe.kt`. Скрипт `FIX_V071_STALE_KOTLIN.ps1` делает это и проверяет необходимые файлы в flavor-каталогах.

1. Распакуйте полный ZIP в локальный репозиторий с веткой `develop`.
2. В PowerShell из корня репозитория запустите `powershell -ExecutionPolicy Bypass -File .\FIX_V071_STALE_KOTLIN.ps1`.
3. В GitHub Desktop должны появиться **удаления (deleted)** исходных Kotlin-файлов в `src/main` (и `src/debug`, если были).
4. Commit и Push в `develop`.
5. GitHub Actions должен собирать два flavor отдельно, с `--flavor hardwareAv1 --dart-define=AV1_PLAYER=hardware` и `--flavor softwareAv1 --dart-define=AV1_PLAYER=software`. Команда `flutter build apk --debug` без `--flavor` для этого проекта больше не подходит.

Отсутствие библиотеки `org.videolan` в hardware-сборке — ожидаемо: hardware flavor не подключает LibVLC. Это перестаёт быть ошибкой после удаления общего legacy-класса `CosmosAv1PlayerActivity.kt`.

Примечание: ZIP содержит исходники, но не является подтверждением успешной Flutter/Gradle-компиляции. `main`/v0.7.0 не менять.
