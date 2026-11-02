# Dream Cast TV v0.7 — полноценная Release-сборка

Ветка: `develop`. Package ID: `com.dreamcast.app`. Flutter mode: `release`. Версия APK: `0.7.0`; versionCode: `700000 + GitHub run_number`.

## Подпись

Workflow **не создаёт временный ключ**: нужен постоянный Android keystore. Для обновления установленной v0.6.0 необходимо подписать v0.7 **тем же ключом**, что и v0.6.0. Если ключ другой, Android выдаст INSTALL_FAILED_UPDATE_INCOMPATIBLE. Не удаляйте установленную v0.6.0 ради проверки до резервного копирования её данных.

GitHub → Settings → Secrets and variables → Actions → New repository secret:

- `DCTV_KEYSTORE_BASE64` — Base64 содержимого .jks/.keystore (без переносов строк)
- `DCTV_STORE_PASSWORD` — пароль хранилища
- `DCTV_KEY_ALIAS` — alias ключа
- `DCTV_KEY_PASSWORD` — пароль самого ключа

На Windows в PowerShell 5.1 получить Base64:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\path\to\release.jks')) | Set-Clipboard
```

Никогда не добавляйте keystore, пароль или `android/key.properties` в Git.

## Сборка

1. Заменить исходники `develop` из ZIP (при копировании удалить старый `.github/workflows/release-candidate.yml`, если он есть).
2. Сохранить существующий workflow Debug в репозитории.
3. Commit → Push в `develop`.
4. GitHub Actions → **Build Dream Cast TV v0.7 Release** → Run workflow → `develop`.
5. Artifact `dreamcast-tv-v0.7-release` содержит `app-release.apk` и SHA-256.

Если secrets отсутствуют, job завершится с понятной ошибкой, а **не** создаст APK с непредсказуемой подписью.

## Проверка обновления

Перед установкой сравните сертификаты старого и нового APK:

```powershell
apksigner verify --print-certs old-v0.6.0.apk
apksigner verify --print-certs app-release.apk
```

Значение `Signer #1 certificate SHA-256 digest` должно совпадать (если нет схемы смены подписи). Тогда `adb install -r app-release.apk` обновит существующее приложение.

**Компиляция Flutter/Gradle здесь не выполнялась** — нужна проверка GitHub Actions.
