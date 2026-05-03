# ClickHouse split-schema logging

## Цель

Эта версия переводит ClickHouse backend с одной общей таблицы `avilixlogger_actions` на набор специализированных таблиц. Это уменьшает вес БД и ускоряет GUI/rollback-запросы, потому что ClickHouse читает только нужные колонки и только нужный домен событий.

## Таблицы

При `schemaMode = "split"` создаются таблицы с префиксом `tablePrefix`:

- `<prefix>_blocks` — блоки и block entity NBT;
- `<prefix>_containers` — открытия контейнеров, PUT/TAKE, slot snapshots;
- `<prefix>_entities` — spawn/death/mount/interact/owner;
- `<prefix>_items` — drop/pickup/craft/smelt;
- `<prefix>_players` — join/leave/death и player inventory snapshots;
- `<prefix>_chat` — чат;
- `<prefix>_compat` — Create trains, planes, Aeronautics и другие compat-события.

Старая таблица `table = "avilixlogger_actions"` больше не используется для новых ClickHouse-записей в split-режиме, но может читаться для миграции.

## Рекомендованный конфиг

```toml
[general]
storageBackend = "dual" # или clickhouse после проверки
keepDays = 30

[clickhouse]
url = "jdbc:clickhouse:http://127.0.0.1:8123/default"
database = "avilix_logger"

# legacy table для чтения старых CH-логов
table = "avilixlogger_actions"

# новый режим
schemaMode = "split"
tablePrefix = "avilixlogger"
readLegacyUnifiedTable = true
useZstdCodec = true

batchSize = 10000
flushIntervalMs = 1000
queueCapacity = 1000000
selectQueryTimeoutSec = 5
asyncInsert = true
waitForAsyncInsert = true

# Для переходного периода с MySQL:
dualWriteMode = "failover"
dualReadMode = "smart_merge"
dualPreferClickHouseReads = true
```

## Как работает чтение GUI

- Если выбран конкретный тип действия, GUI читает только таблицу этого типа.
- Если выбран блок/радиус, запросы по блокам и контейнерам попадают в таблицы, отсортированные по `(dim, action, x, z, y, ts_ms, id)`.
- Если тип не выбран, используется `UNION ALL` по split-таблицам с внутренним `ORDER BY id LIMIT`, чтобы не вытягивать лишние миллионы строк.
- При `readLegacyUnifiedTable = true` старые записи из `avilixlogger_actions` остаются видимыми.

## Почему вес меньше

В старой схеме каждая строка хранила `data = Base64(GZIP(JSON(LogEntry)))`. Это удобно, но плохо для ClickHouse: база не видит отдельные поля, не может эффективно сжимать повторяющиеся SNBT/JSON по колонкам и не может пропускать лишние колонки.

В split-схеме частые поля вынесены в нормальные колонки, а тяжёлые SNBT/JSON-колонки есть только в тех таблицах, где они нужны. Для больших строк включён `CODEC(ZSTD(3))`.

## Миграция

1. Запустить с `storageBackend = "dual"`, `dualWriteMode = "failover"`, `dualReadMode = "smart_merge"`.
2. Проверить GUI: блоки, контейнеры, чат, самолёты/поезда/Aeronautics, rollback.
3. После проверки можно переключить `storageBackend = "clickhouse"`.
4. Старые MySQL-логи остаются видимыми в `dualReadMode = "smart_merge"` или `dualReadMode = "merge"`.
