# Runtime DB driver fix

The previous archive bundled DB libraries with `jarJar`, but NeoForge dev runs (`runClient` / `runServer`) load the mod from `build/classes` instead of the final jar. In that mode nested jarJar libraries are not guaranteed to be present on the normal runtime classpath.

Fixed by adding `localRuntime` entries for:

- `com.zaxxer:HikariCP:5.1.0`
- `com.mysql:mysql-connector-j:8.0.33`
- `com.clickhouse:clickhouse-jdbc-all:0.9.8` with `transitive = false`

Also added explicit driver loading for MySQL and ClickHouse before creating connections/pools.
