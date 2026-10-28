# AE2: шаблоны, физические слоты устройств и пресс

28.10.2026: [native-проверка запитанной ME-grid и заполненного инвентаря](AE2_GRID_COMPATIBILITY_2.2.0.md) дополняет remote capture: обнаружение block/part, full/incremental wire payloads, stale ID, скрытие, потеря и возврат питания; шесть сравнений SHIFT_CLICK/MOVE_REGION с чистым AE2. Это тестовый пакет без изменения production-кода. Отрисованный remote клиент, AuthCore и полный модпак остаются в приёмке; ниже сохранены предыдущие этапы.

28.10.2026: [добавлен отдельный захват Pattern Access Terminal doAction/quickMovePattern](AE2_REMOTE_PATTERN_COMPATIBILITY_2.2.0.md). Тринадцать native строк проверяют настоящий provider address, terminal/source/slot, partial failure и fallback через 128 полных кандидатов без удержания их снимков. Discovery настоящей grid, addons и положительный production AuthCore остаются в приёмке; ниже описан предыдущий этап.

27.10.2026. Проверенный код `6ff9c65caa3894b37979e77807d26dd0a3f3696e`, tree `b81f469df1114b4347035fc418a5954d2541ba75`. Основа — `7f233cdfb9d7254e36e1177ab5cb430969235c90`; ветка — `work/2.2.0-ae2-patterns-20261027`. [Предыдущие device settings](AE2_DEVICE_SETTINGS_COMPATIBILITY_2.2.0.md), [ME transfers](AE2_TRANSFER_COMPATIBILITY_2.2.0.md) и границы захвата меню сохранены. Релизный JAR не создавался.

## Исправления

Режим Pattern Encoding Terminal, флаги замены предметов/жидкостей и выбранный stonecutting recipe отсутствуют в обычном exportSettings. Теперь snapshot этого конкретного меню читает их из PatternEncodingLogic через исходный host, по одному получению logic: pattern_mode, pattern_substitution, pattern_fluid_substitution, stonecutting_recipe. Они используют прежние typed ME_SETTING_CHANGE с полными before/after и явным отсутствием recipe. Отстающие синхронизируемые поля меню не подменяют реальные значения.

CRAFTING_GRID, smithing и stonecutting slots терминала являются представлениями того же encoded input inventory, который уже доступен через PROCESSING_INPUTS. Для него сохраняется один канонический набор input/output slots; дополнительные зеркала не добавляются в snapshot. Проверены одиночные изменения во всех четырёх режимах. При кодировании crafting pattern AE2 также заполняет сохранённый encoded output: эта отдельная реальная дельта остаётся в истории, вместе с расходом blank pattern и появлением encoded pattern.

INSCRIBER_PLATE_TOP и INSCRIBER_PLATE_BOTTOM раньше отсутствовали в выбранных семантиках capture. Теперь физические операции с обоими слотами проходят тот же механизм ME_PUT/ME_TAKE, что и прочие локальные inventory slots устройств. Инвентарь игрока и обычный player-side crafting grid не добавлены в захват.

Прежняя физическая ME-строка содержала item snapshot, но только текстовое extra `device inventory ...`. В minimal detail mode либо при выключенном storeNonRollbackDetails эта строка не попадала под обязательное сохранение ME transfer details: полный предмет и компоненты могли отсутствовать при чтении истории. Теперь физические строки используют прежний Ae2Transfer v1 с компактным extra и отдельным Domain=device-slot. В evidence находятся точная фактическая дельта, item resource/key type, а полный нативный ItemStack остаётся в ITEM. Источник сохраняет меню, SlotSemantic и номер menu slot; адрес и измерение — исходный target.

Это изменение локального физического слота, а не обещание переноса через виртуальную сеть. При замене предмета/его компонентов сохраняются прежние две строки — TAKE старого и PUT нового; это не новый полный snapshot контейнера для rollback. ITEM содержит полный изменённый stack, count/Amount — величину дельты. Native item serialization выполняется один раз на строку. При ошибке снимка фиксируется bounded diagnostic ae2-device-snapshot, без повторной сериализации и выдуманного пустого предмета.

Существующая политика ClickHouse для typed ME transfers сохраняет ITEM/evidence даже в minimal mode. Изменение таблиц, ActionType ordinal, маршрутов сети и формата журнала не потребовалось. Старые ME-строки и network transfer v1 без Domain читаются по-прежнему; неизвестный Domain не принимается за новый context. История не загружает AE2 и не реконструирует модовые keys.

Исправление сохраняет подробности новых событий. Компоненты ранее усечённых записей нельзя восстановить из одной краткой строки.

## Потоки и стоимость

AuthCore, категории, logAe2 и проверка server thread сохранены. Новые getter вызываются только для PatternEncodingTermMenu внутри разрешённого production capture; raw optional preflight дополнительно проверяет четыре getter логики и host.getLogic без инициализации AE2. Семь групп mixin hooks и 110 supplied hook target-классов остаются прежними; два дополнительных API-класса проверяются отдельно.

Нет новых ticking hooks, сканирования сети/удалённых поставщиков, capability lookup, загрузки чанков или дополнительных игровых действий. Снимки ItemStack и чтение логики остаются в серверном потоке; SNBT/JSON, journal и БД используют существующие worker. Production по-прежнему отправляет каждую строку в ограниченную очередь до сериализации следующей, учитывая payload и сохраняя backpressure/oversized-alone. Компактный device evidence добавляет небольшое состояние на строку, но не второй native item encode. Полные обязательные details требуют больше места, чем прежняя усечённая история. Численного выигрыша MSPT/heap этот этап не доказывает.

## Локальные доказательства

Java 21.0.10, NeoForge 21.1.247, присланные AE2 19.2.18 и GuideME 21.1.19; SHA-256 AE2 `cb19624319aaf06c5e60ac16923f2d5b8c503edc9b5fc4c4c3c65a729049c655`. Чужие JAR не менялись. Локальный AE2 профиль не содержит Create; совместная загрузка проверяется CI. Поправка локального NeoForm ограничена fallback определения пути Java при недоступном ProcessHandle.command; оригинальный tool сохранён.

Ae2PatternNativeSmoke устанавливает настоящие part/BE и использует native receiveClientAction/setFilter/clicked/ConfigButtonPacket:

| Сценарий | Проверка |
| --- | --- |
| Processing | Режим, substitution/fluid substitution, вход, два выхода, cycle output, расход blank, encoded pattern, повтор encode без дельты и clear |
| Crafting | Oak log → planks через настоящий recipe manager; сохранённый computed output; encoded pattern и clear обоих состояний |
| Smithing | Netherite upgrade template + diamond sword + netherite ingot, реальное кодирование/декодирование шаблона и clear |
| Stonecutting | Native recipe manager выбирает рецепт для stone, client action передаёт настоящий ResourceLocation payload; encode/decode и удаление выбранного recipe |
| Pattern Provider block и part | Установка, замена и извлечение настоящих processing/crafting patterns; blocking mode; извлечение семи diamonds из native return inventory |
| Inscriber | Установка/извлечение calculation press сверху и silicon press снизу; auto-export setting |

Пакет дал 56 отделённых строк: actor/адрес, ожидаемое точное число дельт каждого действия, отсутствие дублей от зеркальных slots, FIFO AsyncLogStorage, полный configuration context либо device ITEM/evidence, настоящая LogText строка и Gzip replay. AE2_PATTERN_NATIVE_OK. Прежние nine-device 52 rows, stock 8 → 32, transfer и scope regressions также прошли. Production capture выключен: accepted=0, adapterFailures=[]. Это native мутации и data-only kernels; положительная AuthCore публикация не проверяется.

Первый сценарий ожидал только две строки crafting encode; native показал третью сохранённую output-дельту. Ожидания исправлены по реальному контракту. Затем исправлен тестовый JSON ResourceLocation: штатный client action использует объект Gson, а не строку ID. Финальный native прогон прошёл все четыре режима и остальные устройства. Начальный запуск с остатками чужих smoke-классов в локальном output остановился до fixture; чистая компиляция устранила смешение. Общие pipeline tests выполняются отдельно от AE2-only runtime, поскольку они требуют Create.

Пройдены compileJava/compileCreateSmokeJava/testAsyncPipeline/testCreatePreflight/testExactModpackPreflight/verifyMixinSafety, inventory/profile comparison/ProfileSummary и чистый запуск без AE2 — AE2_ABSENT_NATIVE_OK. AE2_CONTRACTS_OK включает fault refusal отсутствующих pattern API. AE2_DEVICE_EVIDENCE_OK проверяет device domain/count/components, отказ неизвестного domain, учёт обоих payload, worker-only SNBT для ITEM/evidence, сохранность метаданных и настоящий DiskLogJournal с ID. Raw 110 targets не доказывают отсутствие transformed конфликтов полного набора 267 JAR.

## CI

[Четыре серверных профиля и настоящая ClickHouse](https://github.com/Vlad-Ras/Logger/actions/runs/40583314480), [GUI](https://github.com/Vlad-Ras/Logger/actions/runs/40583314564). Все четыре серверных профиля, отдельная настоящая ClickHouse и GUI завершились успешно на указанной code-ревизии. Новые native cases прошли на AE2 19.2.12 и 19.2.18 вместе с Create: на обеих версиях 56 строк. Отдельный absence step также успешен. GUI regression проверил настоящие экраны history/actions/confirmation/results/help на трёх размерах; это регрессия общего GUI, не AE2 production end-to-end.

ClickHouseNativeSmoke дополняет ME fixtures физическим encoded-pattern item с компонентами больше 3 КБ. Проверяются полные ITEM/evidence, device domain, delta, source/адрес, exact ID и краткий feed в minimal mode с storeNonRollbackDetails=false; затем те же данные читаются после нового подключения. AE2_DEVICE_CLICKHOUSE_OK получен дважды; прежние AE2_TRANSFER_CLICKHOUSE_OK, AE2_CONFIGURATION_CLICKHOUSE_OK и CLICKHOUSE_NATIVE_OK сохранены. Это изолированная проверка хранилища, не авторизованный gameplay → БД → GUI. Private JAR в CI не загружаются.

## Что ещё открыто

Pattern Access Terminal хранит удалённые инвентари вне обычных menu slots и переопределяет doAction; quickMovePattern тоже отдельный путь. Новый пакет не объявляет их покрытыми. Нужен отдельный перехват с честным адресом фактически изменённого поставщика и без обхода всей сети на каждое действие.

Не проверены исполнение autocrafting jobs/процессоров, все terminal/addon действия, реальные виртуальные/составные storage и их rollback, весь transformed модпак, production AuthCore/конфиги/скрипты/ClickHouse/GUI/полный rollback job. Четыре режима кодирования рецептов не равны выполнению автоматического крафта; Inscriber cases не включают реальное изготовление процессора. Native manual stop Scguns 1.5 остаётся отдельным блокером. Поезда и вырезанный портальный переход не возвращаются в работу.

Нет A/B/A MSPT/heap/GC результатов фермы 8 пил + 8 активаторов с саженцами + portable storage interface. Длительное противодавление и прежние около 60 000 мкс/тик Observable этим этапом не объявляются устранёнными.
