# Cloudflare-домен

Cloudflare-домен - это собственный домен для маршрута `Cloudflare CDN`. Приложение
подключается к `kws<dc>.<ваш-домен>` через Cloudflare, а DNS-записи указывают на Telegram DC.

```text
TG Proxy -> wss://kws4.example.com/apiws -> Cloudflare -> Telegram DC
```

## Что нужно

- домен в Cloudflare zone;
- DNS-записи `A` для Telegram DC;
- включенный proxy status у этих записей;
- в приложении: `Подключение -> Cloudflare / Worker -> Cloudflare CDN`.

Cloudflare proxy status управляет тем, идет ли HTTP/HTTPS трафик через сеть Cloudflare или
напрямую на origin. Официальная справка:
[Proxy status](https://developers.cloudflare.com/dns/proxy-status/).

## DNS-записи

В Cloudflare откройте `DNS -> Records` и добавьте записи:

| Type | Name | IPv4 address | Proxy status |
| --- | --- | --- | --- |
| A | `kws1` | `149.154.175.50` | Proxied |
| A | `kws2` | `149.154.167.51` | Proxied |
| A | `kws3` | `149.154.175.100` | Proxied |
| A | `kws4` | `149.154.167.91` | Proxied |
| A | `kws5` | `149.154.171.5` | Proxied |

Telegram Web K сейчас публикует явные WebSocket ingress только для DC1-5. Не создавайте
`kws203`, `kws204` или wildcard с предположением, что новый DC автоматически поддержит тот же
контракт: TG Proxy 1.2.0 не строит для них ложный Cloudflare candidate.

В интерфейсе Cloudflare `Proxied` обычно отображается оранжевым облаком.

## SSL/TLS

Для этого способа Cloudflare должен принимать HTTPS/WebSocket от телефона и проксировать
соединение к Telegram IP. В Cloudflare проверьте `SSL/TLS -> Overview`.

Практически используемый режим для такого CF-прокси - `Flexible`. Если у вашего домена уже есть
обычный сайт, не меняйте глобальный SSL/TLS mode без понимания последствий: лучше используйте
отдельный поддомен или Cloudflare Worker.

## Что вводить в приложении

В поле `Cloudflare CDN домены` вводится базовый домен без `kws`:

```text
example.com
```

Приложение само будет строить адреса:

```text
kws1.example.com
kws2.example.com
kws3.example.com
kws4.example.com
kws5.example.com
```

Можно указать несколько базовых доменов, каждый с новой строки.

## Проверка

1. Включите `Cloudflare CDN`.
2. Если используете только свои домены, включите `Свои Cloudflare-домены`.
3. Нажмите `Тест`.
4. Проверьте, что большинство DC получает `OK`.
5. Откройте `Диагностика` и проверьте активный маршрут.

## Direct mapping для медиа

Это расширенная настройка маршрута Direct, а не способ добавить новый Cloudflare DC. Если
текстовые сообщения работают, а фото/видео по Direct грузятся плохо, можно проверить override:

```text
4:149.154.167.220
```

Если не помогло:

```text
2:149.154.167.220
4:149.154.167.220
```

Сохраняйте только валидный mapping: приложение не должно принимать неправильный IP или дубль DC.

## Частые ошибки

`WS handshake failed: 403/404`
: Проверьте, что запись `kws<dc>` существует и включена как `Proxied`.

`timeout`
: DNS еще не обновился, сеть режет Cloudflare или Cloudflare не может достучаться до origin.

`HTTP 429`
: Cloudflare ограничил маршрут. Попробуйте другой домен, Worker или VPS Relay.

`SSL error`
: Проверьте режим `SSL/TLS`. Для обычного сайта на том же домене безопаснее не менять общий режим, а использовать отдельный домен/поддомен под TG Proxy.
