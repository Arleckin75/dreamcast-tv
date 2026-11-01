# Cosmos: принудительный AV1 480p fMP4 — только Debug

При запуске резервного LibVLC приложение загружает HLS master playlist, находит `RESOLUTION=854x480` с `av01`, читает media playlist и проверяет наличие `#EXT-X-MAP` и сегментов `.m4s`/`.mp4`. Только после этого передаёт **адрес media playlist**, а не master playlist, в LibVLC. Другие качества и исходный master в этой диагностике не используются.

Если подходящего варианта нет, воспроизведение не начинается. URL и токены не записываются в DCTV_480P и DCTV_AV1.

```powershell
& "D:\adb\adb.exe" -s 192.168.0.9:5555 logcat -c
# Запустите серию и подождите 20 секунд
& "D:\adb\adb.exe" -s 192.168.0.9:5555 logcat -d -v threadtime -s DCTV_480P:I DCTV_AV1:I | Out-File "$HOME\Desktop\cosmos-480p.txt" -Encoding utf8
```

Проверить: `selected AV1 480p fMP4`, `videoTracks`, `selectedVideo`, `Vout event`, и визуальное наличие изображения. Не путать `SurfaceView` с реальным видеокадром. Код не изменяет Release v0.6.0, интерфейс серий или выбор источника.
