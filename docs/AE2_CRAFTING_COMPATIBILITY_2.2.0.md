# AE2: приём заказа автокрафта и подтверждённая отмена

28.10.2026: [настоящий расчёт рецептов, Molecular Assembler и завершение CPU задания](AE2_ASSEMBLY_COMPATIBILITY_2.2.0.md): ME_CRAFT_FINISH с native JobId/CPU и planned-output; проверены log → planks → 8 sticks, honey block + 4 glass bottle remainders и отмена с возвратом 2 logs. Обе версии AE2: 7 новых строк, 3 jobs, 2 завершения. Production AuthCore и остальные рецепты/машины остаются в приёмке; ниже — предыдущие этапы.

28.10.2026. Продолжение [native ME-grid](AE2_GRID_COMPATIBILITY_2.2.0.md). База `4e0b597f6d8e150f2f8e4c31ec8f9051c54b0214`; ветка `work/2.2.0-ae2-crafting-20261028`. Релизный JAR не создаётся.

## Какие события добавлены

В конец ActionType добавлены ME_CRAFT_REQUEST и ME_CRAFT_CANCEL. Прежние ordinal не изменяются. Это записи аудита задания, отдельно от ME_PUT/ME_TAKE, ITEM_CRAFT и физических слотов. Захват подчиняется прежним Logger/AuthCore/logAe2 и категории 2; команды и права не расширяются.

CraftingCPUCluster.submitJob оборачивается целиком. После фактического успешного результата сохраняются принятый план, конкретный CPU и ID его нового native link. Для standalone заказа ICraftingSubmitResult.link равен null по контракту AE2; ID берётся непосредственно из craftingLogic.getLastLink конкретного процессора. Поиск CPU или задания по сети для аудита не выполняется. Автоматический выбор остаётся у CraftingService; логгер получает уже выбранный native CPU.

CraftingService.submitJob также обёрнут: его запись создаётся только при возвращённом отказе. Поэтому успешный запрос не дублируется в сервисе и процессоре. Сохраняется настоящий errorCode, в том числе отсутствие/неподходящий CPU, занятость, недостаток памяти, неполный план и недостающий ингредиент. Прямой отказ стороннего вызова cluster.submitJob в обход сервиса этим пакетом не перехватывается. Неизвестный источник без честного адреса не дополняется выдуманными координатами.

CraftingCPUMenu.cancelCrafting перехватывает единственный фактический вызов cancelJob на выбранном CPU. Перед ним фиксируются исходное меню/актор/адрес, текущий link и исходный планируемый результат. Строка отмены появляется только если этот link перешёл в isCanceled и не isDone. Пустой процессор, повторная отмена и завершённое без отмены задание не создают ложную ME_CRAFT_CANCEL. CraftingStatusMenu наследует этот путь. Отменяющий игрок может отличаться от первоначального заказчика; обе записи связываются native JobId.

Original вызывается один раз; результат и исключение не заменяются. Если CPU успел создать новый совпадающий по finalOutput job и затем original бросил исключение, запись принятия сохраняет JobId и NativeThrew. Старый неизменившийся link не выдаётся за новое задание. Подтверждённая отмена также фиксируется из finally при исключении после неё. Неизвестные частичные эффекты addon callback без доступного подтверждения состояния остаются за этой границей.

## Адрес, количество и данные

При отправке основные dim/x/y/z относятся к source host IActionSource: настоящий block/part или позиция игрока для переносного/безадресного player source. Местоположение и актор фиксируются до native вызова; переключение player.containerMenu их не переназначает. Для UI отмены основным адресом служит host исходного CraftingCPUMenu. CPU хранит собственные Dimension, BoundsMin/BoundsMax и имя; адрес источника не подменяется адресом процессора. UUID/имя актора используют прежний CauseContext; machine source не приписывается ближайшему игроку.

Ae2Crafting v1 содержит typed Outcome, ResourceKind/Resource/KeyType, точный long Amount, QuantityMeaning=planned-output, CPU, JobId при наличии и ErrorCode отказа. Для предмета сохраняется полный native ItemStack ключа с компонентами в ITEM; количество задаёт отдельный Amount, а не count этого stack. Для жидкости/нестандартного ключа сохраняется полный native key NBT. Сводка feed содержит long и результат; GUI показывает понятную метку и сведения о задании. Внутренний prefix не выдаётся за подпись предмета.

Количество отмены — исходный планируемый finalOutput задания, не число оставшихся изделий или возвращённых ингредиентов. Принятый план не является доказательством производства/доставки предметов. Запись отмены не является подтверждением полного возврата ресурсов. Обычный item rollback для этих типов не запускается; native тест вызывает настоящий rollback kernel и проверяет отказ без выдачи/изъятия предметов.

Обе записи входят в container preset и таблицу containers; ITEM/evidence обязательны и в minimal mode с storeNonRollbackDetails=false. Существующая схема БД, journal и разрешения сохраняются. Это новые append-only action значения; к старым строкам новые детали не добавляются задним числом.

## Стоимость и потоки

Нет новых production тиков, обхода grid/рецептов/слотов, ожидания Future, повторного submit/cancel или дополнительной симуляции AE2. Читаются только finalOutput текущего плана, источник и несколько полей выбранного CPU/link. Plan.usedItems/emittedItems/patternTimes логгер не перечисляет. Для вызова через сервис возможны два временных контекста, но полные NBT сериализуются только для создаваемой строки, один раз. Цена контекстов и getter/key reads существует; численный выигрыш MSPT/heap не заявляется.

Живые AEKey/CPU/link читаются синхронно в серверном потоке. Scope не уходит в worker; ссылки очищаются в finally и finish идемпотентен. Постоянного реестра заданий и ThreadLocal нет. В прежнюю ограниченную очередь поступает LogEntry с отделёнными значениями/NBT. SNBT/JSON, journal и БД выполняются фоном; byte weight и backpressure сохраняются. У неавторизованных/отключённых hooks original выполняется без чтения plan/source/CPU для аудита. Data inspectors не включают AuthCore и не пишут в production storage.

## Native-проверки и CI

Проверенный код — `1fe03823ecbe34a2bc14b2830937b67ab3ad5e12`, tree `31217fc0a75b868e65d0feb1ee4dc5cb2449e839`. [Четыре серверных профиля и настоящая ClickHouse](https://github.com/Vlad-Ras/Logger/actions/runs/40739207284), [GUI](https://github.com/Vlad-Ras/Logger/actions/runs/40739207294) завершились успешно. CompileJava/compileCreateSmokeJava/testAsyncPipeline/testCreatePreflight/verifyMixinSafety прошли во всех профилях; native AE2 выполнен на 19.2.12 и 19.2.18 вместе с Create. AE2_CRAFTING_NATIVE_OK на обеих версиях: accepted=5, rejected=5, cancelled=4, rows=14. Прежние remote 11/13 строк, quick move 18-only через 128 full candidates, grid 6 saturation cases/9 строк, absence AE2/Create, Create/Backpacked и actor/aircraft regressions сохранены.

AE2_CRAFTING_EVIDENCE_OK проверяет append-only типы, category gate, точный Long.MAX_VALUE, typed outcome, full key, worker-only SNBT и byte weight, Gzip/настоящий DiskLogJournal с тем же ID/актором/адресом и отказ malformed summary. AE2_CRAFTING_CLICKHOUSE_OK получен дважды: accepted/refused/cancelled, полный ITEM или non-item key, CPU/JobId и actor/address, exact ID/feed/details в minimal mode и повторное чтение после нового подключения. GUI regression отрисовал общие history/actions/confirmation/results/help на трёх размерах; это не положительный production игровой end-to-end с новыми заданиями.

Первый CI на `707c410` завершился ошибкой тестового окружения: detached storage test вызвал LogText.toChatLine, чей vanilla hover codec требует предварительной инициализации реестров Minecraft. Исправлены только проверки изолированного хранилища: они проверяют summary label без игрового renderer. Настоящий LogText проверяется для native строк на запущенном сервере; GUI regression выполняется на настоящем клиенте. Исправление не меняет production payload, авторизацию или игровые результаты.

Оба исходных архива восстановлены без изменения. Присланный AE2 19.2.18 имеет SHA-256 `cb19624319aaf06c5e60ac16923f2d5b8c503edc9b5fc4c4c3c65a729049c655`; все 1 826 его class-файлов побайтно совпали с публичным Maven artifact 19.2.18 из CI. Отдельно совпали CraftingService, CraftingCPUCluster, CraftingCPUMenu и CraftingCpuLogic. Это переносит raw контракт новых AE2 целей на присланный байткод, но не является новым полным exact-modpack preflight или transformed запуском всех 267 JAR. Приватные JAR в CI не загружались.

Локально пройдены Java 21 ProfileSummaryTest, Python inventory/profile comparison, JSON mixin config, append-only enum prefix и git diff --check. Полная локальная Gradle/native сборка после очистки окружения этим этапом не засчитывается; новые игровые результаты получены из указанного CI.

Ae2GridNativeSmoke дополнен настоящим Crafting Storage 1k в запитанной native grid. Узлы и кластер инициализируются обычными серверными тиками; используются явные fixture links. Ae2CraftingNativeSmoke вызывает настоящий CraftingService/CPU/CraftingCPUMenu и настоящие native links, но передаёт небольшие управляемые ICraftingPlan. Это приём, отмена и состояние исполнения; асинхронный расчёт рецептов и производство цепочкой molecular assemblers этим не подменяются.

Сценарии: auto/explicit CPU; INCOMPLETE_PLAN, CPU_TOO_SMALL, NO_SUITABLE_CPU_FOUND, MISSING_INGREDIENT, CPU_BUSY; standalone link с null result.link; полные pattern components; machine fluid 5 000 000 000; native client action cancelCrafting, idle/repeat и смена открытого меню; завершение job без ложной отмены; исключения после admission/cancel, вызов original один раз и сохранение его исключения; disabled poison inputs и off-thread отказ; detached worker/replay и запрет item rollback.

Завершение контролируемого standalone job проверяется через настоящий craftingLogic.insert: native link без requesting machine возвращает 0 фактически доставленных единиц, хотя CPU завершает job. Fixture сохраняет именно этот результат, а не ожидает выдуманную доставку 2 diamonds. Положительный результат игрового производства из этого сценария не выводится.

Положительные строки возвращают data-only inspectors до/после реальной операции. Преобразованные wrappers отдельно проходят disabled/fault проверки. Production enabled остаётся false; AuthCore/ключи не подменяются. Это native операции и production data kernels, не положительная authenticated публикация через все hooks.

Raw preflight расширен на три mixin группы: service, CPU cluster и CPU menu. Проверяются signatures/INVOKE, публичные getters и craftingLogic field, ровно один cancelJob call; удаление methods/field и дублирование cancel call должны отключить всю AE2 интеграцию. Версии по-прежнему ограничены 19.2.12/19.2.18.

## Остаток

Не закрыты полный native расчёт рецептов и цепочки assembler/processing, все addon CPU/requester/PatternContainer и отмены вне штатного UI пути, отдельная история pause/resume и завершения, сохранение/перезагрузка мира, индекс поиска по JobId и переносные/межмерные меню во всех сценариях. Нужны полный согласованный набор 267 JAR с конфигами/скриптами, production AuthCore → ClickHouse → GUI и безопасный виртуальный ME rollback.

Отрисованный remote клиент Pattern Access Terminal остаётся отдельным пунктом предыдущего пакета. Native Scguns 1.5 manual stop и A/B/A MSPT/heap/GC фермы 8 пил + 8 активаторов с саженцами + portable storage interface остаются открытыми. Прежние около 60 000 мкс/тик Observable не объявляются устранёнными. Поезда и вырезанный портал не возвращаются в работу.
