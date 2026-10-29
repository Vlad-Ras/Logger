# AE2: отмена начатой сборки и сохранённый мир

29.10.2026: [отмена связанного заказа AE2 машиной](AE2_REQUESTER_CANCEL_COMPATIBILITY_2.2.0.md): новый nexus cancel hook сохраняет requester/CPU/JobId и planned output с нейтральным actor, подтверждённая отмена перед ошибкой получает NativeThrew; CPU flag/order protocol защищает существующую UI запись. Четыре native cases дополняют external processing; production AuthCore приёмка остаётся открытой. Ниже — предыдущие этапы.

29.10.2026: [внешний processing route и автономный заказ ME Interface](AE2_PROCESSING_COMPATIBILITY_2.2.0.md): реальная Blast Furnace/Import Bus, ожидание топлива и возврата, частичная доставка и сохранение исходного planned amount; выбранный Interface с Crafting Card инициирует собственный non-standalone job и получает результат в stock. Это dev acceptance без изменения production hooks; ниже — предыдущие этапы.

29.10.2026. Продолжение [расчёта рецептов и Molecular Assembler](AE2_ASSEMBLY_COMPATIBILITY_2.2.0.md). База `701b58ee5f6297de1a7204f8a2764622c6118b83`; ветка `work/2.2.0-ae2-lifecycle-20261029`. Этот пакет расширяет dev acceptance и CI. Production hooks, типы событий, схемы БД, права и AuthCore не меняются; релизный JAR не создаётся.

## Что проверяется

Ae2LifecycleNativeSmoke запускает три отдельных server processes в одном отдельном `run-ae2-lifecycle` мире. Штатный shutdown сохраняет мир на диск; следующий процесс загружает те же blocks, cell, CPU и assembler. Прямые native readFromNBT/writeToNBT, подмена ICraftingPlan, ручные вызовы tickCraftingLogic/tickingRequest и вставка готовых изделий для имитации производства не используются.

Стенд содержит реальные Pattern Provider, Molecular Assembler, Crafting Storage 1k, ME Drive/Item Cell 1k и Creative Energy Cell. Два crafting patterns получены через RecipeManager/PatternDetailsHelper: log → 4 planks и 2 planks → 4 sticks. Future настоящего beginCraftingCalculation проверяется на следующих dev server ticks; get(0, MILLISECONDS) вызывается только после isDone. План на 16 sticks использует 2 logs и два рецепта.

| Процесс / сценарий | Проверяемый native результат |
| --- | --- |
| seed: отмена после начала | Ждём, пока CPU передаст одно бревно в assembler; второе остаётся внутри CPU. Штатный menu action cancelCrafting отменяет тот же JobId. CPU возвращает 1 log, уже начатый assembler заканчивает 4 planks. После опустошения machine/return buffer и ещё 25 тиков: 1 log, 4 planks, 0 sticks; новых рецептов нет |
| seed: повторная отмена | Повторный client action на idle CPU не создаёт cancellation scope/строку. Для отменённого job ровно request + cancel, без false finish |
| seed: подготовка сохранения | В отдельном новом job снова переданы 1 log в assembler и 1 log в CPU. В 19.2.18 штатный toggleScheduling ставит задание на паузу; в 19.2.12 native pause отсутствует и job остаётся работающим. В этом состоянии сервер останавливается и сохраняет настоящий мир |
| resume: загрузка | В новом процессе, до подключения питания, CPU busy с прежним native JobId и исходным planned output 16 sticks. В 19.2.18 сохраняется suspended, в 19.2.12 job активен без паузы. CPU содержит 1 log, assembler — 1 log. Blocks и содержимое читаются из мира, setup их не заменяет |
| resume: уже начатая операция, только 19.2.18 | После восстановления питания assembler заканчивает 4 planks; на паузе CPU не выдаёт следующую операцию. Ещё 25 тиков проверяются CPU stock 1 log + 4 planks, пустые assembler/return buffer и отсутствие sticks в cell |
| resume: продолжение | В 19.2.18 повторный штатный toggleScheduling продолжает тот же job; в 19.2.12 он продолжает работу сразу после подключения питания. В обоих случаях submitJob повторно не вызывается. Обычные AE2 ticks дают ровно 16 sticks, 0 logs, 0 planks, idle CPU и пустые machine/return buffer |
| verify: ещё один restart | Третий процесс загружает уже завершённый мир. Ещё 25 активных тиков: CPU idle, cell хранит 16 sticks, completion inspector пуст. Завершённое native задание не восстанавливается повторно |

Пауза AE2 19.2.18 останавливает назначение следующих рецептов CPU; она не прерывает работу, уже принятую assembler. Отмена также не отменяет физическое превращение бревна, ранее переданного машине. Поэтому mid-job cancel не должен возвращать два исходных logs или уничтожать четыре произведённые planks. Проверка явно отделяет это от немедленной отмены из предыдущего пакета.

Explicit GridHelper connections — исключительно dev wiring: после запуска они соединяют сохранённые узлы с power node заново. Соседство provider/assembler естественное. Это проверка persistence job/inventories и восстановления выбранной grid, а не сохранения игровых кабелей, multi-chunk topology или chunk unload/load. Forced chunk используется только в стенде; production Logger его не создаёт.

## История и границы доказательства

Для реально принятых/отменённых/завершённых заданий вызываются существующие data-only inspectors. В первом процессе для mid-cancel проверяются две строки и исходный JobId, отсутствие false completion и false cancellation при pause/idle action. Новый job получает другой UUID. Его исходный request с actor сохраняется как Gzip JSON sidecar после materialization через AsyncLogStorage worker; отдельный identity sidecar содержит UUID actor/job. Sidecars не содержат состояния CPU/assembler и не используются для восстановления игрового задания.

Во втором процессе restored completion inspector создаётся для загруженного CPU до подключения питания. Он выдаёт один ME_CRAFT_FINISH с тем же JobId, адресом CPU и нейтральным actor. Повторное finish пусто, idle CPU не создаёт scope; original request/actor читается из сохранённой квитанции, повторного admission нет. Сохраняются полный ITEM/evidence, planned-output, worker encoding и Gzip. Настоящий RollbackEngine не выдаёт предметы по такой строке. Третий процесс дополнительно проверяет сохранённый finish и фактический cell balance.

Inspectors в dev живут между фазами. Production scope по-прежнему живёт только внутри native операции. AuthCore не обходится, production capture выключен: отсутствие лишней строки здесь означает отсутствие лишнего inspector receipt, а не доказанный положительный authorized append через весь сервер/ClickHouse/GUI. Нового хранилища job IDs, production ticking, сканирования grid/рецептов или повторного исполнения операций Logger не добавляет.

В API 19.2.12 нет isJobSuspended/toggleScheduling. Тест определяет наличие штатного menu action и только в новом варианте читает native getter через dev reflection; прямой вызов API 19.2.18 исключён из общего compile contract. Отсутствие паузы не имитируется подменой CPU/NBT.

Новые файловые операции и ожидание worker shutdown принадлежат dev fixture. Они не вводятся в production server thread. Изменён только createSmoke source set и условия dev запуска/CI. Численные MSPT, allocation, heap или GC результаты этим не устанавливаются.

## Проверки

Проверенный код — `0f6c98af52c5c82ede2e40ce98f621e4c353f96a`, tree `4b2ec94af22ef97cd45e816ea2c30d784402603e`. [Серверная матрица и настоящая ClickHouse](https://github.com/Vlad-Ras/Logger/actions/runs/40792003716); [GUI](https://github.com/Vlad-Ras/Logger/actions/runs/40792003737). Четыре серверных профиля, настоящая ClickHouse и GUI полностью успешны; новые lifecycle steps пройдены на обеих версиях AE2.

CI требует AE2_MID_CANCEL_NATIVE_OK, AE2_LIFECYCLE_SEEDED, AE2_LIFECYCLE_RESUMED и AE2_LIFECYCLE_NATIVE_OK, сохранение всех измерений после каждого из трёх запусков и непустой world/level.dat. Любые AE2_NATIVE_FAILED, отключение интеграции или ошибка mixin/startup приводят к отказу шага. Каждый запуск использует отдельный runServer --no-daemon; native fixture отмечает PID в логе. У 19.2.12 nativePause=false, у 19.2.18 nativePause=true.

Фактический лог подтверждает три различных PID и один и тот же job UUID до/после загрузки:

| AE2 | PID seed / resume / verify | Job UUID seed = resume | Native pause |
| --- | --- | --- | --- |
| 19.2.12 | 3777 / 4084 / 4411 | 374b24d1-72cc-4ed6-976c-fa29b25284e7 | отсутствует, job сохранён работающим |
| 19.2.18 | 3793 / 4103 / 4403 | c28480fe-de1e-4479-95e8-5028eb07e1c9 | сохранена и снята штатным menu action |

Сохранены прежние crafting/assembly/grid/remote/menu/settings/transfer регрессии. Новые lifecycle receipts не выдают проверку production AuthCore за выполненную: настоящая ClickHouse и GUI ниже — общие регрессии существующего пути хранения/отображения. AE2_CRAFTING_CLICKHOUSE_OK получен дважды для accepted/refused/cancelled/finished, full key/planned long/CPU/job/actor, exact feed/details и нового подключения в minimal mode. CART_GUI_CLIENT_OK проверяет history/actions/confirmation/results/help на 320×240, 640×360 и 960×540.

Локально прошли Python inventory/profile comparison, git diff --check и JSON mixin configs. Java 21 ProfileSummaryTest, compileJava/compileCreateSmokeJava/testAsyncPipeline/testCreatePreflight/verifyMixinSafety прошли в CI. Пройдены Create, Create absence, Backpacked и actor/aircraft regressions, AE2 absence на current-fd134. Полная локальная Java/Gradle/native сборка этим проходом не засчитывается. Прежнее доказательство равенства 1 826 class-файлов присланного/публичного AE2 19.2.18 не повторяется и не подменяет transformed запуск всех 267 JAR.

Первый source commit fcaf466 использовал отсутствующий в API 19.2.12 getter паузы; старые профили и GUI отказали на компиляции dev fixture. Исправление ограничено dev version-specific pause inspection; повторная матрица полностью прошла на указанном выше коде. Производственные перехваты этим исправлением не менялись.

## Что остаётся открытым

Проверен выбранный standalone vanilla crafting job с in-flight assembler input: сохранённый работающим в 19.2.12 и на штатной паузе в 19.2.18; затем сохранённое idle состояние. Не заявлены все точки interruption, аварийный kill процесса/недописанный мир, restart работающего без паузы CPU в 19.2.18, chunk unload/load, multi-chunk кабели, смена recipes/addons между запусками, requesting machines, processing/fluid/smithing/stonecutting routes, переполнение/внешние составные storage или виртуальный ME rollback. Отдельная история переключения pause/resume пока не добавлена: проверено native поведение без ложных request/cancel/finish.

Остаются положительный production AuthCore → hooks → ClickHouse → GUI путь, отрисованный remote terminal, transformed совместимость всего присланного модпака с конфигами/скриптами, native Scguns 1.5 manual stop и реальный A/B/A профиль фермы 8 пил + 8 deployers с саженцами + portable storage interface. Прежние около 60 000 мкс/тик Observable не объявляются устранёнными. Поезда и вырезанный портал в scope не возвращаются.
