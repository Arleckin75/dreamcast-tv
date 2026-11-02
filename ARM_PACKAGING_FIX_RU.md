# v0.7.1 — исправление упаковки x86_64

Причина: Flutter/Gradle могут включить x86_64 в Debug APK, даже если указан `--target-platform android-arm,android-arm64` и ABI-фильтры flavor.

Исправление: `android/app/build.gradle.kts` использует `android.packaging.jniLibs.excludes` для исключения `**/x86/**` и `**/x86_64/**` из обеих TV-сборок. Сохраняются `armeabi-v7a` и `arm64-v8a`.

Почему обе: обе сборки v0.7.1 ориентированы на реальные ARM Android TV. Поддержка x86-эмуляторов больше не входит в эту конфигурацию.

Workflow `.github/workflows/build-tv-apk.yml` проверяет ABI **обоих** Debug APK после компиляции и публикует artifacts лишь при успешной проверке.

## Применение

1. В GitHub Desktop выбрать `develop`.
2. Распаковать этот ZIP в корневую папку репозитория с заменой двух файлов, сохраняя вложенные каталоги.
3. Commit / Push.
4. Запустить существующий `Build Dream Cast TV APK` для `source_branch=develop`.
5. Проверить два задания; скачать APK и проверить запуск на устройствах.

Не переносить исправление в `main` (v0.7.0). Flutter SDK в окружении подготовки патча отсутствует: сборка Android не выполнялась.
