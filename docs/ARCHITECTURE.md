# Архитектура TG Proxy

TG Proxy - Android-приложение, которое поднимает локальный MTProto endpoint для Telegram и выбирает стабильный upstream-маршрут для текущей сети.

```text
Telegram Android
  -> MTProto Proxy (127.0.0.1:1443)
  -> TG Proxy service
  -> Route Engine
  -> Direct WS / VPS Relay / Cloudflare Worker / Cloudflare CDN
  -> Telegram DC
```

## Android app

Приложение отвечает за UI, настройки, сетевые профили, импорт/экспорт, диагностику и проверку обновлений Android APK.
Основной язык проекта - Java.

## Foreground service

`ProxyService` является источником истины о работе прокси:

- слушает ли локальный порт;
- активен ли маршрут;
- сколько времени работает прокси;
- какой профиль сети выбран;
- какой маршрут используется;
- почему произошел reconnect, fallback или stop.

При активном прокси service не должен сам останавливаться из-за временной недоступности маршрута. Он должен продолжать искать рабочий upstream, пока пользователь явно не остановит прокси.

## Route Engine

Route Engine строит список кандидатов для каждого DC и выбирает порядок проверки с учетом типа сети и профиля.
Пинг на главном экране проверяет только активный маршрут; полная route matrix находится в диагностике.
Для каждого профиля отдельно хранится маска разрешённых route types. Отключённый route не получает
новых сессий, его уже открытые сессии закрываются, а остальные маршруты продолжают работу.

Маршруты:

- `Direct WS` - WebSocket/TLS напрямую к Telegram DC;
- `VPS Relay` - WebSocket/TLS к `tgproxy-relay`, затем TCP к Telegram DC;
- `Cloudflare Worker` - WebSocket/TLS к Worker, затем TCP к Telegram DC;
- `Cloudflare CDN` - WebSocket/TLS через пользовательские `kws<dc>` записи;
- `Public Cloudflare` - встроенный публичный fallback;

Runtime Route Engine не выдаёт неподтверждённый «TCP fallback»: Telegram подключается к
локальному MTProto listener, а каждый upstream-кандидат должен быть одним из явно проверяемых
WebSocket/Relay-маршрутов выше.

## Network profiles

Профиль сети хранит статистику маршрутов для конкретной сети, а не просто одно имя оператора.
Wi-Fi различается по SSID или приватному стабильному идентификатору BSSID/network ID, если
Android скрывает SSID. В UI такой профиль честно называется `Wi-Fi (имя недоступно)`, без
псевдослучайного «имени сети». Мобильные профили различаются по оператору и активной SIM/eSIM,
но две SIM одного оператора используют один профиль.

Примеры:

- `Home Wi-Fi`;
- `Work Wi-Fi`;
- `Tele2 LTE`;
- `MTS 5G`;
- `Default mobile`.

## VPS Relay

Серверная часть вынесена в отдельный репозиторий: [Dushnyj/TG-Proxy-Relay](https://github.com/Dushnyj/TG-Proxy-Relay).
Android хранит Relay profiles и отдельное Keystore-хранилище владельца (SSH/admin/client
secrets), реализует import/share и SSH auto-setup flow. Обычный получатель client token не
получает owner API. Go server source и release archives собираются отдельно.

Версионный контракт:

```text
appVersion
relayVersion
protocolVersion
minimumSupportedProtocol
features
```

TG Proxy Android `1.3.0` устанавливает `TG Proxy VPS Relay 1.3.0`. Совместимость определяется
не сравнением строк версии, а диапазоном protocol и features из `/capabilities`.

Relay 1.3.0 хранит несколько IPv4/IPv6 endpoints на DC с точным port и ролями
`regular/media/cdn/static`. Опциональная signed topology обновляется только после Ed25519,
schema, generation, time-window, address и size validation; запись выполняется атомарно. При
сбое источника продолжает использоваться last-known-good, затем embedded/static bootstrap.

Direct, Worker и Cloudflare не объявляются динамическими только потому, что parser принял новый
DC. Кандидат создаётся лишь при наличии проверяемого destination для конкретного route. Полная
модель и synthetic DC204 matrix описаны в [TELEGRAM_TOPOLOGY.md](TELEGRAM_TOPOLOGY.md).
