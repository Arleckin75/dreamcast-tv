# Подпись и выпуск Release APK

Релизный workflow — `.github/workflows/build-tv-release.yml`. Release собирается только
из `main`, с постоянным ключом и версией из `native-android/app/build.gradle.kts`. Ключ не создаётся
автоматически; debug-подпись вместо Release не подставляется.

## Ключ и GitHub Secrets

Если приложение уже выпускалось, используйте прежний ключ для совместимого
обновления. Для первого выпуска ключ можно создать через JDK 17:

```sh
keytool -genkeypair -v -keystore dreamcast-tv-upload.jks -alias dreamcasttv -keyalg RSA -keysize 3072 -validity 10000
```

Храните keystore вне репозитория с резервной копией. В **Settings → Secrets and
variables → Actions** настройте имена, которые использует текущий workflow:

| Secret | Значение |
| --- | --- |
| `TV_RELEASE_KEYSTORE_BASE64` | Base64 содержимого keystore |
| `TV_RELEASE_STORE_PASSWORD` | Пароль keystore |
| `TV_RELEASE_KEY_PASSWORD` | Пароль ключа |
| `TV_RELEASE_KEY_ALIAS` | Alias ключа |

Получить Base64 в буфер обмена PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\path\to\dreamcast-tv-upload.jks')) | Set-Clipboard
```

Не добавляйте ключи, пароли или `native-android/key.properties` в Git и артефакты.

## Локальная подпись

Создайте `native-android/key.properties` с реальными значениями:

```properties
storeFile=C:/path/to/dreamcast-tv-upload.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=dreamcasttv
keyPassword=YOUR_KEY_PASSWORD
```

Далее используйте `./scripts/build_native.ps1 -Configuration release`. Требования к SDK и dav1d —
в [инструкции сборки](BUILD_TV.md).

## Сборка и публикация

Особенности слияния веток описаны в [историческом плане переноса v0.8.5 в main](docs/MAIN_MIGRATION_RU.md).

1. Перенесите проверенные изменения в `main`.
   Версия этого выпуска — `0.9.3`, Android versionCode — `903001`.
   Описание для GitHub Releases берётся из [заметок к релизу](RELEASE_NOTES_v0.9.3.md).
2. Запустите **Build Dream Cast TV Release APK** из `main`.
3. Флажок **Publish v0.9.3 on GitHub Releases (main only)** включён по умолчанию.
   Для получения APK без публикации снимите его (`publish: false`).
4. Получите артефакт `dreamcast-tv-v0.9.3-unified-signed-release`, проверьте отчёты и устройства.
5. При включённом флажке (`publish: true`) после успешных проверок автоматически
   создаётся GitHub Release v0.9.3. Для предварительной проверки APK сначала
   выполните запуск без публикации.

Задания подписи находятся непосредственно в релизном workflow и
используют прежнее окружение `release-signing`; переносить или создавать
секреты заново не требуется.

Перед публикацией проверьте поиск с системной клавиатурой на old ATV и Google TV,
воспроизведение, сохранение позиции и переходы между сериями в обоих режимах декодирования.
Неподтверждённые проверки перечислены в заметках к релизу.

Workflow сохраняет проверку подписи относительно опубликованного v0.7.0:
сертификаты должны совпадать, а новый versionCode — быть больше прежнего.

Единый Release использует `com.dreamcast.app`; Debug устанавливается отдельно
как `com.dreamcast.app.kotlin`. Для обновления Release должны совпадать package
ID и допустимая подпись, а versionCode должен соответствовать правилам обновления.

Сравните сертификаты до установки:

```sh
apksigner verify --print-certs old.apk
apksigner verify --print-certs new.apk
```

Компиляция, подпись и аудит APK не заменяют проверку воспроизведения, поиска,
сохранения локальных данных и обновления на реальном TV.
