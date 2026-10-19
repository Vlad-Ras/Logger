# Исходники AvilixLogger 1.2.22

Архив содержит исходный код, Gradle Wrapper, ресурсы, конфигурацию обфускации, регрессионные проверки и GitHub Actions. Сборочная база — актуальная ветка исправлений 2.1.22; номер 1.2.22 выбран по запросу владельца проекта. Список изменений и ограничения: `docs/RELEASE_1.2.22.md`.

## Сборка

Нужны JDK 21 и доступ к Maven-репозиториям, указанным в `build.gradle`.

```sh
chmod +x gradlew
./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx3G clean build compileCreateSmokeJava
```

Готовый обфусцированный мод: `build/libs/avilixlogger-1.2.22.jar`. Не устанавливайте одновременно старую и новую версию одного мода. Для GUI нужен этот мод на сервере и клиенте; пользователи без клиентского мода сохраняют командный доступ.

Версии Minecraft/NeoForge и зависимостей закреплены в `gradle.properties` и `build.gradle`. Прежние журналы, идентификаторы действий и структуры квитанций сохранены; файлы игрового мира и настройки сервера в архив не включены.

## Дополнительные проверки

```sh
./gradlew testAsyncPipeline
# Нужен локальный тестовый ClickHouse с пользователем/паролем logger_test:
AVILIX_TEST_CLICKHOUSE_URL=http://127.0.0.1:8123 ./gradlew testCartOwnerDatabase
# Нативный Create: стандартный запуск dev-сервера после принятия EULA в run/eula.txt:
./gradlew -PcreateSmoke runServer
# Проверка настоящего клиента; на Linux без дисплея нужен Xvfb:
./gradlew -PcreateSmoke -PguiSmoke runClient
```

Проверки Create находятся в отдельном source set и не входят в поставляемый JAR. Полный воспроизводимый порядок — `.github/workflows/build.yml`. Проверки не отключают авторизацию рабочего мода и не выдают административные права игровому пользователю.
