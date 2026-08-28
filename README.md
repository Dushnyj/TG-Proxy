# TG Proxy

<p align="center">
  <img src="docs/assets/tg-proxy-icon.png" width="132" alt="TG Proxy">
</p>

<p align="center">
  <strong>Локальный MTProto-прокси и устойчивые маршруты Telegram для Android</strong><br>
  Direct WS · Cloudflare · личный VPS Relay · автоматический failover
</p>

<p align="center">
  <a href="https://github.com/Dushnyj/TG-Proxy/releases"><img alt="release" src="https://img.shields.io/badge/release-1.3.0-3390EC?style=for-the-badge"></a>
  <img alt="Android 5.0+" src="https://img.shields.io/badge/Android-5.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white">
  <a href="https://github.com/Dushnyj/TG-Proxy/actions/workflows/ci.yml"><img alt="Android CI" src="https://img.shields.io/github/actions/workflow/status/Dushnyj/TG-Proxy/ci.yml?branch=main&style=for-the-badge&label=CI"></a>
  <a href="LICENSE"><img alt="MIT" src="https://img.shields.io/badge/license-MIT-111827?style=for-the-badge"></a>
</p>

![Схема маршрутов TG Proxy](docs/assets/tg-proxy-hero-v2.png)

TG Proxy поднимает локальный MTProto endpoint, обычно `127.0.0.1:1443`. Telegram подключается
к нему на том же телефоне, а приложение выбирает рабочий upstream для текущей Wi-Fi или
мобильной сети. Это не системный VPN: другой трафик Android через TG Proxy не проходит.

## Интерфейс

<table>
  <tr>
    <td align="center"><a href="docs/assets/app/01-main-screen.png"><img src="docs/assets/app/01-main-screen.png" width="225" alt="Главный экран"></a><br><sub>Главный экран</sub></td>
    <td align="center"><a href="docs/assets/app/02-background-check.png"><img src="docs/assets/app/02-background-check.png" width="225" alt="Проверка фоновой работы"></a><br><sub>Непрерывная работа</sub></td>
    <td align="center"><a href="docs/assets/app/04-vps-relay-empty.png"><img src="docs/assets/app/04-vps-relay-empty.png" width="225" alt="Настройки VPS Relay"></a><br><sub>VPS Relay</sub></td>
  </tr>
</table>

Скриншоты сняты на чистой debug-установке и не содержат production endpoint или credentials.

## Быстрый старт

1. Скачайте universal APK из [последнего релиза](https://github.com/Dushnyj/TG-Proxy/releases).
2. Установите APK и разрешите уведомления foreground service.
3. Откройте проверку непрерывной работы и устраните красные пункты батареи/автозапуска.
4. Запустите TG Proxy большой кнопкой.
5. Нажмите ссылку `tg://proxy?...`, затем включите предложенный MTProto-прокси в Telegram.

Полная инструкция с объяснением состояний: **[Быстрый старт](docs/GETTING_STARTED.md)**.

## Как проходит соединение

```text
Telegram Android
  -> MTProto 127.0.0.1:1443
  -> TG Proxy route engine
  -> Direct WS | VPS Relay | Cloudflare Worker | Cloudflare CDN
  -> Telegram DC (regular / media)
```

| Маршрут | Для чего |
| --- | --- |
| **Direct WS** | быстрый прямой WebSocket/TLS к Telegram DC |
| **VPS Relay** | личный управляемый сервер и основной контролируемый fallback |
| **Cloudflare Worker** | ваш Worker принимает WebSocket и открывает TCP к Telegram DC |
| **Cloudflare CDN** | ваш домен и `kws<dc>` DNS-записи |
| **Public Cloudflare** | встроенный публичный резервный пул, когда он доступен |

Каждый тип можно независимо включить для конкретного сетевого профиля. Выбранный здоровый
маршрут имеет приоритет, ошибки получают cooldown, новое MTProto-соединение автоматически
переходит к следующему кандидату. Regular и media endpoint проверяются отдельно.

Подробности: [Маршрутизация](docs/ROUTING.md) и
[Telegram topology](docs/TELEGRAM_TOPOLOGY.md).

## Возможности

- локальный MTProto listener и кликабельная Telegram-ссылка;
- отдельные профили Wi-Fi, операторов, dual SIM/eSIM;
- настоящий `req_pq/resPQ` probe, не зависящий от открытого окна Telegram;
- route health, MTProto RTT, аптайм, трафик и история переключений;
- foreground service, boot restore, watchdog и помощник батареи/OEM-автозапуска;
- единый список VPS Relay: серверы, независимые токены, основное и резервы для каждого профиля;
- несколько VPS Relay с автоматическим failover;
- автонастройка Linux VPS по SSH с read-only планом, backup, проверкой и rollback;
- публичный IP, бесплатный DuckDNS или любой принадлежащий пользователю домен/поддомен;
- owner-панель: client tokens, устройства, sessions, block/unblock и примерный GeoIP;
- одна кнопка передачи Relay: HTTPS-ссылка, QR, PNG через системный Share или файл;
- импорт с preview и выбором сетевых профилей;
- signed dynamic Relay topology с atomic last-known-good;
- полный TXT/ZIP диагностический отчёт для issue;
- обновления через GitHub Releases.

## VPS Relay без ручной настройки сервера

Серверная часть находится в
[Dushnyj/TG-Proxy-Relay](https://github.com/Dushnyj/TG-Proxy-Relay). В Android откройте
**Настройки → Relay → Автонастройка VPS**, введите данные, выданные хостингом, и подтвердите SSH
fingerprint. Приложение само:

- определит Linux, архитектуру, package manager и init;
- обнаружит существующий Relay и безопасные web-конфигурации;
- предложит использовать сохранённый token или создать отдельный;
- скачает подходящий release asset и проверит SHA-256;
- установит service, nginx/Certbot, HTTPS и автопродление;
- создаст backup и откатит свою транзакцию при неудаче;
- проверит health/version/capabilities, regular/media TCP и реальный MTProto.

Домен покупать не требуется: можно использовать публичный IP или бесплатный DuckDNS.

- [Какой VPS нужен](https://github.com/Dushnyj/TG-Proxy-Relay/blob/main/docs/VPS_REQUIREMENTS.md)
- [Автонастройка и owner-доступ](docs/VPS_RELAY.md)
- [Создать DuckDNS](docs/DUCKDNS.md)
- [Поделиться ссылкой/QR и импортировать](docs/SHARING_AND_IMPORT.md)

## Диагностика и поддержка

Если приложение остановилось, Telegram отключил прокси, не грузится media или маршруты
переключаются неверно:

1. откройте **Диагностика**;
2. сбросьте старый результат;
3. воспроизведите проблему;
4. сохраните полный ZIP;
5. создайте подходящий issue и приложите архив.

Краткий текст и скриншот не заменяют полный route/event report. Инструкции:
[Диагностика](docs/DIAGNOSTICS.md) · [Поддержка](SUPPORT.md).

## Документация

### Пользователю

- [Быстрый старт](docs/GETTING_STARTED.md)
- [VPS Relay](docs/VPS_RELAY.md)
- [DuckDNS](docs/DUCKDNS.md)
- [Ссылка, QR и импорт](docs/SHARING_AND_IMPORT.md)
- [Cloudflare Worker](docs/CLOUDFLARE_WORKER.md)
- [Cloudflare-домен](docs/CLOUDFLARE_DOMAIN.md)
- [Диагностика](docs/DIAGNOSTICS.md)
- [Приватность и секреты](docs/PRIVACY_AND_SECRETS.md)

### Разработчику

- [Вся документация](docs/README.md)
- [Архитектура](docs/ARCHITECTURE.md)
- [Надёжность Android-сервиса](docs/RELIABILITY.md)
- [Failure injection](docs/FAILURE_INJECTION.md)
- [Окружение и тесты](docs/DEVELOPMENT.md)
- [Процесс релиза](docs/RELEASES.md)
- [CONTRIBUTING.md](CONTRIBUTING.md)

## Сборка

Требуются JDK 17 и Android SDK 34:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
```

Официальный tag `v*` запускает GitHub Actions и публикует signed APK:

```text
TG-Proxy-v<version>-android-arm64-v8a-release.apk
TG-Proxy-v<version>-android-armeabi-v7a-release.apk
TG-Proxy-v<version>-android-x86_64-release.apk
TG-Proxy-v<version>-android-universal-release.apk
SHA256SUMS.txt
```

## Безопасность

Не публикуйте SSH, raw client/owner/DuckDNS tokens, keystore, приватные keys, production config
или полный MTProto secret. Обычная ссылка Relay содержит только один выбранный client token.

Уязвимости: [SECURITY.md](SECURITY.md). Лицензия: [MIT](LICENSE).
