# AE2: отмена CPU без открытого меню

29.10.2026. Продолжение [отмены связанного заказа](AE2_REQUESTER_CANCEL_COMPATIBILITY_2.2.0.md). База `aa3c1a04741334a7f73335a6c83cb52aa1914d14`; ветка `work/2.2.0-ae2-cpu-cancel-20261029`. Проверенный код — `a14662fa87154236ea926732405af89765efc484`, tree `a14b3734668d0a0178261e2419d42d2d4b926ad8`. Выпускной JAR не создаётся.

## Изменение захвата

Прежний nexus hook намеренно пропускал CPU-инициированную отмену: native CraftingLink.cancel выставляет собственный canceled flag перед nexus.cancel. Это сохраняло одну UI запись, но прямые CraftingCPUCluster.cancelJob и CraftingCpuLogic.cancel без меню оставались без receipt.

Теперь существующий CraftingCpuLogicMixin оборачивает обе ветви finishJob. При success=true сохраняется прежний ME_CRAFT_FINISH. При success=false capture снимается до native операции и подтверждается только если прежний link стал canceled, не done. Исходный Operation получает тот же boolean и вызывается один раз; исходное исключение распространяется, доказанный переход перед ошибкой помечается NativeThrew. Scope очищает контекст и link в finally; повторный finish пуст. Текущий JobId и полный original finalOutput сохраняются до очистки native job. Нет ожидания Future, поиска recipes или искусственного вывода возвращённых предметов из planned amount.

Без UI основной адрес — CPU machine, actor нейтральный; сохраняются CPU bounds/dimension/name, native JobId, full key/ITEM, planned long output, StateTransition=cpu-cancelled-job, CancellationOrigin=cpu. Ближайший игрок, создатель старого заказа и открытое постороннее меню автором не назначаются.

CraftingCPUMenuMixin больше не создаёт второй receipt. Он устанавливает AEBaseMenu в добавленное к native logic поле только на время native cancelJob и восстанавливает предыдущее значение в finally. Общий finish(false) использует этот источник: UUID/имя текущего редактора, прежний адрес menu target и CancellationOrigin=cpu-menu. Вложенный вызов восстанавливает предыдущий источник. Это одна ссылка на CPU, null вне вызова, без ThreadLocal или постоянного job/player registry. Disabled AuthCore/category/logAe2 path не устанавливает источник; server-thread guard предшествует чтению CPU state. Runtime интерфейс и прежний nexus view добавлены в keep rules; обфусцированный выпуск в этом проходе не проверяется.

Сохранена дедупликация с requester hook: nexus уже canceled / link canceled или done отсекаются до CPU capture; CPU native link устанавливает свой флаг до nexus, и тот пропускает CPU-инициированное событие. Завершение после linked cancellation не создаёт ещё один cancel или ложный finish. Это доказанный протокол и проверенные data-only predicates; положительный authorized production append/dedup остаётся отдельной приёмкой.

## Native и storage сценарии

Тот же настоящий Interface/Crafting Card → Pattern Provider → Blast Furnace → Import Bus стенд выполняет уже семь автономных recipe jobs. Прежние card removal, requester API, nexus+fault и paired UI сохранены. Добавлены три различных JobId:

| Сценарий | Проверка |
| --- | --- |
| CPU API | Настоящий CraftingCPUCluster.cancelJob после отправки 2 raw iron в печь; paired links canceled, tracker освобождён; нейтральный actor и CPU address |
| Logic API | Настоящий CraftingCpuLogic.cancel в том же физическом processing route; правильный JobId, original planned 2 ingots и отсутствие второго receipt |
| Отмена и ошибка | Transformed finishJob(false) wrapper вызывает injected Operation один раз с false; Operation выполняет настоящий native cancel и бросает тот же Throwable; inspector выдаёт подтверждённую отмену с NativeThrew |

Fault без native перехода сохраняет working job и не выдаёт cancelled row. Running, idle, повторная отмена и успешное завершение также не создают ложного cancel. После каждого сценария уже отправленная руда штатно переплавляется: 2 ingots приходят в Interface stock через обычное пополнение, CPU idle, новый crafting job не появляется. Удаление карты после отмены предотвращает replacement craft request; обычный restock остаётся включён. Fixture не подменяет ICraftingPlan/links/CPU NBT и не вызывает native ticking вручную.

Семь data-only rows: шесть с нейтральным actor, одна с игроком. Три linked rows указывают requester, четыре CPU/UI rows — CPU. Проверяются original JobId, full item, planned long, оба failure markers, отсутствие false completion, FIFO через двухэлементную AsyncLogStorage очередь, Gzip, реальные LogText/details и отказ item rollback applied=0/skipped=1. UI источник устанавливается inspector fixture вручную и восстанавливается; это проверка transformed runtime view и data kernel, не положительный AuthCore hook route.

Существующий controlled-plan fixture дополнительно отменяет standalone CPU через реальный cancelJob: нейтральный actor, CPU coordinates, полный fluid key и planned 5 000 000 000. Количество прежних строк 14 (5 accepted/5 rejected/4 cancelled) сохраняется. Для успешного standalone completion CPU cancel inspector даёт null. Controlled plan не объявляется настоящим fluid recipe/машиной.

Pure journal/Gzip/worker checks и настоящая ClickHouse расширены CPU neutral и CPU-menu player cancellation с NativeThrew, Long.MAX_VALUE/full fluid key, точными actor/address/CPU/JobId, typed feed/details, minimal mode и новым подключением. Используются прежние ActionType/category/schema; права, команды и rollback политика не расширяются.

Raw preflight дополнительно требует ровно один путь CPU.cancelJob → logic.cancel → finishJob(false) → link.cancel. Отсутствующие методы, удалённый/дублированный вызов и true вместо false отклоняют всю optional интеграцию. Прежний link flag/order и все optional contracts остаются. Число mixin групп не меняется: 15/16 для 19.2.12/19.2.18.

## Проверки и стоимость

[Четыре серверных профиля и настоящая ClickHouse](https://github.com/Vlad-Ras/Logger/actions/runs/40851990097), [GUI](https://github.com/Vlad-Ras/Logger/actions/runs/40851990048) полностью успешны на указанном source commit, без повторной попытки. AE2_CPU_CANCEL_NATIVE_OK и расширенный AE2_REQUESTER_CANCEL_NATIVE_OK обязательны перед общим AE2_NATIVE_OK и получены на обеих версиях: семь автономных jobs, machineRows=6/uiRows=1/rows=7. AE2_CRAFTING_NATIVE_OK сохранил 14 строк и подтвердил direct standalone fluid cancel 5 000 000 000. Сохранены processing (3 rows/2 finish), assembly (7 rows/3 jobs/2 finish), grid (6 saturation cases/9 rows), remote (11/13 rows) и три server processes lifecycle (nativePause=false/true для 12/18).

AE2_CRAFTING_CLICKHOUSE_OK получен дважды с новыми CPU neutral и CPU-menu player cancellation/failure details после нового подключения. CART_GUI_CLIENT_OK: настоящая отрисовка history/actions/confirmation/results/help на 320×240, 640×360 и 960×540. Это общая storage/rendering регрессия, без заявления нового native authorized end-to-end. Пройдены compileJava/compileCreateSmokeJava/testAsyncPipeline/testCreatePreflight/verifyMixinSafety, Java 21 ProfileSummaryTest и Python inventory/profile tools, AE2 absence, Create, Create absence, Backpacked и actor/aircraft regressions. Локально выполнены Python inventory/profile comparison, git diff --check и разбор mixin JSON; полная локальная Java 21/Gradle/native сборка не засчитывается. Прежнее совпадение 1 826 классов присланного AE2 19.2.18 с публичным JAR сохраняется как историческое доказательство; новый transformed прогон всех 267 JAR не заявляется.

Новый CPU capture работает только в существующем native finishJob(false). При disabled capture выполняется прежний быстрый guard; при requester cleanup уже canceled link прекращает обработку до metadata/key snapshots. UI передаёт одну временную ссылку; сбор native ключа и координат остаётся на сервере, отделённые SNBT/JSON/journal/БД — в прежнем worker. Новых world/grid/slot/capability scans, tick subscriptions, forced chunks, map jobs или ThreadLocal нет. Реальные новые события увеличивают историю; bounded bytes/backpressure сохранены. Численный выигрыш MSPT/heap/latency не измерен.

Открыты raw CPU-link.cancel вне CPU finish, потерянный peer/node/requester и неизвестные topology callbacks, полная история причин expiry и pause/resume, прочие requesting machines/addons/fluid/multi-output recipes и virtual ME rollback. Native load invalid finalOutput не получает выдуманный key. Общий hook не доказывает все необычные пути чужих модов. Остаются production AuthCore → hook → ClickHouse → GUI, transformed весь модпак с configs/scripts, rendered remote terminal, Scguns 1.5 manual stop и A/B/A MSPT/heap/GC фермы 8 пил + 8 активаторов с саженцами + portable storage interface. Около 60 000 мкс/тик Observable не объявляются устранёнными. Поезда и вырезанный портал не возвращаются.
