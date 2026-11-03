# Подпись и выпуск Release APK

Основной workflow — `.github/workflows/build-tv-apk.yml`. Release собирается только
из `main`, с постоянным ключом и версией из `pubspec.yaml`. Ключ не создаётся
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

Не добавляйте ключи, пароли или `android/key.properties` в Git и артефакты.

## Локальная подпись

Создайте `android/key.properties` с реальными значениями:

```properties
storeFile=C:/path/to/dreamcast-tv-upload.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=dreamcasttv
keyPassword=YOUR_KEY_PASSWORD
```

Далее используйте `dart run scripts/build_tv.dart hardware release` или
`dart run scripts/build_tv.dart software release`. Требования к SDK и LibVLC —
в [инструкции сборки](BUILD_TV.md).

## Сборка и публикация

Особенности слияния веток описаны в [плане переноса v0.8.4 в main](docs/MAIN_MIGRATION_RU.md).

1. Перенесите проверенные изменения в `main`.
   Версия этого выпуска — `0.8.4`, Android versionCode — `804001`.
   Описание для GitHub Releases берётся из [заметок к релизу](RELEASE_NOTES_v0.8.4.md).
2. Запустите **Build Dream Cast TV APK** из `main` с `build_mode: release`.
3. Для получения APK без публикации оставьте `publish: false`. Также можно
   использовать **Build Dream Cast TV Release APK**, который передаёт это значение.
4. Получите артефакты `dreamcast-tv-v0.8.4-software_AV1-signed-release` и
   `dreamcast-tv-v0.8.4-hardware_AV1-signed-release`, проверьте отчёты и устройства.
5. Публикация через основной workflow требует отдельного `publish: true`.

Перед публикацией проверьте поиск с системной клавиатурой на old ATV и Google TV,
воспроизведение, сохранение позиции и переходы между сериями в обеих сборках.
Неподтверждённые проверки перечислены в заметках к релизу.

Workflow сохраняет проверку подписи относительно опубликованного v0.7.0:
сертификаты должны совпадать, а новый versionCode — быть больше прежнего.

Обе Release-сборки используют `com.dreamcast.app`; Debug устанавливается отдельно
как `com.dreamcast.app.diagnostic`. Для обновления Release должны совпадать package
ID и допустимая подпись, а versionCode должен соответствовать правилам обновления.

Сравните сертификаты до установки:

```sh
apksigner verify --print-certs old.apk
apksigner verify --print-certs new.apk
```

Компиляция, подпись и аудит APK не заменяют проверку воспроизведения, поиска,
сохранения локальных данных и обновления на реальном TV.
