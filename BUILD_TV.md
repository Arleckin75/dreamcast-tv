# Сборка единого Dream Cast TV APK

Один APK содержит Media3/ExoPlayer, системные декодеры Android и dav1d.
Выбор «Авто / Аппаратное / Программное AV1 — dav1d» выполняется в плеере.
Отдельных hardware/software flavor больше нет.

## Локально

Нужны JDK 17, Android SDK Platform 36, Build Tools 36.0.0, NDK 28.2.13676358,
CMake 3.22.1, Python 3 и Git. Настройте `JAVA_HOME` и `ANDROID_HOME`.

```powershell
python -m pip install meson ninja
python scripts/prepare_media3_dav1d.py --ndk "$env:ANDROID_HOME/ndk/28.2.13676358"
native-android/gradlew.bat -p native-android :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

На Linux/macOS используйте `bash native-android/gradlew -p native-android`.
PowerShell-обёртка: `scripts/build_native.ps1`, тесты — `-Test`, Release —
`-Configuration release`. Параметр `-Player` больше не используется.

APK: `native-android/app/build/outputs/apk/debug/app-debug.apk`.
Release: `native-android/app/build/outputs/apk/release/app-release.apk`
(без настроенной подписи — `app-release-unsigned.apk`).
Debug package — `com.dreamcast.app.kotlin`, Release — `com.dreamcast.app`.
Для независимой тестовой установки: `"-PnativeDebugSuffix=.unified"`.

## GitHub Actions

**Build unified Debug and Release APKs (develop)** запускается при каждом push
в `develop` и вручную. Собирает Debug, запускает unit-тесты, lint и аудит APK,
затем собирает минифицированный Release (R8 и shrinkResources), запускает
`lintRelease` и тот же аудит. Release подписан debug-ключом CI
(`-PreleaseDebugSigning=true`), его можно установить для проверки, но не публиковать.
Артефакты — `dreamcast-tv-unified-debug`, `dreamcast-tv-unified-release-debugsigned`;
отчёты, mapping R8 и аудиты — `dreamcast-tv-unified-develop-reports`.
Workflow устанавливает фиксированные NDK/CMake и собирает закреплённый dav1d
под ARMv7 и ARM64.

Локально тот же Release: добавьте `-PreleaseDebugSigning=true` к `:app:assembleRelease`
(действует, только если нет `native-android/key.properties`).

**Build Dream Cast TV Release APK** запускается вручную из `main` и собирает один
подписанный APK. Публикацию можно отключить параметром workflow. Проверки
сертификата относительно v0.7.0 и versionCode сохранены.
См. [подпись](RELEASE_SIGNING_RU.md).

Перед выпуском проверяйте реальные HLS/DASH-потоки, аппаратное AV1 и dav1d,
отключённый пункт аппаратного режима, смену режима в паузе, перемотку,
скорость, прогресс, следующую серию, фон и PiP.
