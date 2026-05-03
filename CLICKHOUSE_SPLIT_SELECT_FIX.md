# ClickHouse split SELECT fix

Исправлена ошибка чтения split-схемы ClickHouse:

```text
Unknown expression identifier entity_type ... FROM avilix_logger.avilixlogger_players
```

Причина: таблица `avilixlogger_players` не содержит колонку `entity_type`, но общий SELECT для split-таблиц пытался читать `entity_type AS entity_type` и из player-таблицы.

Что изменено:

- для `avilixlogger_players` теперь выбирается `'' AS entity_type`;
- `entity_uuid`, `entity_nbt`, `player_inv_before`, `player_inv_after` продолжают читаться из `avilixlogger_players`;
- запись в `avilixlogger_players` больше не отправляет лишнее поле `entity_type`.

Миграция БД не нужна. Достаточно заменить jar/проект и перезапустить сервер.
