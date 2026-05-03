# ClickHouse HTTP fix

ClickHouse backend no longer uses `com.clickhouse:clickhouse-jdbc*`.

Reason: ClickHouse JDBC 0.9.x can enter NeoForge's JPMS module layer as modules like `com.clickhouse.client`, which then require `com.clickhouse.data` and crash startup:

```text
java.lang.module.FindException: Module com.clickhouse.data not found, required by com.clickhouse.client
```

The backend now uses ClickHouse HTTP API through JDK `HttpURLConnection`. This removes ClickHouse JDBC dependencies and avoids JPMS / Guava / commons / lz4 conflicts.

Existing config remains compatible:

```toml
[clickhouse]
url = "jdbc:clickhouse:http://127.0.0.1:8123/default"
```

The logger strips `jdbc:clickhouse:` and internally uses `http://127.0.0.1:8123/`.
