# AE2: точные ресурсы и фактическое количество переноса

27.10.2026. Проверенный код `227a3054109f68ed7549a7b94e159eb24eb404eb`, tree `9b2327ef0144f919879e2354055137adb31e93dc`. Основа — `4d9433050f4d8c443b37fb7c20e0b18922adad4a`; рабочая ветка — `work/2.2.0-ae2-transfers-20261027`. [Предыдущие границы захвата меню](AE2_MENU_COMPATIBILITY_2.2.0.md) сохранены. Релизный JAR не собирался.

## Исправления данных

Прежний item transfer сохранял снимок предмета и long amount в extra, но основная строка использовала насыщенный int count. Не-предметные ключи сохранялись только display name/ID/amount со всегда заданной подписью mB: полный ключ и вторичные компоненты отсутствовали, сторонний тип ресурса мог выглядеть жидкостью.

Новые ME_PUT/ME_TAKE получают компактный extra `ae2-transfer-v1|kind|resource|keyType|amount` и отделённый снимок `Kind=Ae2Transfer, Version=1` в прежнем beAfter. Снимок хранит точный long Amount, Resource/KeyType/ResourceKind, NativeUnit/AmountPerUnit. Предмет использует прежний полный itemStackNbt; для непредметного ключа добавляется полный native toTagGeneric, включая вторичные компоненты. Количество берётся из фактического результата native операции, не из запроса или simulation.

Legacy int count сохранён для совместимости предметных записей. Новые строки/подробности используют точный long из extra; у жидкостей показывается сырое количество в мБ, у других типов — количество единиц без ложной подписи жидкости. В подробностях также доступны родная единица AE2 и её масштаб. История читается без загрузки AE2 и без реконструкции стороннего key-класса; полный ключ не сериализуется повторно ради строки GUI. Native реконструкция ключа выполняется только в dev fixture для проверки.

Фоновый ClickHouse writer сохраняет новый полный transfer snapshot даже при minimal detail mode и выключенном storeNonRollbackDetails. Feed хранит компактные type/resource/long amount, target_kind различает item/fluid/resource. Схема таблиц, ActionType ordinal и формат дискового журнала не меняются. Полные ключи увеличивают объём подробностей относительно прежней неполной записи; это сохранность данных, не результат оптимизации объёма БД.

Старые extra и device inventory/settings events остаются с прежним представлением. Неизвестный/испорченный формат не принимается за v1, несовпадение summary и полного Amount отвергается при разборе context. Полный ключ не является BE-состоянием для восстановления: откат виртуального ME-хранилища этим этапом не добавлен.

## Источник и нагрузка

Production transfer сначала проверяет MODULATE, положительный результат, параметры и прежние AuthCore/категории/logAe2. Data-only transferRow не публикует строки и нужен для отдельной проверки снимков; он также проверяет реальный серверный поток до чтения IActionSource/key. Источник без честной позиции по-прежнему не получает выдуманные координаты.

Человек сохраняет UUID/имя. Machine source получает позицию своего BE/part; совместный player+machine source сохраняет человека и координаты устройства. FakePlayer получает SYSTEM без человеческого UUID. Для lambda IActionHost позиция и имя определяются из одного результата: один getActionableNode и один getOwner. Прежде blockEntity/machineName повторяли getActionableNode и getOwner. Известный BE/part обходится без дополнительного grid lookup; для part сохраняется сторона. Нет capability поиска, загрузки чанков или тиков.

Native key/item serialization и отделение NBT остаются в серверном потоке. В очереди находятся только LogEntry, строки/числа и отделённые tags; временная пара level/row не передаётся worker. SNBT/JSON, журнал и БД используют прежние фоновые потоки. Полный payload учитывается существующим byte accounting, включая oversized-alone поведение; unlimited queue и overflow discard не добавлены. Добавление полного non-item key требует дополнительной native сериализации относительно старой неполной строки. Отсутствие display-name formatting и повторных owner lookup не объявляется численным выигрышем MSPT/heap.

Прежние guards powered/NetworkStorage и finally-cleanup не перепроектировались. Положительный authenticated emit и подавление всех возможных вложенных сторонних storage callbacks не подтверждаются этим неавторизованным стендом.

## Проверки

Локально: Java 21.0.10, NeoForge 21.1.247, присланные AE2 19.2.18 и GuideME 21.1.19, без Create в native AE2 профиле. SHA-256 AE2 — `cb19624319aaf06c5e60ac16923f2d5b8c503edc9b5fc4c4c3c65a729049c655`. Чужие JAR не менялись; локальная поправка NeoForm касается только определения пути Java при недоступном ProcessHandle.command.

Ae2TransferNativeSmoke использует настоящие NetworkStorage.mount/insert/extract и StorageHelper.poweredInsert/poweredExtraction с ограниченным тестовым MEStorage и IEnergySource. Это настоящие маршрутизация/энергетический helper, не конкретная cell drive или полностью собранная ME-сеть. Проверены:

- SIMULATE 100 → 37 без изменения запаса; MODULATE 100 → 37; извлечение 20; priority/fallback 99 → 33 с балансом обоих хранилищ.
- Совместный player+machine source; позиция настоящего Interface BE и lambda grid owner ровно по одному чтению.
- Настоящий long transfer `(1L << 32) + 123`, отдельный legacy int count и точное количество в строке.
- Жидкость с custom-name component: вставка 1843, извлечение 487, баланс 1356; native AEKey.fromTagGeneric восстанавливает тот же ключ с компонентами.
- Power-limited insert 8 и extract 5, simulation без расхода энергии/ресурсов, пустая энергия и исходное исключение хранилища. Проверено число native мутаций; это не положительная проверка production публикации.
- Наличие обоих преобразованных audit hook в NetworkStorage и StorageHelper. Disabled production capture, simulation/zero и off-thread data capture не читают poison IActionSource; anonymous source не выдумывает позицию, FakePlayer не становится человеком.
- Десять отделённых записей проходят ordered AsyncLogStorage, проверку actor/source/адреса/количества, настоящую LogText строку и Gzip replay. Item components и полный fluid key сохранены. Production capture выключен: accepted=0, adapterFailures=[].

Маркеры: AE2_TRANSFER_NATIVE_OK вместе с прежними AE2_STOCK_NATIVE_OK / AE2_MENU_SCOPE_NATIVE_OK / AE2_NATIVE_OK. Отдельный запуск без AE2 даёт AE2_ABSENT_NATIVE_OK.

Ae2TransferDetailsTest проверяет Long.MAX_VALUE, item/fluid/generic units, legacy/malformed refusal и mismatch Amount. ProbeTag разрешает SNBT encoding только event worker; проверены snapshot weight, captured metadata, Gzip и настоящий DiskLogJournal с точным ID/полным ключом. AE2_TRANSFER_EVIDENCE_OK. Synthetic generic schema fixture не является native-проверкой произвольного стороннего AEKey.

Также пройдены compileJava/compileCreateSmokeJava/testAsyncPipeline/testCreatePreflight/testExactModpackPreflight/verifyMixinSafety и inventory/profile comparison/ProfileSummary. Exact preflight — 108 supplied target-классов; он не доказывает отсутствие transformed конфликтов всех 267 JAR.

[CI: четыре серверных профиля и настоящая ClickHouse](https://github.com/Vlad-Ras/Logger/actions/runs/40553635191), [GUI](https://github.com/Vlad-Ras/Logger/actions/runs/40553635236). Все четыре серверных профиля, отдельная настоящая ClickHouse и GUI завершились успешно на указанной code-ревизии. Native AE2 transfer tests прошли на 19.2.12 и 19.2.18, отсутствие AE2 также проверено. Новые native transfer tests добавлены к AE2 19.2.12 и 19.2.18. ClickHouseNativeSmoke проверяет item/fluid/generic fixtures: точные ID, полный ключ >512 символов, Long.MAX_VALUE/большой fluid amount, source/адрес и лёгкий feed в minimal mode, затем повторяет проверку после нового подключения. AE2_TRANSFER_CLICKHOUSE_OK получен дважды; это изолированная БД, не AuthCore/GUI production end-to-end. Присланные private JAR в CI не загружаются.

## Оставшаяся приёмка

Не закрыты все конкретные терминалы/ячейки/шины, crafting, составные/виртуальные хранилища, произвольные сторонние ключи и авторизованная цепочка игровое действие → БД → GUI. Нужны production конфигурации/AuthCore и полный согласованный transformed модпак. Native stop Scguns 1.5 остаётся отдельным блокером. Поезда и вырезанный портальный переход не возвращаются в работу.

Новых A/B/A MSPT/heap/GC измерений фермы 8 пил + 8 активаторов с саженцами + portable storage interface нет. Длительная нехватка пропускной способности по-прежнему может создавать противодавление. Прежние ~60 000 мкс/тик Observable не объявляются устранёнными.
