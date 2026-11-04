# AE2: полные изменения фильтров, улучшений, ячеек и настроек

30.10.2026: [стоимость захвата терминала AE2](AE2_TERMINAL_PERFORMANCE_2.2.0.md): follow-up переиспользует отделённые неизменившиеся физические слоты; добавлены powered обычный ME terminal, idle entry probe и A/B/A allocation/CPU/wall замер data kernel. Это отдельная проверка цены второго снимка; production MSPT и причина жалобы на 1.2.22 не объявляются установленными. Ниже — предыдущие этапы.

28.10.2026: [Pattern Access Terminal теперь наблюдает достигнутые native inventories отдельно от обычных menu slots](AE2_REMOTE_PATTERN_COMPATIBILITY_2.2.0.md). Адресом строки служит поставщик; исходный терминал/runtime ID/slot сохраняются в source. Native inspector-пакет — 13 строк; production AuthCore и grid discovery остаются открытыми.

Следующий этап 27.10.2026: [настройки четырёх режимов Pattern Encoding Terminal, сохранение компонентов физических слотов в minimal mode, два варианта Pattern Provider и оба слота пресса](AE2_PATTERN_COMPATIBILITY_2.2.0.md). Дополнительный native пакет — 56 строк; удалённый Pattern Access Terminal остаётся отдельной задачей.

27.10.2026. Проверенный код `ca0d64d35ff37f1d2d255f5909640ea65d778b5b`, tree `e136ce8d01a76fc9709c8a1cffe64c1ec25c1874`. Основа — `455dfafde8b94e7593b9e5e74df08f7ed186cd3e`; ветка — `work/2.2.0-ae2-device-settings-20261027`. [Границы меню](AE2_MENU_COMPATIBILITY_2.2.0.md) и [точные transfers](AE2_TRANSFER_COMPATIBILITY_2.2.0.md) сохранены. Релизный JAR не создавался.

## Исправления данных

Прежние ME_FILTER_CHANGE / ME_UPGRADE_CHANGE / ME_CELL_CHANGE сохраняли текст и один представительный предмет. Этого недостаточно для точной истории обеих сторон, особенно при изменении только компонентов. Настройки были текстовыми; приоритет и порог storage level emitter отсутствовали в exportSettings.

Новые четыре вида configuration events получают extra с префиксом `ae2-config-v1|` и читаемой краткой подписью. В прежних beBefore/beAfter сохраняются полные отделённые состояния `Kind=Ae2Configuration, Version=1`:

- Domain=slot: Slot, Semantic, ResourceKind/Resource, точный long Amount и полный native Item с компонентами для каждой непустой стороны. Очистка слота имеет явное empty-состояние без Item; отсутствие слота в изменившемся layout не считается очисткой.
- Domain=setting: имя, явный Present и Value. Отсутствующее значение отличается от пустой строки; учитываются добавление, изменение и удаление настройки.

CONFIG/PROCESSING_INPUTS/PROCESSING_OUTPUTS относятся к фильтрам, UPGRADE — к улучшениям, VIEW_CELL/STORAGE_CELL — к ячейкам. Полные wrapped GenericStack сохраняют исходные компоненты и long amount, без усечения до int. Обычная замена только компонентов тоже оставляет оба полных состояния. ITEM использует уже отделённый after либо before, без третьего native encode.

История распознаёт схему без загрузки AE2 и без восстановления ItemStack/AEKey из модового класса. Краткая строка скрывает служебный префикс, подробности показывают оба значения. Неизвестная версия, неполная пара, несовпадение action/semantic/slot/setting и некорректный payload не принимаются за новый context. Старые записи сохраняют прежнее текстовое представление.

ClickHouse сохраняет обе стороны даже в minimal detail mode при storeNonRollbackDetails=false. Схема БД, ActionType ordinal, маршруты сети, права и формат журнала не меняются. Это сохранение доказательств аудита; автоматический откат виртуального ME-хранилища не добавлен.

## Захват и нагрузка

PriorityMenu.setPriority(int) и StorageLevelEmitterMenu.setValue(long) обёрнуты прежним вложенным Ae2MenuCapture с закрытием в finally. Исходное действие выполняется ровно один раз, исходное исключение сохраняется. Priority читается из IPriorityHost только для PriorityMenu; reporting value — из самого StorageLevelEmitterPart только для его меню, а не из отстающего клиентского поля. Новые обёртки проверяются optional preflight: семь групп hooks, 110 supplied target-классов всего.

Production capture/compare/stockAmount сохраняют категории, AuthCore, logAe2 и server-thread guards. Позиция и измерение берутся из исходного BE/part-target; переносное меню использует игрока. Нет поиска capability, загрузки чанков, опроса мира, новых тиков или дополнительных игровых действий.

Native чтение и сравнение ItemStack, копирование выбранных слотов и native сериализация изменённых предметов остаются в серверном потоке. Каждая непустая сторона изменённого слота сериализуется один раз. Неизменившиеся слоты не сериализуются; display-name/hover-name formatting для configuration producer удалён. SNBT/JSON, дисковый журнал и БД выполняются прежними worker-потоками. Полная пара требует больше native работы и места, чем прежняя неполная запись; численного ускорения этим этапом не заявлено.

Production передаёт каждую отделённую строку в существующую ограниченную очередь до сериализации следующей пары. Дополнительного списка всех сериализованных пар перед очередью нет. Data-only changes собирает список только при явном вызове dev-теста и ничего не публикует. Payload обеих сторон учитывается byte accounting; общий представительный Item учитывается консервативно повторно. Oversized-alone и противодавление сохранены, unlimited queue и overflow discard не добавлены. Level/Menu/Player не передаются worker.

## Локальная проверка

Java 21.0.10, NeoForge 21.1.247, присланные AE2 19.2.18 и GuideME 21.1.19. SHA-256 AE2: `cb19624319aaf06c5e60ac16923f2d5b8c503edc9b5fc4c4c3c65a729049c655`. Чужие JAR не менялись. Локальный AE2 профиль содержит AE2/GuideME без Create; совместная загрузка с Create проверяется CI. Локальная поправка NeoForm ограничена определением пути Java при недоступном ProcessHandle.command; оригинальный инструмент сохранён.

Ae2DeviceSettingsNativeSmoke устанавливает настоящие part/BE и открывает конкретные native меню:

| Устройства | Проверенные операции |
| --- | --- |
| Storage Bus, Import Bus, Export Bus, Formation Plane, Storage Level Emitter, Interface | Фильтр, изменение компонентов, очистка; подходящее для устройства улучшение, удаление; доступные enum settings |
| Drive, ME Chest, Cell Workbench | Установка, замена компонентов и удаление ячейки; Cell Workbench fuzzy mode через native client action |
| PriorityMenu на Storage Bus | Реальный client action setPriority(-73), повтор без изменения |
| Storage Level Emitter | Реальное setReportingValue с `(1L << 40) + 13`, повтор без изменения |

В сумме: девять конкретных устройств плюс подменю priority; filterCases=6, upgradeCases=6, cellCases=3, settingsCases=8, 52 отделённые строки. Реальные операции используют setFilter, clicked/mayPlace, ConfigButtonPacket и зарегистрированные client actions. Все строки проверяют actor/source/адрес, полную пару, компоненты, читаемую LogText строку, ordered AsyncLogStorage и Gzip replay. Настоящий stock confirm 8 → 32 отдельно проверяет полный before/after wrapped item и original target.

Маркеры: AE2_DEVICE_SETTINGS_NATIVE_OK и AE2_STOCK_CONFIGURATION_NATIVE_OK вместе с прежними AE2_NATIVE_OK / AE2_STOCK_NATIVE_OK / AE2_MENU_SCOPE_NATIVE_OK / AE2_TRANSFER_NATIVE_OK. Повтор после изменения production streaming успешен. Production capture выключен: accepted=0, adapterFailures=[]. Настоящие native операции и data-only kernels проверены отдельно от разрешённой AuthCore публикации. Положительный authenticated emit этим стендом не подтверждён.

Wrapper fault kernels двух новых преобразованных методов проверяют Operation ровно один раз, аргументы, исходное исключение и depth=0; это не все игровые варианты отказа. Добавление/удаление setting проверено синтетическими Snapshot maps, не динамической регистрацией в native ConfigManager. Cell Workbench проверяет компоненты ячейки и fuzzy setting, не содержимое настоящей сетевой ячейки или откат grid.

Ae2ConfigurationDetailsTest проверяет четыре action types, полные пары и long amounts, explicit empty/absent, metadata-only changes, malformed/legacy refusal. ProbeTag разрешает SNBT encode только event worker; проверены вес payload, неизменность захваченных метаданных, Gzip и настоящий DiskLogJournal с точным ID. Маркер AE2_CONFIGURATION_EVIDENCE_OK.

Пройдены compileJava/compileCreateSmokeJava/testAsyncPipeline/testCreatePreflight/testExactModpackPreflight/verifyMixinSafety и inventory/profile comparison/ProfileSummary. Exact preflight 110 классов не доказывает отсутствие transformed конфликтов всех 267 JAR.

Отдельный чистый запуск без AE2 дал AE2_ABSENT_NATIVE_OK. Первый локальный переход между профилями встретил временный .rsync-tmp при упаковке Gradle cache, а следующий оставил старые native AE2 smoke-классы в output и остановился на их зависимости AEKey. Удалены только сгенерированные createSmoke outputs/incremental metadata; повтор с --no-build-cache успешен. Production классы и чужие JAR для этого не менялись.

## CI

[Четыре серверных профиля и настоящая ClickHouse](https://github.com/Vlad-Ras/Logger/actions/runs/40579570285), [GUI](https://github.com/Vlad-Ras/Logger/actions/runs/40579570244). Все четыре серверных профиля, отдельная настоящая ClickHouse и GUI завершились успешно на указанной code-ревизии. Новые native device/stock cases прошли на AE2 19.2.12 и 19.2.18 вместе с Create; отдельный запуск без AE2 также успешен. GUI regression проверил реальные экраны истории/actions/confirmation/results/help на трёх размерах; это регрессия общего GUI, а не AE2 production end-to-end.

ClickHouseNativeSmoke использует изолированные fixtures всех четырёх action types: полный Item/components, обе стороны, exact long, ID/actor/source/адрес и краткий feed. Проверка работает в minimal mode с storeNonRollbackDetails=false и повторяется после нового подключения: AE2_CONFIGURATION_CLICKHOUSE_OK получен дважды. Прежние CLICKHOUSE_NATIVE_OK, два AE2_TRANSFER_CLICKHOUSE_OK и два JOURNAL_PRESSURE_CLICKHOUSE_OK сохранены. Это проверка БД, отдельно от авторизованной цепочки native gameplay → emit → GUI. Private JAR в CI не загружаются.

## Оставшаяся приёмка

Остаются остальные терминалы/меню и аддоны AE2, crafting, реальные виртуальные/составные хранилища и их rollback, произвольные сторонние keys, production AuthCore/ClickHouse/GUI/полный rollback job и согласованный transformed набор 267 JAR. Отдельно открыт native manual stop Scguns 1.5. Поезда и вырезанный портальный переход в работу не возвращаются.

Нет новых A/B/A MSPT/heap/GC замеров фермы с 8 пилами, 8 активаторами с саженцами и portable storage interface. Длительная нехватка пропускной способности остаётся причиной противодавления; прежние около 60 000 мкс/тик Observable не объявляются устранёнными.
