# Разработка TG Proxy Android

## Требования

- Git;
- JDK 17;
- Android SDK Platform 34 и Build Tools;
- Android Debug Bridge — только для установки и instrumented-тестов;
- Windows, Linux или macOS.

Проект использует Gradle Wrapper 8.2. Глобальная установка Gradle не нужна.

## Получить код

```bash
git clone https://github.com/Dushnyj/TG-Proxy.git
cd TG-Proxy
```

При необходимости укажите путь к Android SDK в локальном `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
```

Файл игнорируется Git и не должен содержать общие или production-секреты.

## Проверки

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
```

На Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon
```

Instrumented-тесты требуют подключённое устройство или эмулятор:

```bash
./gradlew connectedDebugAndroidTest --no-daemon
```

## Установить debug APK

После `assembleDebug` выберите APK своей ABI или universal APK из:

```text
app/build/outputs/apk/debug/
```

Пример:

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

Debug application ID — `com.dushnyj.tgproxy.debug`; он может быть установлен рядом с release.

## Основные части

| Компонент | Назначение |
| --- | --- |
| `MainActivity` | главный экран, настройки и запуск пользовательских flow |
| `ProxyService` | foreground service и жизненный цикл локального listener |
| `MtProtoProxyEngine` | локальный MTProto endpoint и маршрутизация соединений |
| `RouteEngine` | кандидаты, приоритет, cooldown и failover |
| `VpsSetupActivity` / `VpsSetup*` | read-only audit, план, установка и rollback VPS |
| `VpsOwnerActivity` / `VpsOwnerClient` | owner API, токены и устройства |
| `Diagnostics*` | безопасный отчёт и журнал событий |

Архитектурная карта: [ARCHITECTURE.md](ARCHITECTURE.md).

## Правила для сетевого кода

- HTTP/TCP/WebSocket handshake не заменяет Telegram MTProto proof;
- regular и media endpoint проверяются отдельно;
- неизвестный DC нельзя направлять на адрес, переданный клиентом;
- поздний результат старой route generation не должен менять активное состояние;
- изменение настроек должно быть атомарным и не разрушать last-known-good;
- внешние импорты всегда требуют preview и подтверждение.

## Тестовые данные

Используйте только зарезервированные примеры:

```text
relay.example.com
203.0.113.10
192.0.2.15
client-secret-for-test
```

Не добавляйте реальные домены, IP VPS, SSH-данные, токены, снимки SharedPreferences, ADB backup,
полные диагностические архивы или скриншоты с личными данными. Локальные `adb-*`, `qa-*` и
`logs-*` игнорируются, но перед коммитом всё равно проверяйте `git status`.

## Перед pull request

1. Обновите тесты и документацию.
2. Запустите unit tests, lint и debug build.
3. Проверьте `git diff --check`.
4. Просканируйте историю и рабочее дерево на секреты.
5. Заполните checklist в шаблоне pull request.

Общие правила: [CONTRIBUTING.md](../CONTRIBUTING.md).
