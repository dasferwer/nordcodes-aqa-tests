# Обнаруженные дефекты

Проверка от 02.11.2026, Java 17, приложение `internal-0.0.1-SNAPSHOT.jar`. SHA-256 бинарника указан в README. Все запросы выполнялись с правильным демонстрационным API-ключом. Внешний сервис заменён WireMock; при позитивных запросах он отвечает HTTP 200.

## BUG-01. Допустимые токены с буквами G-Z отклоняются

**Основание:** прямое требование задания: 32 символа из `A-Z0-9`.

**Серьёзность:** Major. Пользователи с частью допустимых токенов не могут войти.

1. Настроить `POST /auth` внешнего сервиса на HTTP 200.
2. Отправить `POST /endpoint`, `Content-Type: application/x-www-form-urlencoded`, `Accept: application/json`, `X-Api-Key: qazWSXedc`.
3. Передать `token=ABCDEFGHIJKLMNOPQRSTUVWXYZ012345&action=LOGIN`.

**Ожидание:** успешный вход, `result=OK`, запрос с тем же токеном на `/auth`, возможность выполнить ACTION.

**Факт:** HTTP 400, внешний сервис не вызывается:

```json
{"result":"ERROR","message":"token: must match \"^[0-9A-F]{32}$\""}
```

Также воспроизведено для `G` + 31 буква `A` и 31 буква `A` + `Z`. Токены из цифр и букв A-F проходят.

**Автотест:** `ValidationTest.acceptsValidToken`, 3 падающих набора данных.

## BUG-02. Неподдерживаемый HTTP-метод возвращает серверную ошибку

**Основание:** проверка HTTP-протокола; конкретные HTTP-статусы в ТЗ не перечислены. Для неподдерживаемого метода RFC 9110, раздел 15.5.6, предусматривает 405 Method Not Allowed.

**Серьёзность:** Minor. Запрос отклоняется, но клиентская ошибка представлена как неисправность сервера.

1. Использовать допустимый токен `AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` и правильный API-ключ.
2. Отправить `GET /endpoint` с телом `token=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&action=LOGIN` и типом `application/x-www-form-urlencoded`.
3. Повторить с PUT, DELETE и PATCH.

**Ожидание:** клиентская ошибка, обычно HTTP 405; внешних запросов нет. Автотест допускает любой HTTP 4xx.

**Факт:** каждый из четырёх методов возвращает HTTP 500:

```json
{"result":"ERROR","message":"Internal Server Error"}
```

**Автотест:** `ValidationTest.rejectsOtherMethods`, 4 падающих набора данных.

## BUG-03. Неподдерживаемый Content-Type возвращает серверную ошибку

**Основание:** проверка HTTP-протокола; в ТЗ указан формат `application/x-www-form-urlencoded`. RFC 9110, раздел 15.5.16, описывает 415 Unsupported Media Type.

**Серьёзность:** Minor. Неверный формат запроса выглядит как сбой сервера.

1. Отправить `POST /endpoint` с правильным API-ключом и `Accept: application/json`.
2. Установить `Content-Type: application/json`.
3. Передать `{"token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","action":"LOGIN"}`.
4. Повторить с `Content-Type: text/plain`.

**Ожидание:** клиентская ошибка, обычно HTTP 415; внешних запросов нет. Автотест допускает любой HTTP 4xx.

**Факт:** оба типа тела возвращают HTTP 500:

```json
{"result":"ERROR","message":"Internal Server Error"}
```

**Автотест:** `ValidationTest.rejectsOtherContentTypes`, 2 падающих набора данных.

## Итог запуска

73 проверки: 64 успешных, 9 падений по трём описанным дефектам. Ошибок тестовой инфраструктуры и пропущенных проверок нет. Дефекты приложения не исправлялись: задача состоит в его тестировании.

Источник для BUG-02 и BUG-03: [RFC 9110, HTTP Semantics](https://www.rfc-editor.org/rfc/rfc9110.html).
