# ClickHouse Gradle dependency fix

Исправлена зависимость ClickHouse JDBC для NeoForge/Minecraft 1.21.1.

Проблема была в том, что `com.clickhouse:clickhouse-jdbc:0.9.8` и classifier `:all` всё равно подтягивали транзитивные зависимости из POM:

- `commons-compress:1.28.0`
- `commons-codec:1.19.0`
- `commons-io:2.20.0`
- `commons-lang3:3.20.0`
- `guava:33.x`
- `at.yawk.lz4:lz4-java`

NeoForge/Minecraft 1.21.1 держит свои версии через `strictly`, поэтому Gradle не мог выбрать совместимую версию.

Теперь используется shaded all-in-one artifact без транзитивных зависимостей:

```gradle
jarJar(implementation("com.clickhouse:clickhouse-jdbc-all:0.9.8") {
    transitive = false
}) {
    version { prefer '0.9.8' }
}
```

Это оставляет внутри jar только готовый ClickHouse JDBC all-in-one jar и не пытается подмешивать его зависимости в общий compileClasspath.
