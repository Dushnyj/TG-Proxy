# Документация TG Proxy

- [VPS Relay в приложении](VPS_RELAY.md) - ручной ввод, импорт, экспорт, QR и автонастройка VPS через отдельный Relay-репозиторий.
- [Cloudflare Worker](CLOUDFLARE_WORKER.md) - бесплатный Worker endpoint для маршрута `Cloudflare Worker`.
- [Cloudflare-домен](CLOUDFLARE_DOMAIN.md) - собственный домен с `kws<dc>` DNS-записями.
- [Диагностика](DIAGNOSTICS.md) - отчеты, логи, route matrix и безопасная передача данных.
- [Маршрутизация](ROUTING.md) - порядок кандидатов, сетевые профили и fallback.
- [Telegram topology](TELEGRAM_TOPOLOGY.md) - DC options, границы route types, signed LKG и synthetic DC204.
- [Failure injection](FAILURE_INJECTION.md) - автоматические и полевые сценарии отказов.
- [Whitelist-only сети РФ](RUSSIAN_WHITELIST_RESEARCH.md) - доказательная база и test plan без production-реализации транспорта.
- [Надежность Android-сервиса](RELIABILITY.md) - foreground service, уведомление, батарея и автозапуск.
- [Архитектура](ARCHITECTURE.md) - основные компоненты Android-приложения.

Серверная часть VPS Relay находится в отдельном репозитории: [Dushnyj/TG-Proxy-Relay](https://github.com/Dushnyj/TG-Proxy-Relay).
