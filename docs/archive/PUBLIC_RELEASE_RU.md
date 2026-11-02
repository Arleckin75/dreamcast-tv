# Публичный релиз v0.7.1

Патч меняет только `.github/workflows/build-tv-apk.yml` на ветке `develop`.

Запуск: GitHub Desktop → develop → распаковать → commit/push. GitHub Actions → Build Dream Cast TV APK → Run workflow → Use workflow from **develop**, Build mode **release**, Source branch **develop**.

После успешной сборки двух вариантов, проверки APK, подписи, versionCode и сравнения сертификата с опубликованной v0.7.0 публикация `v0.7.1` происходит автоматически в GitHub Releases (4 файла). `main` не изменяется.

Публикация останавливается, если: один APK не собран, аудит не прошёл, SHA256 не совпал, v0.7.0 содержит не ровно один APK, сертификат отличается или уже есть релиз v0.7.1. Проверка установки на устройствах подписанных Release APK до публикации автоматически не выполняется.

Секреты подписи должны быть настроены в Environment `release-signing`, как и для v0.7.0. При отсутствии секретов публикации не будет.
