# Supplementaries: тексты коврика, доски и указателей

Дата: 27.10.2026. Код/тесты: `0534d4d40e225872a5782d3527e3a4e2491a2b77`, дерево `e72a26704d44e87892a4d89e22b8f7b1c8ee1281`. База — `240d46f528279c7e5320802b81e3808e5b8697ab`, ветка `work/2.2.0-sign-edits-20261027`. Релизный JAR не создавался.

## Точный состав стенда

| Предоставленный мод | Версия | SHA-256 |
| --- | --- | --- |
| Supplementaries | 1.21.1-4.0.2 | `2c2e0c98e0cc21874d8b2130bc773a2a5a6cadd854b9c8163d0e1f32e2ddffb0` |
| Moonlight | 1.21.1-3.7.2 | `4a9f15108607cc70273f8a0b6eb24e1e99af8eef44bc3ae8681767ca7ce21b0f` |
| Amendments | 1.21-2.1.11 | `c9b80de3b6870c191543463ebba40117de957d797acf84d9355456da7ee74423` |

JAR Supplementaries объявляет NeoForge **[21.1.247,]** и Moonlight **[1.21.1-3.6.4,]**. Прежний локальный стенд 21.1.244 не соответствует первому ограничению. Этот отдельный прогон выполнен на Java 21 и **NeoForge 21.1.247**, без изменения дескрипторов чужих JAR. Это не утверждение о текущей версии production-сервера и не его автоматическое обновление.

В стенде также есть предоставленные Create 6.0.10, AE2 19.2.18 и GuideME для их зависимостей. Переданные JAR, полный состав сборки и журналы не публикуются. Локальный NeoForm использует отдельную копию инструмента с двумя fallback Java-path, поскольку ProcessHandle.info.command недоступен в среде; оригинал, Minecraft и авторизация не менялись.

## Найденный обход и новый захват

`ServerBoundSetTextHolderPacket.handle` фильтрует текст через common pool и передаёт результат в MinecraftServer executor. Его private `updateSignText(ServerPlayer,List)` работает с `ITextHolderProvider` и `TextHolder.acceptClientMessages`, минуя ванильный SignBlockEntity.setText. Обычного перехвата ванильной таблички для этого пути недостаточно.

Два optional mixin проверяются вместе по версии и raw bytecode без инициализации целей. Первый открывает scope только вокруг серверного callback после фильтрации. Второй лениво снимает before при первом acceptClientMessages — **после** native wax/claim checks. При отказе до setter снимков нет; при двух указателях общий before снимается один раз. После native callback снимается один after. Native вызов выполняется один раз, его исключение не перехватывается как успех; observer failure не повторяет и не заменяет действие мода.

Категории 1 и 2, logBlocks, AuthCore, реальный разрешённый actor, server thread и восстановление проверяются до собственного снимка. Перед after/publication флаги проверяются повторно. Уже зарезервированный откатом адрес блокирует packet mutation, включая выключенный захват; штатный restoring обход остаётся. Специальные Create structural/deployer observers сохраняются, их совместные снимки не подменяются отдельной строкой.

Поддержаны точные `DoormatBlockTile`, `NoticeBoardBlockTile` и `SignPostBlockTile`. Проверяются уже загруженный чанк, фактический BE и его идентичность после действия. Нет обхода соседей, capability lookup, extra ticking, принудительной загрузки чанков или повторного вызова native permission check. Scope удерживает Player/BE только синхронно; в очередь передаётся отделённая строка, SNBT-кодирование остаётся у фонового worker. Это структурные границы, не новый MSPT/heap benchmark. Блокирующее противодавление очереди при перегрузке остаётся открытым.

## Данные события и отображение

Создаётся `BLOCK_ENTITY_NBT_CHANGE`, source=`mod:supplementaries:text_edit`. UUID/имя относятся к настоящему ServerPlayer callback, координаты — к изменённому блоку, а не месту игрока. Полные BE before/after сохраняют остальное состояние; завершение native callback отражено в extra. Сравниваются только содержимое `TextHolder.message` и нормализованный `filtered_message`: root у коврика/доски, `SignUp` и `SignDown` у указателей. Отсутствующий filtered равнозначен raw. No-op, воск, поворот и цвет сами по себе не объявляются редактированием текста.

GUI показывает конкретные строки до/после, верхний и нижний указатели и фильтрованный вариант. Нативный NBT component codec используется с registry context; вывод — обычный literal text. Чужие click events из текста не исполняются. Отсутствующие/повреждённые данные дают пустое дополнение деталей. Ограничены размер SNBT и число отображаемых строк.

Параллельно исправлены guards общего SignTextAudit: отключение категорий 1 или 2 прекращает собственные BE-снимки; before/after учитывают logBlocks/AuthCore/restoring, after повторно проверяет серверный поток. Необходимые Create observers остаются отдельным контуром. Сбой наблюдателя учитывается ограниченной диагностикой, original setter не повторяется.

## Native-проверки

| Путь | Подтверждено на отдельных тестовых UUID/позициях |
| --- | --- |
| `supplementaries:doormat` | Один holder, настоящие private callback и public async packet, raw/filtered, журнал/детали |
| `supplementaries:notice_board` | Один holder, те же операции; полные BE-снимки и native load сохраняют текст |
| `supplementaries:way_sign` | Два holder, верх/низ изменены независимо и сохранены; настоящий native BE round trip |
| Повтор / воск / занятый редактор | Повтор не создаёт текстовой дельты; воск и реально присутствующий рядом чужой claimant отвергаются |
| Native claim semantics | Случайный UUID без entity очищается; при свободной записи принимается и далёкий ServerPlayer — исходное поведение Moonlight |
| Зарезервированный адрес | Packet не меняет текст во время отката при выключенном AuthCore; собственный перехват действительно применён |
| Незагруженный адрес | Callback возвращается без загрузки целевого чанка |
| Общая ванильная табличка с Amendments | Настоящие front/back updateSignText, wax refusal, формирование before/after деталей, no-op/glow separation и точный автор; прежний DEATH_SIGN_NATIVE_OK |
| Контракты | Оба mixin реально применены; удаление native updateSignText / acceptClientMessages отвергается fault-preflight |

Для native public packet fixture использует соединение в памяти с isAcceptingMessages=true и подавленной отправкой. Сам фильтр текста, common-pool этап, MinecraftServer executor и callback настоящие. Это модель подключённого транспорта, не обход AuthCore или native wax/claim checks. В ожидании ограниченного fixture callback исполняется server event loop; дополнительных production тиков Logger не добавляет. При поиске тестовых методов используются точные MethodHandle-сигнатуры, без загрузки всех клиентских renderer signatures TextHolder.

Получены три SUPPLEMENTARIES_TEXT_PROVIDER_NATIVE_OK, SUPPLEMENTARIES_TEXT_NATIVE_OK, SUPPLEMENTARIES_NATIVE_OK и DEATH_SIGN_NATIVE_OK. SUPPLEMENTARIES_NATIVE_FAILED/MixinApplyError/MixinTransformerError отсутствуют в итоговом прогоне; оба hook отмечены transformed debug log. AuthCore не обходился: **accepted=0, adapterFailures=[]**. Native механика, detached kernel и persistence проверялись отдельно от положительной авторизованной публикации.

## Хранение и проверки

Для этих BLOCK_ENTITY_NBT_CHANGE действует обычный storeRollbackDetails: его явное отключение по-прежнему учитывается.

Unit-проверки покрывают root и оба указателя, filtered-only, эквивалентный no-op, style/rotation/wax, повреждённую схему, обе отключённые категории, exact editor/coordinates и compressed journal. HTTP проверяет полные before/after при отключённых optional details и поиск по точному ID. Реальная ClickHouse проверяет те же значения, actor, координаты и ID/feed в minimal до и после повторного открытия базы.

Локально прошли compileJava, compileCreateSmokeJava, testAsyncPipeline, testExactModpackPreflight, verifyMixinSafety и инструменты inventory/profile comparison. Raw preflight: **161 mixin / 106 предоставленных target-классов**, включая два новых Supplementaries. Это не проверка конфликтов после преобразований всех 267 JAR. [Итоговые серверные профили и настоящая ClickHouse](https://github.com/Vlad-Ras/Logger/actions/runs/40407726508), [GUI](https://github.com/Vlad-Ras/Logger/actions/runs/40407726514). Все четыре серверных профиля, настоящая ClickHouse и GUI завершились успешно на указанном коде. SUPPLEMENTARIES_TEXT_CLICKHOUSE_OK получен дважды: до и после повторного открытия. После проверенной ревизии изменены только документация и комментарий, уточняющий native wax/claim semantics.

Повторение native-стенда:

```bash
./gradlew runServer -PsupplementariesSmoke \
  -PsupplementariesSmokeMods="$SUPPLEMENTARIES_SMOKE_MODS" \
  -Pneo_version=21.1.247 -PcompatCreateVersion=6.0.10-281 \
  -PcompatAe2Version=19.2.18 -PcompatFrameworkVersion=1.21.1-0.13.11
```

Нужны отдельный run-supplementaries, EULA, loopback-порт и disposable мир. Каталог содержит предоставленные Supplementaries/Moonlight/Amendments и необходимые зависимости. Fixture завершает сервер; успех проверяется по маркеру **и отсутствию failure**, не только exit code Gradle.

## Оставшиеся границы

Это три выбранных пути текстового пакета, не весь Supplementaries/Moonlight/Amendments. Открыты остальные блоки/меню/автоматизация, прямые серверные изменения TextHolder вне packet callback, Create display targets, книги/карты/страницы доски, поворот/мимикрия/чернила/смывание и другие совместные действия. Native BE save/load проверен; полный production rollback job на этих блоках и авторизованный emit → ClickHouse → GUI требуют отдельной приёмки.

Native callback при свободной записи принимает далёкого игрока. Logger сохраняет его реальную идентичность и исходное поведение мода, не добавляет и не заменяет серверные разрешения. Общие AuthCore/OP-проверки функций Logger не ослаблялись.

Полный модпак, native manual stop Scguns 1.5, queue backpressure, клиентский профиль памяти и A/B/A замеры фермы 8 пил + 8 активаторов/саженцы + portable storage interface остаются открытыми. Устранение прежних ~60 000 мкс/тик Observable не подтверждено.
