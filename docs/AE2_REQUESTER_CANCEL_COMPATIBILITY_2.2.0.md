# AE2: отмена связанного заказа машиной

29.10.2026: [прямая отмена CPU AE2 и единый UI источник](AE2_CPU_CANCEL_COMPATIBILITY_2.2.0.md): общий finishJob(false) закрывает direct CPU/logic API, временный menu source сохраняет редактора без второй UI строки; linked cleanup отсекается по прежнему canceled link. Семь автономных native jobs, standalone fluid long и storage проверки; production AuthCore и численные замеры остаются открытыми. Ниже — предыдущие этапы.

29.10.2026. Продолжение [автономного заказа и внешней обработки](AE2_PROCESSING_COMPATIBILITY_2.2.0.md). База `0074a306b90bb57192691e14eb1dad6e96a935b6`; ветка `work/2.2.0-ae2-requester-cancel-20261029`. Пакет добавляет production захват linked cancellation, native acceptance и проверки хранения; релизный JAR не создаётся.

## Исправленный пропуск

Прежний ME_CRAFT_CANCEL создавался через CraftingCPUMenu.cancelCrafting. Удаление Crafting Card и вызов requester link.cancel отменяют настоящий job через CraftingLinkNexus и не обязаны проходить через меню CPU. Теперь новый CraftingLinkNexusMixin оборачивает native cancel(), а CraftingLinkAccess открывает только requester/CPU references и исходный canceled flag. Постоянного реестра jobs, поиска по сетям или тиков Logger нет.

Перед чтением nexus действуют существующие category/logAe2/Logger/AuthCore guards и проверка server thread. Затем проверяются постоянное число ссылок/флагов: nexus ещё не canceled/done, обе native links присутствуют, CPU link принадлежит текущему CraftingCPUCluster job, UUID обоих links совпадают, requester существует. После штатной операции строка появляется только при подтверждённой отмене nexus и обоих links, без done. Scope очищается в finally, повторный finish пуст. Исходная операция вызывается один раз, её исключение сохраняется; подтверждённая отмена перед исключением получает NativeThrew.

У CPU-инициированной отмены CraftingLink.cancel выставляет собственный canceled flag перед вызовом nexus.cancel. Новый захват отсекает этот случай до снимка key/CPU metadata: существующая UI отмена сохраняет игрока и свою строку. Этот протокол дополнительно проверяется raw preflight: ровно один setCanceled(true), ровно один nexus.cancel и первый вызов перед вторым. Standalone link не имеет такого paired nexus и не получает ложную машинную строку.

Для принятой linked cancellation основной адрес — requester machine/part, actor нейтральный. Ближайший игрок или текущий открытый UI автором не назначаются. В details находятся прежний native JobId, CPU dimension/bounds/name, полный key/ITEM, planned long output, StateTransition=linked-job-cancelled и CancellationOrigin=linked-job. Это общий переход связи; точная причина вроде network expiry не выдумывается из одного флага. Удаление карты игроком отдельно видно в существующей истории изменения upgrade; новая автоматическая строка не подменяет этот actor.

Используется прежний ActionType.ME_CRAFT_CANCEL и существующий source/category/preset/storage путь. Новых enum ordinals, схем БД, прав или команд нет. Для cancelled+NativeThrew GUI details теперь пишет «Отмена подтверждена, затем операция завершилась ошибкой», а не описание создания задания.

## Четыре настоящих автономных задания

Ae2RequesterCancelNativeSmoke продолжает реальный Interface/Crafting Card → Pattern Provider → vanilla Blast Furnace → bottom Import Bus route предыдущего пакета. Каждый job рассчитывает и запускает сам InterfaceLogic/MultiCraftingTracker. Fixture не подменяет ICraftingPlan, links или CPU/NBT и не вызывает native ticking вручную. Верхняя сторона печи получает 2 raw iron, нижняя сторона отдаёт результат. Перед каждой отменой оба ингредиента уже в furnace input, output/stock ещё пусты.

| Сценарий | Native действие и доказательство |
| --- | --- |
| Удаление Crafting Card | Настоящее изменение upgrade inventory вызывает MultiCraftingTracker.cancel → requester link.cancel → nexus.cancel. Оба links canceled, requester освобождает job |
| API requester | Прямой вызов существующего requester link.cancel отменяет тот же CPU job и освобождает native requester link |
| Nexus и последующее исключение | Вызван настоящий nexus.cancel, затем injected Operation бросает исходное исключение. Transformed wrapper вызывает Operation один раз и сохраняет тот же Throwable; inspector сохраняет подтверждённую отмену с NativeThrew |
| CPU UI с paired requester | Настоящий client action cancelCrafting отменяет non-standalone job. Прежний UI inspector выдаёт одну строку с UUID игрока и адресом CPU; native cancellation flag/order и повторный finished inspector проверяются отдельно |

Три machine rows имеют нейтрального actor и адрес Interface; четвёртая UI row имеет actor игрока и адрес CPU. У всех разные реальные JobId, исходный planned output 2 ingots и CPU evidence. Running nexus, повторная отмена, уже canceled nexus, idle CPU и completion после отмены не дают дополнительных inspector receipts. Disabled и off-thread capture проверяются poison state, getters которого бросают при любом чтении.

Отмена не отзывает уже переданную в печь руду. Native печь продолжает работу и создаёт 2 ingots; crafting card удаляется, чтобы не возник замещающий craft job, но обычное пополнение Interface stock из сети остаётся доступным. Поэтому к концу каждого сценария CPU idle, native requested jobs пусты, furnace input/output пусты, raw iron=0, cell ingots=0, Interface stock=2. Это обычная доставка уже произведённых ресурсов после отмены, а не успешное завершение отменённого CPU job. Ещё 25 dev ticks подтверждают этот выбранный баланс без возрождения задания.

Dev reset очищает только известный stock прошлого законченного сценария и вносит новые 2 raw iron/уголь. Готовые изделия не вставляются для имитации производства. Reflection в dev fixture читает native tie и вызывает преобразованный wrapper/существующий nexus.cancel для fault scenario; production доступа через reflection не добавляется.

## Данные, стоимость и границы проверки

Четыре data-only строки проходят существующую двухэлементную AsyncLogStorage очередь с byte limit, полным ITEM/evidence, planned long, уникальными JobId, ordered fault marker и Gzip. Реальные LogText/Details сохраняют читаемые подписи; настоящий rollback kernel отказывает выдаче/изъятию предметов по job receipts: applied=0/skipped=1. Pure core/journal и ClickHouse fixtures расширены нейтральной машинной отменой, StateTransition/CancellationOrigin и NativeThrew, включая minimal mode и повторное подключение.

Native scopes в dev создаются до инициирующего действия и завершаются после него; production scope живёт только внутри native nexus.cancel. AuthCore не обходится, production capture выключен. Число строк относится к data-only inspectors; положительный authorized hook append и фактическая production дедупликация с UI ещё требуют приёмки. Raw contract/order и преобразованные native wrappers проверяются отдельно; это не полный end-to-end производственный прогон.

Новый hook вызывается только при штатной отмене nexus. Известные ссылки читаются за постоянное число операций; native key/CPU снимок остаётся на сервере, отделённый SNBT/JSON/journal/БД — в прежнем worker пути. Новые корректные cancellation rows увеличивают историю относительно пропущенных событий, с прежним byte accounting/backpressure. Численные MSPT/allocation/heap результаты этим не устанавливаются. Production не получает world/grid/slot scans, capability lookup, forced chunk, tick subscription, job registry или ThreadLocal.

Raw preflight проверяет два новых mixin, все Shadow/Accessor поля и описанный cancel flag/order protocol. Удаление cancel methods, каждого используемого поля, изменение аргумента true или порядка вызовов должны отключить всю optional AE2 интеграцию. Поддержаны по-прежнему только 19.2.12/19.2.18; это 15/16 mixin групп по версии.

## Проверки

Проверенный код — `8623ec100bce1368e9f8c9a1434c22e8201e86ed`, tree `27e677e9921c36e8df41b79a748d2d1110e24c5a`. [Серверная матрица и настоящая ClickHouse](https://github.com/Vlad-Ras/Logger/actions/runs/40842157575); [GUI](https://github.com/Vlad-Ras/Logger/actions/runs/40842157403). Четыре серверных профиля, настоящая ClickHouse и GUI полностью успешны; новые native cases прошли на обеих версиях AE2.

AE2_REQUESTER_CANCEL_NATIVE_OK обязателен в CI до общего AE2_NATIVE_OK: four autonomous jobs, machineRows=3/uiRows=1/rows=4, разные реальные JobId, card removal/API/nexus+fault/UI, работа печи после отмены, poison guards, worker/Gzip/typed details и отказ item rollback. Предыдущие AE2_PROCESSING_NATIVE_OK (3 rows/2 finish), assembly (7 rows/3 jobs/2 finish), crafting (14 rows), grid (6 saturation cases/9 rows), remote (11/13 rows по версии) и lifecycle acceptance сохраняются.

ClickHouse уже дал AE2_CRAFTING_CLICKHOUSE_OK дважды: accepted/refused/cancelled/finished planned long output, full key, actor/CPU/job identity и новые neutral machine cancel/failure details в minimal mode после нового подключения. CART_GUI_CLIENT_OK проверил history/actions/confirmation/results/help на 320×240, 640×360, 960×540. Эти общие storage/rendering регрессии не объявляются положительным authorized end-to-end для новых native hooks.

Локально прошли Python inventory/profile comparison, git diff --check и JSON mixin configs. Java 21 ProfileSummaryTest и compileJava/compileCreateSmokeJava/testAsyncPipeline/testCreatePreflight/verifyMixinSafety прошли в CI. Пройдены также AE2 absence на current-fd134, Create, Create absence, Backpacked и actor/aircraft regressions. Полная локальная Gradle/native сборка этим проходом не засчитывается. Прежнее доказательство совпадения 1 826 классов присланного/публичного AE2 19.2.18 не повторяется и не является новым transformed прогоном всех 267 JAR.

## Открытые границы

Новый захват охватывает связанный nexus с доступными requester/CPU и совпадающим текущим job. Потерянный requester/node/peer, неизвестные callbacks, direct CPU.cancelJob вне UI, standalone non-UI cancellation и все network expiry/topology случаи этим не объявляются закрытыми. Точная причина отмены не выводится из эвристики, а автоматическая строка не получает неподтверждённого автора. Отдельная история pause/resume, остальные requesting machines/addons/fluid/multi-output routes и виртуальный ME rollback остаются открытыми.

Остаются production AuthCore → hooks → ClickHouse → GUI, transformed приёмка полного модпака с конфигами/скриптами, отрисованный remote терминал, native Scguns 1.5 manual stop и A/B/A MSPT/heap/GC фермы 8 пил + 8 активаторов с саженцами + portable storage interface. Прежние около 60 000 мкс/тик Observable не объявляются устранёнными. Поезда и вырезанный портал в scope не возвращаются.
