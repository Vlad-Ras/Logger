# AE2: расчёт рецептов, сборщик и завершение задания

29.10.2026: [отмена начатой сборки и сохранённый мир AE2](AE2_LIFECYCLE_COMPATIBILITY_2.2.0.md): отдельный dev/CI стенд с тремя server processes проверяет mid-job cancel, native JobId/inventories после disk save/load и завершённое idle состояние при следующем restart. В 19.2.12 сохраняется работающий job, в 19.2.18 — job на штатной паузе; in-flight assembler продолжает уже принятый рецепт. Production capture/авторизация не меняются; ниже — предыдущие этапы.

28.10.2026. Продолжение [приёма и отмены заказов](AE2_CRAFTING_COMPATIBILITY_2.2.0.md). База `510c207d27feace3aee80ccc01fcfadfb94064ed`; ветка `work/2.2.0-ae2-assembly-20261028`. Релизный JAR не создаётся.

## История завершения

В конец ActionType добавлен ME_CRAFT_FINISH. Прежние ordinals, схемы БД, команды и права не изменяются. Тип входит в category/container preset 2, получает понятную подпись, typed feed и обязательные ITEM/evidence в minimal mode, как прежние job receipts. Ae2Crafting v1 допускает новый Outcome=finished только с этим типом; QuantityMeaning остаётся planned-output.

Новый CraftingCpuLogicMixin оборачивает native finishJob(boolean). Capture создаётся только для success=true и после прежних Logger/AuthCore/logAe2/category/server-thread guards. До original фиксируются link, исходный finalOutput, CPU source и границы процессора. Original вызывается один раз. Из finally строка создаётся только если прежний link не отменён и больше не является текущим заданием CPU; неизменившееся задание не объявляется завершённым. Повторный finish inspector пуст, idle CPU не даёт scope. Ошибка после подтверждённой очистки job сохраняет NativeThrew; исходное исключение не подменяется ошибкой аудита.

Основной адрес — core CPU из его собственного MachineSource; Dim/BoundsMin/BoundsMax и JobId находятся в полных details. Это действие процессора, поэтому actor нейтральный: текущий/ближайший игрок не назначается автором. Первоначальный заказчик остаётся в ME_CRAFT_REQUEST с тем же native JobId. Постоянный индекс заданий и отдельный поиск по JobId этим не добавляются.

Статус «завершён процессором» описывает native состояние задания. AE2 может закончить standalone job, даже когда его crafting link вернул 0 принятых единиц, а окончательная вставка в хранилище ещё выполняется. Поэтому ME_CRAFT_FINISH не является ITEM_CRAFT, квитанцией о доставке или готовым rollback планом. Количество относится к исходному планируемому результату; фактический баланс в тестах проверяется отдельно.

## Настоящий расчёт и производство

Ae2AssemblyNativeSmoke запускается после прежней grid регрессии в том же изолированном запитанном мире. Добавляются настоящий Molecular Assembler рядом с Pattern Provider, ME Drive с Item Cell 1k и реальные crafting patterns из RecipeManager/PatternDetailsHelper. Сборщик соединяется с provider естественным соседством; для drive используется явный fixture link. Узлы, storage mounts, crafting provider и машины обслуживаются обычными серверными тиками AE2. Внутренние tickingRequest/tickCraftingLogic вручную не вызываются.

Рецепты рассчитываются настоящим beginCraftingCalculation с REPORT_MISSING_ITEMS. Fixture проверяет Future.isDone на следующих серверных тиках; get вызывается только после готовности с нулевым timeout. ICraftingPlan не подменяется, готовые изделия не вставляются вручную для имитации производства.

| Сценарий | Реальный результат |
| --- | --- |
| Нет ингредиентов | Native simulation/missingItems; submit возвращает INCOMPLETE_PLAN; CPU не начинает работу |
| Два зависимых рецепта | В ячейку внесены 2 oak logs. Настоящий план oak log → oak planks → 8 sticks содержит два patterns и требует 1 log. После обычных тиков: 1 log, 0 planks, 8 sticks; assembler и provider return buffer пусты |
| Crafting remainder | Из 4 honey bottles сборщик произвёл 1 honey block; в реальную ячейку вернулись 4 glass bottles; исходных honey bottles не осталось |
| Отмена рассчитанного задания | Настоящий план на 16 sticks требует 2 logs. После admission вызывается штатный client action cancelCrafting до следующего machine tick. CPU возвращает 2 logs, planks/sticks не появляются; completion inspector не выдаёт false finish |

Проверяются новый уникальный native JobId для каждого из трёх jobs, связка request/finish/cancel, точный CPU/source address, отсутствие приписывания CPU finish текущему игроку, worker FIFO, полный ITEM/evidence, Gzip, LogText и отказ настоящего rollback kernel от выдачи/изъятия предметов по job receipt. Completion wrapper отдельно проходит success=false/true с fault Operation: исходный flag, один вызов и то же исключение. Disabled и off-thread capture не включают аудит. Два законченных производства плюс отменённый job дают 7 строк: один отказ, три принятия, два завершения, одна отмена.

Это data-only inspectors до/после настоящих игровых операций. В production hook scope живёт только внутри finishJob; в dev fixture inspector удерживается до наблюдаемого окончания асинхронного сценария. Рабочий AuthCore не обходится, положительный authorized append через все hooks остаётся отдельной приёмкой. Фактические балансы Drive проверяют выбранные рецепты, а не каждую внутреннюю ME transfer запись при выключенном production capture.

## Стоимость и проверки

Новый hook вызывается при native завершении задания, без дополнительных production тиков, расчётов рецептов, обхода grid, чтения всех слотов или polling Future. Только live key/CPU/link читаются и переводятся в native NBT на сервере; SNBT/JSON, journal и БД идут worker. Ссылки scope очищаются в finally, постоянного registry/ThreadLocal нет. Одна строка на завершение поступает в прежнюю ограниченную очередь с прежним byte accounting и backpressure; численный MSPT/heap выигрыш не заявляется.

Raw preflight проверяет новый WrapMethod и Shadow cluster; отсутствие finishJob/cluster отключает всю optional AE2 интеграцию. Требуется публичный CPU.getSrc и прежние key/link getters. Поддержаны только 19.2.12/19.2.18. Это 13/14 mixin групп по версии, без утверждения о новом полном transformed прогоне всех 267 JAR.

Проверенный код — `0b15b35ac9b1e27be49390ab2780915fbb62cadc`, tree `d11f21fe3aa6ef12cfc61da725c1e738782ca06d`. [Четыре серверных профиля и настоящая ClickHouse](https://github.com/Vlad-Ras/Logger/actions/runs/40753707160), [GUI](https://github.com/Vlad-Ras/Logger/actions/runs/40753707024) успешны. Native на 19.2.12 и 19.2.18 дал AE2_ASSEMBLY_NATIVE_OK: finished=2, rows=7, jobs=3 с перечисленными фактическими балансами. Сохранены прежние 14 crafting receipts, remote 11/13 строк (quick move/128 full candidates только на 19.2.18) и grid 6 saturation cases/9 строк. Пройдены absence AE2/Create, Create/Backpacked/actor/aircraft и compileJava/compileCreateSmokeJava/testAsyncPipeline/testCreatePreflight/verifyMixinSafety во всех четырёх профилях.

Ae2CraftingDetailsTest расширен четвёртым исходом finished, нейтральным actor и nullable UUID, append-only ordinal и category gate. Сохранены Long.MAX_VALUE, полный key/ITEM, planned-output, worker-only SNBT, queue byte weight и настоящий DiskLogJournal/Gzip с тем же ID. AE2_CRAFTING_CLICKHOUSE_OK получен дважды: accepted/refused/cancelled/finished сохраняют полные details, CPU/JobId, nullable actor, адрес и exact feed/ID в minimal mode после нового подключения. Общая GUI регрессия отрисовала history/actions/confirmation/results/help на трёх размерах; это не authenticated end-to-end игровой записи.

Локально прошли Python inventory/profile comparison, JSON mixin config, проверка неизменности прежнего enum prefix и git diff --check. Java 21 ProfileSummaryTest выполнен в CI. Полная локальная Gradle/native сборка после очистки окружения этим проходом не засчитывается. Новая цель CraftingCpuLogic входит в ранее проверенные 1 826 совпавших class-файлов присланного/публичного AE2 19.2.18; это прежнее доказательство raw эквивалентности, не новый запуск всего модпака.

## Открытые границы

Проверены выбранные vanilla crafting цепочки и возврат container remainder. Не закрыты все processing/smithing/stonecutting/fluid/addon recipes и requesting machines, отмена во время уже начатой сборки, переполненные/внешние составные хранилища, сохранение/перезагрузка мира, отдельная история pause/resume и полноценный виртуальный ME rollback. Неизвестные сторонние callback эффекты не исправляются повторным исполнением native действий.

Остаются отрисованный remote Pattern Access Terminal клиент и полный production AuthCore → ClickHouse → GUI путь на согласованном модпаке с конфигами/скриптами. Совпадение raw AE2 байткода с публичным артефактом, зафиксированное предыдущим пакетом, не заменяет transformed совместимость всего набора. Native Scguns 1.5 manual stop и A/B/A MSPT/heap/GC фермы 8 пил + 8 активаторов с саженцами + portable storage interface остаются открытыми. Прежние около 60 000 мкс/тик Observable не объявляются устранёнными; поезда и вырезанный портал в работу не возвращаются.
