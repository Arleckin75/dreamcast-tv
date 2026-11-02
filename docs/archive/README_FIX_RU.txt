Dream Cast TV v0.7.1 / develop / software_AV1 ONLY

Из logcat: AWindowHandler SIGSEGV (null pointer dereference) в /vendor/lib/egl/libGLES_mali.so после выхода из LibVLC.
Изменение: остановить VLC пока SurfaceView в Activity; через 700 мс отсоединить/освободить VLC, затем Activity.finish().
Это workaround для асинхронного видеовывода; необходимо проверить на Cosmos, гарантии нет.

Применение: распаковать архив поверх проекта в develop, commit, push, запустить debug сборку.
Проверка: 15-20 раз открыть видео -> Back -> список эпизодов, сохранить прогресс.
Main/hardware не изменены.
