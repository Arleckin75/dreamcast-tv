# Dream Cast TV v0.7 — перенос в main

В этом архиве только `.github/workflows/release.yml`.

1. Распакуйте поверх локальной ветки `develop` (НЕ заменяйте остальные файлы).
2. Commit + Push `develop`.
3. GitHub: Pull requests → New pull request → base `main`, compare `develop`.
4. Проверьте Files changed: нет случайных удалений и секретных файлов.
5. Merge после успешных проверок.
6. Actions → Build Dream Cast TV v0.7 Release → Run workflow, branch `main`.

Workflow требует DCTV_KEYSTORE_BASE64, DCTV_STORE_PASSWORD, DCTV_KEY_ALIAS, DCTV_KEY_PASSWORD. Для обновления установленной 0.6.0 должен использоваться тот же сертификат подписи.

В этом архиве нет APK и ключей подписи. Merge репозитория из этого окружения не выполнен.
