# Toshiba TV cleanup — 2026-11-03
Disabled Bluetooth TV Hider, user excluded it. Undo: adb -s 192.168.0.9:5555 shell pm enable --user 0 com.zzggsoft.bluetoothtvhider
Disabled Bluetooth Pair, user excluded it. Undo: adb -s 192.168.0.9:5555 shell pm enable --user 0 com.waylonhuang.bluetoothpair
Disabled com.vestel.vestelreporter, approved reporting/demo/recommendation component. Undo: adb -s 192.168.0.9:5555 shell pm enable --user 0 com.vestel.vestelreporter
Disabled fusion.android.tv.demo, approved reporting/demo/recommendation component. Undo: adb -s 192.168.0.9:5555 shell pm enable --user 0 fusion.android.tv.demo
Disabled com.google.android.tvrecommendations, approved reporting/demo/recommendation component. Undo: adb -s 192.168.0.9:5555 shell pm enable --user 0 com.google.android.tvrecommendations

User retained stock launcher, Chromecast, Assistant, TalkBack, TTS, backdrop, Smart Connect and selected media apps. Play Games and TalkBack were already disabled and remain unchanged by explicit request. Netflix, Prime Video, YouTube Music and the UK app pack were already disabled; no new changes to those packages.

Animation scales were already 0.5, unchanged. Cache trimming completed successfully: adb -s 192.168.0.9:5555 shell pm trim-caches 999G. Deleted cache cannot be restored; apps regenerate it. No app data was cleared.

Protected: TV/input/arbitrator, Vestel keycontroller, remote update and firmware services, Bluetooth stack, keyboard, Wi-Fi/network, Play Store, GMS, GSF, fused location, current launcher. Infrared configuration app left untouched because its role in the owner's remote setup is unclear.

Classification: telemetry = Vestel Reporter; advertising/demo/content = Retail Demo, Google TV Recommendations; optional background tools = Bluetooth TV Hider and Bluetooth Pair; preloads = already-disabled Netflix/Prime/YouTube Music/UK apps; user-dependent = retained user selections; protected = core services above.

Inputs menu screenshot checked after each batch. HDMI1 selection opened com.mediatek.wwtv.tvcenter/.nav.TurnkeyUiMainActivity. Audio and physical remote require user verification. Recommendations from Lampa and Spotify remained visible before reboot.

Reference: https://github.com/Gathin23/cleanyourtv/blob/main/examples/toshiba-cosmos-android-tv/debloat.txt (downloaded as reference-debloat.txt). Applied only inspected and user-approved packages, not the entire reference list.

Final verification: reboot completed; Home screenshot shows stock Android TV Home; YouTube MainActivity resumed and screenshot saved. Package-list comparison confirms exactly five new disabled packages and no packages re-enabled. Netflix intentionally not launched because it was disabled before this session and owner excluded it.

RAM (MiB, dumpsys values divided by 1024): total 1336.6 before/after; Free RAM including reclaimable cache 361.6 before, 436.5 after; Used RAM 1062.9 before, 922.1 after. Raw unused RAM 199.1 before, 169.1 after. Measurements reflect differing uptime and running apps, not a controlled benchmark. No guaranteed performance gain inferred.

Recommendations from retained Lampa and Spotify remain after reboot. Their rows need separate launcher channel configuration; no app data or channel database was changed in this session.
UI: Lampa Recommendations switched ON -> OFF; History left OFF. Undo: Home > Customize channels > Lampa TV > Recommendations ON.
UI: Spotify channel switched ON -> OFF. Undo: Home > Customize channels > Spotify ON.
Final channel update supersedes earlier note: Lampa Recommendations and Spotify switched off via stock UI, apps retained. restore.ps1 invokes restore-home-rows.ps1 to re-enable these two rows through inspected UI switches. Cache regeneration is automatic and cache deletion cannot be undone.
Restore-home-rows.ps1 tested successfully (exit 0): both previously enabled rows restored and verified ON from UI XML; then both hidden again. Final screenshot home-clean-final.png. Full restore command: powershell -NoProfile -ExecutionPolicy Bypass -File .\tv-debloat\restore.ps1. Run while TV is awake and ADB connected. Re-enabled packages were not tested by full restore to preserve cleanup state.
Navigation hardened to reset focus to top before selecting Lampa and Spotify. NUM channel settings left unchanged; an empty NUM New Movies row remains visible.
