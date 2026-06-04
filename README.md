# TG Proxy

<p align="center">
  <img src="docs/assets/tg-proxy-icon.png" width="128" alt="TG Proxy icon">
</p>

<p align="center">
  <strong>TG Proxy by Dushnyj</strong><br>
  локальный MTProto-прокси для Telegram на Android
</p>

<p align="center">
  <img alt="version" src="https://img.shields.io/badge/version-1.0.0-3390EC?style=for-the-badge">
  <img alt="status" src="https://img.shields.io/badge/status-stable-20A464?style=for-the-badge">
  <img alt="platform" src="https://img.shields.io/badge/platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white">
  <img alt="proxy" src="https://img.shields.io/badge/proxy-MTProto-229ED9?style=for-the-badge&logo=telegram&logoColor=white">
  <img alt="ci" src="https://img.shields.io/badge/ci-GitHub%20Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white">
  <img alt="license" src="https://img.shields.io/badge/license-MIT-111827?style=for-the-badge">
</p>

TG Proxy поднимает прокси прямо на телефоне, по умолчанию на `127.0.0.1:1443`. В Telegram достаточно добавить MTProto-прокси с локальным адресом, после чего приложение перенаправляет трафик через WebSocket-маршруты Telegram и Cloudflare-настройки.

## Возможности

- локальный MTProto-прокси без отдельного приложения-клиента;
- подключение Telegram одной ссылкой `tg://proxy?...`;
- Cloudflare Proxy и Cloudflare Worker как резервные маршруты;
- проверка пинга, счетчики Up/Down и аптайм;
- уведомление в шторке с трафиком, временем работы и кнопкой остановки;
- автозапуск при открытии приложения и после включения устройства;
- проверка обновлений из GitHub Releases с прогрессом скачивания.

## Установка

1. Откройте [Releases](https://github.com/Dushnyj/TG-Proxy/releases).
2. Скачайте `TG-Proxy-v<version>-android-universal-release.apk`.
3. Установите APK и откройте TG Proxy.
4. Нажмите большую кнопку запуска.
5. Нажмите ссылку Telegram в блоке `Информация`.

Telegram добавит локальный MTProto-прокси:

```text
Сервер: 127.0.0.1
Порт: 1443
Secret: dd...
```

Для стабильной загрузки медиа разрешите TG Proxy работу в фоне и отключите агрессивную оптимизацию батареи для приложения.

## Настройки Медиа

Если чаты работают быстро, а фото или видео грузятся плохо, проверьте блок `Датацентры Telegram (DC -> IP)`. Практически полезный вариант:

```text
4:149.154.167.220
```

TG Proxy также умеет обрабатывать конфигурацию:

```text
2:149.154.167.220
4:149.154.167.220
```

В этом режиме медиа для DC2 сначала уходит через Worker/CF fallback, а прямой WebSocket используется только как запасной вариант.

## Релизные Файлы

GitHub Actions собирает подписанные APK:

```text
TG-Proxy-v<version>-android-arm64-v8a-release.apk
TG-Proxy-v<version>-android-armeabi-v7a-release.apk
TG-Proxy-v<version>-android-universal-release.apk
TG-Proxy-v<version>-android-x86_64-release.apk
SHA256SUMS.txt
```

Большинству пользователей подходит `universal`. ABI-сборки нужны только если требуется APK строго под архитектуру устройства.

## Разработка

```bash
./gradlew testDebugUnitTest assembleDebug assembleRelease --no-daemon
```

Версия задается в `app/build.gradle`. Релиз запускается тегом того же номера:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Workflow состоит из трех этапов: `Проверка`, `Сборка`, `Загрузка релиза`. Текст GitHub Release берется из [CHANGELOG.md](CHANGELOG.md).

## Источник Идеи

Архитектурный ориентир для локального MTProto -> WebSocket-прокси: [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy).

TG Proxy является самостоятельным проектом `Dushnyj/TG-Proxy`.

## Безопасность

Правила сообщения об уязвимостях описаны в [SECURITY.md](SECURITY.md).

## Лицензия

MIT. См. [LICENSE](LICENSE).
