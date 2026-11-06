# Лицензии компонентов воспроизведения

Проверены исходники используемых версий: Media3 1.9.2, dav1d 1.5.1,
cpu_features 0.10.1 и LLVM notices установленного Android NDK 28.2.13676358.
Проверка относится к цепочке воспроизведения; это не полный аудит всех
транзитивных зависимостей приложения.

## dav1d

Оригинальный [COPYING](https://code.videolan.org/videolan/dav1d/-/blob/42b2b24fb8819f1ed3643aa9cf2a62f03868e3aa/COPYING)
содержит BSD-2-Clause: при распространении бинарников нужно воспроизвести
уведомление об авторских правах, условия и отказ от гарантий. Копия `COPYING`
сохранена без изменений. Дополнительно сохранены исходные заголовки с авторскими
уведомлениями из `src` и `include`, включая ARM-код; перечень содержит также
файлы других архитектур из исходного дистрибутива.

## Media3, cpu_features и runtime

- [Media3 / ExoPlayer](https://github.com/androidx/media/blob/1.9.2/LICENSE) и
  JNI-обвязка Media3 — Apache-2.0. Сохранены текст лицензии и copyright-заголовки
  обвязки. Файл `media3-NOTICES.txt` собран нами из исходных заголовков;
  это не выдаваемый за оригинал upstream-файл NOTICE.
- [cpu_features](https://github.com/google/cpu_features/blob/v0.10.1/LICENSE) —
  Apache-2.0; его полный LICENSE также содержит BSD-уведомление для `ndk_compat`.
  Сохранён весь текст и исходные copyright-заголовки.
- JNI статически использует C++ runtime. `ndk-LLVM-NOTICE.txt` — неизменённая
  копия `toolchains/llvm/prebuilt/windows-x86_64/NOTICE` из NDK 28.2.13676358,
  включая LLVM exceptions, libc++ / libc++abi и дополнительные уведомления.
  Это общий bundle уведомлений LLVM; наличие текста не означает, что все
  перечисленные в нём части LLVM включены в приложение.

Apache-2.0 допускает использование в GPLv3-проекте:
[Apache Software Foundation](https://apache.org/licenses/GPL-compatibility.html).
Лицензия самого приложения остаётся GPLv3; тексты библиотек сохраняют свои условия.
Ссылки на источники и сведения о сборке не отменяют обязанностей GPLv3 при
распространении приложения, включая предоставление соответствующих исходников.

## Хранение и проверка

Тексты Media3 лежат в `assets/licenses/`. Тексты dav1d, cpu_features и
NDK runtime — в `native-android/app/src/main/assets/licenses/`.
Все они упаковываются в единый APK; лицензии открываются из assets без сети.

`scripts/decoder_license_notices.py` сверяет тексты с закреплёнными upstream
исходниками. Проверка вызывается `prepare_media3_dav1d.py` до сборки dav1d;
пропавшие или устаревшие уведомления останавливают подготовку. При обновлении
NDK нужно отдельно сверить LLVM notice с runtime новой версии.
При обновлении Media3/dav1d/cpu_features также обновляются commit ID и ссылки
в скриптах, копии LICENSE/COPYING и собранные исходные copyright-заголовки;
сверка выполняется до сборки и повторяется на готовом APK.

`audit_av1_apk.py` требует Media3-тексты; `audit_dav1d_apk.py`
дополнительно требует уведомления dav1d/runtime и наличие copyright/условий BSD.
Инструментальная проверка читает каждый показываемый документ из установленного
APK и открывает лицензию dav1d в «О программе».
