# API: этапы 4–9

Все запросы ниже отправляются на `http://localhost:8080`. Перед ручной проверкой
запустите PostgreSQL и приложение по [README](../README.md). Машинная схема
реализованных маршрутов — [OpenAPI](openapi.json).

Регистрация всегда создаёт роль `USER`. Email приводится к нижнему регистру,
пробелы по краям удаляются. Пароль должен содержать от 12 до 72 байт UTF-8;
он не возвращается и не сохраняется в открытом виде.

```http
POST /api/v1/auth/register
Content-Type: application/json

{"email":"user@example.test","password":"ReplaceWithStrongPassword123!"}
```

Успех: `201` и объект `{"id":"...","email":"user@example.test","role":"USER"}`.
Поле `role` в запросе запрещено. Повтор email даёт `409 EMAIL_ALREADY_EXISTS`.

```http
POST /api/v1/auth/login
Content-Type: application/json

{"email":"user@example.test","password":"ReplaceWithStrongPassword123!"}
```

Успех: `200` с `accessToken`, `refreshToken`, `tokenType: "Bearer"` и
`expiresIn` в секундах. Неверные учётные данные дают `401 UNAUTHENTICATED`.
Access token передаётся через `Authorization: Bearer <accessToken>`:

```http
GET /api/v1/users/me
Authorization: Bearer <accessToken>
```

Refresh token — случайная непрозрачная строка. В БД хранится SHA-256 хеш.
Обмен одноразовый: после успешного refresh предыдущий refresh token больше
не принимается. Срок сессии отсчитывается от login и не продлевается при обмене.

```http
POST /api/v1/auth/refresh
Content-Type: application/json

{"refreshToken":"<currentRefreshToken>"}
```

Ответ имеет ту же форму, что login, с новым refresh token. При logout
отзывается вся сессия, включая ранее выданные access tokens.

```http
POST /api/v1/auth/logout
Content-Type: application/json

{"refreshToken":"<currentRefreshToken>"}
```

Успех: `204`. Просроченный, использованный или отозванный токен даёт `401`.
Административный аккаунт создаётся отдельной локальной командой после
установки `BOOTSTRAP_ADMIN_EMAIL` и `BOOTSTRAP_ADMIN_PASSWORD` в окружении:

```powershell
.\gradlew.bat bootRun --args="--spring.main.web-application-type=none --payment.auth.bootstrap-admin=true"
```

Команда создаёт нового `ADMIN`. Если email уже принадлежит `USER` или `ADMIN`,
создание завершается конфликтом; существующая роль не повышается. Держите
пароль вне командной строки и истории shell.

Открыть один RUB-кошелёк и прочитать свой остаток:

```http
POST /api/v1/wallets
Authorization: Bearer <accessToken>
Content-Type: application/json

{"currency":"RUB"}
```

Ответ: `201`, заголовок `Location: /api/v1/wallets/<id>` и тело с `id`,
`currency: "RUB"`, `balanceMinor: 0`, `createdAt`. `accountId` не раскрывается.
Повтор: `409 WALLET_ALREADY_EXISTS`.

```http
GET /api/v1/wallets/me
Authorization: Bearer <accessToken>

GET /api/v1/wallets/<id>
Authorization: Bearer <accessToken>
```

Первый ответ — пустой массив или один собственный кошелёк. Второй возвращает
собственный кошелёк. Чужой либо отсутствующий UUID даёт одинаковый
`404 RESOURCE_NOT_FOUND`; роль `ADMIN` не даёт доступа к чужому балансу.

Ошибки имеют `application/problem+json`: `type`, `title`, `status`, `detail`,
`instance`, `code`, `traceId`. Сервер создаёт `X-Request-Id` для каждого
запроса и не доверяет переданному клиентом значению. Обычно `traceId` в теле
равен этому заголовку. При повторе сохранённого денежного отказа тело, включая
`traceId` исходной команды, воспроизводится без изменений, а `X-Request-Id`
указывает на новый HTTP-запрос.

Денежные команды используют `Idempotency-Key`. Допустимы 1–128 символов
`[A-Za-z0-9._:-]`; ключ регистрозависим. Его область — инициатор и тип команды.
Одинаковый ключ с теми же параметрами возвращает сохранённый HTTP-статус и тело;
разные параметры дают `409 IDEMPOTENCY_CONFLICT`. При недостатке средств
сохраняется отказ `REJECTED`; после пополнения прежний ключ по-прежнему
возвращает его, для новой попытки нужен новый ключ.

Тестовое пополнение доступно только `ADMIN`. UUID кошелька сообщает владелец;
администратору не требуется право читать его остаток:

```http
POST /api/v1/admin/top-ups
Authorization: Bearer <adminAccessToken>
Idempotency-Key: 1a2b3c4d-1111-4111-8111-111111111111
Content-Type: application/json

{"walletId":"11111111-1111-4111-8111-111111111111","amountMinor":100000,"currency":"RUB"}
```

Перевод выполняет владелец исходного кошелька; кошелёк получателя может
принадлежать другому пользователю:

```http
POST /api/v1/payments/transfers
Authorization: Bearer <accessToken>
Idempotency-Key: 2a2b3c4d-2222-4222-8222-222222222222
Content-Type: application/json

{"fromWalletId":"11111111-1111-4111-8111-111111111111","toWalletId":"22222222-2222-4222-8222-222222222222","amountMinor":25000,"currency":"RUB"}
```

При успешном проведении оба маршрута возвращают `201`, заголовок
`Location: /api/v1/payments/<id>` и объект платежа с полями `id`, `type`,
`status: "POSTED"`, `amountMinor`, `currency: "RUB"`, `createdAt`.
Начальный лимит нового пополнения или перевода — 100000000 копеек. Перевод
самому себе, превышение лимита, переполнение остатка или нехватка средств
дают сохраняемый `409` с `code` и `paymentId`, без проводок. `USER`
на административном маршруте получает `403 FORBIDDEN`, чужой исходный
кошелёк — `404 RESOURCE_NOT_FOUND`; такие ошибки не создают платёж.

Прочитать результат по `id` из `Location` или по `paymentId` из отказа:

```http
GET /api/v1/payments/<id>
Authorization: Bearer <accessToken>
```

Проведённый платёж виден инициатору и владельцам участвующих пользовательских
кошельков. Отклонённый виден только инициатору и включает `rejectionCode`.
Чужой или отсутствующий платёж даёт одинаковый `404 RESOURCE_NOT_FOUND`.
Ответ содержит `id`, `type`, `status`, `amountMinor`, `currency`,
`createdAt`; у возврата дополнительно есть `originalPaymentId`.

Полный возврат инициирует владелец кошелька, получившего исходный проведённый
перевод. Сумму, валюту и обратное направление определяет сервер по исходному
платежу. Тело может отсутствовать либо быть пустым JSON-объектом; дополнительные
поля и JSON `null` недопустимы:

```http
POST /api/v1/payments/<transferId>/refund
Authorization: Bearer <recipientAccessToken>
Idempotency-Key: 3a2b3c4d-3333-4333-8333-333333333333
Content-Type: application/json

{}
```

Успех: `201`, `Location: /api/v1/payments/<refundId>` и отдельный платёж
`REFUND` со статусом `POSTED`, исходной суммой и `originalPaymentId`.
Исходный `TRANSFER` остаётся `POSTED`. Прежний ключ возвращает точно сохранённые
статус и тело. Новая попытка после успешного возврата даёт сохраняемый
`409 PAYMENT_ALREADY_REFUNDED`; нехватка средств получателя — сохраняемый
`409 INSUFFICIENT_FUNDS` без проводок. После пополнения нужна новая попытка
с новым ключом. Отправитель исходного перевода и посторонний пользователь
получают `404 RESOURCE_NOT_FOUND`. Получатель доступной операции иного типа
получает сохраняемый `409 PAYMENT_NOT_REFUNDABLE`.

История принадлежит владельцу кошелька и содержит только проведённые движения.
У входящего движения `deltaMinor` положителен, у исходящего отрицателен:

```http
GET /api/v1/wallets/<walletId>/transactions?page=0&size=20
Authorization: Bearer <accessToken>
```

Ответ `200` содержит `items`, `page`, `size`, `totalElements`, `totalPages`.
Каждый элемент имеет `paymentId`, `type`, `createdAt`, `currency`, знаковое
`deltaMinor` и `originalPaymentId` для движения возврата. `page` начинается
с 0, `size` — от 1 до 100 (по умолчанию 20); сортировка стабильная:
`createdAt DESC`, затем `paymentId DESC`. Отклонённые попытки не создают
движение. Чужой либо отсутствующий кошелёк даёт одинаковый `404`.

Администратор запускает синхронную сверку без изменения денежных данных:

```http
GET /api/v1/admin/reconciliation
Authorization: Bearer <adminAccessToken>
```

Ответ `200` содержит `consistent`, `checkedAccounts`,
`checkedLedgerTransactions` и массив `discrepancies`. Сверка сравнивает
остатки с проводками, проверяет баланс каждой денежной транзакции и отсутствие
отрицательных остатков пользовательских счетов. При расхождении
`consistent=false`, но HTTP-статус остаётся `200`; исправления не выполняются.
`USER` получает `403 FORBIDDEN`.
