# Dream Cast TV v0.7.1 — исключение x86/x86_64 из software_AV1

Этот патч меняет ТОЛЬКО два файла в ветке develop:

- `android/app/build.gradle.kts`: фильтр native ABI для flavor `softwareAv1` оставляет только `armeabi-v7a` и `arm64-v8a`.
- `.github/workflows/build-tv-apk.yml`: сборка software c `--target-platform android-arm,android-arm64`; дополнительная проверка ZIP-содержимого готового APK с ошибкой CI при наличии x86/x86_64, а также при отсутствии ARM32/ARM64. Сохранены проверки AV1-зависимостей из предыдущего патча.

## Применение

1. GitHub Desktop: ветка `develop`.
2. Распаковать ZIP в корень репозитория с заменой двух файлов.
3. Commit -> Push origin.
4. Actions -> Build Dream Cast TV APK -> Run workflow -> `source_branch: develop`.
5. После сборки проверить статус `Verify software APK excludes x86 ABIs` и скачать artifacts.

`main` НЕ изменять.

Важно: исключение x86_64 уменьшает только software APK и исключает запуск на x86_64-эмуляторах. Режим hardware_AV1 и рабочая логика плееров не меняются. Реальная компиляция и итоговый размер APK здесь не проверялись.
