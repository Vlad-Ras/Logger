# ClickHouse Stage 2 optimization

## Что изменено

- Добавлена лёгкая таблица `avilixlogger_feed` для GUI/lookup-страниц.
- Тяжёлые данные rollback/details остаются в split-таблицах:
  - `avilixlogger_blocks`
  - `avilixlogger_containers`
  - `avilixlogger_entities`
  - `avilixlogger_items`
  - `avilixlogger_players`
  - `avilixlogger_chat`
  - `avilixlogger_compat`
- В таблицы добавлены координатные поля:
  - `chunk_x`, `chunk_z`
  - `region_x`, `region_z`
- GUI-выдача идёт через `feed`, поэтому обычный просмотр не читает SNBT/NBT/diff-поля.
- `queryReverse`, JSON/details и rollback читают detail-таблицы.
- Если details для non-rollback события отключены, GUI-details получает лёгкую строку из `feed`.
- Добавлены включаемые ClickHouse skipping indexes.
- Добавлены разные retention-настройки для feed/details/chat/compat.
- Добавлен `detailMode`: `minimal`, `balanced`, `full`, `debug`.

## Рекомендованный конфиг

```toml
[general]
storageBackend = "dual"
keepDays = 30

[clickhouse]
schemaMode = "split"
useFeedTable = true
detailMode = "balanced"

storeRollbackDetails = true
storeNonRollbackDetails = false

feedKeepDays = 90
detailKeepDays = 30
chatKeepDays = 14
compatKeepDays = 60

addSkippingIndexes = true

dualWriteMode = "failover"
dualReadMode = "smart_merge"
dualPreferClickHouseReads = true
```

## Режимы detailMode

- `minimal` — хранит только rollback-критичные details.
- `balanced` — рекомендованный режим: rollback-критичные details + лёгкий feed для остального.
- `full` — хранит full details для всех событий.
- `debug` — как full, но предназначен для временной диагностики.

## Важный смысл новой схемы

Обычная страница GUI больше не сканирует тяжёлые SNBT/JSON/NBT поля.
Сначала открывается лёгкий список из `feed`, а полные данные читаются только при details/JSON/rollback.
