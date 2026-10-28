# AE2: удалённые слоты Pattern Access Terminal

28.10.2026: [приём заказов автокрафта и подтверждённая UI отмена AE2](AE2_CRAFTING_COMPATIBILITY_2.2.0.md): actual CPU/native JobId, source address/actor, полный ключ и planned-output long, реальные errorCode и partial failure. Два append-only типа; обе версии AE2 дали по 14 строк (5 принятий, 5 отказов, 4 отмены). Полный расчёт рецептов/производство и production AuthCore остаются в приёмке; ниже — предыдущие этапы.

28.10.2026: [native-проверка запитанной ME-grid и заполненного инвентаря](AE2_GRID_COMPATIBILITY_2.2.0.md) дополняет remote capture: обнаружение block/part, full/incremental wire payloads, stale ID, скрытие, потеря и возврат питания; шесть сравнений SHIFT_CLICK/MOVE_REGION с чистым AE2. Это тестовый пакет без изменения production-кода. Отрисованный remote клиент, AuthCore и полный модпак остаются в приёмке; ниже сохранены предыдущие этапы.

28.10.2026. Проверенный код `bab2f74e9774b0107b8b5ad1389f213c945123cf`, tree `36f4860251ac77867fae506f56a9ffc4283c4d6b`. Основа — `8abf4f6ad843c7a489b9a96237ec88366b847350`; ветка — `work/2.2.0-ae2-remote-patterns-20261028`. [Шаблоны и физические слоты](AE2_PATTERN_COMPATIBILITY_2.2.0.md), [device settings](AE2_DEVICE_SETTINGS_COMPATIBILITY_2.2.0.md) и [ME transfers](AE2_TRANSFER_COMPATIBILITY_2.2.0.md) сохранены. Релизный JAR не создавался.

## Что было пропущено

Pattern Access Terminal не добавляет удалённые pattern inventories в обычные menu slots. Его собственный doAction переопределяет AEBaseMenu.doAction, а quickMovePattern имеет отдельное тело. Старые снимки обычного меню не могли увидеть эти локальные слоты поставщика; успешное изменение в них не обязано проходить через NetworkStorage/StorageHelper.

Добавлены общий WrapMethod doAction и отдельный WrapMethod quickMovePattern для AE2 19.2.18, где этот метод существует; в 19.2.12 quick move hook не применяется. Внутри них перехватывается чтение ContainerTracker.server. Он получает тот конкретный инвентарь, до которого дошло оригинальное действие. Три accessor проверяют container, serverId и server. Нативный GETFIELD выполняется один раз; при выключенном capture возвращается исходный inventory без чтения tracker metadata или снимков. Проверки категорий/AuthCore/logAe2 и server thread сохранены.

Scoped wrapper делегирует настоящему inventory его size/limits/validation, getSlotInv/getSubInventory, insert/extract/set и уведомления. Нативные фильтры, возврат остатка, ограничение максимума stack и исключения остаются у AE2. До изменения запоминаются только достигнутые слоты. MOVE_REGION получает живые ItemStack и может изменить их по ссылке: здесь снимок берётся при первом чтении непустого слота. Вложенное массовое действие включает этот режим и для уже открытого scope.

После действия сравниваются окончательные состояния. Отменённая замена, откат неудачной вставки к исходному содержимому, no-op и simulation не создают ложных ME_PUT/ME_TAKE. Для неудачных попыток вставки в полный слот временный before удаляется сразу. Если native действие успело изменить слот и бросило исключение, доступная реальная дельта сохраняется; оригинальная операция не повторяется и её исключение не заменяется ошибкой аудита.

Owner scope закрывается в finally; поле меню очищается до подготовки записей. Finish освобождает player/host/inventory/snapshots из capture и идемпотентен. Вложенное действие не закрывает внешний scope. Положительный production scope с разрешённым AuthCore остаётся отдельной приёмкой; см. точный способ native проверки ниже.

## Адрес и доказательства

Основные dim/x/y/z относятся к изменённому поставщику, а не к терминалу или текущей позиции игрока. Поддержаны реальные BE/part и PatternProviderLogicHost, предоставляющий свой BE. Для неизвестного PatternContainer без честного BE возвращается исходный inventory и bounded diagnostic ae2-remote-origin: чужие координаты не выдумываются. Не вызываются getGrid, поиск владельца по сети или capability.

Source содержит исходный terminal dimension/position, runtime serverId поставщика и настоящий индекс его ENCODED_PATTERN inventory. Это локальный индекс инвентаря поставщика, не номер обычного menu slot. ServerId принадлежит текущему сеансу терминала и не является постоянным UUID устройства; исторический адрес задают dim/x/y/z. Адрес терминала фиксируется до native действия, а поставщик выбирается в native GETFIELD. Смена player.containerMenu внутри callback не подменяет исходный контекст.

Дельты используют прежние ME_PUT/ME_TAKE и Ae2Transfer v1 с Domain=device-slot. Сохраняются UUID/имя актора, точное количество, полный native ItemStack и компоненты самого шаблона. Компонентная замена даёт TAKE старого и PUT нового. Это итоговая физическая дельта действия, не snapshot виртуальной ME-сети и не готовый план rollback. Creative duplicate изменяет cursor без изъятия из поставщика: ложной ME_TAKE нет; отдельный аудит создания cursor этим пакетом не добавлен.

Существующая политика ClickHouse сохраняет ITEM/evidence и при minimal mode с storeNonRollbackDetails=false. Схема БД, ActionType ordinal, команды, права и формат journal не менялись. Старые усечённые записи не дополняются задним числом.

## Стоимость и потоки

Логгер не перечисляет byId, устройства grid или все inventory слоты заранее. Native quick move сам выбирает допустимых кандидатов; наблюдаются только реально достигнутые inventories. Создаются временные wrappers/cache записей достигнутых inventory и before для затронутых слотов. Неизменившийся full candidate не удерживает полные item snapshots до конца поиска. MOVE_REGION требует before живых прочитанных слотов одного native inventory. Для большого addon inventory цена такого снимка растёт с числом прочитанных слотов.

Чтение живых ItemStack, их копирование/сравнение и native item serialization остаются в серверном потоке. Сериализуются только реальные дельты, один encode на создаваемую строку. Production передаёт строки по одной в прежнюю ограниченную очередь; списка всех сериализованных результатов перед очередью нет. SNBT/JSON, journal и БД выполняются worker. Byte accounting, oversized-alone и backpressure сохранены; Level/Player/Menu/Inventory не передаются фоновому потоку.

Нет новых тиков, вызовов игровых действий, forced chunk loads или обхода всей сети от Logger. Дополнительные чтения/копии нужны для доказательств достигнутых операций; этот этап не доказывает нулевую цену или численный выигрыш MSPT/heap.

## Native и локальные проверки

Java 21.0.10, NeoForge 21.1.247, присланные AE2 19.2.18 и GuideME 21.1.19; SHA-256 AE2 `cb19624319aaf06c5e60ac16923f2d5b8c503edc9b5fc4c4c3c65a729049c655`. Присланные JAR не менялись; для дополнительного локального старого профиля взят официальный Maven Central AE2 19.2.12, отдельно от 267 присланных файлов. Локальный AE2 профиль содержит AE2/GuideME без Create; CI проверяет совместную загрузку. После очистки временного окружения инструменты и архивы восстановлены; NeoForm fallback определения пути Java ограничен отдельным tool JAR, исходный инструмент сохранён.

Ae2RemoteNativeSmoke ставит настоящий Pattern Access Terminal, Pattern Provider block и part. Native ContainerTracker создаётся dev-fixture и добавляется в native byId; используется общая тестовая group. Автоматическое обнаружение поставщиков в настоящей запитанной grid и доставка клиентских discovery packets этим не подтверждены.

Dev-fixture временно устанавливает в приватный scope исходного меню Capture.inspect с production=false. Настоящие преобразованные doAction/quickMovePattern и GETFIELD hooks выполняются реально, а finish возвращает только отделённые строки в тестовый список. Штатный Capture.begin остаётся закрытым без AuthCore, production LoggerRuntime.storage.append не вызывается, авторизация/ключи не подменяются. Это проверка native операций и production data kernels, не положительный authenticated emit.

| Сценарий | Проверенное поведение |
| --- | --- |
| Обычные удалённые действия | Вставка, компонентная замена, отказ чужого предмета с восстановлением содержимого, извлечение |
| Количества | Place single, split и shift; overstack fixture сравнивает split с вызовом без инспектора и штатным maxStackSize cap |
| Массовое извлечение | MOVE_REGION изменяет живые stack references; все реальные дельты сохраняются при включении live-read режима вложенным native wrapper |
| Нулевые дельты | Creative duplicate, неверный slot/ID, source count != 1, полный поставщик, simulation |
| Быстрое перемещение (19.2.18) | Настоящий quickMovePattern; полный кандидат; fallback через 128 полных однослотовых inventories к реальному поставщику; retainedSlots=1 при успехе |
| Граница чтения (19.2.18) | Отдельный невыбранный inventory бросил бы исключение при любом чтении; native поиск и Logger его не трогают |
| Отказы и меню | Один native set с частичной мутацией и исходным исключением; callback переключает player.containerMenu, запись остаётся у исходного terminal/provider |
| Scope и история | Преобразованные wrappers (один в 19.2.12, два в 19.2.18) с fault Operation вызывают original один раз при выключенном capture; outer inspector не закрывается вложенным действием; off-thread inspector отказывается; повторный finish пуст |

Присланный AE2 19.2.18 дал 13 строк, публичный 19.2.12 — 11 строк без отсутствующего в нём quick move. Маркер AE2_REMOTE_NATIVE_OK явно содержит quickMove/fullCandidates/rows; 128 full candidates проверяются только на 19.2.18. Для каждой проверены provider runtime ID против настоящего tracker host, точные координаты/измерение, исходный terminal address, actor, slot, ITEM/evidence, порядок AsyncLogStorage, LogText и Gzip. Полный ItemStack декодируется обратно настоящим PatternDetailsHelper, сохраняя pattern components. Прежние 56 pattern/device, 52 device settings, stock 8 → 32, transfer/scope regressions прошли. accepted=0, adapterFailures=[].

Первое ожидание split 5 → 3/2 оказалось неверным: native AppEngInternalInventory ограничивает результат maxStackSize шаблона. Fixture исправлен на прямое сравнение native baseline и наблюдаемого вызова, без изменения игровых результатов.

Пройдены compileJava/compileCreateSmokeJava/testAsyncPipeline/testCreatePreflight/testExactModpackPreflight/verifyMixinSafety и inventory/profile comparison/ProfileSummary. Чистый сервер без AE2 даёт AE2_ABSENT_NATIVE_OK. Девять mixin групп для 19.2.12, десять для 19.2.18 и 112 supplied target-классов: preflight получает фактическую версию AE2 и проверяет соответствующие declared remote overrides, accessor fields и точные native GETFIELD counts 5 (doAction) и 1 (quick move только для 19.2.18). Отсутствующий quick move не маскируется fallback для версии, которая обязана его иметь; неожиданный quick move в 19.2.12 также отвергается. Fault tests удаляют оба methods/поля/field access и требуют отказ интеграции. Исправлен контракт отказа для отсутствующего declared override: унаследованный AEBaseMenu.doAction не считается допустимым remote target. Первая CI-матрица выявила отсутствие quickMovePattern и protected isPlayerSideSlot в 19.2.12; entry points разделены по версиям, а dev-test использует общий публичный SlotSemantic и reflection только для существующего quick move. После этого локальные классы полностью перекомпилированы: старый incremental output ранее сохранял прежнюю сигнатуру plugin. Raw preflight не подтверждает отсутствие transformed конфликтов всех 267 JAR.

AE2_REMOTE_EVIDENCE_OK проверяет полный synthetic pattern, actor, provider coordinates, terminal/source/slot/runtime ID через Gzip и настоящий DiskLogJournal с точным ID. Общие byte weight/worker-only SNBT/device schema и queue pressure regressions сохранены.

## CI и БД

[Четыре серверных профиля и настоящая ClickHouse](https://github.com/Vlad-Ras/Logger/actions/runs/40602741593), [GUI](https://github.com/Vlad-Ras/Logger/actions/runs/40602741471). Все четыре серверных профиля, отдельная настоящая ClickHouse и GUI завершились успешно на указанной code-ревизии. Native remote cases вместе с Create прошли на 19.2.12 (quickMove=false, rows=11) и 19.2.18 (quickMove=true, fullCandidates=128, rows=13); absence step успешен. GUI regression проверил настоящие history/actions/confirmation/results/help на трёх размерах; это общая GUI регрессия, не remote production end-to-end.

ClickHouseNativeSmoke добавляет remote source с отдельными provider coordinates и terminal address/runtime ID/slot, actor UUID/name и полным pattern ITEM больше 3 КБ. Проверяются точный ID, payload, delta, source/адрес и feed в minimal mode, затем повторное чтение после нового подключения. AE2_REMOTE_CLICKHOUSE_OK получен дважды; прежние device/configuration/transfer и CLICKHOUSE_NATIVE_OK сохранены. Это изолированные storage fixtures, отдельно от авторизованной игровой публикации. Private JAR в CI не загружаются.

## Остаток

Native discovery и wire payloads вынесены в следующий пакет выше. Не закрыты отрисовка/обработчик remote GUI и настоящий клиентский сеанс, все addon PatternContainer и сторонние transformed hooks, насыщенный player inventory во всех region сценариях, история creative cursor creation, исполнение autocrafting jobs, реальные виртуальные/составные storage и их rollback. Нужны полный согласованный модпак, production конфиги/скрипты/AuthCore, end-to-end ClickHouse/GUI/rollback job. Native manual stop Scguns 1.5 остаётся отдельным блокером; поезда и вырезанный портал в работу не возвращаются.

Нет новых A/B/A MSPT/heap/GC результатов фермы 8 пил + 8 активаторов с саженцами + portable storage interface. Длительное противодавление и прежние около 60 000 мкс/тик Observable не объявляются устранёнными.
