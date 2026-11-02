# Dream Cast TV v0.7.1 — диагностика лишнего ExoPlayer

Этот патч **не удаляет ExoPlayer** из software_AV1. Он исправляет ложную ошибку обнаружения LibVLC (`org.videolan.android:libvlc-all`) и записывает точную цепочку происхождения Media3/ExoPlayer через Gradle `dependencyInsight`.

В `develop` заменить два файла из архива, затем Commit → Push и запустить знакомый Debug workflow для `develop`.

Ожидается, что software_AV1 по-прежнему не пройдёт строгую проверку ExoPlayer: это честное состояние проекта до разделения Android-реализации Flutter video_player.

Нельзя просто исключить Media3 из Gradle: скомпилированный `video_player_android` использует эти классы. Требуется либо реализация отдельного hardware-only видеоплагина, либо раздельная Flutter-конфигурация зависимостей с собственным программным видео-адаптером. До такой переработки сохраняйте рабочую v0.7.0 в main.
