# Dream Cast TV v0.7.1 — исправление публикации Debug APK

Оба flavor APK компилируются. Flutter создаёт имена в нижнем регистре:
- `app-softwareav1-debug.apk`
- `app-hardwareav1-debug.apk`

Ранее workflow искал CamelCase `softwareAv1` / `hardwareAv1`, поэтому падал уже после сборки. Исправлен путь к APK, сохранены раздельные artifacts и SHA-256.

1. В GitHub Desktop открыть `develop`.
2. Заменить ТОЛЬКО `.github/workflows/build-tv-apk.yml` файлом из этого ZIP.
3. Commit → Push origin.
4. Actions → Build Dream Cast TV APK → Run workflow → source_branch `develop`.

В `main` ничего не менять. Исходники приложения не менялись.
