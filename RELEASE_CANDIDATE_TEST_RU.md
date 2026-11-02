# Dream Cast TV v0.7 — проверка Release Candidate

Workflow: **Build Dream Cast TV Release Candidate** → **Run workflow** → branch **develop**.

* Собирает настоящий оптимизированный `flutter build apk --release` (ARMv7 и ARM64), но с временным тестовым ключом.
* Идентификатор APK: `com.dreamcast.app.rc`. Приложение устанавливается отдельно от `com.dreamcast.app` (v0.6.0) и Debug `com.dreamcast.app.diagnostic`.
* Artifact: `dreamcast-tv-v0.7-release-candidate` → `app-release.apk`.
* Для каждой сборки генерируется **новый ключ**, поэтому следующая RC может не устанавливаться поверх предыдущей: удалите только тестовый пакет `com.dreamcast.app.rc` перед переустановкой. Его локальные данные при удалении стираются.
* Настройки и прогресс не разделяются между тремя package ID. Проверяйте сохранение истории внутри RC отдельно.
* **Не публикуйте этот APK как стабильный релиз**. Для production потребуется постоянный сохранённый release keystore и проверка обновления с v0.6.0.
* При копировании проекта в существующий Git-репозиторий сохраняйте остальные workflows в `.github/workflows`.

Установка через ADB:

```powershell
& "D:\adb\adb.exe" -s 192.168.0.9:5555 install -r "app-release.apk"
```

Проверка: 720p на Cosmos через LibVLC, 1080p на аппаратной AV1-приставке, пауза/перемотка, выход в главное меню, «Продолжить просмотр».
