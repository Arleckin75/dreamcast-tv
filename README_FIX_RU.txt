Исправление для Build Dream Cast TV Release APK (v0.7.1)

Устраняет зависимость шага аудита от отсутствующих в Git android/gradlew и gradle-wrapper.jar.
Использует gradle/actions/setup-gradle@v4 с Gradle 8.14, затем запускает Gradle :app:dependencies.

Только файл .github/workflows/build-tv-release.yml.
Работайте в отдельной ветке fix/v071-release-audit-gradle и создайте Pull Request в защищённую main.
Не меняйте release-signing и ветки защиты.
После Merge запускайте Build Dream Cast TV Release APK из main.

Примечание: синтаксис YAML проверен. Выполнение GitHub Actions не проверено.
