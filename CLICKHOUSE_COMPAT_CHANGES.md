# Avilix Logger — ClickHouse backend

Добавлена поддержка ClickHouse как отдельного backend хранения логов.

## Режимы

`serverconfig/avilixlogger-common.toml`:

```toml
[general]
storageBackend = "mysql"      # mysql, clickhouse, dual
keepDays = 30

[clickhouse]
url = "jdbc:clickhouse:http://127.0.0.1:8123/default"
database = "avilix_logger"
table = "avilixlogger_actions"
user = "default"
password = ""
poolSize = 3
batchSize = 5000
flushIntervalMs = 1000
queueCapacity = 500000
selectQueryTimeoutSec = 5
asyncInsert = true
waitForAsyncInsert = true

# dual-настройки
dualPreferClickHouseReads = true
dualWriteMode = "mirror"          # mirror, failover
dualReadMode = "primary_fallback" # primary_fallback, smart_merge, merge
dualFallbackCooldownMs = 10000
```

### mysql
Старое поведение. Пишет и читает только MySQL/MariaDB.

### clickhouse
Пишет и читает только ClickHouse. Подходит для тяжёлого логгера, больших GUI-запросов и больших объёмов логов.

### dual + mirror
Миграционный режим. Пишет одновременно в ClickHouse и MySQL.
По умолчанию читает из ClickHouse, а если там пусто/ошибка — пробует MySQL.

```toml
[general]
storageBackend = "dual"

[clickhouse]
dualPreferClickHouseReads = true
dualWriteMode = "mirror"
dualReadMode = "primary_fallback"
```

Плюс: максимально безопасно, обе БД содержат копию логов.
Минус: MySQL продолжает получать всю нагрузку записи.

### dual + failover
Приоритетный режим. Пишет в ClickHouse, пока он здоров. Если ClickHouse падает/не отвечает, новые логи временно уходят в MySQL.

```toml
[general]
storageBackend = "dual"

[clickhouse]
dualPreferClickHouseReads = true
dualWriteMode = "failover"
dualReadMode = "primary_fallback"
dualFallbackCooldownMs = 10000
```

Плюс: MySQL почти не нагружается и используется как аварийный буфер.
Минус: если ClickHouse упал на некоторое время, часть логов окажется только в MySQL. После восстановления ClickHouse эти старые fallback-логи сами не переносятся обратно.

Если нужно видеть в GUI и ClickHouse, и MySQL-логи после периода fallback, лучше включить:

```toml
dualReadMode = "smart_merge"
```

`smart_merge` сначала читает ClickHouse и трогает MySQL только если ClickHouse не набрал полную страницу. `merge` делает запрос в обе БД и объединяет результат. Это закрывает дырки после fallback-периодов, но тяжелее для GUI и менее идеально для cursor-пагинации, потому что id в MySQL и ClickHouse имеют разную природу.

## Что изменено в коде

- `ClickHouseLogStorage` — backend ClickHouse.
- `DualLogStorage` — зеркальная запись в обе БД.
- `PriorityFailoverLogStorage` — приоритетная запись в основную БД с fallback во вторую.
- `HealthAwareLogStorage` — неблокирующий health-флаг для failover-режима.
- `LoggerRuntime` теперь выбирает backend по `general.storageBackend` и dual-политике.
- `LoggerConfig` получил секцию `[clickhouse]` и dual-настройки.
- `build.gradle` использует `com.clickhouse:clickhouse-jdbc-all:0.9.8` через jarJar с `transitive = false`, чтобы не конфликтовать со строго зафиксированными зависимостями Minecraft/NeoForge (`commons-*`, `guava`, `lz4`).

## Таблица ClickHouse

Создаётся автоматически:

```sql
CREATE DATABASE IF NOT EXISTS avilix_logger;

CREATE TABLE IF NOT EXISTS avilix_logger.avilixlogger_actions
(
    id UInt64,
    ts_ms UInt64,
    dim LowCardinality(String),
    x Int32,
    y Int32,
    z Int32,
    action UInt16,
    actor_name LowCardinality(String),
    actor_uuid String,
    data String
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(toDateTime(intDiv(ts_ms, 1000)))
ORDER BY (dim, action, actor_name, ts_ms, id);
```

`data` хранит gzipped JSON в Base64. Остальные колонки нужны для быстрых фильтров GUI/команд.

## Рекомендованный запуск

1. Сначала поставить `storageBackend = "dual"`, `dualWriteMode = "mirror"`.
2. Запустить сервер и проверить, что таблица в ClickHouse создаётся.
3. Проверить GUI, `/log lookup`, rollback по блоку/радиусу.
4. После проверки перейти на `dualWriteMode = "failover"`, если MySQL нужен только как резерв.
5. Если ClickHouse стабилен и MySQL больше не нужен, можно переключить `storageBackend = "clickhouse"`.

## Важно

Локальная компиляция в контейнере не была подтверждена: Gradle wrapper попытался скачать Gradle 8.14 и упёрся в отсутствие/таймаут доступа к дистрибутиву. Код внесён, но первый запуск в IDE может потребовать подтянуть зависимости через Gradle.
