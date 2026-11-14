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

### Номер выпуска

Номер вычисляется автоматически и только при выпуске: workflow берёт последний
тег `vX.Y.Z` и прибавляет единицу к последней цифре. Каждая цифра — от 0 до 9:
0.9.5 → 0.9.6 → … → 0.9.9 → 1.0.0 → … → 1.9.9 → 2.0.0. Узнать следующий номер:

```bash
python scripts/app_version.py next
```

Android versionCode выводится из номера: X × 1 000 000 + Y × 100 000 + Z × 1000 + 1
(0.9.5 → 905001, 1.0.0 → 1000001), поэтому каждый выпуск ставится поверх прежнего.
Сборки из `develop` и локальные сборки номер не занимают: они получают номер
последнего выпуска с пометкой `-dev` (Release) или `-debug` (Debug) и тот же versionCode.
Логика — в [scripts/app_version.py](scripts/app_version.py) и `native-android/app/build.gradle.kts`.

### Шаги

1. Добавьте заметки к следующему выпуску, `docs/release-notes/RELEASE_NOTES_vX.Y.Z.md`,
   и перенесите проверенные изменения в `main`. Без файла заметок workflow остановится
   до сборки и напишет, какой файл нужен.
2. Запустите **Build Dream Cast TV Release APK** из `main`. Поле **version** оставьте
   пустым, чтобы выпустить следующий номер. Другой номер, например 1.0.0 вместо 0.9.6,
   указывается там же; он должен быть больше последнего выпуска.
3. Флажок **publish** включён по умолчанию.
   Для получения APK без публикации снимите его (`publish: false`).
4. Получите артефакт `dreamcast-tv-vX.Y.Z-unified-signed-release`, проверьте отчёты и устройства.
5. При включённом флажке (`publish: true`) после успешных проверок автоматически
   создаётся GitHub Release и тег `vX.Y.Z` на выпущенном коммите; следующий выпуск
   считается от него. Для предварительной проверки APK сначала выполните запуск без
   публикации: тег при этом не создаётся, и номер остаётся свободным.

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
