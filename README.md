<a name="readme-top"></a>

<p align="center">
  <img src="docs/assets/tg-proxy-icon.png" width="112" alt="Иконка TG Proxy">
</p>

<h1 align="center">TG Proxy</h1>

<p align="center">
  <strong>Локальный MTProto-прокси и устойчивые маршруты Telegram для Android</strong><br>
  Direct WS · Cloudflare · личный VPS Relay · автоматическое переключение
</p>

<p align="center">
  <a href="https://github.com/Dushnyj/TG-Proxy/releases/latest"><img alt="Последний релиз" src="https://img.shields.io/github/v/release/Dushnyj/TG-Proxy?display_name=tag&sort=semver&style=flat-square&label=RELEASE&labelColor=3d3d3d&color=3390EC"></a>
  <img alt="Android 5.0+" src="https://img.shields.io/badge/ANDROID-5.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white&labelColor=3d3d3d">
  <a href="https://github.com/Dushnyj/TG-Proxy/actions/workflows/ci.yml"><img alt="Android CI" src="https://img.shields.io/github/actions/workflow/status/Dushnyj/TG-Proxy/ci.yml?branch=main&style=flat-square&label=CI&labelColor=3d3d3d"></a>
  <a href="LICENSE"><img alt="Лицензия MIT" src="https://img.shields.io/github/license/Dushnyj/TG-Proxy?style=flat-square&label=LICENSE&labelColor=3d3d3d&color=111827"></a>
</p>

<p align="center">
  <a href="https://github.com/Dushnyj/TG-Proxy/releases/latest"><strong>Скачать APK</strong></a>
  &nbsp;•&nbsp;
  <a href="docs/GETTING_STARTED.md"><strong>Быстрый старт</strong></a>
  &nbsp;•&nbsp;
  <a href="docs/README.md"><strong>Документация</strong></a>
  &nbsp;•&nbsp;
  <a href="https://github.com/Dushnyj/TG-Proxy/issues/new/choose"><strong>Сообщить о проблеме</strong></a>
</p>

<p align="center">
  <strong>Навигация</strong><br>
  <a href="#quick-start">Быстрый старт</a> ·
  <a href="#user-scenarios">Сценарии</a> ·
  <a href="#interface">Интерфейс</a> ·
  <a href="#how-it-works">Как это работает</a> ·
  <a href="#routes">Маршруты</a> ·
  <a href="#vps-relay">VPS Relay</a><br>
  <a href="#features">Возможности</a> ·
  <a href="#limitations">Ограничения</a> ·
  <a href="#support">Поддержка</a> ·
  <a href="#documentation">Документация</a> ·
  <a href="#development">Разработка</a>
</p>

<a href="docs/assets/tg-proxy-hero-v2.png">
  <img src="docs/assets/tg-proxy-hero-v2.png" alt="TG Proxy: локальный MTProto, маршрутизация, Direct WS, VPS Relay и Cloudflare">
</a>

---

<a name="overview"></a>

## Что такое TG Proxy

TG Proxy поднимает на телефоне локальный MTProto endpoint, обычно `127.0.0.1:1443`.
Telegram подключается к нему как к обычному MTProto-прокси, а приложение выбирает рабочий
upstream для текущей Wi-Fi или мобильной сети.

- локальный endpoint доступен только на телефоне;
- маршруты настраиваются отдельно для профилей Wi-Fi и SIM;
- regular- и media-соединения Telegram проверяются независимо;
- остальной трафик Android через TG Proxy не проходит.

> [!NOTE]
> **TG Proxy — не системный VPN.** Он маршрутизирует MTProto-соединения Telegram, включая
> обычные и media DC. Звонки Telegram (`VoIP`, `WebRTC`, `P2P`, `STUN` и `TURN`) через
> приложение не проходят.

<a name="quick-start"></a>

## Быстрый старт

1. Скачайте **universal APK** из [последнего релиза](https://github.com/Dushnyj/TG-Proxy/releases/latest).
2. Установите приложение. На Android 13+ разрешите уведомления foreground service.
3. Откройте помощник фоновой работы и настройте батарею и автозапуск.
4. Запустите TG Proxy большой кнопкой.
5. Нажмите ссылку `tg://proxy?...`, добавьте предложенный локальный прокси и включите его в Telegram.

> [!IMPORTANT]
> Запущенный TG Proxy и успешно проверенный маршрут ещё не означают, что Telegram уже
> использует прокси. Локальный прокси необходимо добавить и включить в самом Telegram.

Доступ к Wi-Fi/геолокации нужен только для точного имени сетевого профиля, а камера
запрашивается только при сканировании QR-кода. Полная пошаговая инструкция:
**[Быстрый старт TG Proxy](docs/GETTING_STARTED.md)**.

<a name="user-scenarios"></a>

## Выберите свой сценарий

| Сценарий | Что делать |
| --- | --- |
| **Нужно быстро запустить Telegram** | Установите APK, запустите TG Proxy и включите локальный прокси в Telegram. Приложение само проверит доступные маршруты текущего профиля сети. |
| **Мне прислали VPS Relay** | Откройте ссылку, импортируйте `.tgproxy`-файл, отсканируйте QR-код либо введите endpoint и client token вручную. Затем выберите профили сети. |
| **У меня есть собственный VPS** | Запустите автонастройку, проверьте SSH fingerprint, результат read-only-аудита и предложенный план, затем установите Relay и создайте или выберите client token. |

> [!TIP]
> Если Relay уже настроен и вам прислали подключение, SSH-доступ не нужен. SSH нужен владельцу
> для автонастройки и обновления Relay; client tokens, устройства и sessions управляются через
> owner-панель по HTTPS.

<a name="interface"></a>

## Интерфейс

<p align="center">
  <a href="docs/assets/app/05-vps-auto-setup-access.png"><img src="docs/assets/app/05-vps-auto-setup-access.png" width="270" alt="Автонастройка VPS в TG Proxy"></a>
  &nbsp;
  <a href="docs/assets/app/10-relay-share-sheet.png"><img src="docs/assets/app/10-relay-share-sheet.png" width="270" alt="Передача подключения VPS Relay"></a>
</p>

<p align="center"><sub>Автонастройка VPS · Безопасная передача Relay</sub></p>

<details>
<summary><strong>Другие экраны приложения</strong></summary>

<br>

<p align="center">
  <a href="docs/assets/app/01-main-screen.png"><img src="docs/assets/app/01-main-screen.png" width="220" alt="Главный экран TG Proxy"></a>
  &nbsp;
  <a href="docs/assets/app/02-background-check.png"><img src="docs/assets/app/02-background-check.png" width="220" alt="Проверка фоновой работы"></a>
  &nbsp;
  <a href="docs/assets/app/08-diagnostics-export.png"><img src="docs/assets/app/08-diagnostics-export.png" width="220" alt="Экспорт диагностики"></a>
</p>

</details>

Все демонстрационные данные обезличены. Скриншоты не содержат production endpoint,
рабочие QR-коды или реальные credentials.

<a name="how-it-works"></a>

## Как это работает

```text
Telegram Android
  → MTProto 127.0.0.1:1443
  → TG Proxy route engine
  → Direct WS | VPS Relay | Cloudflare
  → Telegram DC (regular / media)
```

TG Proxy проверяет доступность, авторизацию и реальный MTProto-ответ. Выбранный здоровый
маршрут используется первым. Ошибка переводит его в cooldown, а новое MTProto-соединение
переходит к следующему кандидату. Результаты и приоритеты хранятся отдельно для каждого
сетевого профиля.

Подробности: [Маршрутизация](docs/ROUTING.md) и
[Telegram topology](docs/TELEGRAM_TOPOLOGY.md).

<a name="routes"></a>

## Маршруты

| Маршрут | Когда использовать | Что требуется |
| --- | --- | --- |
| **Direct WS** | Прямой WebSocket/TLS к официальному Telegram ingress без промежуточного Relay, если он доступен в текущей сети | Ничего |
| **VPS Relay** | Основной контролируемый маршрут и резерв с независимыми client tokens | Собственный Relay или выданное подключение |
| **Cloudflare Worker** | Личный резерв через Worker, который принимает WebSocket и подключается к Telegram DC | Cloudflare-аккаунт и Worker |
| **Свой Cloudflare-домен** | Маршрут через собственный домен и Cloudflare CDN | Домен и DNS Cloudflare |
| **Public Cloudflare** | Встроенный резерв без собственной инфраструктуры, когда публичный пул доступен | Ничего |

> [!CAUTION]
> **Direct WS на мобильных сетях в большинстве случаев не работает или работает
> нестабильно.** Он остаётся прямым соединением с Telegram и может блокироваться мобильным
> оператором по IP или маршруту ещё до TLS. На Wi-Fi Direct WS может работать нормально.
> Для мобильной сети рекомендуется автовыбор с VPS Relay или Cloudflare в качестве резерва.

Доступность любого маршрута зависит от оператора, региона и текущего режима фильтрации.
TG Proxy не гарантирует работу в каждой strict-whitelist сети: Direct, Cloudflare и
зарубежный VPS могут быть ограничены по IP, DNS или SNI.

<a name="features"></a>

## Ключевые возможности

- **Маршрутизация:** Direct WS, VPS Relay, Cloudflare Worker, собственные Cloudflare-домены,
  публичный резервный пул и автоматическое переключение.
- **Сетевые профили:** отдельные настройки для сетей Wi-Fi и мобильных операторов;
  профиль определяется по текущей data SIM/eSIM, но SIM одного оператора используют общий профиль.
- **Проверка Telegram:** самостоятельный `req_pq/resPQ` probe, regular/media DC,
  MTProto RTT, route health и история переключений.
- **Надёжность:** foreground service, watchdog и восстановление после загрузки при включённом
  автозапуске; помощник батареи и OEM-настроек.
- **VPS Relay:** SSH-автонастройка с read-only аудитом, backup, проверкой, rollback,
  HTTPS, owner-панелью, токенами, устройствами и sessions.
- **Передача и импорт:** ссылка, QR, PNG, системное меню Share, `.tgproxy`-файл,
  preview и выбор профилей перед сохранением.
- **Диагностика:** понятные проверки, события маршрутов и полный TXT/ZIP для issue.
- **Обновления:** GitHub Releases, `SHA256SUMS.txt`, проверка package name, версии и подписи
  перед запуском системного установщика Android.

<details>
<summary><strong>Дополнительные технические возможности</strong></summary>

- несколько VPS Relay с автоматическим failover;
- client tokens для отдельных людей и устройств;
- block/unblock, sessions и приблизительный GeoIP при включённом provider;
- опциональная signed dynamic Relay topology с atomic last-known-good;
- публичный IP, бесплатный DuckDNS или собственный домен/поддомен;
- безопасный экспорт выбранного сетевого профиля с preview перед импортом.

</details>

<a name="vps-relay"></a>

## Личный VPS Relay

Серверная часть находится в
[Dushnyj/TG-Proxy-Relay](https://github.com/Dushnyj/TG-Proxy-Relay). Relay меняет первый
сетевой узел: телефон подключается к вашему серверу, а уже сервер устанавливает соединение
с Telegram DC.

### Если подключение уже готово

Откройте **Настройки → VPS Relay → Подключения VPS Relay → Добавить подключение** и
используйте ссылку, QR-код, `.tgproxy`-файл или ручной ввод. Добавленное подключение можно
назначить основным или резервным для одного либо нескольких профилей сети.

### Если VPS принадлежит вам

Откройте **Настройки → VPS Relay → Автонастройка VPS**. Мастер:

1. подключится по SSH после проверки host-key fingerprint;
2. выполнит read-only аудит Linux, Relay и web stack;
3. покажет понятный план и запросит подтверждение;
4. установит или обновит Relay только при безопасной конфигурации;
5. создаст backup, проверит HTTPS, regular/media и реальный MTProto;
6. откатит свою транзакцию, если итоговая проверка не пройдёт.

> [!TIP]
> Домен покупать не обязательно. В зависимости от доступности портов и сертификата можно
> использовать публичный IP, бесплатный DuckDNS или принадлежащий вам домен/поддомен.

Автонастройка не изменяет сервер, если DNS ведёт на другой VPS, требуемые порты заняты
неизвестным сервисом или существующий web stack нельзя дополнить изолированно.

- [Требования к VPS](https://github.com/Dushnyj/TG-Proxy-Relay/blob/main/docs/VPS_REQUIREMENTS.md)
- [Автонастройка и owner-доступ](docs/VPS_RELAY.md)
- [Создать адрес DuckDNS](docs/DUCKDNS.md)
- [Поделиться подключением или импортировать его](docs/SHARING_AND_IMPORT.md)

<a name="limitations"></a>

## Важные ограничения

- Telegram должен использовать добавленный и включённый локальный MTProto-прокси.
- Telegram-звонки и остальной трафик Android не маршрутизируются.
- Android и оболочка производителя могут остановить приложение, если не настроены батарея
  и OEM-автозапуск; универсального API для проверки всех OEM-настроек нет.
- Direct WS, Cloudflare и VPS Relay зависят от доступности первого сетевого узла в конкретной сети.
- Android backup для TG Proxy отключён. Удаление приложения или очистка данных удалит локальные
  owner-, SSH- и DuckDNS-данные.
- Экспорт выбранного профиля не является полной резервной копией всего приложения.

<a name="security"></a>

## Приватность и безопасность

> [!CAUTION]
> **Ссылка, QR-код и `.tgproxy`-файл Relay являются bearer credential.** Любой получатель
> client token сможет использовать подключение, пока владелец не отзовёт token. Удаление
> подключения с телефона не отзывает token на VPS.

Owner token, SSH-пароль, приватный ключ и DuckDNS token не входят в обычную ссылку Relay.
Raw client token хранится только там, где он был создан или импортирован, и не восстанавливается
из server-side hash.

Перед публикацией диагностического ZIP просмотрите его: секреты маскируются, но архив содержит
технические сведения об устройстве, сети, доменах и маршрутах.

Подробнее: [Данные, приватность и секреты](docs/PRIVACY_AND_SECRETS.md) ·
[Политика безопасности](SECURITY.md).

<a name="support"></a>

## Диагностика и поддержка

Если приложение остановилось, Telegram отключил прокси, не загружается media или маршруты
переключаются неверно:

1. откройте **Диагностика** и сбросьте старый результат;
2. воспроизведите проблему;
3. сохраните полный ZIP;
4. проверьте содержимое архива и приложите его к подходящему обращению.

- [Ошибка Android-приложения](https://github.com/Dushnyj/TG-Proxy/issues/new?template=bug_report.yml)
- [Проблема VPS Relay или автонастройки](https://github.com/Dushnyj/TG-Proxy/issues/new?template=vps_relay.yml)
- [Предложить функцию](https://github.com/Dushnyj/TG-Proxy/issues/new?template=feature_request.yml)
- [Сообщить об уязвимости закрыто](https://github.com/Dushnyj/TG-Proxy/security/advisories/new)

Инструкции: [Диагностика](docs/DIAGNOSTICS.md) · [Поддержка](SUPPORT.md).

<a name="documentation"></a>

## Документация

### Пользователю

- [Быстрый старт](docs/GETTING_STARTED.md)
- [Маршруты и автоматический failover](docs/ROUTING.md)
- [VPS Relay](docs/VPS_RELAY.md)
- [Ссылка, QR-код и импорт](docs/SHARING_AND_IMPORT.md)
- [Cloudflare Worker](docs/CLOUDFLARE_WORKER.md)
- [Собственный Cloudflare-домен](docs/CLOUDFLARE_DOMAIN.md)
- [Диагностика](docs/DIAGNOSTICS.md)
- [Приватность и секреты](docs/PRIVACY_AND_SECRETS.md)

### Разработчику

- [Вся документация](docs/README.md)
- [Архитектура Android](docs/ARCHITECTURE.md)
- [Надёжность Android-сервиса](docs/RELIABILITY.md)
- [Telegram topology](docs/TELEGRAM_TOPOLOGY.md)
- [Failure injection](docs/FAILURE_INJECTION.md)
- [Окружение, сборка и тесты](docs/DEVELOPMENT.md)
- [Процесс релиза](docs/RELEASES.md)
- [История изменений](CHANGELOG.md)
- [Правила участия](CONTRIBUTING.md)

<a name="development"></a>

## Разработка

<details>
<summary><strong>Сборка из исходников и локальные проверки</strong></summary>

Требуются JDK 17 и Android SDK 34.

Linux/macOS:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
```

Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon
```

Tag `v*`, совпадающий с `versionName`, запускает release workflow. После тестов, проверки
signing secrets и подписи публикуются архитектурные APK, universal APK и `SHA256SUMS.txt`.

Подробности: [Разработка](docs/DEVELOPMENT.md) · [Выпуск](docs/RELEASES.md).

</details>

---

<p align="center">
  <a href="https://github.com/Dushnyj/TG-Proxy/releases">Релизы</a> ·
  <a href="CHANGELOG.md">История изменений</a> ·
  <a href="docs/README.md">Документация</a> ·
  <a href="SUPPORT.md">Поддержка</a> ·
  <a href="SECURITY.md">Безопасность</a> ·
  <a href="LICENSE">MIT License</a>
</p>

<p align="center"><a href="#readme-top">Наверх ↑</a></p>
