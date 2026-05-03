# AvilixLogger all-in-one build

В этом архиве собраны все последние правки:

- ClickHouse backend (`storageBackend=clickhouse`).
- Dual storage (`storageBackend=dual`).
- Mirror write mode: запись одновременно в ClickHouse и MySQL.
- Failover write mode: запись сначала в ClickHouse, если он недоступен — в MySQL.
- Переключаемое чтение для GUI/команд/rollback:
  - `dualReadMode="primary_fallback"` — читать основную БД, fallback использовать только при ошибке/пустом результате.
  - `dualReadMode="merge"` — читать обе БД, объединять результат и убирать дубли. Нужно, чтобы при переходе на ClickHouse не потерять старые MySQL-логи в просмотре.
- Единый генератор id для новых dual/failover-записей (`LogIdGenerator`), чтобы MySQL и ClickHouse использовали сопоставимые cursor-id для GUI-пагинации.
- Aeronautics / Sable / Simulated compat layer:
  - системные изменения блоков;
  - block entity NBT/inventory diffs;
  - Mounted Potato Cannon;
  - Hot Air Burner;
  - rollback-friendly snapshots.

## Рекомендуемый конфиг для плавного перехода

```toml
[general]
storageBackend = "dual"

[clickhouse]
dualPreferClickHouseReads = true

# mirror = писать в обе БД; failover = писать в ClickHouse, а MySQL использовать только при проблемах ClickHouse
dualWriteMode = "failover"

# primary_fallback = быстрее; merge = показывать данные из обеих БД одновременно
dualReadMode = "merge"
```

Когда старые MySQL-логи уже не нужны в GUI, можно вернуть:

```toml
dualReadMode = "primary_fallback"
```

## Важное замечание по старым логам

Старые MySQL-логи, созданные до этой версии, имеют обычные `AUTO_INCREMENT` id. Новые dual/failover-записи получают общий timestamp-based id и в MySQL, и в ClickHouse. Поэтому для периода миграции рекомендуется держать `dualReadMode="merge"`, а после перехода на ClickHouse — `primary_fallback`.
