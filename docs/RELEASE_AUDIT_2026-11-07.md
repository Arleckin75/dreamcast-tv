# Проверка release и зависимостей — 7 ноября 2026

Проверена локальная ветка `develop`, исходники `4748a638` с исправлением
`native-android/dav1d/consumer-rules.pro`. Зависимости при проверке не обновлялись.

## Найденная и исправленная ошибка

На Cosmos минифицированный release падал при запуске программного AV1:
`SIGABRT` в `ExoPlayer:Dav1d`. JNI не находил `DecoderInputBuffer.data`
и `VideoDecoderOutputBuffer.width` (`NoSuchFieldError`).

Существовавшее правило сохраняло только `androidx.media3.decoder.av1.**`.
Общие классы буферов Media3 находятся в другом пакете; R8 переименовывал их поля.
Добавлены правила для классов, полей и методов, которые `dav1d_jni.cc`
ищет через `FindClass`, `GetFieldID` и `GetMethodID`, включая унаследованный `setFlags`.

После исправления программное воспроизведение проверено на обоих устройствах.

## Сборка и устройства

Использован настоящий тип сборки `release`: R8 и shrinkResources включены,
`debuggable` выключен. Только application ID изменён через локальный Gradle init
script на `com.dreamcast.app.releaseaudit`; готовый APK подписан Android Debug
ключом. Основное приложение установкой не заменялось.

| Проверка | Cosmos, Android 11, ARMv7 | Google TV Streamer, Android 14, ARM64 |
| --- | --- | --- |
| Запуск, каталог, карточка релиза | Пройдено | Пройдено |
| AV1, автоматический выбор | `libdav1d`, 720p | `c2.mtk.av1.decoder`, 1080p |
| Программный AV1 | Воспроизводится | Воспроизводится, 720p |
| Аппаратный AV1 | Корректно недоступен для выбранного видео | Воспроизводится |
| Переключение декодеров | Проверено состояние недоступного пункта | Hardware → dav1d → Hardware, позиция сохраняется |
| Возобновление после force-stop | Сохранена позиция 00:49 | Сохранена позиция 01:24 |
| Потоки | HLS и DASH | HLS |
| Экран лицензий | Открыт полный текст dav1d из «О приложении» | Открыт полный текст dav1d из «О приложении» |

Использовалась первая серия «Красной реки» из живого каталога.
На Cosmos при программном DASH зарегистрированы 27 пропущенных кадров за
19,4 секунды. Это короткая функциональная проверка, не длительный тест плавности.

`assembleRelease` и `lintRelease` завершились успешно. Lint: 0 ошибок,
53 предупреждения, 5 подсказок. Проверка APK `audit_av1_apk.py` пройдена:
оба ABI, ELF alignment ≥ 16 КБ, файлы лицензий, отсутствие Flutter/LibVLC.
API 23 проверен настройками сборки и lint; устройства Android 6 для запуска не было.

APK: `.tools/dreamcast-tv-release-audit-fixed.apk`.
SHA-256: `d71d2c128417d357a1a60d6ef515a30132d29ad62f7c4c02c6fa359a4a1828ac`.
Локальные журналы: `.tools/release-audit-fixed-build.log`,
`.tools/release-audit-devices/`; дампы до исправления сохраняют исходный crash.

## Зависимости

Версии сверены с metadata официальных Google Maven и Maven Central на дату проверки.
Новизна версии сама по себе не является доказательством дефекта.

| Библиотека | В проекте | Стабильная версия в репозитории |
| --- | --- | --- |
| Media3 | 1.9.2 | 1.11.1 |
| Compose BOM | 2025.08.01 | 2026.09.00 |
| Activity Compose | 1.10.1 | 1.13.0 |
| Lifecycle | 2.9.3 | 2.11.0 |
| TV Material | 1.0.1 | 1.1.0 |
| WorkManager | 2.10.3 | 2.12.0 |
| Core KTX | 1.16.0 | 1.19.1 |
| Coroutines | 1.10.2 | 1.11.0 |
| OkHttp | 4.12.0 | 5.5.1 |
| jsoup | 1.18.3 | 1.24.1 |
| Coil, ветка 2.x (`io.coil-kt`) | 2.7.0 | 2.7.0 |

Источники: [Media3](https://developer.android.com/jetpack/androidx/releases/media3),
[Compose BOM](https://developer.android.com/develop/ui/compose/bom),
[AndroidX](https://developer.android.com/jetpack/androidx/versions),
[jsoup](https://jsoup.org/news/),
[Coroutines](https://github.com/Kotlin/kotlinx.coroutines/releases).
Точные metadata URL сохранены в `.tools/release-audit-versions.json`.

Обновление Media3 требует согласованного изменения app, dav1d-модуля и закреплённых
исходников decoder_av1 в `scripts/prepare_media3_dav1d.py`. Замена одного номера
версии в app не проверяет совместимость JNI. NDK в обоих модулях и
`.android-ndk-version` совпадает: `28.2.13676358`.

## Записи об уязвимостях

Запрос OSV по Maven-координатам из release dependency report дал следующие находки:

- **jsoup 1.18.3**: [исчерпание памяти в XML-парсере](https://github.com/advisories/GHSA-65r4-943x-97jj),
  исправлено в 1.23.2; [очистка HTML с пользовательским Safelist](https://github.com/jhy/jsoup/security/advisories/GHSA-pmhh-3w7g-xqp8),
  исправлено в 1.23.1. Приложение использует HTML-парсинг `Jsoup.parse`,
  вызовов XML parser, Cleaner или Safelist в его исходниках не найдено.
- **Guava 33.3.1-android**, транзитивная зависимость:
  [выделение памяти при Java-десериализации коллекций](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94),
  исправлено начиная с 33.7.2-android. В исходниках приложения больше нет
  `ObjectInputStream`/`readObject` после удаления LegacyPreferences.

Наличие затронутой версии подтверждено; достижимость уязвимых сценариев в приложении
не подтверждена. Рекомендуется отдельное обновление jsoup и Guava с проверкой
парсеров и проигрывателя. Результат OSV сохранён в `.tools/release-audit-osv.json`.
Проверка не является исчерпывающим аудитом безопасности, в частности native dav1d
не проверялся через Maven OSV.

## Последующее обновление jsoup и Guava

Выполнено отдельным локальным изменением в `develop` поверх `58e3ac77`:

- jsoup: `1.18.3` → `1.23.2`, версия с исправлениями обеих найденных уязвимостей;
- Guava: `33.3.1-android` → `33.7.2-android`, через dependency constraint в app.
  Guava остаётся транзитивной зависимостью; Gradle подтвердил выбор Android-версии.

Выбраны исправленные версии без перехода на более широкие изменения jsoup 1.24.x.
Media3, Compose и остальные прямые зависимости не обновлялись.

Проверки после обновления:

- все 104 unit-теста, включая тесты парсеров: 0 ошибок, 0 пропусков;
- `assembleRelease`, `lintRelease`: успешно; lint — 0 ошибок, 53 предупреждения,
  5 подсказок;
- `audit_av1_apk.py`: PASS;
- повторный OSV-запрос по 137 уникальным Maven-координатам из release dependency
  report: известных уязвимостей не найдено; отдельно проверены обе новые версии;
- Cosmos: AV1 через dav1d, возобновление сохранённого просмотра с 01:09;
  затем очищены данные только тестового пакета и проверены загрузка живого каталога,
  карточка, список серий, выбор HLS и воспроизведение с нуля;
- Streamer: аппаратный AV1 1080p, возобновление с 01:39, переключение на dav1d 720p
  с сохранением позиции и дальнейшее воспроизведение.

Устройства проверены на минифицированном release с прежним отдельным ID
`com.dreamcast.app.releaseaudit` и тестовой подписью. Основные установки не заменены.
Это короткие функциональные проверки; длительная плавность воспроизведения
не измерялась.

APK: `.tools/dreamcast-tv-dependency-update.apk`.
SHA-256: `997d634866c8c99047844cde6002b0bce87e98425edc4a8db489538ebc80f8ef`.
Журналы и результаты: `.tools/dependency-security-update-build.log`,
`.tools/dependency-security-update-runtime-osv.json`,
`.tools/dependency-security-update-osv.json`,
`.tools/dependency-security-update-devices/`.

## Последующее обновление Media3 и AV1-модуля

Выполнено локально в `develop` поверх `3d387c4c`:

- все runtime-компоненты Media3 и зависимости dav1d-модуля: `1.9.2` → `1.11.1`;
- Java/JNI-исходники AV1-обёртки закреплены на commit
  `8c6678b657ede1e7883fc164ef73ed483c7796c3` официального тега `1.11.1`;
- `MetadataRetriever` перенесён на новый пакет `androidx.media3.inspector`,
  добавлена зависимость `media3-inspector:1.11.1`;
- dav1d `1.5.1`, cpu_features `0.10.1`, NDK и minSdk сохранены;
- обновлены версия на экране лицензий, ссылки и snapshot уведомлений Media3.

Upstream удалил явный флаг ELF alignment. Первый APK не прошёл наш аудит:
ARMv7 JNI-библиотека имела выравнивание 4 КБ. Флаг
`-Wl,-z,max-page-size=16384` теперь передаётся через CMake arguments в нашем
Gradle-модуле для обоих ABI. Закреплённые исходники upstream остаются неизменёнными.
Keep-правила JNI сохранены; поиск полей и методов в новой обёртке сверён с ними.

Проверки итоговой версии:

- 104 unit-теста: успешно, без пропусков;
- 7 Python-тестов упаковки APK: успешно;
- минифицированный `assembleRelease`, `lintRelease`: успешно,
  lint — 0 ошибок, 49 предупреждений, 5 подсказок;
- `audit_av1_apk.py`: PASS, оба ABI с ELF alignment ≥ 16 КБ;
- snapshot лицензий совпадает с закреплёнными исходниками;
- release dependency report не содержит Media3 1.9.2, все компоненты согласованы
  на 1.11.1; OSV-запрос по Maven-координатам из него: находок нет;
- на отдельной минифицированной release-установке Cosmos воспроизводит AV1 через
  dav1d (HLS и DASH), Streamer — через dav1d и аппаратный `c2.mtk.av1.decoder`;
  переключение декодера сохраняет позицию и состояние паузы;
- на отдельной debug-установке `com.dreamcast.app.media3audit` существующий
  `UnifiedAv1PlaybackTest` прошёл на каждом устройстве (по одному тесту):
  автоматический выбор, ручная смена декодера, сохранение позиции и паузы,
  перемотка, возврат из фона, дальнейшее воспроизведение.

Debug-тест дополняет ручную проверку release; он не заменяет проверку R8.
Основные установки приложения не заменены. Длительная плавность и Android 6
на физическом устройстве не проверялись.

Итоговый тестовый APK: `.tools/dreamcast-tv-media3-111.apk`.
SHA-256: `2ed6e59c31a95670d198927dc2b23e1bb72cac67407c7a2eb3a8659ed9c1abae`.
Артефакты: `.tools/media3-111-build.log`,
`.tools/media3-111-alignment-build.log`, `.tools/media3-111-native-prepare.log`,
`.tools/media3-111-packaging-tests.log`, `.tools/media3-111-osv.json`,
`.tools/media3-111-instrumentation-build.log`,
`.tools/media3-111-test-192.168.0.9.log`, `.tools/media3-111-test-192.168.0.10.log`,
`.tools/media3-111-devices/`.

## Исследование обновлений Compose и остальных AndroidX

Изучены официальные release notes и metadata AAR/BOM. Версии в Gradle
в рамках этого исследования не изменялись. Перечисленная польза — основание
для проверки обновления, а не доказательство наличия соответствующего бага в приложении.

| Компонент | Рассмотренный следующий шаг | Вывод для проекта |
| --- | --- | --- |
| WorkManager 2.10.3 | 2.11.2 | Наиболее конкретная польза для периодической проверки новых серий: исправления фоновой сети и перепланирования периодических заданий после необработанных исключений. Обновлять `work-runtime-ktx` и `work-testing` вместе. |
| Compose BOM 2025.08.01 (Compose 1.9.0) | BOM 2026.06.01 (Compose 1.11.4) | Полезны исправления фокуса в переиспользуемых lazy-контейнерах и оптимизации runtime. В 1.11 переработан SlotTable, поэтому изменение требует тщательной проверки каталога, прокрутки и фокуса. |
| TV Material 1.0.1 | 1.1.0, вместе с выбранным Compose | Есть исправления отрисовки текста при масштабировании Surface и фокуса Carousel. В приложении используются TV Button/Text/Theme, но не upstream Carousel; исправление Carousel не следует считать исправлением нашей собственной карусели. |
| Activity Compose 1.10.1 | 1.13.0, отдельным шагом | Добавлена интеграция событий PiP; с 1.12 переписана обработка Back поверх NavigationEvent. Прямой необходимости для текущего кода не выявлено. Проверять Back на всех экранах и возврат из плеера/PiP. |
| Lifecycle 2.9.3 | 2.10.0 при необходимости | 2.9.4 исправляет преимущественно KMP-сборку. Главное новшество 2.10 — scoped lifecycle в Compose; сейчас оно не используется. Срочного основания для обновления нет. |
| Core KTX 1.16.0 | Пока оставить | Польза актуальных изменений для наших используемых API не установлена. Последняя 1.19.1 требует новой сборочной среды. |

### Ограничения и совместимость

- Наши параметры: minSdk 23, compileSdk 36, AGP 8.11.1, Kotlin 2.2.20.
- BOM 2026.06.01 согласует Foundation/UI/Runtime 1.11.4 и Material3 1.4.0.
  Прямой AAR UI 1.11.4 требует minSdk 23, compileSdk ≥ 35 и AGP ≥ 8.6.0;
  такие же проверенные пороги у TV Material 1.1.0 и Lifecycle Runtime Compose 2.10.0.
  Это предварительная проверка: полный граф зависимостей ещё не собирался.
- Последний BOM 2026.09.00 содержит Compose 1.12.1. Прямой AAR UI 1.12.1
  требует compileSdk 37 и AGP ≥ 9.1.0; release notes Compose говорят о переходе
  на API 37 / AGP 9.2.0. В любом случае текущей среды недостаточно — это отдельная
  миграция инструментов сборки, а не обычное обновление библиотек.
- Activity 1.13.0: проверенный прямой AAR требует minSdk 23,
  compileSdk ≥ 36, AGP ≥ 8.9.1. Текущая среда удовлетворяет этим порогам.
- WorkManager 2.11.2 сохраняет API 23; 2.12.0 повышает минимум до API 24
  и исключит Android 6. Не выбирать 2.12.0 без отдельного решения о поддержке ОС.
- Lifecycle 2.11.0 также связан с обновлением Compose / compileSdk 37.
- Прямой AAR Core 1.19.1 требует compileSdk 37 и AGP ≥ 9.1.0;
  Core KTX в новой ветке объединён с основным Core. Переход без необходимости не предлагается.
- Исправление D-pad в TextField из Foundation 1.9.0 уже есть в текущем BOM;
  его нельзя учитывать как новую пользу от обновления.

Предлагаемый порядок: WorkManager 2.11.2 отдельно, затем Compose BOM 2026.06.01
и TV Material 1.1.0 одной согласованной группой. Для UI проверить тесты поиска,
каталога и фокуса, возврат между экранами, длинные списки и минифицированный release
на обоих устройствах. Activity/Lifecycle/Core отложить до конкретной необходимости
или подтверждённого требования обновлённого графа зависимостей.

Источники: [WorkManager](https://developer.android.com/jetpack/androidx/releases/work#2.11.2),
[Compose UI](https://developer.android.com/jetpack/androidx/releases/compose-ui),
[Foundation](https://developer.android.com/jetpack/androidx/releases/compose-foundation),
[Runtime](https://developer.android.com/jetpack/androidx/releases/compose-runtime),
[TV Material](https://developer.android.com/jetpack/androidx/releases/tv),
[Activity](https://developer.android.com/jetpack/androidx/releases/activity),
[Lifecycle](https://developer.android.com/jetpack/androidx/releases/lifecycle),
[Core](https://developer.android.com/jetpack/androidx/releases/core),
[BOM 2026.06.01](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.06.01/compose-bom-2026.06.01.pom).
Локальные выписки и извлечённые metadata: `.tools/androidx-review/`.
