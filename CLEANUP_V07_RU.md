# Dream Cast TV v0.7 — очистка экспериментального кода

- Удалены старые экспериментальные инструкции COSMOS_*, Impeller и диагностический HLS инспектор.
- Удалён канал и механизм перезапуска Impeller (и скрытый Flutter-виджет).
- Сохранён рабочий Media3 selectCompatible; удалён отдельный 15-секундный диагностический probe.
- LibVLC, fMP4 resolver и Media3 selector перенесены из src/debug в src/main; зависимости подключены через implementation; fallback включён для Android Release.
- Сохранение позиции LibVLC выполняется отдельным периодическим таймером каждые 5 секунд на протяжении всего воспроизведения.
- Логирование LibVLC снижено с verbose=2 до verbose=0.
- Отладочный applicationIdSuffix .diagnostic сохранён: тестовый APK не заменяет установленный Release.

**Важно:** Flutter/Gradle сборка здесь не выполнялась; перед публикацией Release требуется проверка CI и подписи. Сам ZIP не содержит `.github/workflows` — сохраняйте существующие workflow репозитория при обновлении `develop`.
