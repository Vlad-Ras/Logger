# Gradle fix 2

Исправлена Gradle 8.14+ deprecation warning из `problems-report.html`:

```gradle
maven { url "https://api.modrinth.com/maven" }
```

заменено на:

```gradle
maven { url = uri("https://api.modrinth.com/maven") }
```

Также при запуске из IDE не держи старый jar этого же мода в `run/mods`, иначе NeoForge видит два `avilixlogger`:
- `build/classes/java/main`
- `run/mods/avilixlogger-1.7.0.jar`

Для dev-запуска старый `run/mods/avilixlogger-1.7.0.jar` лучше удалить.
