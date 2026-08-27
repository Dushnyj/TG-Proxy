# Выпуск TG Proxy Android

Релизные APK создаются только GitHub Actions из тега `v*`. Локальная debug-сборка не является
официальным релизом.

## Перед тегом

1. Обновите `versionCode` и `versionName` в `app/build.gradle`.
2. Добавьте секцию той же версии в `CHANGELOG.md`.
3. Проверьте совместимость с версией TG Proxy VPS Relay и обновите ссылки в документации.
4. Запустите:

   ```bash
   ./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
   ```

5. Убедитесь, что CI и secret scan зелёные, а рабочее дерево чистое.

## Signing secrets GitHub

В **Settings → Secrets and variables → Actions** должны существовать:

```text
ANDROID_SIGNING_KEY_B64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

`ANDROID_SIGNING_KEY_B64` — base64-содержимое release keystore. Keystore и пароли никогда не
добавляются в Git.

## Создать релиз

```bash
git tag -a v1.2.0 -m "TG Proxy Android v1.2.0"
git push origin main
git push origin v1.2.0
```

Workflow `.github/workflows/release.yml`:

1. сверяет тег с `versionName`;
2. запускает unit tests;
3. декодирует временный keystore только на runner;
4. собирает signed APK для `arm64-v8a`, `armeabi-v7a`, `x86_64` и universal;
5. проверяет подписи `apksigner`;
6. создаёт `SHA256SUMS.txt`;
7. публикует GitHub Release с секцией из changelog.

Тот же релиз можно повторно запустить вручную через **Actions → Release TG Proxy Android → Run
workflow**, указав уже существующий тег.

## Проверка опубликованного релиза

- workflow завершился без пропущенных jobs;
- в Release есть четыре APK и `SHA256SUMS.txt`;
- SHA-256 каждого APK совпадает;
- APK имеет ожидаемый package name и release-подпись;
- universal APK устанавливается поверх предыдущей release-версии;
- настройки и owner-доступ сохраняются после обновления;
- приложение запускает foreground service и открывает ссылку Telegram.

## Если публикация упала

Не перемещайте уже опубликованный тег на другой commit. Исправьте причину, подготовьте новую
patch-версию и создайте новый тег. Повторный ручной запуск допустим только если исходный tag и
содержимое commit не менялись, например после временного сбоя runner.
