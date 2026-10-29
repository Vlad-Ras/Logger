# AE2: внешняя обработка и автономный заказ интерфейса

29.10.2026. Продолжение [проверок сохранённого мира и отмены](AE2_LIFECYCLE_COMPATIBILITY_2.2.0.md). База `4ab772f082d0cb7e16a512e524d6874ff4c86c88`; ветка `work/2.2.0-ae2-processing-20261029`. Пакет расширяет dev acceptance/CI; production hooks, схемы, права и AuthCore не меняются. Релизный JAR не создаётся.

## Настоящий внешний маршрут

Ae2ProcessingNativeSmoke запускается после прежних native grid/assembly suites. Их assembler/Drive сначала закрываются, после чего в том же изолированном запитанном мире устанавливаются ванильная Blast Furnace под Pattern Provider, Import Bus под нижней стороной печи, новый Drive/Item Cell 1k и ME Interface. Добавленные узлы соединяются с power node явными dev connections; физический доступ provider → верхняя сторона печи и import bus → нижняя сторона печи выполняется штатными AE2 adapters/capabilities.

В RecipeManager проверяется настоящий blasting recipe raw iron → iron ingot. В provider находится native processing pattern с этим соотношением. Первая задача рассчитывается реальным beginCraftingCalculation: nonsimulation plan использует 2 raw iron, один processing pattern и планирует 2 iron ingots. Submit выполняется от MachineSource реального Interface block, без player и без requester. Данные admission inspector фиксируют адрес этой машины, нейтральный actor и native CPU/JobId.

Ванильная печь и узлы AE2 работают обычными игровыми тиками. Готовые слитки не вставляются вручную; serverTick/tickingRequest/tickCraftingLogic и методы исполнения processing pattern не вызываются напрямую. Подготовка стенда вносит только исходные ресурсы, уголь и конфигурацию. До запуска дополнительно проверяется, что верхний native ItemHandler принимает raw iron в SIMULATE и не меняет input slot. Горизонтальная сторона ванильной печи предназначена для топлива; ingredient route направлен сверху. Native Future проверяется на следующих dev server ticks и читается get(0, MILLISECONDS) только после isDone.

| Сценарий | Проверяемый результат |
| --- | --- |
| Нет топлива | CPU уже передал raw iron во внешнюю печь. 30 dev ticks без топлива не дают результата или завершения; CPU остаётся busy |
| Заблокирован возврат | Import Bus имеет настоящий filter на diamond. Печь производит iron ingot, но он остаётся в output slot; в cell и Interface нет результата, CPU остаётся busy. После 10 тиков filter снимается штатной конфигурацией |
| Первая доставка | В cell оказывается 1 ingot из запрошенных 2; CPU остаётся busy. Completion inspector не выдаёт finish, исходный planned output остаётся 2 |
| Полное завершение standalone job | Печь/return buffer пусты, cell хранит ровно 2 ingots, raw iron не осталось; Interface stock пуст. ME_CRAFT_FINISH с прежним JobId и адресом CPU хранит planned amount 2 |

Это внешний processing route, а не crafting grid Molecular Assembler. Выбран только item-only blasting recipe. Проверка не объявляет покрытыми все сторонние машины и их побочные эффекты.

## Настоящий автономный requester

После завершения первой задачи её два слитка извлекаются при dev reset, в cell вносятся ещё 2 raw iron. В настоящий Interface устанавливается Crafting Card, stock slot настраивается на 2 iron ingots. Дальше fixture не вызывает beginCraftingCalculation/submitJob от имени этого заказа и не подменяет tracker: расчёт и admission инициирует штатный InterfaceLogic/MultiCraftingTracker на обычных AE2 ticks.

После начала задания проверяются настоящий requester link в Interface.getRequestedJobs и отдельный CPU link: разные объекты с одним новым native UUID, оба non-standalone. Новый UUID отличается от предыдущего standalone job. До первой доставки stock/cell пусты. После первого слитка stock=1, cell=0, CPU busy и requester link ещё не done. Completion inspector по-прежнему не выдаёт преждевременный finish.

В конце Interface stock содержит ровно 2 iron ingots, cell не получает эти результаты, raw iron израсходовано, печь/return buffer пусты, CPU idle. Requester link done, не canceled; getRequestedJobs пуст после настоящего terminal callback. Ещё 30 активных dev ticks не создают нового job или дополнительных слитков. Вторая строка завершения хранит новый JobId, нейтральный actor, CPU address и исходный planned amount 2.

Autonomous admission проверяется по настоящему состоянию CPU/requester и балансу, но его отдельная admission строка этим dev fixture не перехватывается: production capture выключен и не обходится. Для первого реально рассчитанного standalone job применяется data-only admission inspector с MachineSource; для обоих реальных завершений — completion inspectors. Всего три receipt: один machine-source request и два CPU finish. Это не утверждение о положительном AuthCore → hook → DB append для автономного заказа.

## Данные и стоимость

Три отделённые строки проходят существующую двухэлементную AsyncLogStorage очередь с byte limit, FIFO, worker materialization, полный ITEM/evidence и Gzip. Проверяются neutral actor, iron_ingot resource, planned long amount 2, исходный JobId/CPU и отсутствие внутреннего prefix в реальном LogText. Настоящий RollbackEngine отказывается выдавать или изымать предметы по job receipts: applied=0/skipped=1.

Production ME_CRAFT_FINISH по-прежнему означает завершение CPU job и planned-output; фактическая доставка в конкретный cell/Interface устанавливается отдельными native balance assertions. Native key/NBT снимается существующим helper на серверном потоке, SNBT/JSON/journal/БД остаются в прежнем worker пути. Новых production тиков, polling world/grid/inventories, retained job registry или дополнительных операций других модов Logger не вводит. Работа и polling этого dev fixture не входят в production source set. Численные MSPT/heap/allocation результаты не заявляются.

## Проверки

Проверенный код — `b59dbac9e88da1f8266ce1ccafdd123dc0435d74`, tree `94a3fbc7c3d3f4c5b0123c6d76394de366dda1ec`. [Четыре серверных профиля и настоящая ClickHouse](https://github.com/Vlad-Ras/Logger/actions/runs/40835856393), [GUI](https://github.com/Vlad-Ras/Logger/actions/runs/40835856463). Четыре серверных профиля, настоящая ClickHouse и GUI полностью успешны; новые native processing/requester steps прошли на AE2 19.2.12/19.2.18.

Обязательный AE2_PROCESSING_NATIVE_OK допускается только после всех перечисленных состояний и worker/rollback проверок. Это rows=3, finished=2: один machine-source admission с настоящим планом и два фактических CPU завершения. У автономного заказа проверяются native linked requester/CPU state и stock; его admission receipt не наблюдался. Прежний AE2_NATIVE_OK теперь печатается после нового сценария, поэтому старые этапы не могут объявить весь native suite успешным до processing acceptance.

В обоих текущих native 19.2.12/19.2.18 логах AE2_PROCESSING_NATIVE_OK подтверждает rows=3/finished=2 и перечисленные реальные балансы. Сохранены прежние 14 crafting receipts (5 accepted/5 rejected/4 cancelled), 7 assembly rows/3 jobs/2 finish, 6 grid saturation cases/9 rows и remote 13 rows/128 full candidates. На 19.2.12 remote quickMove отсутствует и прежних remote rows 11. Повторно прошли выбранные трёхпроцессные lifecycle cases: active job на 19.2.12, paused job на 19.2.18 и idle reload.

Настоящая ClickHouse повторно дала AE2_CRAFTING_CLICKHOUSE_OK дважды: accepted/refused/cancelled/finished, full key, planned long, actor/CPU/JobId, exact feed/details, minimal mode и новое подключение. GUI дал CART_GUI_CLIENT_OK для history/actions/confirmation/results/help на 320×240, 640×360, 960×540. Это общие regression contracts хранения/отображения; три новых native processing receipts не выдаются за положительный production AuthCore → hooks → ClickHouse → GUI append.

Локально прошли Python inventory/profile comparison, git diff --check и JSON mixin configs. Полная локальная Gradle/native сборка не засчитывается. Java 21 ProfileSummaryTest и явные compileJava/compileCreateSmokeJava/testAsyncPipeline/testCreatePreflight/verifyMixinSafety прошли в CI. Также успешны AE2 absence на current-fd134, Create, Create absence, Backpacked и actor/aircraft regressions. Прежнее raw доказательство равенства 1 826 классов присланного/публичного AE2 19.2.18 не повторяется и не подменяет transformed запуск всех 267 JAR.

Первая ревизия dev стенда 328f934 направляла raw iron в горизонтальную fuel face печи и получила timeout phase=3 с пустым furnace inventory. Исправление направляет provider сверху и заранее проверяет SIMULATE insert через настоящий ItemHandler без изменения input slot. Правила стороннего инвентаря не обходятся; production hooks не изменены.

## Открытые границы

Проверен выбранный реальный Interface с Crafting Card и item-only processing через vanilla Blast Furnace/Import Bus. Не закрыты все requesting machines/export buses/addons, fluid/chemical/multi-output processing, другие smithing/stonecutting routes, непрерывное изменение demand во время работы, автоматическая отмена requester и отдельная её история, внешние составные или переполненные storage, chunk unload/load и все crash/restart interruption points. Отдельная история pause/resume и виртуальный ME rollback остаются открытыми.

Положительная production приёмка с AuthCore, полным модпаком и его конфигами/скриптами, отрисованный remote терминал, native Scguns 1.5 manual stop и A/B/A MSPT/heap/GC фермы 8 пил + 8 активаторов с саженцами + portable storage interface этим не заменяются. Прежние около 60 000 мкс/тик Observable не объявляются устранёнными. Поезда и вырезанный портал не возвращаются в scope.
