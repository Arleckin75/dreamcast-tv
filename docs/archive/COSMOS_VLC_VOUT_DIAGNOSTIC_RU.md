# Диагностика LibVLC на Cosmos — v0.7 develop

Добавлены Android логи `DCTV_AV1`: `surfaceCreated/surfaceChanged/surfaceDestroyed`, `vlcVout attached`, события `Opening`, `ESAdded`, `ESSelected`, `Vout`, `EncounteredError`, а также периодические выборки состояния плеера: число видеодорожек, выбранная видеодорожка, состояние поверхности, число видеовыходов, время воспроизведения.

Для LibVLC включён `--verbose=2` (подробные логи с тегами VLC). Не отправляйте полный журнал VLC без проверки: он может содержать ссылки потоков и токены. Собирайте прежде всего `DCTV_AV1`, а при необходимости очищайте URL.

`Vout` означает, что создан видеовыход; само по себе событие не подтверждает вывод первого видимого кадра. Проверка HLS 480p по-прежнему best-effort (`:adaptive-logic=lowest`).

После запуска серии на Cosmos в PowerShell:

```powershell
& "D:\adb\adb.exe" -s 192.168.0.9:5555 logcat -d -v threadtime -s DCTV_AV1:I > "$HOME\Desktop\cosmos-vout.txt"
```

Проверка компиляции в этой среде не выполнена. Стабильная v0.6.0 / main не меняется.
