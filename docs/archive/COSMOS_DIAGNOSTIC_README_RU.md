# Cosmos Android TV 11 — diagnostic build (develop only)

This ZIP contains the full v0.7 Flutter source with two deliberately isolated changes:

1. **Only debug AndroidManifest:** `EnableImpeller=false` (use the alternative Flutter renderer).
2. **Only debug applicationId:** `com.dreamcast.app.diagnostic`, allowing installation beside signed v0.6.0 Release without wiping its data. The application launcher label is otherwise unchanged; expect two Dream Cast icons.
3. **Only debug logging** in `video_player_screen.dart`: `DCTV_DIAG` lines for controller initialization, playback state and errors. No video URLs or header values printed.

## Test

Build the Debug APK from `develop` via the usual GitHub workflow. Install on Cosmos:

```powershell
& "D:\adb\adb.exe" -s 192.168.0.9:5555 install -r "PATH_TO_DEBUG_APK"
```

Before launching the diagnostic app:

```powershell
& "D:\adb\adb.exe" -s 192.168.0.9:5555 logcat -c
```

Play a failing episode in **the diagnostic copy**. Collect logs:

```powershell
& "D:\adb\adb.exe" -s 192.168.0.9:5555 logcat -d -v threadtime | Select-String 'DCTV_DIAG|MediaCodec|CCodec|ExoPlayer|Surface|libEGL|flutter' | Out-File "$HOME\Desktop\cosmos-diagnostic-log.txt" -Encoding utf8
```

Check whether the picture appears, and send the filtered log.

**Limit:** Flutter `VideoPlayerController` doesn't expose ExoPlayer's actual selected video track, decoder name or first-rendered-frame event. Those require Android Media3 analytics instrumentation in the plugin. This diagnostic build tests the renderer hypothesis and logs publicly available controller state; it does not claim to expose those deeper events.

No changes to signed Release settings or to main branch. Build not verified in this environment (Flutter SDK not installed).
