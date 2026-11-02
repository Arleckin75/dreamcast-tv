# Cosmos: AV1 720p / 1080p fMP4 (diagnostic develop)

- По умолчанию — 720p, прямой HLS media-плейлист с сегментами `.m4s` и `EXT-X-MAP`.
- Кнопка ↑ — выбрать 1080p; кнопка ↓ — вернуться на 720p.
- 480p исключён из выбора в диагностическом LibVLC-плеере.
- При недоступности 1080p приложение сохраняет текущий режим.
- Смена качества перезапускает воспроизведение с начала.
- Журналы: `DCTV_QUALITY`, `DCTV_FMP4`, `DCTV_AV1`, `DCTV_LAYOUT`.
- Основной ExoPlayer на совместимых устройствах не изменён. Release v0.6.0 не затронут.

PowerShell (после запуска обоих режимов):

```powershell
& "D:\adb\adb.exe" -s 192.168.0.9:5555 logcat -d -v threadtime -s DCTV_QUALITY:I DCTV_FMP4:I DCTV_AV1:I DCTV_LAYOUT:I | Out-File "$HOME\Desktop\cosmos-1080p.txt" -Encoding utf8
```
