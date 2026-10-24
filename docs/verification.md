# Проверка MVP v0.1.0

Источник ожидаемого поведения — [mvp.md](mvp.md). Автоматические интеграционные
тесты запускают настоящую PostgreSQL 17 через Testcontainers. Для HTTP,
блокировок, журналов и доступа H2 не применяется.

| Сценарии | Проверка |
| --- | --- |
| MVP-01, 03, 04, 18, 23, 29 | `IdentityHttpIntegrationTest`, `WalletIntegrationTest`, `PaymentCommandHttpIntegrationTest`, `HistoryReconciliationHttpIntegrationTest`: роли, чужие ресурсы, повторная проверка доступа |
| MVP-02, 05, 06, 09 | `WalletIntegrationTest`, `PaymentCommandHttpIntegrationTest`: один кошелёк, проводки, перевод, запрет перевода себе |
| MVP-07, 08, 10, 27, 28 | `ApiHttpIntegrationTests`, `PaymentCommandHttpIntegrationTest`: строгие входные данные, лимит, переполнение, сохранённые отказы, изменение лимита |
| MVP-11–16, 22 | `IdempotencyIntegrationTest`, `PaymentCommandHttpIntegrationTest`: отпечаток, области ключа, конкуренция, потерянный ответ через повтор, технический откат |
| MVP-17, 19–21, 24 | `PaymentCommandHttpIntegrationTest`: полный возврат, недостаток средств, два ключа, повтор и неподходящая операция |
| MVP-25, 29 | `HistoryReconciliationHttpIntegrationTest`: корректный журнал и искусственное расхождение в изолированной БД |
| MVP-26 | `scripts/backup.sh`, `scripts/restore-check.sh`, перезапуск Compose и `scripts/demo.py` на отдельной локальной БД |

Проверка одновременных встречных переводов использует 24 HTTP-команды с барьером
начала, отдельные транзакции PostgreSQL и завершается сверкой. Это ограниченная
проверка конкурентности, а не доказательство заданной пропускной способности.
Нагрузочные показатели сервера требуют повторного измерения на фактической ВМ.

Команды: `docker info`, затем `./gradlew check build` (Windows:
`.\gradlew.bat check build`), `docker compose ... config --quiet`, локальная
сборка образа и сценарий из [release.md](release.md). Результаты конкретного
прогона и неподтверждённые серверные действия фиксируются в
[плане](implementation-plan.md).
