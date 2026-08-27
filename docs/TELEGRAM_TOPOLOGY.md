# Telegram topology и границы маршрутов

Документ фиксирует доказательную базу и контракт TG Proxy 1.2.0. Он не смешивает пять
разных сущностей: client DC endpoint, Telegram WebSocket ingress, media/CDN DC, MTProxy
middle proxy и наш VPS Relay.

## Как официальный клиент получает topology

1. `help.getConfig` возвращает `config.dc_options`; изменения также приходят через
   `updateConfig` / `updateDcOptions`.
2. Один `dcOption` содержит `id`, `ip_address`, `port` и флаги `ipv6`, `media_only`,
   `tcpo_only`, `cdn`, `static`, `this_port_only`, `secret`.
3. У DC может быть несколько вариантов адреса и порта. Официальный клиент хранит их как
   набор, разделяет media/CDN и address family, проверяет доступность и переключается между
   вариантами.
4. `upload.fileCdnRedirect` переводит конкретный файл на CDN DC. `help.getCdnConfig`
   содержит публичные ключи CDN, но не заменяет `dc_options` как список transport endpoints.
5. `getProxyConfig` / `proxy-multi.conf` относится к topology официального MTProxy middle
   proxy. Это не безопасный источник произвольных client DC адресов для TG Proxy Relay.

Первичные источники:

- [Telegram `dcOption`](https://core.telegram.org/constructor/dcOption)
- [Telegram API configuration](https://core.telegram.org/api/config)
- [`help.getConfig`](https://core.telegram.org/method/help.getConfig)
- [Telegram CDN](https://core.telegram.org/cdn)
- [Telegram updates](https://core.telegram.org/api/updates)
- [официальный MTProxy](https://github.com/TelegramMessenger/MTProxy)
- [Telegram Android ConnectionsManager](https://github.com/DrKLO/Telegram/blob/62b56a07ca7e30e39f7fd00a6728d6bbd716ca1c/TMessagesProj/jni/tgnet/ConnectionsManager.cpp#L3330-L3459)
- [Telegram Android Datacenter](https://github.com/DrKLO/Telegram/blob/62b56a07ca7e30e39f7fd00a6728d6bbd716ca1c/TMessagesProj/jni/tgnet/Datacenter.cpp#L196-L345)

## Фактическая цепочка TG Proxy

```text
Telegram local MTProto handshake (dc, sign/media, test)
  -> MtProtoHandshakeParser
  -> normalized dc + media + test
  -> RouteEngine allow-list + priority + health/cooldown
     -> Direct WS
     -> Worker
     -> Custom Cloudflare
     -> Public Cloudflare
     -> VPS Relay
        -> Relay capability contract
        -> signed topology snapshot/LKG/bootstrap
        -> endpoint pool and bounded race
        -> Telegram TCP endpoint
```

Отрицательный production DC сохраняет прежнюю семантику media. Synthetic `DC204` и
`-DC204` проходят parser и normalization; route type решает, способен ли он доставить этот
DC, а не общий hardcoded `known DC` фильтр.

## Почему routing knowledge различается по маршрутам

### Direct WS

Официальный Telegram Web K публикует явный WSS ingress только для DC 1–5. TG Proxy не
синтезирует `kws204` и больше не подменяет DC203 на DC2. Для DC1–5 фиксированный bootstrap
IP одновременно участвует в гонке с живым DNS hostname `kwsN.web.telegram.org`; победивший
endpoint сохраняется в диагностике и используется независимым MTProto ping.

### Worker

Текущий Worker получает destination от Android. Поэтому он работает только для DC, у
которого Android имеет проверенный raw mapping. Для неизвестного DC кандидат не создаётся.
Это честное `REQUIRES_UPDATED_ROUTING_DATA`, а не ложная попытка.

### Custom/Public Cloudflare

Они зависят от явной Telegram WebSocket topology и потому ограничены DC1–5. Wildcard DNS
может сократить число DNS records, но не доказывает существование нового Telegram WSS host.
DC203/204 не превращаются в `kws203`/`kws204` автоматически.

### VPS Relay

Relay является source of truth для raw Telegram endpoints. Android передаёт только
`dc/media/test`, а сервер выбирает IP, port, IPv4/IPv6 и роль endpoint. Новый Relay объявляет
точные текущие DC, поддержку signed dynamic topology и revision через `/capabilities`.
Старый Relay без `/capabilities` считается legacy static-map сервером и не рекламирует
неизвестный DC.

## Endpoint model Relay

```text
DC
  regular: [IPv4:port, IPv6:port, ...]
  media:   [IPv4:port, IPv6:port, ...]
  cdn:     [IPv4:port, IPv6:port, ...]
```

Для media порядок: `media -> cdn -> regular`. Для main: `regular -> cdn`. Точный port
сохраняется; голый IP мигрирует как `:443`. `this_port_only` тем самым соблюдается. Endpoint
с `secret` отклоняется: raw Relay не реализует transport secret и не должен притворяться,
что умеет его применять.

Endpoint pool:

- до 32 DC и 32 endpoints на DC;
- race волнами до трёх адресов с задержкой 200 мс;
- endpoint-level failure count и exponential cooldown;
- один half-open probe после cooldown;
- проигравшие sockets физически закрываются;
- глобально до 1024 sessions, до 128 sessions на token и до 128 pending dials.

## Signed dynamic topology

```text
valid signed bundle
  -> validate schema/generation/time/limits/IP/port
  -> fsync temporary 0600 file
  -> atomic rename + directory fsync
  -> publish in-memory snapshot

failure/corruption/replay
  -> keep signed last-known-good

no signed snapshot yet
  -> embedded owner bootstrap
```

Bundle содержит `schema`, монотонный `generation`, `notBefore`, `expiresAt`, production/test
endpoint maps и Ed25519 signature. HTTPS не является доверием сам по себе. Redirects и
environment proxy отключены, DNS source обязан разрешаться только в public IP. Manifest
может содержать только public IP literals; localhost, private, link-local, CGNAT,
documentation, benchmark, multicast и reserved ranges отклоняются.

Периодическое обновление использует jitter и exponential backoff. Запрос неизвестного DC
может один раз запустить немедленный signed refresh; глобальный lock и 30-секундный cooldown
не позволяют использовать это как update-source DoS. При недоступности источника остаётся
LKG и нормальный fallback на другие route types.

## Synthetic DC204 / -DC204

| Участок | Статус | Причина |
|---|---|---|
| handshake/parser/normalization | WORKS | произвольный валидный DC ID, sign задаёт media |
| Direct | CORRECTLY UNSUPPORTED | нет подтверждённого Telegram WSS ingress |
| Worker | REQUIRES_UPDATED_ROUTING_DATA | destination задаёт Android static map |
| Custom Cloudflare | CORRECTLY UNSUPPORTED | нельзя синтезировать WSS hostname |
| Public Cloudflare | CORRECTLY UNSUPPORTED | тот же предел WSS topology |
| legacy VPS Relay | CORRECTLY UNSUPPORTED | negotiated static legacy scope |
| static new Relay без DC204 | CORRECTLY UNSUPPORTED | capability list не содержит DC |
| dynamic new Relay с новым signed bundle | WORKS | server-side endpoint resolution |
| dynamic Relay до получения bundle | TEMPORARY FAILURE + FALLBACK | on-demand refresh, затем cooldown |

## Route matrix

| Сценарий | Direct | Worker | Custom CF | Public CF | VPS Relay |
|---|---|---|---|---|---|
| new DC | unsupported без нового WSS ingress | нужен новый Android mapping | unsupported | unsupported | автоматически после signed topology |
| changed IP | live hostname race для DC1–5 | нужен новый mapping | origin/CDN policy | внешний public pool | новый signed generation |
| dead endpoint | fixed-IP/DNS race | route cooldown/fallback | domain pool/cooldown | pool/cooldown, 429 отдельно | endpoint race/cooldown |
| arbitrary port | WSS 443 | текущий Worker contract 443 | HTTPS 443 | HTTPS 443 | поддерживается |
| IPv6 endpoint | через DNS, если платформа выберет | зависит от Worker | зависит от DNS/CDN | зависит от public endpoint | literal IPv6 поддерживается |
| media/CDN | WSS DC1–5 | известный raw DC | WSS DC1–5 | WSS DC1–5 | media/cdn/regular endpoint roles |
| update source unavailable | embedded WSS/DNS | embedded map | configured domains | embedded pool | signed LKG; owner bootstrap, если LKG ещё нет |

## MTProto proof

DNS, TCP, TLS и HTTP `101` — только transport preconditions. Рабочий Android route получает
первый реальный Telegram payload до выбора победителя. Ручные проверки и независимый ping
выполняют encrypted `req_pq` и проверяют валидный `resPQ` отдельно для main/media. Relay
`/test-routes` намеренно маркирует ответ `TCP_ONLY`; после него Android выполняет MTProto
proof и только затем сохраняет Relay.

VoIP/WebRTC/P2P/STUN/TURN не входят в эту архитектуру. Приложение остаётся обычным локальным
MTProto proxy и не использует `VpnService`, TUN, tun2socks или системный VPN.
