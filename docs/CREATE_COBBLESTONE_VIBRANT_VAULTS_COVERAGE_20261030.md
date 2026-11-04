# Проход из двух модов: Create Cobblestone и Create: Vibrant Vaults

30.10.2026. Основа: локальный `991981d` после OPAC/FTB Teams.
Пара этого прохода: **Create Cobblestone 1.5.0+neoforge-1.21.1-153** и
**Create: Vibrant Vaults 0.3.2**, с Create **6.0.10**.

По указанию пользователя компиляция, все тесты, проверка точных class-файлов,
GUI/ClickHouse/AuthCore-прогоны, JAR и измерения оставлены на этап оптимизации.
Ниже — реализованные границы по исходникам, не подтверждённая игровая приёмка.
Два мода не вычитаются из незакрытого остатка матрицы.

## Create Cobblestone

| Область | Реализация |
| --- | --- |
| Выпуск предметов | Native tick: фактическое изменение выходного ItemStack, компоненты и количество. Неизменившийся полный буфер не порождает событие |
| Интервалы выпуска | Положительное накопление одного предмета объединяется до 20 native ticks; первый/последний буфер, дробный остаток, тип генератора и диапазон ticks. Продукция имеет SYSTEM, даже если flush вызван игроком |
| Внешний доступ | Container.removeItem, removeItemNoUpdate, setItem, clearContent; capability InvWrapper использует эти же мутации. Native результат и исключение сохраняются |
| Переключение типа | changeType: raw ID/блок/stress/rate/storage до/после, очищаемый runtime buffer и дробный остаток при включённой категории контейнеров. Охватывает updateType, выбор игроком и сброс на NONE при invalid/disabled type |
| Живое чтение NBT | read на установленном серверном BE; первичная гидратация без установленного world identity не объявляется изменением игрока |
| Удаление/выгрузка/остановка | Pending production flush до SmartBlockEntity.setRemoved/onChunkUnloaded и до остановки writers. При заполнении лимита 1024 pending producers новый producer пишет немедленно |

Снимок намеренно отделяет **RuntimeBuffer** от сохранённого NBT. В выбранной
версии upstream write не сохраняет items и available. Logger не исправляет
это поведение и не объявляет такой снимок готовым контрактом восстановления.

На idle tick нет сериализации NBT/перечисления инвентарей. Нативный callback
делает ограниченные сравнения; копии стека создаются при изменении выпуска,
отделённые NBT — при flush. Изменение другого предмета/уменьшение буфера
заканчивает интервал. Перед внешней мутацией интервал также заканчивается.
Nested changeType внутри tick не создаёт второй production receipt.

## Create: Vibrant Vaults

| Область | Реализация |
| --- | --- |
| Горизонтальные/вертикальные vaults | Физический inventory каждого BE; insert/extract/set, bulk deserialize/resize, clearContent и applyInventoryToBlock |
| Общее хранилище | Combined wrapper исполняет native операции над физическими member handlers. Их адреса и slots сохраняются отдельно; observer не вызывает initCapability/getControllerBE и не сканирует структуру |
| Связность | setController, removeController, setWidth, setHeight: raw controller, radius/length, lastKnownPos, фактический blockstate и точные XYZ/dimension |
| Packager | unwrapBox только при simulate=false, attemptToSend, queue/heldBox/previouslyUnwrapped, clear/destroy, адрес по табличке и компьютерный адрес/режим |
| Frogport | 18 физических слотов; target, addressFilter, acceptsPackages, goggles/yaw, animatedPackage и направление. startAnimation, clear/destroy и завершение native движения пакета |
| Меню frogport | Одна фактическая click/button операция: полный локальный inventory, player inventory, cursor и sync properties; nested slot receipts подавлены. Generic open/close fallback для распознанного меню не устанавливается |
| Stock link | placedBy отдельно от автора, native frequency UUID, redstone priority и addedGlobally; direct power callback |
| Redstone requester | Сохранённые order/crafts/address/partial flags/frequency и успешность прошлого request. Native logistics submission имеет отдельный accepted/rejected/native-threw receipt, даже если прежний success flag не изменился |
| Factory panel | Добавленные модом restocker color и restocker flag, включая Create factory panel вне namespace аддона |
| Native settings packets | Обёрнут именно applySettings после upstream проверки range/permissions; cause берётся от реального sender, до/после охватывает фактическую мутацию |

Аддон использует BE классы и registry types Create. Поэтому выбор выполняется
по namespace **установленного блока**, а не по namespace его BE type.
FactoryPanel — явное исключение для добавленного аддоном цвета.

Handlers связываются при штатном setLevel после конструкции, в том числе до
первого tick. Это позволяет наблюдать member, ещё не прошедший initialize,
без собственного обхода multiblock. Фактический capture всё равно требует
server thread, loaded chunk, совпадение BE с установленным объектом и отсутствие
restoring. Ссылки на handler/owner слабые. Detached/moving contraption BE этим
адаптером не объявляются стационарным хранилищем.

Обычная slot мутация снимает только затронутый слот. Полный локальный inventory
снимается для bulk/menu операций; packager дополнительно сохраняет queue,
поскольку распаковка может пройти без помещения пакета в heldBox. Общая
структура хранилища не перечисляется. Пределы: 16384 физических слота, 4096
элементов очереди; превышение вызывает диагностику и отказ неполного снимка.

Сериализатор BE не вызывается для наблюдения: в частности, native frogport
write меняет sendAnticipate. Используются выбранные raw fields и codecs
target/order. Native sendData наблюдает подтверждённый diff с baseline;
неизвестный автор более раннего raw изменения остаётся SYSTEM. Native tick
packager/frogport сравнивает короткие markers, сериализует только изменение
предметного состояния и не считает animation progress отдельным событием.

Принятие заказа логистикой не является доставкой: **DeliveryConfirmed=false**.
Штатные place/break/wrench blockstate, craft/dye recipes, drops/pickups и
взаимодействия продолжают использовать существующие общие обработчики Logger.
Это описание наследуемых путей, а не новый результат их native приёмки.

## Журнал и ограничения

Добавлены append-only MACHINE_STATE_CHANGE и MACHINE_CONTENT_CHANGE.
Настройки подчиняются категории блоков/logBlocks, предметы — категории
контейнеров/logContainers. Передача buffer/package contents в снимки настроек
зависит от включённой категории контейнеров. Добавлены command aliases
machine/machine_state и machine_items/machine_content/production, подписи,
GUI presets и маршруты split ClickHouse. Evidence новых типов сохраняется в
minimal details.

Снимки имеют точные XYZ/dimension/blockstate. Diff/SNBT выполняется в worker
над отделёнными tags; world/BE/player туда не передаются. Generic rollback
отклоняет mod:createcobblestone:* и mod:create_vibrant_vaults:* и в preview,
и в apply. Не вводится новый контракт восстановления пакетов/мультиблока.

Optional группы закреплены на двух точных версиях аддонов и Create 6.0.10.
Группа включается после существующего raw preflight всех объявленных mixin
targets/signatures/injection points. Несовместимость отключает optional группу.
Это предохранитель на запуске, а не уже выполненная проверка пользовательских JAR.

## Первичные исходники

- [Create Cobblestone v1.5.0](https://github.com/StickyPiston-development/CreateCobblestone/tree/056ae9c17bf759591674a09e37290dece7ff4dbc): MechanicalGeneratorBlockEntity, MechanicalGeneratorBlock, GeneratorType, GeneratorTypeLoader, build.gradle. Полная версия артефакта формируется из mod_version + loader/Minecraft + GITHUB_RUN_NUMBER.
- [Vibrant Vaults 0.3.2](https://github.com/ZLT9/create-vibrant-vaults/tree/b5f2e3b8bcc1724f7e37fc6522460801929e47dc): ModBlocks/ModBusEvents, VerticalVaultBlock, ItemVaultConnectivityHelper, server mixins. Java-код совпадает с version-bump a610953; последующие изменения этой ветки его не меняют.
- [Create mc1.21.1-6.0.10](https://github.com/Creators-of-Create/Create/tree/mc1.21.1-6.0.10): SmartBlockEntity, SyncedBlockEntity, ItemVaultBlockEntity, PackagerBlockEntity/PackagerItemHandler, PackagePortBlockEntity/PackagePortMenu, FrogportBlockEntity, SmartInventory, LogisticallyLinkedBehaviour, StockCheckingBlockEntity, RedstoneRequesterBlockEntity и BlockEntityConfigurationPacket.

Матрица пользователя указывает SHA-256:
Create Cobblestone `a2c8bffb1ebd3fcc0f381a6be498bc16c6990bd31bff1f5c0852ffe5ed35eaf4`;
Vibrant Vaults `f724d69b3e69169d1439ba3f447025f02dca039a63586c7a6519c71dafba6130`.
Их совпадение с закреплёнными Java sources в этом проходе не подтверждалось.

## Отложенная приёмка и открытые границы

Нужны compilation, exact-bytecode/preflight и отсутствие аддонов; реальный
разрешённый AuthCore append, masks/off/restoring/no-op/simulate; hopper,
direct API, GUI shift/cursor/button/close и исключения после частичной мутации;
full buffer, same-type reset, disabled/unloaded generator type, fractional
rate и production flush на stop/unload; horizontal/vertical multiblock
form/split/wrench/break, member до initialize, исходный Create vault рядом
с vibrant; все цветные packager/frogport/stock-link/requester, package queues,
native принятие/отказ заказа, panel color; сохранение/reopen журнала,
minimal ClickHouse, поиск и отказ rollback. Затем профилирование TPS/allocations
и проверка отсутствия duplicate receipts/новых chunk tickets.

Глобальная смена правил генераторов datapack reload не объявляется отдельным
полностью закрытым аудитом: upstream loader меняет registry в async reload
chain без явного game executor. Логируются фактические изменения живого
генератора, когда native применяет/сбрасывает его тип. Аудит самих глобальных
определений reload/config требует отдельной границы с отделёнными данными;
observer не добавляет небезопасное чтение этого registry с server thread.

Открыты неподтверждённые обходы raw mutable stacks/fields без native mutation
или notification, модификации других аддонов, а также приёмка наследуемых
base-Create/contraption путей. Полное игровое закрытие этой пары и модпака
в целом пока не заявляется.
