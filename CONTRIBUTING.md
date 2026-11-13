# Как вносить изменения

## Ветки

- `develop` — текущая разработка. Каждый push собирает Debug и минифицированный
  Release, запускает тесты, lint и аудит APK (см. [BUILD_TV.md](BUILD_TV.md)).
- `main` — только выпущенные версии. Выпуск собирается вручную workflow
  **Build Dream Cast TV Release APK** (см. [RELEASE_SIGNING_RU.md](RELEASE_SIGNING_RU.md)).

Крупные или рискованные изменения (обновления Compose, Media3, OkHttp, переработка
экранов и плеера) делайте в отдельной ветке от `develop` и вливайте через pull request
со squash merge: в истории остаётся один коммит с понятным сообщением.

## Сообщения коммитов

Используется [Conventional Commits](https://www.conventionalcommits.org/ru/v1.0.0/):

```
<тип>: <что изменилось, в повелительном наклонении>

<зачем это нужно и что проверено; при необходимости — что не проверено>
```

| Тип | Когда |
| --- | --- |
| `feat` | Новая возможность для пользователя |
| `fix` | Исправление ошибки |
| `build` | Gradle, зависимости, NDK, dav1d |
| `ci` | GitHub Actions |
| `refactor` | Изменение структуры кода без изменения поведения |
| `test` | Только тесты |
| `docs` | Документация и заметки к выпуску |
| `chore` | Подготовка выпуска, версия, прочее |

Примеры:

```
fix: keep Media3 buffer fields used by dav1d JNI from R8
build: update OkHttp to 5.4.0
docs: add release notes for v0.9.6
```

Не используйте сообщения вроде `fix`, `audit`, `clear`, `rebuild`: по ним
невозможно понять изменение без чтения diff. Язык сообщения — английский или
русский, но единообразно в пределах одного pull request.

## Перед коммитом

```powershell
native-android/gradlew.bat -p native-android :app:testDebugUnitTest :app:lintDebug
```

Изменения плеера, декодеров или R8-правил дополнительно проверяйте минифицированным
Release (`:app:assembleRelease -PreleaseDebugSigning=true`) на устройстве с
аппаратным AV1 и на устройстве без него.
