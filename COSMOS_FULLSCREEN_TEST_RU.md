# Cosmos AV1 — тест полноэкранного изображения и таймкода

На базе проверенного принудительного HLS 480p fMP4. В debug activity добавлена синхронизация размеров SurfaceView → VLCVout.setWindowSize, сброс ручного масштаба/аспекта и диагностика времени VLC.

После установки debug APK очистить logcat, запустить эпизод и оставить воспроизведение на 30–40 секунд. Снять журнал:

```powershell
& "D:\adb\adb.exe" -s 192.168.0.9:5555 logcat -d -v threadtime -s DCTV_480P:I DCTV_AV1:I DCTV_LAYOUT:I | Out-File "$HOME\Desktop\cosmos-fullscreen.txt" -Encoding utf8
```

Проверить, заполняет ли изображение телевизор и движется ли видео; сам `timeMs=0` не доказывает остановку: измеряется VLC player clock, возможно отличающийся от реального отображения кадров.

Изменения только в debug Android Activity; интерфейс и release не менялись.
