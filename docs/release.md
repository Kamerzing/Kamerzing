# Выпуск и развёртывание v0.1.0

Это учебный сервис с тестовыми деньгами. Выпускной артефакт — проверенный образ
`ghcr.io/kamerzing/payment-core:v0.1.0`; код собирается в CI, сервер получает готовый образ.
Файлы развёртывания находятся в `deploy/`. Существующий `compose.yaml` предназначен
для локальной PostgreSQL и не заменяется.

## Подготовка выпуска

На машине с JDK 21 и Docker выполнить `docker info`, затем:

```bash
sh gradlew --no-daemon --max-workers=2 check build
docker build -t payment-core:0.1.0-local .
```

Workflow `.github/workflows/release.yml` повторяет проверку и собирает образ.
При отправке тега `v0.1.0` он публикует образ в GHCR через `GITHUB_TOKEN`.
Тег создают только после проверки изменений; текущая документация не создаёт его
автоматически. Для развёртывания укажите в `PAYMENT_IMAGE` опубликованный digest
`ghcr.io/kamerzing/payment-core@sha256:...`.

## Сервер

Нужны Linux, Docker Engine с Compose plugin, DNS-запись домена на адрес сервера,
доступные порты 80/443, место для постоянного тома PostgreSQL и отдельное
хранилище резервных копий. Ориентир ресурсов — 4 vCPU, 2 ГБ RAM, 100 ГБ диска;
проверьте фактическую ВМ до запуска. [Caddy](https://caddyserver.com/docs/automatic-https)
получает и обновляет TLS-сертификат для указанного домена, когда DNS и порты настроены.

Перенесите `deploy/compose.prod.yaml`, `deploy/Caddyfile`,
`deploy/deploy.env.example`, каталог `scripts/` и готовый образ на сервер.
В корне проекта:

```bash
cp deploy/deploy.env.example deploy/deploy.env
install -m 700 -d deploy/secrets deploy/backups
openssl rand -base64 36 | tr -d '\n' > deploy/secrets/db_password
openssl rand -base64 48 | tr -d '\n' > deploy/secrets/jwt_secret
chmod 600 deploy/secrets/*
```

Замените `PAYMENT_DOMAIN` и `PAYMENT_IMAGE` в `deploy/deploy.env`. Никогда не
помещайте пароли и JWT-ключ в этот файл. Compose монтирует секреты только в
нужные контейнеры; PostgreSQL использует `POSTGRES_PASSWORD_FILE`, Spring Boot —
`configtree:/run/secrets/`. Существующий том БД сохраняйте при обновлениях.

```bash
docker compose --env-file deploy/deploy.env -f deploy/compose.prod.yaml config --quiet
docker compose --env-file deploy/deploy.env -f deploy/compose.prod.yaml pull
docker compose --env-file deploy/deploy.env -f deploy/compose.prod.yaml up -d
curl --fail https://YOUR_DOMAIN/actuator/health/readiness
```

Если GHCR-пакет закрыт, сначала настройте `docker login ghcr.io` с токеном
только на чтение пакета. Внешние порты принадлежат Caddy; PostgreSQL и приложение
доступны только в сетях Compose. Логи каждого контейнера ограничены ротацией.

## Первый администратор и демонстрация

Однократная команда создаёт `ADMIN`; существующего пользователя она не повышает.
Считайте учётные данные без записи пароля в историю shell:

```bash
read -r -p 'Admin email: ' BOOTSTRAP_ADMIN_EMAIL
read -r -s -p 'Admin password: ' BOOTSTRAP_ADMIN_PASSWORD; echo
export BOOTSTRAP_ADMIN_EMAIL BOOTSTRAP_ADMIN_PASSWORD
docker compose --env-file deploy/deploy.env -f deploy/compose.prod.yaml run --rm --no-deps \
  -e BOOTSTRAP_ADMIN_EMAIL -e BOOTSTRAP_ADMIN_PASSWORD app \
  --spring.main.web-application-type=none --payment.auth.bootstrap-admin=true
unset BOOTSTRAP_ADMIN_EMAIL BOOTSTRAP_ADMIN_PASSWORD
```

Для демонстрации задайте `PAYMENT_ADMIN_EMAIL` и `PAYMENT_ADMIN_PASSWORD` в
окружении и запустите `python3 scripts/demo.py --base-url https://YOUR_DOMAIN`.
Скрипт создаёт двух новых пользователей и кошельки, выполняет пополнение,
перевод и его повтор, возврат, проверяет историю, права и сверку. Он печатает
только ID операций и итог, без токенов и паролей. Запускайте его там, где
допустимо создать тестовые данные.

## Резервная копия, восстановление и перезапуск

```bash
bash scripts/backup.sh
bash scripts/restore-check.sh deploy/backups/payment_core_YYYYMMDDTHHMMSSZ_PID.dump
docker compose --env-file deploy/deploy.env -f deploy/compose.prod.yaml restart app
curl --fail https://YOUR_DOMAIN/actuator/health/readiness
```

`backup.sh` создаёт сжатый архив `pg_dump` и SHA-256 checksum с правами только
владельца. Копируйте оба файла **вне сервера** и периодически проверяйте
восстановление. `restore-check.sh` проверяет checksum и восстанавливает архив во временную отдельную
БД, сверяет журнал SQL-запросами и запускает приложение для проверки Flyway и
Hibernate. Только созданная им временная БД удаляется после успешной или
неуспешной проверки; `KEEP_RESTORE_DB=1` оставляет её для осмотра. Текущую БД
и Docker volumes скрипты не удаляют. На работающем сервере данные могут измениться
после создания архива, поэтому сравнивайте состояние именно с моментом копии.

При обновлении сохраните предыдущий digest образа. Для отката верните его в
`PAYMENT_IMAGE` и выполните `up -d`; сначала проверьте совместимость уже
применённых миграций. Для v0.1.0 новых миграций после V4 нет.
