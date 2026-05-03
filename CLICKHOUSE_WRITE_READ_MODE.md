# ClickHouse/MySQL read-write mode

Recommended migration mode:

```toml
[general]
storageBackend = "dual"

[clickhouse]
dualPreferClickHouseReads = true
dualWriteMode = "primary_only"
dualReadMode = "smart_merge"
```

Behavior:

- New log rows are written only to the preferred storage. With `dualPreferClickHouseReads=true`, this is ClickHouse.
- MySQL is kept opened only as a read source, so old pre-migration logs remain visible in `/log`, GUI, rollback lookup, etc.
- `dualReadMode=smart_merge` first queries ClickHouse. If ClickHouse returns a full requested page, MySQL is not queried. If ClickHouse fails or returns fewer rows than requested, the logger queries MySQL, deduplicates rows, sorts them, and returns one combined page.
- `dualReadMode=merge` is still supported and always queries both ClickHouse and MySQL. Use it only when you need the safest full merge and accept the extra load.
- `dualReadMode=primary_fallback` is still supported and only uses MySQL when ClickHouse fails or returns nothing.
- ClickHouse startup now prints an explicit console line: `ClickHouse connected successfully ...`.

If an existing config was already generated before this patch, edit `config/avilixlogger-common.toml` manually; NeoForge will not overwrite old values just because defaults changed.
