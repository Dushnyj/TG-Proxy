# Whitelist-only сети РФ: отдельное исследование

Статус среза: **2026-08-27**. Этот документ не является production-функцией. В TG Proxy и
Relay не добавлен whitelist transport, как и требовалось задачей.

## Короткий результат

**Универсальный зарубежный transport со статусом PROVEN/REPRODUCIBLE для всех МТС,
МегаФон, Билайн и T2, всех регионов и всех whitelist-сценариев не найден.** Нельзя честно
назвать «100% рабочим» смену User-Agent, TLS fingerprint, WebSocket, HTTP/2, HTTP/3, ECH,
Cloudflare Worker или классический domain fronting.

Официальные страницы операторов подтверждают доступ только к определённому набору сервисов,
но не публикуют полный rule engine. Независимые измерения показывают, что российский TSPU/DPI
способен классифицировать и блокировать по нескольким признакам. Конкретный набор правил
различается по оператору, региону, периоду и типу сети, поэтому новость «Cloudflare/сайт X
открывается» не доказывает, что разрешён весь ASN или произвольный hostname на том же IP.

Источники:

- [публичный список Минцифры](https://storage.consultant.ru/ondb/attachments/202509/05/iddoc_297576_idnews_64854_Informatsija_Mintsifry_Rossii_ot_05_09_2025_spisok_resursov_oEZ.pdf)
- [МТС: белый список сервисов](https://kaliningrad.mts.ru/personal/belyj-spisok-servisov)
- [T2: доступ к интернету в условиях ограничений](https://rf.t2.ru/help/article/internet-access-in-blocked-areas)
- [Билайн: белые списки](https://moskva.beeline.ru/customers/press/news/details/zapuskaem-belie-spiski/)
- [CNews: запуск whitelist у операторов, включая МегаФон](https://www.cnews.ru/news/top/2025-09-05_v_runet_po_belym_spiskam)
- [Censored Planet: измерение TSPU](https://censoredplanet.org/assets/tspu-imc22.pdf)
- [Freedom Checker: методика распределённых измерений VPN/proxy](https://freedomchecker.ateo.digital/en/methodology/)
- [OONI: блокировка Telegram в России](https://explorer.ooni.org/findings/2026-russia-blocked-telegram)
- [региональные различия ограничений](https://www.kommersant.ru/doc/8862389)
- [полевые наблюдения MTProxy](https://riposte.levelflow.org/2026/06/mtproxy/)
- [Cloudflare WebSockets](https://developers.cloudflare.com/network/websockets/)
- [Cloudflare Workers TCP sockets](https://developers.cloudflare.com/workers/runtime-apis/tcp-sockets/)
- [Cloudflare routing](https://developers.cloudflare.com/workers/configuration/routing/)
- [Cloudflare Error 1013](https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-1xxx-errors/error-1013/)
- [Android ECH](https://developer.android.com/privacy-and-security/encrypted-client-hello)
- [Cloudflare ECH](https://developers.cloudflare.com/ssl/edge-certificates/ech/)
- [FOCI 2025: ECH и censorship](https://www.petsymposium.org/foci/2025/foci-2025-0016.php)
- [FOCI 2026: QUIC и censorship](https://www.petsymposium.org/foci/2026/foci-2026-0010.pdf)

## 1. Что именно может фильтровать whitelist

Наблюдатель между телефоном и Internet видит последовательно:

```text
DNS query/answer (если не DoH/DoT)
destination IP + subnet + ASN
TCP port или UDP/QUIC port
TLS ClientHello:
  legacy/version, cipher suites, extensions, ALPN,
  SNI (если не защищён ECH), outer SNI при ECH,
  TLS fingerprint и размер/порядок полей
TLS certificate после ServerHello
HTTP Host / HTTP/2 :authority / WebSocket headers —
  только если TLS терминируется/перехватывается либо после активного endpoint
packet sizes, timing, directionality, lifetime
повторные destinations и application-flow fingerprint
```

Whitelist может быть реализован как IP/subnet/ASN rule, DNS/SNI rule, сочетание IP+SNI,
certificate policy или DPI classifier. При обычном end-to-end TLS оператор не читает HTTP
Host и WebSocket headers напрямую, но видит IP, порт, ClientHello и traffic shape. При
принудительном/корпоративном TLS interception видимость шире; для обычной мобильной сети нет
доказательства массовой установки операторского CA на телефон.

### По операторам

| Оператор | Подтверждено | Точная техническая реализация |
|---|---|---|
| МТС | официальный whitelist и региональная страница | UNKNOWN без синхронного PCAP/active probes |
| МегаФон | публично сообщается whitelist-доступ | UNKNOWN; нельзя переносить измерение другого региона |
| Билайн | официальный запуск whitelist | UNKNOWN без полевого измерения конкретной сети |
| T2 | официальная инструкция/перечень доступных сервисов | UNKNOWN без полевого измерения |

Статус «UNKNOWN» здесь означает отсутствие воспроизводимого packet-level доказательства
конкретного rule set, а не предположение, что фильтрации нет.

## 2. Что видит DPI при маскировке под разрешённый сайт

Чтобы поток выглядел как `https://allowed.example`, но дошёл до нашего зарубежного backend,
одновременно должны быть истинны условия:

1. destination IP/ASN разрешён оператором;
2. видимый SNI или ECH outer name разрешён;
3. certificate соответствует имени, которое проверяет клиент;
4. CDN/frontend действительно принимает этот SNI;
5. после TLS он легально маршрутизирует Host/`:authority`/path к контролируемому backend;
6. long-lived bidirectional WebSocket/HTTP stream разрешён frontend и его ToS;
7. DPI не отделяет tunnel по flow fingerprint или active probing;
8. блокировка нашего tenant hostname/path/IP не происходит отдельно от крупного сервиса.

Одной подмены `Host`, User-Agent или TLS fingerprint недостаточно. Если соединение идёт на IP
зарубежного VPS, IP allowlist остановит его до HTTP. Если SNI указывает разрешённый сайт, но
CDN не разрешает fronting к другому tenant, edge отклонит запрос. Cloudflare документирует
Error 1013 для несовместимого SNI/Host; классический cross-tenant domain fronting нельзя
считать рабочей Cloudflare-схемой.

ECH скрывает inner ClientHello только при поддержке DNS HTTPS record, клиента и edge. Оно не
скрывает destination IP и outer ClientHello, не выдаёт право маршрутизировать разрешённый
hostname к чужому origin и потому само по себе whitelist не обходит.

## 3. Текущие route types

| Route | Whitelist-only статус | Обоснование |
|---|---|---|
| Direct WS | NOT WORKING в строгом whitelist, если Telegram IP/WSS hostname отсутствует в списке | оператор видит Telegram destination/SNI; изменить Host после блокировки IP нельзя |
| зарубежный VPS Relay | NOT WORKING напрямую; PARTIALLY PROVEN в обычном DPI без строгого whitelist | виден IP/hostname VPS; нужен реально разрешённый внешний frontend |
| Cloudflare Worker `workers.dev` | UNKNOWN/обычно NOT WORKING в строгом списке | доступность одного CF сайта не разрешает `workers.dev` tenant |
| Worker custom domain | UNKNOWN | заработает только если именно hostname/IP разрешён и edge доступен |
| Custom Cloudflare WSS | UNKNOWN | тот же allowlist boundary; wildcard DNS не даёт разрешение |
| Public Cloudflare | NOT RELIABLE | shared endpoint может быть доступен, заблокирован или отвечать 429; нет operator-wide proof |

## 4. Кандидаты camouflage/transport

| Кандидат | Статус | Причина |
|---|---|---|
| классический cross-domain fronting | NOT WORKING на Cloudflare | edge проверяет SNI/Host и может вернуть 1013 |
| SNI разрешённого сайта + наш VPS IP | NOT WORKING | IP/certificate не соответствуют разрешённому сайту |
| только browser-like TLS/uTLS fingerprint | NOT WORKING как whitelist-решение | меняет classifier, но не destination/IP/SNI authorization |
| ECH | PARTIALLY PROVEN как сокрытие inner SNI, NOT PROVEN как tunnel | IP/outer name и frontend routing остаются |
| HTTP/2 tunnel/WebSocket | transport primitive, не bypass | сначала нужен разрешённый endpoint |
| HTTP/3/QUIC | transport primitive, UNKNOWN по региону | UDP может фильтроваться отдельно; destination остаётся |
| MASQUE/CONNECT через внешний сервис | UNKNOWN | нужен разрешённый сервис, который явно предоставляет proxy и допускает наш traffic |
| разрешённый CDN custom hostname | UNKNOWN | hostname должен реально попасть в operator whitelist |
| shared SaaS frontend → controlled origin | UNKNOWN/high ToS risk | нужен поддерживаемый двунаправленный routing, а не open redirect/webhook |
| foreign anycast edge с нашим tenant | PARTIALLY PROVEN вне whitelist | shared IP повышает collateral cost, но tenant SNI можно блокировать отдельно |
| обычный foreign VPS WSS | REPRODUCIBLE при обычной Telegram-specific блокировке, NOT WORKING при strict destination whitelist | это текущий Relay, но не camouflage |

## 5. Direct

Единый camouflage поверх Direct противоречит понятию Direct: если клиент сначала идёт на
разрешённый frontend, это уже relay/tunnel route. Поэтому whitelist-compatible режим имеет
смысл реализовывать как отдельный transport к Relay, а не переименовывать его в Direct.

## 6. VPS Relay

Это единственная разумная точка для будущего whitelist transport:

```text
TG Proxy local MTProto
  -> allowlisted foreign edge/frontend
  -> authenticated tunnel
  -> foreign VPS Relay
  -> Telegram DC
```

Но frontend должен быть **фактически разрешён** в конкретной сети и официально уметь
проксировать наш bidirectional stream. На текущей доказательной базе такой один frontend для
всех операторов/регионов не найден. Поэтому production-код сейчас не добавлен.

## 7. Worker / Cloudflare

Cloudflare технически поддерживает WebSocket и Worker может открывать outbound TCP, но это
доказывает функциональность edge, а не попадание tenant в российский whitelist. Custom host
даёт контролируемое имя, однако его можно блокировать отдельно по SNI/DNS. Public endpoint
имеет ещё риски 429, lifetime и чужой availability. Cloudflare следует оставить обычным
fallback, но не маркировать как whitelist transport до полевых измерений.

## 8. Единый transport или route-specific

Единого camouflage для Direct/Worker/Custom/Public/VPS нет. Общий внешний transport можно
поместить **перед VPS Relay**, после чего внутренний Relay уже доставляет любой обычный
MTProto main/media/CDN поток. Пытаться сохранить семантику всех route types за таким frontend
не даёт преимуществ и усложняет диагностику.

## 9. Минимальный будущий portfolio

Пока все элементы имеют статус CANDIDATE, не production recommendation:

1. `Allowlisted Edge A -> VPS Relay` — основной long-lived WebSocket/HTTP2 stream;
2. `Allowlisted Edge B -> VPS Relay` — другой ASN/provider и независимый hostname;
3. bounded HTTPS request/stream transport через разрешённую платформу, только если её API и
   ToS прямо допускают это;
4. обычный VPS Relay/Direct/Cloudflare fallback вне strict whitelist.

Portfolio становится пригодным только после operator/region measurements. Без найденных
разрешённых frontends перечисление протоколов не создаёт покрытие.

## 10. Наиболее устойчивая схема без серверов в РФ

Архитектурно — несколько зарубежных shared frontends разных провайдеров перед одним или
несколькими зарубежными Relay, с capability negotiation, отдельным health/cooldown и
MTProto proof. Практически её статус сейчас **UNKNOWN**, потому что не доказан набор frontends,
разрешённый всеми исследуемыми whitelist implementations.

Shared IP повышает collateral damage блокировки, но не гарантирует устойчивость: оператор
может блокировать наш SNI, DNS или tenant path без блокировки всего CDN.

## 11. Что имеет доказанную работоспособность

- PROVEN: текущий Relay передаёт обычный MTProto через зарубежный VPS в сетях, где доступ к
  этому VPS/домену не закрыт strict allowlist.
- PROVEN: Cloudflare умеет WebSocket/Worker TCP как платформа.
- PROVEN: операторы публикуют whitelist-доступ к отдельным сервисам.
- NOT PROVEN: что произвольный наш Worker/custom domain/VPS доступен в strict whitelist.
- NOT PROVEN: универсальное разрешённое имя/edge для четырёх операторов и всех регионов.
- NOT PROVEN: что ECH/uTLS/QUIC превращает запрещённый destination в разрешённый.

## 12. Field test plan

Для каждого МТС/МегаФон/Билайн/T2 минимум в Москве, Новосибирске и ещё одном регионе:

```text
operator, region, tariff/SIM, 4G/5G/Wi-Fi
timestamp and restriction trigger
DNS mode and answers
destination IP/subnet/ASN
visible SNI or ECH outer/inner state
ALPN and TLS fingerprint
certificate
HTTP Host/:authority at controlled edge
route/transport candidate
PCAP on foreign frontend and Relay
active-probe results
MTProto resPQ
login/updates/messages/photo/voice/video/large file/reconnect/long-lived
success/failure and first blocked layer
```

Нужны пары контрольных опытов: тот же IP с разрешённым/нашим SNI, тот же SNI с разными IP,
TCP 443 против QUIC 443, WebSocket против HTTP/2 stream, foreground/background, несколько
tenant hostnames одного CDN. Проверка выполняется только во время реально активного
whitelist-only режима; обычная сеть не подтверждает результат.

## 13. Следующая отдельная задача

1. Подготовить diagnostic build с выбором experimental transport без автоматического
   включения и с подробным layer timing.
2. Развернуть минимум два зарубежных edge providers и два Relay origins.
3. Зафиксировать разрешённые кандидаты отдельным подписанным manifest без raw credentials.
4. Провести полевую operator/region matrix выше.
5. Оставить только PROVEN/REPRODUCIBLE варианты; UNKNOWN не включать по умолчанию.
6. Для прошедших вариантов спроектировать отдельный route type `Whitelist Relay`, а не
   менять Direct/Worker semantics.
7. Проверить ToS, rate limits, active probing, tenant-level blocking, стоимость и поведение
   после публичного распространения.

До этого момента обещание «всегда работает при белых списках» технически недоказуемо.
