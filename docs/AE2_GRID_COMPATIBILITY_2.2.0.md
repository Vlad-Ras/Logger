# AE2: запитанная ME-grid и заполненный инвентарь

28.10.2026: [настоящий расчёт рецептов, Molecular Assembler и завершение CPU задания](AE2_ASSEMBLY_COMPATIBILITY_2.2.0.md): ME_CRAFT_FINISH с native JobId/CPU и planned-output; проверены log → planks → 8 sticks, honey block + 4 glass bottle remainders и отмена с возвратом 2 logs. Обе версии AE2: 7 новых строк, 3 jobs, 2 завершения. Production AuthCore и остальные рецепты/машины остаются в приёмке; ниже — предыдущие этапы.

28.10.2026: [приём заказов автокрафта и подтверждённая UI отмена AE2](AE2_CRAFTING_COMPATIBILITY_2.2.0.md): actual CPU/native JobId, source address/actor, полный ключ и planned-output long, реальные errorCode и partial failure. Два append-only типа; обе версии AE2 дали по 14 строк (5 принятий, 5 отказов, 4 отмены). Полный расчёт рецептов/производство и production AuthCore остаются в приёмке; ниже — предыдущие этапы.

28.10.2026. Продолжение [удалённых действий Pattern Access Terminal](AE2_REMOTE_PATTERN_COMPATIBILITY_2.2.0.md). Этот пакет добавляет native-проверки существующего capture; production-код и его стоимость не меняются. Релизный JAR не создаётся.

## Граница нового стенда

Предыдущий remote smoke вручную конструирует ContainerTracker и регистрирует его в byId. Новый Ae2GridNativeSmoke этого не делает: настоящие Pattern Access Terminal part, Pattern Provider block/part и Creative Energy Cell устанавливаются в изолированном мире. Их собственные managed nodes инициализируются обычными серверными тиками; GridHelper создаёт явные тестовые соединения. Терминал обнаруживает активных поставщиков собственным broadcastChanges и сам выдаёт runtime ID. Чтение byId в fixture только проверяет соответствие выданного ID реальному host.

Стенд ждёт настоящих powered/booted/channel состояний, не подменяет IGrid, IGridNode или energy/pathing services и не вызывает их внутренние tick-функции вручную. Принудительно удерживается один тестовый chunk; после проверки установленные блоки/узлы удаляются и прежнее forced-состояние восстанавливается. Это dev-only поведение, отсутствующее в рабочем Logger. Соединение соседних кабелей, сохранение и загрузка мира этим не подтверждены.

Выходящие full/incremental PatternAccessTerminalPacket проходят настоящий registry-aware write/decode, включая полные компоненты pattern. Очищающий пакет сбрасывает detached receiver model. InventoryActionPacket также проходит write/decode и настоящий handleOnServer с ID из discovery. Модель приёмника не является клиентским экраном: логин, транспорт через подключённого клиента, обработчик client screen и отрисовка удалённого GUI остаются отдельной приёмкой.

## Сценарии

| Сценарий | Проверяемый результат |
| --- | --- |
| Настоящая сеть | Обнаружены BE и part, ID в full payload совпадают с native tracker и host |
| Удалённое действие | Вставка и извлечение через декодированный InventoryActionPacket; provider address и дельта проверяются прежним inspector |
| Инкрементальное обновление | Стабильный provider ID, полный pattern после вставки и явная пустая запись после извлечения |
| Фильтры | Скрытый поставщик исключён в VISIBLE и снова обнаружен в ALL; настройки и ID возвращаются к исходному сценарию |
| Удаление part | Native full update удаляет его из receiver model; действие со старым ID не меняет cursor и не создаёт строк |
| Потеря питания | Отсоединённый terminal становится inactive, очищает список; старые ID отвергаются |
| Возвращение сети | После powered/booted terminal обнаруживает оставшийся provider с новым ID; старый ID не работает, новый принимает действие и обновляет payload |
| Заполненный Inventory | SHIFT_CLICK и MOVE_REGION, по 0/1/2 свободных слота: шесть сравнений с прямым вызовом AE2 без наблюдателя |

В каждом сценарии заполнения сравниваются все physical provider slots и все основные player inventory slots с native baseline. Состав строк выводится из окончательного before/after каждого слота: тип TAKE/PUT, количество, индекс и полные pattern components. Это проверка наблюдателя, а не исправление алгоритма AE2. Если собственная операция AE2 изменяет слот неожиданным образом, ожидается именно её фактический результат; Logger не повторяет игровое действие для исправления или восстановления.

Положительный scope устанавливается только как data-only inspector, по прежнему механизму remote smoke. Production AuthCore не подменяется, Ae2Audit.enabled остаётся false; авторизованная отправка рабочего журнала этим не доказывается. Новые строки проверяются как detached evidence. Прежние remote pipeline, journal, ClickHouse и GUI регрессии выполняются отдельно.

## Проверки и остаток

Проверяемый код — `d29f1800473d915d1d761fb7572440f97822a52b`, tree `73a1cd6bf54c1256ad0d632bf53f67b7f8c9f540`, база `83a8909f4b37440453802e07591c0eac3a195a7f`; ветка `work/2.2.0-ae2-grid-20261028`. [Серверная матрица и настоящая ClickHouse](https://github.com/Vlad-Ras/Logger/actions/runs/40687465963), [GUI](https://github.com/Vlad-Ras/Logger/actions/runs/40687465836). Native AE2 шаги выполняются на 19.2.12 (current-fd134) и 19.2.18 (client-s4-core). Это публичные артефакты CI на NeoForge 21.1.219, а не новый запуск всех присланных JAR.

Все четыре серверных профиля, отдельная настоящая ClickHouse и GUI завершились успешно на этой ревизии. На обеих версиях AE2 получены AE2_GRID_NATIVE_OK: saturationCases=6, wirePackets=10, clears=6, rows=9. Это девять итоговых физических дельт всего grid fixture, а не девять пользовательских операций. Длительность fixture составила 12 серверных тиков для 19.2.12 и 5 для 19.2.18; это ожидание готовности изолированной сети, не измерение производительности и не сравнение версий.

Сохранены remote regressions: 11 строк в 19.2.12 и 13 в 19.2.18, где также проверены quick move и 128 full candidates. Пройдены отсутствие AE2/Create, Create, Backpacked и actor/aircraft smoke; compileJava/compileCreateSmokeJava/testAsyncPipeline/testCreatePreflight/verifyMixinSafety успешны в четырёх профилях. AE2_REMOTE_CLICKHOUSE_OK получен дважды с прежним fixture полного evidence и новым подключением; CART_GUI_CLIENT_OK подтверждает общую историю/actions/confirmation/results/help на 320×240, 640×360 и 960×540, без заявления remote client end-to-end.

Локальные ProfileSummaryTest, inventory-modpack, profile-comparison и git diff --check прошли. Полная локальная компиляция не завершена: после воспроизводимого ограничения ProcessHandle у NeoForm и отдельной поправки только определения пути Java загрузка зависимостей получила HTTP 403. Локальный native/полный exact-modpack preflight этим проходом не засчитывается; игровые результаты берутся из CI.

Общий AE2_NATIVE_OK теперь выдаётся только после завершения grid fixture; AE2_GRID_NATIVE_OK обязателен в AE2 шагах CI. Ошибка или превышение 260 настоящих серверных тиков даёт AE2_NATIVE_FAILED и останавливает стенд, не выдавая успешный общий маркер. Класс включён в ограниченный ae2Smoke source set и не попадает в production sources.

Открыты отрисованный remote client GUI и реальный сетевой сеанс, production AuthCore/ClickHouse/rollback end-to-end, addon PatternContainer, NOT_FULL/pinned-host и остальные комбинации фильтров, кабели/перезагрузка мира, autocrafting jobs, виртуальный ME rollback и все взаимодействия полного набора 267 JAR. Этот пакет не заменяет A/B/A MSPT/heap/GC фермы с 8 пилами + 8 активаторами и portable storage interface; прежние около 60 000 мкс/тик Observable не объявляются устранёнными. Native manual stop Scguns 1.5 остаётся отдельным блокером. Поезда и вырезанный портал не возвращаются в работу.
