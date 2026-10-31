# Dream Cast TV — Release APK

Этот проект собирает тестовый Debug APK (старый workflow) и подписанный Release APK (новый workflow `Build Dream Cast TV Release APK`).

## 1. Создать ключ локально на Windows

Установите JDK 17 или используйте `keytool.exe` из установленной Java. В PowerShell:

```powershell
keytool -genkeypair -v -keystore "$HOME\dreamcast-tv-upload.jks" -alias dreamcasttv -keyalg RSA -keysize 3072 -validity 10000
```

Задайте надёжный пароль хранилища и пароль ключа (можно одинаковые, если `keytool` предлагает), запомните alias `dreamcasttv`. **Не загружайте `.jks` в GitHub и никому не передавайте пароль.** Сделайте резервную копию `.jks` и паролей в безопасном месте. При потере ключа нельзя будет обновить установленный Release APK с той же подписью.

## 2. Настроить GitHub Actions Secrets

Repository → Settings → Secrets and variables → Actions → New repository secret.

Добавьте четыре секрета:

- `TV_RELEASE_KEYSTORE_BASE64` — base64 содержимого `.jks` (создаётся командой ниже)
- `TV_RELEASE_STORE_PASSWORD` — пароль keystore
- `TV_RELEASE_KEY_PASSWORD` — пароль ключа
- `TV_RELEASE_KEY_ALIAS` — `dreamcasttv`

Чтобы получить Base64 и скопировать в буфер обмена (PowerShell):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\dreamcast-tv-upload.jks")) | Set-Clipboard
```

Вставьте буфер в значение первого секрета. Не печатайте его в чат, логи или файлы репозитория.

## 3. Сборка

Сделайте commit и push в `develop`. В GitHub → Actions → Build Dream Cast TV Release APK → Run workflow выберите **develop** для первого теста. Успешный запуск даст Artifact `dreamcast-tv-release-apk` с `app-release.apk` и контрольной суммой SHA256.

После проверки и переноса стабильного кода в `main` можно запустить тот же workflow для `main`.

## 4. Установка

Release и Debug используют один `applicationId` (`com.dreamcast.app`), но разные ключи подписи. Android **не установит** Release поверх Debug. Перед удалением Debug сохраните нужную историю/настройки: удаление приложения может удалить локальные данные. Для независимого тестирования без удаления Debug нужен отдельный applicationId — в текущем проекте это не настроено.

## Важно

- Не коммитьте `android/key.properties` или `*.jks` — они исключены в `android/.gitignore`.
- Не используйте GitHub Actions для публикации приватного ключа как artifact.
- Не считайте Release APK проверенным, пока workflow не собрался и приложение не протестировано на телевизоре.
