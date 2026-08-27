# Failure-injection и field validation

Цель — отличать доказанное автоматическими тестами от проверки реального оператора и
Telegram account. HTTP `200`, TCP connect и WebSocket `101` сами по себе не считаются
успехом Telegram.

## Автоматизированная матрица

| Сценарий | Injection | Ожидаемый результат | Автоматическая проверка |
|---|---|---|---|
| endpoint A dead, B works | первый dial зависает до context timeout, второй отдаёт socket | B выигрывает менее чем за общий timeout, A получает cooldown | Relay `TestDeadFirstEndpointRacesToWorkingAlternative` |
| endpoint cooldown | повторный запрос сразу после failure | A не dial-ится, B остаётся доступным | Relay `TestFailedEndpointIsNotRedialedDuringCooldown` |
| new DC204 | handshake `dc=204` | только dynamic-capable Relay candidate | Android `RouteEngineTest` |
| new media DC -204 | negative DC | `dc=204, media=true`; те же capability rules | Android parser/route tests |
| stale Android vs new Relay | dynamic capability + DC204 | Relay candidate без APK DC map | Android capability/route tests |
| new Android vs old Relay | `/capabilities` = 404 | только embedded legacy DC; нет false DC204 | Android capability/client tests |
| changed IP/port/IPv6 | signed endpoint list | exact address and port preserved | Relay config/topology tests |
| corrupt signature | one changed field/signature | bundle rejected, current LKG unchanged | `internal/topology` tests |
| expired/stale/replayed update | expired time / lower generation | update rejected; signed LKG retained | topology bundle/manager tests |
| malicious endpoint | localhost/private/link-local/CGNAT/docs/reserved | config/manifest rejected | Relay config tests |
| destination injection | `dst`, duplicate `dc`, unknown query | request rejected before dial | Relay WebSocket internal tests |
| oversized topology | >32 DC, >32 endpoints/DC, >1 MiB | rejected before publication | Relay config/topology tests |
| Cloudflare 429 | classified response | per-route cooldown, fallback remains | Android route/error tests |
| all routes cooling | reconnect burst | one leased half-open probe, no storm | Android RouteEngine tests |
| route disabled at runtime | uncheck route | pending attempt invalidated; only sessions on disabled route close | Android runtime/profile tests |
| owner block | active device then block | its sessions close, reconnect gets 403, token remains | Relay owner tests |
| owner disconnect | active device then disconnect | sessions close, reconnect remains allowed | Relay owner tests |
| long/fragmented media frame | continuation + interleaved control frame | binary message reassembled; limits enforced before allocation | Android/Relay WebSocket tests |
| local upload EOF | half-close | Telegram download drains until bounded timeout | bridge/half-close tests |

## Команды локального gate

```powershell
cd C:\Source\TG\TG-Proxy
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon

cd C:\Source\TG\TG-Proxy-Relay
go test ./...
go test -race ./...
go build -trimpath -o tgproxy-relay ./cmd/tgproxy-relay
```

Дополнительно:

```powershell
cd C:\Source\TG\TG-Proxy
.\gradlew.bat lintDebug --no-daemon

cd C:\Source\TG\TG-Proxy-Relay
go vet ./...
```

## Реальная Telegram matrix после подключения телефона

ADB сейчас намеренно не требуется. После подключения устройства прогоняется отдельный
operator matrix; до него нельзя объявлять реальное media/CDN поведение подтверждённым.

Для каждого `operator × region × Wi-Fi/mobile × route type`:

1. Очистить диагностику TG Proxy.
2. В профиле оставить только проверяемый route type.
3. Запустить TG Proxy и добавить локальный MTProto proxy в Telegram.
4. Проверить вход в session и updates не менее 10 минут.
5. Отправить и скачать:
   - текст;
   - фото;
   - voice message;
   - video message/кружок;
   - GIF/sticker;
   - документ 10–50 MiB;
   - файл больше 1 GiB;
   - параллельный download + upload.
6. Во время large download выполнить Wi-Fi → mobile → Wi-Fi, погасить экран, дождаться
   reconnect и проверить продолжение.
7. Для VPS отдельно выключить endpoint A, оставить B; затем изменить IP/port в новом signed
   generation; затем выключить update source и повторить на LKG.
8. Сохранить TG Proxy ZIP/TXT, Relay journal и timestamps. Где возможно — PCAP на VPS.

## Поля одного результата

```text
timestamp (UTC and local)
operator / region / network type
Android version / manufacturer / model / app version
network profile key (без raw credential)
route type / route key / active endpoint
dc / media / test
topology generation / source
TCP/TLS/WS result
MTProto resPQ proof
content type / size / direction / duration
reconnect count / bytes transferred
success | failure | partial
classified error and first failing layer
```

## Критерий PASS

- route выбран только после первого Telegram payload или отдельного валидного `resPQ`;
- main и media проверены раздельно;
- large transfer не обрезан и не завис навсегда после half-close;
- при failure одного endpoint/route происходит bounded fallback, а не reconnect storm;
- выключенный route не появляется в новых попытках;
- foreground service и listener остаются живыми после экрана, Doze и смены сети;
- `/test-routes ... OK TCP_ONLY` без последующего MTProto proof не считается PASS.
