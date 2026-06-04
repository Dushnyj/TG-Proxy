# Cloudflare Worker

Cloudflare Worker - бесплатный fallback-маршрут. TG Proxy подключается к вашему Worker по
WebSocket, Worker открывает TCP-соединение к Telegram DC.

```text
TG Proxy -> wss://<worker-domain>/apiws?dst=<telegram-ip>&dc=<dc> -> Worker -> Telegram DC:443
```

## Что нужно

- аккаунт Cloudflare;
- созданный Worker;
- домен Worker вида `name.account.workers.dev` или custom domain;
- в приложении: `Подключение -> Cloudflare / Worker -> Cloudflare Worker домены`.

Cloudflare рекомендует использовать production Worker на route или custom domain, а не только
на `workers.dev`, если endpoint важен для постоянной работы. Официальная справка:
[Routes and domains](https://developers.cloudflare.com/workers/configuration/routing/).

## Создание через Dashboard

1. Откройте [Cloudflare Dashboard](https://dash.cloudflare.com/).
2. Перейдите в `Compute -> Workers & Pages`.
3. Создайте Worker.
4. Откройте `Edit code`.
5. Замените код на шаблон ниже.
6. Нажмите `Deploy`.
7. Скопируйте домен Worker без `https://`.
8. В TG Proxy вставьте домен в поле `Cloudflare Worker домены`.
9. Нажмите `Тест`.

## Создание через Wrangler

Установите Wrangler и выполните deploy. Команда `wrangler deploy` публикует Worker в Cloudflare;
актуальное описание команды есть в официальной документации:
[Workers commands](https://developers.cloudflare.com/workers/wrangler/commands/workers/).

```bash
npm create cloudflare@latest tgproxy-worker
cd tgproxy-worker
npx wrangler deploy
```

## Код Worker

Шаблон ограничивает `dst` списком Telegram DC IP, которые использует приложение. Не удаляйте
эту проверку.

```javascript
import { connect } from "cloudflare:sockets";

const ALLOWED_DST = new Set([
  "149.154.175.50",
  "149.154.167.51",
  "149.154.175.100",
  "149.154.167.91",
  "149.154.171.5",
  "91.105.192.100",
]);

async function toBytes(data) {
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  if (typeof data === "string") return new TextEncoder().encode(data);
  if (data && typeof data.arrayBuffer === "function") {
    return new Uint8Array(await data.arrayBuffer());
  }
  return new Uint8Array();
}

export default {
  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname !== "/apiws") {
      return new Response("Not found", { status: 404 });
    }
    if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
      return new Response("Expected WebSocket", { status: 426 });
    }

    const dst = url.searchParams.get("dst") || "";
    if (!ALLOWED_DST.has(dst)) {
      return new Response("Forbidden dst", { status: 403 });
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();

    const socket = connect({ hostname: dst, port: 443 });
    const reader = socket.readable.getReader();
    const writer = socket.writable.getWriter();

    server.addEventListener("message", async (event) => {
      try {
        await writer.write(await toBytes(event.data));
      } catch {
        try { server.close(1011, "tcp write failed"); } catch {}
      }
    });

    server.addEventListener("close", async () => {
      try { await writer.close(); } catch {}
      try { socket.close(); } catch {}
    });

    (async () => {
      try {
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          if (value) server.send(value);
        }
      } catch {
      } finally {
        try { server.close(); } catch {}
        try { socket.close(); } catch {}
      }
    })();

    return new Response(null, { status: 101, webSocket: client });
  },
};
```

## Что вводить в приложении

В поле `Cloudflare Worker домены` вводите только hostname:

```text
tgproxy-worker.example.workers.dev
worker.example.com
```

Можно указать несколько доменов, каждый с новой строки.

## Проверка

В приложении нажмите `Тест`. Нормальный результат:

```text
DC1 OK
DC2 OK
DC3 OK
DC4 OK
DC5 OK
DC203 OK
```

Если часть DC не проходит, маршрут все равно может быть полезен как fallback. Подробности
смотрите в `Диагностика -> Проверка маршрутов`.

## Частые ошибки

`Expected WebSocket`
: Вы открыли Worker в браузере как обычную страницу. Проверяйте через приложение.

`Forbidden dst`
: Приложение отправило IP, которого нет в `ALLOWED_DST`. Обновите список IP в Worker или приложение.

`WS handshake failed: 403/404`
: Проверьте path `/apiws`, домен Worker и что вы нажали `Deploy` после изменения кода.

`timeout`
: Сеть не достучалась до Cloudflare или Worker не может открыть TCP до Telegram DC.
