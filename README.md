<p align="center">
  <img src="icon.png" width="96" height="96" alt="Dream Cast app icon">
</p>

<h1 align="center">Dream Cast</h1>

<p align="center">
  Современный Android-клиент для просмотра аниме в озвучке Dream Cast.
</p>

<p align="center">
  <img alt="Flutter" src="https://img.shields.io/badge/Flutter-3.x-02569B?style=flat-square&logo=flutter&logoColor=white">
  <img alt="Android" src="https://img.shields.io/badge/Android-Material%203-3DDC84?style=flat-square&logo=android&logoColor=white">
  <img alt="License" src="https://img.shields.io/badge/License-GPLv3-blue?style=flat-square">
</p>

---
<br>
<p align="center">
  <a href="https://github.com/rurin1x/dreamcast-app/releases/latest">
    <img alt="Download APK" src="https://img.shields.io/badge/Download-APK-2ea44f?style=for-the-badge&logo=android&logoColor=white">
  </a>
</p>




## ⚠️ Региональные ограничения

Dream Cast может быть недоступен в некоторых странах и сетях из-за ограничений провайдеров.

Подтверждённые проблемы доступа:
**Россия,** Austria, Belgium, Canada, China, Czech Republic, Denmark, Estonia, France, Germany, Greece, Hong Kong, Hungary, India, Ireland, Italy, Japan, Luxembourg, Malta, Portugal, Romania, Slovakia, Slovenia, Spain, United Kingdom, United States Minor Outlying Islands и United States.

Если приложение не загружает релизы или видео — попробуйте использовать VPN (Финляндия или Нидерланды).



## Скриншоты

<p align="center">
  <img src="screenshots/home.jpg" width="30%">
  <img src="screenshots/detail.jpg" width="30%">
  <img src="screenshots/s_view.jpg" width="30%">

  
</p>
<p align="center">
    <img src="screenshots/library.jpg" width="30%">
    <img src="screenshots/calendar.jpg" width="30%">
    <img src="screenshots/settings.jpg" width="30%">
</p>

<p align="center">
  <img src="screenshots/player.jpg" width="90%">
</p>

## Что умеет

- Просмотр серий в озвучке Dream Cast
- Постеры, описания и метаданные
- Поиск по тайтлам
- Закладки и библиотека
- Сохранение прогресса просмотра
- Поддержка HLS и DASH
- Picture-in-Picture
- Календарь релизов
- Уведомления о новых сериях
- Локальный кэш




## Внутри

Приложение не использует WebView.
Все данные обрабатываются напрямую через парсер и локальный слой приложения:

```text
Dream Cast
→ Dio
→ HTML/API parser
→ PlayerJS decoder
→ Repository
→ Drift cache
→ Riverpod providers
→ Flutter UI
```

Основные технологии:

- Flutter / Dart
- Riverpod
- GoRouter
- Dio
- Drift
- Material 3
- dynamic_color
- video_player
- Workmanager
- flutter_local_notifications

## Сборка

Нужны Flutter SDK, Android SDK и JDK 17. Если Android Studio поставляет другую Java, укажите установленный JDK 17 командой `flutter config --jdk-dir /путь/к/jdk-17`.

```bash
flutter pub get
flutter analyze
flutter test
dart run scripts/build_tv.dart hardware debug
```

Для релизной сборки:

```bash
dart run scripts/build_tv.dart hardware release
```



## Структура

```text
lib/
  app/          запуск приложения, роутер, тема
  core/         база данных, сеть, настройки, логирование
  features/
    home/       главная страница
    library/    закладки и библиотека
    notifications/
    onboarding/
    player/
    profile/
    releases/   парсинг, репозитории и экраны тайтлов
    schedule/
    settings/
```

## Воспроизводимая сборка Android TV

Используйте **Flutter 3.41.5 / Dart 3.11.3**:

```sh
flutter pub get
dart run scripts/build_tv.dart software debug --check
dart run scripts/build_tv.dart hardware debug --check
```

Скрипт выбирает видеоплагин, запускает анализатор и тесты, собирает ARM APK и восстанавливает локальные overrides и lock-файл. Для Release замените `debug` на `release` и предварительно настройте `android/key.properties`. Версия и `versionCode` берутся из `pubspec.yaml` одинаково локально и в CI; сейчас это `0.7.1+710001`. Перед публикацией CI проверяет, что код версии выше опубликованной v0.7.0.

`softwareAv1` предназначен для онлайн-просмотра: скачивание и офлайн-воспроизведение в нём отключены. `hardwareAv1` поддерживает загрузки HLS с общей аудио/видеодорожкой; плейлисты с отдельным аудио или диапазонами байтов пока отклоняются. Все сетевые источники должны использовать HTTPS.

Основной workflow — `build-tv-apk.yml`; старые точки запуска вызывают его. Подписанные Release APK собираются только из `main`, публикация требует явного параметра `publish`. Изменения готовятся в `develop`; перенос в `main` выполняется владельцем проекта.

## Лицензия

Проект распространяется по лицензии GPLv3.

Некоторые идеи парсинга вдохновлены проектом [anicli-api](https://github.com/vypivshiy/anicli-api) (MIT License).

Реализация для приложения переписана на Dart.

## Благодарности

Спасибо Dream Cast за прекрасную озвучку, open-source сообществу за инструменты.
Спасибо людям, которые тестировали приложение и помогали находить проблемы на ранних этапах разработки.

---

<p align="center">
  Dream Cast · GPLv3 · 2026
</p>
