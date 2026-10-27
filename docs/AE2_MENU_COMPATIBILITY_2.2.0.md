# AE2: границы захвата меню и пакетов

27.10.2026: [добавлены полные before/after configuration events, priority и long reporting threshold](AE2_DEVICE_SETTINGS_COMPATIBILITY_2.2.0.md). Новые wrappers расширяют optional preflight до семи групп; exact supplied targets — 110. Девять конкретных устройств и priority submenu проверены native; production AuthCore и полный ME rollback остаются в приёмке. Сведения ниже описывают исторический этап до этого дополнения.

27.10.2026. Проверенный код `9250c70f3ac301353777e6217e7278721410a560`, tree `55871f1268c72b52fa26798aa2dc9f3ed684d185`. Основа — `fb36d3bb02c9c754e4da03f0579c440630c6c90b`, рабочая ветка — `work/2.2.0-ae2-actions-20261027`. Релизный JAR не собирается.

## Исправления

Пять действий AEBaseMenu — clicked, quickMoveStack, doAction, setFilter и receiveClientAction — теперь закрывают вложенный захват в finally. Исключение из native действия не оставляет глубину и прежний снимок в меню. Изменение, выполненное перед исключением, может быть сравнено с after при разрешённом production capture; исходное действие не повторяется, его результат/исключение сохраняются. Ошибка самого аудита не заменяет native исключение.

ConfigButtonPacket удерживает исходный Ae2MenuCapture в локальной переменной до выполнения native handleOnServer. Даже если callback меняет player.containerMenu, закрывается исходный захват. Адрес изменения меню и stock amount берётся из его собственного target; прежнее меню не получает адрес нового открытого интерфейса. Для переносного меню без блочного target сохраняется прежний fallback позиции игрока.

SetStockAmountMenu.confirm проверяет категории/AuthCore/logAe2 и серверный поток до чтения stock configuration. При отключённом захвате выполняется только native confirm. Before/after находятся в локальных переменных; исключение не удерживает предмет или снимок в поле меню. Снимок использует тот же host.getConfig, что и native confirm. Ошибка чтения обозначается null и не превращается в ложный пустой фильтр.

Production capture, compare и stockAmount проверяют серверный поток до обхода слотов/настроек и сериализации. Отделённый snapshot kernel доступен для native-тестов, не публикует строки и не включает авторизацию. Состав выбранных SlotSemantic и формат ME-записей не менялись.

Ae2MixinPlugin проверяет все пять групп перехватов по сырому байткоду без загрузки/инициализации AE2. Поддерживаются проверяемые 19.2.12 и 19.2.18; при отсутствии AE2 либо неподдержанном контракте интеграция не применяется. Диагностика несовместимости выдаётся при загрузке, без повторов по тикам. Это проверка optional контрактов, не доказательство отсутствия конфликтов полного transformed модпака.

## Локальные доказательства

Стенд: Java 21.0.10, NeoForge 21.1.247, присланный AE2 19.2.18 (SHA-256 `cb19624319aaf06c5e60ac16923f2d5b8c503edc9b5fc4c4c3c65a729049c655`) и GuideME 21.1.19. Исходные JAR не менялись. Локальный native профиль содержит AE2/GuideME без Create; CI дополнительно проверяет совместную загрузку с Create. Локальная поправка NeoForm ограничена определением пути Java при недоступном ProcessHandle.command; оригинальный инструмент сохранён.

Ae2NativeSmoke использует настоящий AEBaseMenu с тестовым BlockEntity-host, настоящие AE2 slots/ConfigManager, native client action и ConfigButtonPacket, а также настоящий блок Interface/SetStockAmountMenu.confirm. Проверены:

- Выбор CONFIG/UPGRADE/STORAGE_CELL/STORAGE/MACHINE_OUTPUT; исключение player inventory и crafting grid; отделённость ItemStack и экспортированных settings от последующих изменений.
- Ноль чтений slots/settings при отключённых категориях и отказ snapshot с другого потока до чтения содержимого.
- Настоящий вложенный setFilter внутри client action с исключением: одна мутация, исходное исключение и очистка глубины/снимка. Отдельный native setFilter с отказом inventory также закрывает scope.
- Все пять уже преобразованных wrappers отдельно получают Operation с injected fault: ровно один вызов и сохранность внешнего вложенного scope. Это проверка wrapper kernel, не игрового поведения всех пяти действий.
- Настоящий packet settings с callback, меняющим меню, и с callback, бросающим исключение. Оба исходных scope очищаются; отдельно проверяется адрес старого BE-target после смены меню.
- Настоящий interface stock 8 → 32 и отказ следующего confirm: native amount/result/исключение сохраняются; при выключенном capture только один native getConfig, без дополнительного чтения Logger. В этой проверке navigation callback изолирован, переход по GUI не проверяется. Отдельно helper возвращает null при ошибке чтения и фактический stock после восстановления.

Маркеры успешного запуска: AE2_STOCK_NATIVE_OK, AE2_MENU_SCOPE_NATIVE_OK, AE2_NATIVE_OK. Production capture оставлен выключенным: accepted=0, adapterFailures=[].

Контрольный запуск временно заменяет только скомпилированный AEBaseMenuMixin прежним классом из основы. На том же настоящем client action с исключением получен ожидаемый AE2_NATIVE_FAILED: `AE2 action retained depth/snapshot`. Исходный class-файл кандидата восстановлен в finally. Это воспроизводит утечку scope старого перехвата; Gradle exit 0 после штатного останова fixture не считается успехом игрового теста.

Отдельный запуск без AE2 проверяет загрузку сервера и отсутствие optional targets: AE2_ABSENT_NATIVE_OK. Общие compileJava/compileCreateSmokeJava/testAsyncPipeline/testCreatePreflight/testExactModpackPreflight/verifyMixinSafety пройдены; сырой exact контракт — 108 target-классов. Ae2ContractsTest проверяет удалённые native methods всех пяти групп и отсутствующий stock host: AE2_CONTRACTS_OK.

## CI и повторение

[Матрица четырёх серверных профилей и реальная ClickHouse](https://github.com/Vlad-Ras/Logger/actions/runs/40540543908), [GUI](https://github.com/Vlad-Ras/Logger/actions/runs/40540543721). В матрицу добавлены native AE2 menu/packet regression для 19.2.12 и 19.2.18 и отдельный запуск без AE2. Все четыре серверных профиля, отдельная настоящая ClickHouse и GUI завершились успешно на указанной code-ревизии. Native AE2 steps прошли на 19.2.12 и 19.2.18, отдельный absence step также успешен. ClickHouse regression сохранил прежние CLICKHOUSE_NATIVE_OK и два JOURNAL_PRESSURE_CLICKHOUSE_OK; это регрессия хранилища, не подтверждение production AE2 emit.

Для отдельного неавторизованного тестового мира:

```bash
./gradlew runServer -Pae2Smoke -PcompatAe2Version=19.2.18
./gradlew runServer -Pae2Absent
```

Нужны EULA и отдельный каталог run-ae2. Fixture останавливает сервер. Проверяются маркеры, отсутствие AE2_NATIVE_FAILED / integration disabled / MixinApplyError / MixinTransformerError и ошибок запуска; одного exit code недостаточно. Присланные private артефакты в CI не загружаются.

## Что ещё открыто

Это проверка границ захвата и выбранных native contracts. Не подтверждены все конкретные ME-меню/шины/терминалы, виртуальные и составные хранилища, крафт, жидкости/сторонние ключи, автоматизация сети и полный transformed набор 267 JAR. Нужны авторизованные end-to-end строки, полный history/ClickHouse/GUI/rollback job на production конфигурации.

Не добавлены тики, опрос мира, дополнительные capability lookup или вызовы игровых действий. Native снимки и сравнения предметов AE2 остаются синхронными; SNBT/JSON идут через прежнюю очередь. Нет численного MSPT/heap результата. Сохранены AuthCore, категории и административный доступ; длительное противодавление и A/B/A фермы 8 пил + 8 активаторов с саженцами + portable storage interface остаются открытыми. Scguns manual-stop blocker и исключённые пользователем поезда/портальный переход этим этапом не меняются.


## Дополнение: точные ME transfers, 27.10.2026

[Добавлены полный ключ non-item ресурса, точное long количество, typed feed и обязательные подробности ME transfer в minimal mode](AE2_TRANSFER_COMPATIBILITY_2.2.0.md). Lambda machine source получает BE/имя из одного node/owner lookup; FakePlayer не получает человеческий UUID. Native сеть/энергетический helper проверены с ограниченным тестовым хранилищем и отдельно от production AuthCore. Дополнительная native сериализация non-item key остаётся синхронной; новых MSPT/heap замеров и отката виртуальной ME-сети нет.
