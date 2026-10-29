# Совместимость серверного модпака: текущая работа

29.10.2026: [отмена связанного заказа AE2 машиной](AE2_REQUESTER_CANCEL_COMPATIBILITY_2.2.0.md): новый nexus cancel hook сохраняет requester/CPU/JobId и planned output с нейтральным actor, подтверждённая отмена перед ошибкой получает NativeThrew; CPU flag/order protocol защищает существующую UI запись. Четыре native cases дополняют external processing; production AuthCore приёмка остаётся открытой. Ниже — предыдущие этапы.

29.10.2026: [внешний processing route и автономный заказ ME Interface](AE2_PROCESSING_COMPATIBILITY_2.2.0.md): реальная Blast Furnace/Import Bus, ожидание топлива и возврата, частичная доставка и сохранение исходного planned amount; выбранный Interface с Crafting Card инициирует собственный non-standalone job и получает результат в stock. Это dev acceptance без изменения production hooks; ниже — предыдущие этапы.

29.10.2026: [отмена начатой сборки и сохранённый мир AE2](AE2_LIFECYCLE_COMPATIBILITY_2.2.0.md): отдельный dev/CI стенд с тремя server processes проверяет mid-job cancel, native JobId/inventories после disk save/load и завершённое idle состояние при следующем restart. В 19.2.12 сохраняется работающий job, в 19.2.18 — job на штатной паузе; in-flight assembler продолжает уже принятый рецепт. Production capture/авторизация не меняются; ниже — предыдущие этапы.

28.10.2026: [настоящий расчёт рецептов, Molecular Assembler и завершение CPU задания](AE2_ASSEMBLY_COMPATIBILITY_2.2.0.md): ME_CRAFT_FINISH с native JobId/CPU и planned-output; проверены log → planks → 8 sticks, honey block + 4 glass bottle remainders и отмена с возвратом 2 logs. Обе версии AE2: 7 новых строк, 3 jobs, 2 завершения. Production AuthCore и остальные рецепты/машины остаются в приёмке; ниже — предыдущие этапы.

28.10.2026: [приём заказов автокрафта и подтверждённая UI отмена AE2](AE2_CRAFTING_COMPATIBILITY_2.2.0.md): actual CPU/native JobId, source address/actor, полный ключ и planned-output long, реальные errorCode и partial failure. Два append-only типа; обе версии AE2 дали по 14 строк (5 принятий, 5 отказов, 4 отмены). Полный расчёт рецептов/производство и production AuthCore остаются в приёмке; ниже — предыдущие этапы.

28.10.2026: [native-проверка запитанной ME-grid и заполненного инвентаря](AE2_GRID_COMPATIBILITY_2.2.0.md) дополняет remote capture: обнаружение block/part, full/incremental wire payloads, stale ID, скрытие, потеря и возврат питания; шесть сравнений SHIFT_CLICK/MOVE_REGION с чистым AE2. Это тестовый пакет без изменения production-кода. Отрисованный remote клиент, AuthCore и полный модпак остаются в приёмке; ниже сохранены предыдущие этапы.

28.10.2026: [выбранные remote actions Pattern Access Terminal](AE2_REMOTE_PATTERN_COMPATIBILITY_2.2.0.md) получают точный provider address, полный pattern, terminal/source/slot и actor. Native 13 строк, partial failure и 128 full candidates; девять/десять optional hook групп по версии и 112 supplied targets. Это не discovery/синхронизация всей grid, не полный transformed модпак и не положительная production AuthCore публикация.

27.10.2026: [AE2 pattern/device пакет](AE2_PATTERN_COMPATIBILITY_2.2.0.md) добавляет mode/substitution/recipe settings, устраняет потерю компонентов физических слотов в minimal mode и включает оба press slots. Native четыре режима кодирования, Pattern Provider block/part, return buffer и Inscriber дали 56 строк. Удалённый Pattern Access Terminal и исполнение autocrafting остаются в приёмке.

27.10.2026: [AE2 — полные before/after фильтров, улучшений, ячеек и настроек; priority и reporting threshold](AE2_DEVICE_SETTINGS_COMPATIBILITY_2.2.0.md). Проверены девять конкретных устройств и priority submenu, 52 отделённые строки и отдельно stock 8 → 32. Обе стороны обязательны в minimal detail mode. Это выбранные native/data/storage контракты; production AuthCore, виртуальный ME rollback и полный transformed модпак остаются в приёмке.

Дата: 25.10.2026. Основа — `work/2.2.0` (`f852c7e`). Изменения подготовлены в `work/2.2.0-modpack-performance-20261025` для переноса в `work/2.2.0`; релизный JAR не создаётся. [Проверенная ревизия, матрица CI, сценарии и открытые границы](VERIFICATION_2.2.0_MODPACK_20261025.md).

После получения двух архивов выполнены [проверка точных целей интеграций, native Chipped и повторная матрица CI](VERIFICATION_2.2.0_EXACT_MODPACK_20261025.md). Они дополняют начальный проход; актуальный блокер — согласование состава/зависимостей и оставшиеся реальные сценарии, а не отсутствие самих JAR.

## Объём

Проверить каждый мод из исходного пользовательского списка для Minecraft NeoForge 1.21.1: интерфейсы, контейнеры, настройки, инвентари, автоматизацию, игровые эффекты и жизненный цикл. Собственные Avilix-моды и исключительно клиентские моды исключаются. Обычный запуск и безопасный отказ от неподдержанного отката не считаются полной интеграцией.

Исходный перечень и исторический клиентский лог восстановлены отдельно. После первоначальной проверки получены точные JAR в двух архивах; их SHA-256, вложенные зависимости, entrypoint sides и статические сигналы меню/пакетов инвентаризованы отдельно. Найдены конфликтующие активные mod ID и недостающие обязательные зависимости; эти варианты нельзя незаметно объявлять одним успешно запущенным серверным набором. Для скриптовых механик также нужны используемые серверные определения без секретов. Состав пользовательской сборки и полные логи в публичный репозиторий не копируются.

## Общие пути

- Штатные блоки и их настройки: события/обёртки изменения блока, полные снимки BE, неизменяемые block state. Для нестандартного удаления/удалённого изменения данных требуется отдельная проверка.
- Штатные контейнеры/capability/menu slots: снимки только на событиях открытия, действия и закрытия; разницы в фоне. Это не подтверждает виртуальные хранилища, нестандартные packets и все composed/sided handler.
- В этом проходе добавлены стандартный `handleContainerButtonClick` и снимки `DataSlot` до/после реального действия. `GUI_CHANGE` хранит индексы/значения свойств и класс меню; неизвестным индексам не придумываются семантические имена. Событие аудиторское, универсальный откат чужого меню не разрешается.
- `ENTITY_EQUIPMENT_CHANGE` добавлен для штатного Curios `CurioChangeEvent`. Сохраняются провайдер, тип/индекс слота, предметы с компонентами/расширенным количеством и отдельная идентичность носителя. Уведомление приходит с тика Curios, включая join/clone: носитель не объявляется автором изменения. Logger не добавляет собственного опроса capability каждый тик. Косметические слоты и внешние эффекты аксессуаров этим событием целиком не покрыты.
- Штатные смерти, снаряды, взаимодействия и изменения табличек уже имеют общие пути; нужно проверить их реальные модовые реализации и нестандартные packets.
- Найден обход общего захвата в собственном craft-пакете Chipped: преобразуются слоты самого игрока. Подготовлены отдельный event-driven перехват и ITEM_TRANSFORM с полными снимками; [контракт и native smoke](CHIPPED_COMPATIBILITY.md). Применение обычного частичного отката крафта запрещено.

## Подтверждённые первичные источники нового адаптера

Curios 9.5.1+1.21.1, upstream commit `6b122c8a7e2b514fd94197459ade11f19a0bfb09`:

- [CurioChangeEvent](https://github.com/TheIllusiveC4/Curios/blob/6b122c8a7e2b514fd94197459ade11f19a0bfb09/neoforge/src/main/java/top/theillusivec4/curios/api/event/CurioChangeEvent.java)
- [CuriosEventHandler](https://github.com/TheIllusiveC4/Curios/blob/6b122c8a7e2b514fd94197459ade11f19a0bfb09/neoforge/src/main/java/top/theillusivec4/curios/common/event/CuriosEventHandler.java)
- [CurioInventoryCapability](https://github.com/TheIllusiveC4/Curios/blob/6b122c8a7e2b514fd94197459ade11f19a0bfb09/neoforge/src/main/java/top/theillusivec4/curios/common/capability/CurioInventoryCapability.java)

Первый CI с настоящим Curios выявил несовместимость полного захвата активатора: лениво создаваемые внешние данные отличались между снимками. Причина устранена в границе снимка: Curios может держать пустой десериализованный holder и заполнять штатные слоты при первом capability lookup. Для закреплённой 9.5.1 перед снимком разрешена только нативная инициализация пустого pending holder; отсутствие pending проверяется через один закешированный VarHandle. Непустой pending inventory отклоняется до native reset, а реальные изменения внешнего инвентаря/эффектов без полного контракта остаются барьером отката. Дополнительного тика, переэкипировки, чтения каждого слота в пустом тике или слепой перезаписи attachments нет.

## Остаток приёмки

Проверка реального серверного набора, всех модовых меню/packet/capability/effect путей, скриптов и production AuthCore/ClickHouse остаётся открытой. Проверка выбранных API на CI с Curios/Create/AE2/Backpacked/FD не засчитывается как прохождение полного модпака. У каждого конкретного пути должны быть точная версия, источник, сценарий, ожидаемые строки/данные и доказательство native проверки либо явный открытый статус.

Требования по нагрузке и скорости — в [SERVER_PERFORMANCE_2.2.0.md](SERVER_PERFORMANCE_2.2.0.md).

Для Curios артефакт `yohfFbgD` проверен по SHA-512 `5981a267686b744e7e3c227f78cbcd5267c14ac6979a28e814695f4589273998563147207fef4a5cdb7cdbdc39797cd95d9e4abadb55869f18e02a38d0654ae5`; сигнатуры и ветка native reset сопоставлены с первичным исходником.

## Уточнения предоставленного набора

`no-auto-glass-v2` рассматривается как патч BlockPack по уточнению владельца сервера, а не как самостоятельное направление аудита. Для текущего набора учитывается вариант с этим патчем; повторного выбора между ним и исходной реализацией не требуется. Это уточнение не заменяет native-проверку поведения BlockPack.

Серверный аудит оружия, исторических позиций и перезарядки Scorched Guns 1.5 описан в [SCORCHED_GUNS_COMPATIBILITY.md](SCORCHED_GUNS_COMPATIBILITY.md), включая выполненные проверки и открытые границы.

Дополнительный проход 26.10.2026 добавил аудит турелей и очистку reload session при отключённом сборе; native-проверки подтвердили базовую/дробовую турель, урон/смерть/попадание в блок и три вида взрыва. Выявлен незакрытый native-блокер Scguns 1.5: ручной stop вызывает клиентский ReloadHandler на dedicated server. [Доказательства и partial-статус](VERIFICATION_2.2.0_SCGUNS_20261026.md).

Следующий проход проверил выбранный BlockPack no-auto-glass-v2: обычный/quick-move крафт, fillable crate 1–20, все 54 слота cardboard box, strict restore и отказ от некорректного ×1024, а также отключённый auto-glass. Добавлен аудит custom gift packet и сохранение обеих сторон GUI_CHANGE при отключённых optional details. Native callback BlockPack принимает переключение подарков и от обычного игрока: это исходное поведение мода, отдельное от прав Logger. [Контракт, тесты и открытые пути BlockPack](BLOCKPACK_COMPATIBILITY.md). Следующий проход исправил адрес снимка для групп коробок: вместо кликнутого блока используется BE фактически открытого ChestMenu; native-проверки 144 открытий кубов 1/2/3, отдельные инвентари соседних коробок, четыре CI-профиля, ClickHouse и GUI прошли.

Проход состояний BlockPack добавил native-проверки ключа (69 переключений/14 свойств), шести направлений аквариума, фонаря и BE флага/флюгера. Найдена и исправлена общая потеря BLOCK_INTERACT before/after в ClickHouse при отключённых optional details: парные состояния теперь относятся к rollback details, включая minimal; after-only остаётся optional. Полная авторизованная приёмка и профили нагрузки остаются открытыми. [Сценарии и ограничения](BLOCKPACK_COMPATIBILITY.md).

Следующий проход добавил ITEM_GIFT/ITEM_TRADE для первого входа и завершённого обмена BlockPack: SYSTEM/получатель разделены, фактическая вставка и native spawn acceptance, UUID/позиции покупателя и торговца, обе цены и результат. Native выбранного patch прошёл repeat/disabled/full/partial/cancellation/creative и normal/quick-move/discount/invalid/exhausted trade; журнал/HTTP/реальная ClickHouse проверяются отдельно от AuthCore-gated production emit. Обязательные paired inventory details Chipped ITEM_TRANSFORM также сохраняются при optional pruning. [Контракт и границы](BLOCKPACK_COMPATIBILITY.md#выдача-подарков-и-завершённая-торговля). Полная приёмка серверного модпака остаётся открытой.

Проход 27.10.2026 добавил отдельный аудит текстового packet Supplementaries 4.0.2, который обходит ванильный SignBlockEntity: коврик, доска и два указателя way_sign. Сохраняются настоящий editor/адрес и полные before/after, raw/filtered и конкретные строки GUI. Lazy before выполняется после native wax/claim checks. Общие SignTextAudit guards теперь учитывают обе отключённые категории и повторную проверку перед after. Для предоставленного Supplementaries необходим NeoForge >=21.1.247; отдельный native стенд использует 21.1.247 без правки чужих JAR. Moonlight при свободной записи принимает и далёкого ServerPlayer; это исходная механика, Logger её не меняет. [Точный контракт, проверки и открытые пути](SUPPLEMENTARIES_TEXT_COMPATIBILITY.md). Полный модпак и авторизованная публикация ещё не приняты.

Следующий укрупнённый этап 27.10.2026: [автоматизация текста/книг Supplementaries от Create, реальное меню доски с книгами и картой, полное ядро отката и таблички Amendments](SUPPLEMENTARIES_DISPLAY_COMPATIBILITY.md). Найдены и исправлены сохранение поздних NeoForgeData при загрузке старого снимка, отсутствие restoring scope у общего ядра и поздний отказ неверного BE type. Проверка native/kernel не подменяет production AuthCore/ClickHouse/GUI приёмку.

27.10.2026: [фоновая отбраковка detached NBT для sign/Supplementaries и MenuAction](ASYNC_SNAPSHOT_DELTAS_2.2.0.md) сохраняет payload/атрибуцию и прежние правила no-op, не меняет AuthCore/категории и не добавляет тиков. Подтверждение направления совместимости и performance всего модпака по-прежнему требует отдельной приёмки.


## Дополнение: насыщение journal queue, 27.10.2026

[Исправлены удержание неполной пачки до таймера, отмена retry backoff обычными уведомлениями и lost wakeup sender](JOURNAL_PRESSURE_2.2.0.md). Проверка видимости больше не выбирает старейший файл из всего backlog. Подтверждены disk/replay, настоящая ClickHouse, четыре CI-профиля и GUI. Ограничения памяти и durable acknowledgement сохранены; длительная ресурсная перегрузка и production приёмка остаются открытыми.


## Дополнение: захват меню AE2, 27.10.2026

[Исправлены утечка вложенного scope при исключении, закрытие чужого меню в setting packet и адрес исходного target; stock confirm проверяет отключённый capture до чтения конфигурации](AE2_MENU_COMPATIBILITY_2.2.0.md). Native AEBaseMenu/slots/ConfigButtonPacket и настоящий Interface stock проверены отдельно от авторизованной публикации. Старый перехват воспроизводит retained depth/snapshot; ограничения обязательной авторизации и оставшаяся приёмка остальных ME-путей сохранены. Новых MSPT/heap замеров нет.


## Дополнение: точные ME transfers, 27.10.2026

[Добавлены полный ключ non-item ресурса, точное long количество, typed feed и обязательные подробности ME transfer в minimal mode](AE2_TRANSFER_COMPATIBILITY_2.2.0.md). Lambda machine source получает BE/имя из одного node/owner lookup; FakePlayer не получает человеческий UUID. Native сеть/энергетический helper проверены с ограниченным тестовым хранилищем и отдельно от production AuthCore. Дополнительная native сериализация non-item key остаётся синхронной; новых MSPT/heap замеров и отката виртуальной ME-сети нет.
