# TG Proxy

<p align="center">
  <img src="docs/assets/tg-proxy-icon.png" width="128" alt="TG Proxy icon">
</p>

<p align="center">
  <strong>TG Proxy by Dushnyj</strong><br>
  Android-приложение с локальным MTProto-прокси для Telegram
</p>

<p align="center">
  <img alt="version" src="https://img.shields.io/badge/version-1.1.0-3390EC?style=for-the-badge">
  <img alt="platform" src="https://img.shields.io/badge/platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white">
  <img alt="proxy" src="https://img.shields.io/badge/proxy-MTProto-229ED9?style=for-the-badge&logo=telegram&logoColor=white">
  <img alt="license" src="https://img.shields.io/badge/license-MIT-111827?style=for-the-badge">
</p>

![TG Proxy route overview](docs/assets/tg-proxy-hero-v2.png)

TG Proxy запускает на телефоне локальный MTProto-прокси, обычно `127.0.0.1:1443`.
Telegram подключается к локальному адресу, а приложение выбирает рабочий upstream-маршрут для конкретной сети.

## Навигация

- [Как это работает](#как-это-работает)
- [Возможности](#возможности)
- [Установка APK](#установка-apk)
- [Маршруты](#маршруты)
- [VPS Relay](#vps-relay)
- [Диагностика](#диагностика)
- [Документация](#документация)
- [Сборка](#сборка)
- [Безопасность](#безопасность)

## Как это работает

```text
Telegram Android
  -> MTProto Proxy (127.0.0.1:1443)
  -> TG Proxy route engine
  -> selected upstream:
     Direct WS:        WebSocket/TLS -> Telegram DC
     Cloudflare CDN:   WebSocket/TLS -> Cloudflare -> Telegram DC
     Cloudflare Worker: WebSocket/TLS -> Worker -> TCP Telegram DC:443
     VPS Relay:        WebSocket/TLS -> tgproxy-relay -> TCP Telegram DC:443
```

`WebSocket` здесь не отдельная настройка, а транспорт между Android-приложением и выбранным upstream.
Direct WS идет напрямую к Telegram WebSocket endpoint, Cloudflare CDN проходит через проксируемые `kws<dc>` записи, Worker и VPS Relay принимают WebSocket от приложения и сами открывают TCP-соединение к Telegram DC.

## Возможности

- локальный MTProto-прокси для Telegram на Android;
- автоссылка `tg://proxy` для добавления прокси в Telegram;
- настоящий MTProto `req_pq/resPQ`-отклик активного маршрута без наложения проверок;
- аптайм, трафик и состояние в приложении и foreground-уведомлении;
- восстановление foreground service после завершения процесса, перезагрузки и обновления;
- помощник фоновой работы: уведомления, boot-start, батарея и OEM-автозапуск с постоянным предупреждением о неполной настройке;
- сетевые профили для Wi-Fi, операторов, dual SIM и eSIM;
- безопасно сохранённые данные владельца VPS и автонастройка с транзакционным rollback;
- owner-управление Relay: список/создание/отзыв токенов и известные устройства пользователей;
- одна команда «Поделиться Relay»: кликабельная HTTPS-ссылка, QR, системный Share или файл;
- Cloudflare Worker и пользовательские Cloudflare-домены;
- диагностика с TXT/ZIP-отчетом, копированием и сбросом результата;
- обновления Android-приложения через GitHub Releases.

## Установка APK

1. Откройте [Releases](https://github.com/Dushnyj/TG-Proxy/releases).
2. Скачайте `TG-Proxy-v<version>-android-universal-release.apk`.
3. Установите APK на телефон.
4. Откройте TG Proxy и нажмите запуск.
5. В блоке информации нажмите ссылку Telegram или добавьте прокси вручную:

```text
Тип: MTProto
Сервер: 127.0.0.1
Порт: 1443
Secret: значение из приложения
```

При первом запуске и после обновления откройте помощник фоновой работы: разрешите уведомления,
запуск после перезагрузки, режим батареи «Без ограничений» и OEM-автозапуск. Если часть условий
не выполнена, приложение оставляет видимое предупреждение. Постоянно включённый экран сам по
себе не запрещает Android/MIUI завершить фоновый процесс.

## Маршруты

- `Direct WS` - быстрый прямой WebSocket/TLS к Telegram DC, если сеть его пропускает.
- `VPS Relay` - личный контролируемый fallback через ваш VPS.
- `Cloudflare Worker` - пользовательский Worker endpoint, который прокидывает WebSocket в TCP до Telegram DC.
- `Cloudflare CDN` - собственный Cloudflare-домен с `kws<dc>` DNS-записями.
- `Public Cloudflare` - встроенный публичный fallback-пул, если он доступен.

Практический порядок настройки:

1. Сначала проверьте автоматический маршрут.
2. Если сеть режет Telegram, настройте Cloudflare Worker или свой Cloudflare-домен.
3. Если нужен личный стабильный маршрут, настройте VPS Relay.
4. Если медиа грузится хуже чатов, проверьте DC mapping.

## VPS Relay

Серверная часть живет отдельно: [Dushnyj/TG-Proxy-Relay](https://github.com/Dushnyj/TG-Proxy-Relay).
Android-приложение умеет сохранить Relay, выполнить автонастройку VPS по SSH, управлять
клиентскими токенами владельца и передавать отдельное клиентское подключение одной кнопкой.
SSH-пароль, SSH host key и owner/admin token не входят ни в ссылку, ни в QR-код.

Автонастройка Android скачивает release asset из `TG-Proxy-Relay`, а не из Android-репозитория.
Текущий контракт: `TG Proxy Android 1.1.0` и `TG Proxy VPS Relay 1.1.0`.

## Диагностика

Диагностика показывает сеть, активный профиль, текущий маршрут, настройки, последние ошибки, логи приложения и route matrix.
Отчет можно сохранить в TXT/ZIP, скопировать текст или сбросить результат и собрать заново.

## Документация

- [VPS Relay в приложении](docs/VPS_RELAY.md)
- [Cloudflare Worker](docs/CLOUDFLARE_WORKER.md)
- [Cloudflare-домен](docs/CLOUDFLARE_DOMAIN.md)
- [Диагностика](docs/DIAGNOSTICS.md)
- [Маршрутизация](docs/ROUTING.md)
- [Надежность Android-сервиса](docs/RELIABILITY.md)
- [Архитектура](docs/ARCHITECTURE.md)

## Сборка

```bash
./gradlew testDebugUnitTest assembleDebug --no-daemon
```

Release APK собирается GitHub Actions при публикации тега `v*`:

```text
TG-Proxy-v<version>-android-arm64-v8a-release.apk
TG-Proxy-v<version>-android-armeabi-v7a-release.apk
TG-Proxy-v<version>-android-universal-release.apk
TG-Proxy-v<version>-android-x86_64-release.apk
SHA256SUMS.txt
```

## Безопасность

Диагностические отчеты не должны содержать SSH-пароль, приватные ключи, raw Relay token или полный MTProto secret.
Правила сообщения об уязвимостях описаны в [SECURITY.md](SECURITY.md).

## Лицензия

MIT. См. [LICENSE](LICENSE).
