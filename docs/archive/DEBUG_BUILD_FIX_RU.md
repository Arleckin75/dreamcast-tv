# Исправление сборки Debug после переноса LibVLC в Release

Ошибка `Redeclaration` возникает, если новые Kotlin-классы из `src/main/kotlin` скопированы поверх репозитория без удаления старых версий в `src/debug/kotlin`.

Удалить из Git именно эти три старых файла:

- `android/app/src/debug/kotlin/com/dreamcast/app/CosmosAv1PlayerActivity.kt`
- `android/app/src/debug/kotlin/com/dreamcast/app/CosmosFmp4Resolver.kt`
- `android/app/src/debug/kotlin/com/dreamcast/app/CosmosMedia3Probe.kt`

В `src/main/kotlin/com/dreamcast/app/` должны остаться три одноимённых файла. `progressSaver` уже объявлен в новой версии `CosmosAv1PlayerActivity.kt`, поэтому сообщения `Unresolved reference: progressSaver` могут быть каскадными из-за дублирования классов.

Для Windows: распакуй проект в локальную папку репозитория, открой PowerShell в этой папке, запусти `powershell -ExecutionPolicy Bypass -File .\FIX_DEVELOP_DUPLICATES.ps1`. Скрипт удаляет только три перечисленных Debug-файла, предварительно проверяя наличие новых Main-файлов. Проверь удаления в GitHub Desktop и сделай Commit/Push в `develop`.

Не удаляй `.github/workflows`: они остаются в локальном Git-репозитории, но отсутствуют в передаваемом ZIP.
