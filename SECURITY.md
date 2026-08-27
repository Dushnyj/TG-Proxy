# Политика безопасности

## Поддерживаемые версии

Исправления безопасности выпускаются для последней опубликованной версии TG Proxy.

| Версия | Поддержка |
| --- | --- |
| latest | Да |
| older | Только критические исправления по возможности |

## Закрытое сообщение об уязвимости

Используйте
[Private vulnerability reporting](https://github.com/Dushnyj/TG-Proxy/security/advisories/new).
Не создавайте публичный issue с эксплуатационными деталями или секретами.

Укажите:

- версию TG Proxy и источник APK;
- модель устройства и Android;
- затронутый компонент/маршрут;
- минимальные шаги воспроизведения;
- ожидаемое и фактическое поведение;
- оценку влияния;
- обезличенные логи или proof без production credentials.

Если проблема относится к APK-подписи или updater, приложите имя asset и SHA-256 из
`SHA256SUMS.txt`, но не keystore.

## Никогда не отправляйте публично

- Android release keystore и пароли подписи;
- GitHub, Cloudflare, DuckDNS, client или owner tokens;
- SSH password/private key и подтверждённые production host keys;
- полный MTProto secret;
- production VPS IP/domain вместе с credentials;
- диагностический ZIP без предварительной проверки;
- персональные данные устройств/аккаунтов.

## Область проекта

В scope входят локальный MTProto listener, Android service/storage/import, route selection,
Cloudflare/VPS transports, SSH auto-setup и интеграция с TG Proxy VPS Relay. Проблемы Telegram,
прошивки, оператора или стороннего reverse proxy входят в scope, когда есть воспроизводимая
уязвимость или нарушение заявленного security contract TG Proxy.

Модель локальных данных и экспорта: [docs/PRIVACY_AND_SECRETS.md](docs/PRIVACY_AND_SECRETS.md).
