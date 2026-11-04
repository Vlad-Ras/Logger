# Фоновое сравнение отделённых NBT: 2.2.0

Дата: 27.10.2026. Код/тесты: `476b6903a51a1738be70045373f71c908dd4972c`, дерево `7c2eb2ac023fddefe06a5a88fdf7217d1fd75c8a`. База — `d7ad053e9be70a0995d9e4b37457ed1a5dcdb708`; ветка `work/2.2.0-async-nbt-deltas-20261027`. Релизный JAR не создавался.

## Изменённые пути

| Путь | Серверный поток | Фоновая часть |
| --- | --- | --- |
| Vanilla/Amendments SignTextAudit | Native operation, guards, полные detached snapshots и автор/адрес/время | Сравнение raw/filtered обеих сторон и SNBT в event worker |
| Supplementaries text packet | Native callback, guards, snapshots и точный редактор/адрес | Сравнение root/двух holder и нормализованного filtered в event worker |
| Supplementaries Create display targets | Native getter/ранний read-only predictor, guards, snapshots, адрес механизма | Сравнение текста/Items/PageNumber, включая filtered/resolved/компоненты книги, в event worker |
| MenuAction.emit | Снимки, адрес/автор/время и передача detached данных | Ранее синхронная проверка равенства слотов/BE перенесена в начало существующего CPU callback, до кодирования и fan-out |

Live BE/Player/Menu/ItemStack не передаются новым правилам сравнения. Source/actor/позиция/время фиксируются до handoff, включая enrichment существующего AsyncLogStorage. Снимки передаются с прежним договором владения: detached tag нельзя менять после передачи. Новые world reads, capability lookup, permission checks или тики для сравнения не добавлены.

## Порядок и память

SnapshotDeltaPolicy — закрытый enum из трёх правил, без произвольных callbacks и captures. Кандидат остаётся LogEntry в прежней ограниченной event queue. copyForQueue сохраняет transient правило; worker вызывает resolve до materializeSnapshots. Изменившаяся запись получает прежние полные before/after. Проверенный no-op возвращает null, не кодируется и не доходит до dedup/storage. Его detached payload и deferred states очищаются; повторный resolve/copy не возвращает отвергнутую запись.

AsyncLogStorage освобождает weight/count reservation в finally и при null. Пока сравнение выполняется, кандидат остаётся in-flight: awaitVisible не сообщает об окончании и следующий producer не обходит лимит. Shutdown дожидается разрешения/доставки принятых кандидатов. Проверка materializeSnapshots запрещает кодировать ещё не разрешённое правило. Transient policy/decision не попадают в Gzip/JSON/SQL; миграция БД не нужна.

Для этих трёх text/display путей нет промежуточной многопоточной CPU задачи и второго handoff: сравнение и кодирование выполняет один FIFO event worker. Общий MenuAction сохраняет прежний CPU pipeline; глобальный порядок между всеми независимыми очередями этим этапом не перепроектирован.

Синхронные row helpers оставлены для явного detached kernel/compatibility использования с прежним no-op контрактом. Production after/finish обращаются к pendingRow, который не вызывает changed. AuthCore, категории, logBlocks/restoring и права функций Logger не ослаблялись. Дополнительно display Frame не сравнивает native holder/book, если уже выбранная цель не принадлежит server thread.

## Проверки

SnapshotDeltaWorkerTest сравнивает результаты трёх правил с прежними kernels: raw/filtered-only, no-op, нижний указатель, style/noise, Items/resolved, PageNumber и отсутствующая схема. ProbeTag отказывает при чтении для сравнения/кодирования вне event worker. В настоящей очереди проверены 120 изменений и 120 no-op, FIFO, captured metadata после изменения исходного LogEntry, полные snapshots и журнал. У no-op число SNBT-кодирований равно нулю.

Отдельный крупный no-op удерживает worker latch: проверены in-flight oversized weight, блокировка следующего producer, false awaitVisible до окончания, освобождение permits/bytes, сохранность следующей записи и shutdown. Проверены disabled capture, повторное разрешение/копирование отвергнутого кандидата, очистка payload и запрет раннего кодирования.

Для MenuAction проверены фактический static encoder callback на отдельном потоке и ранний no-op с недоступным live metadata; callback не получает Player/Level/Menu. Bytecode check запрещает возвращение Objects.equals в producer emit.

Native SnapshotDeltaNativeSmoke пропускает настоящие снимки знаков/книг через изолированный AsyncLogStorage sink: проверяет отбраковку no-op, полные before/after, actor/source/адрес/время и Gzip replay. AuthCore не обходится, production logging остаётся выключенным. Это проверка настоящего pipeline и native serializers, а не положительного production authenticated emit. Предыдущие игровые сценарии и ядро отката [сохранены в native regression](SUPPLEMENTARIES_DISPLAY_COMPATIBILITY.md).

[Серверные профили и настоящая ClickHouse](https://github.com/Vlad-Ras/Logger/actions/runs/40506750087), [GUI](https://github.com/Vlad-Ras/Logger/actions/runs/40506750077) завершились успешно на указанной code-ревизии: все четыре серверных профиля, real ClickHouse и GUI. Оба локальных native профиля на 21.1.247 прошли без SUPPLEMENTARIES_NATIVE_FAILED/ошибки запуска/MixinApplyError/MixinTransformerError; accepted=0, adapterFailures=[]. С Create — 13 SNAPSHOT_DELTA_NATIVE_PIPELINE_OK, без Create — четыре. Локально пройдены compileJava/compileCreateSmokeJava/testAsyncPipeline/testCreatePreflight/testExactModpackPreflight/verifyMixinSafety; сырой optional контракт — 163 mixin / 108 target-классов. Private JAR в CI не загружаются.

## Оставшаяся нагрузка

Снятие изменяемого состояния через native serializers, раннее сравнение уже выбранного holder/book и оценка NBT веса перед ограниченной очередью остаются синхронными. Их нельзя объявлять безопасно перенесёнными вместе с detached сравнением.

No-op теперь может занимать event/CPU queue до фоновой отбраковки; это дополнительный объём кандидатов относительно синхронной фильтрации. При насыщении остаётся блокирующее противодавление, без unlimited queue, overflow discard или inline server fallback. Перенос не является обещанием меньшего MSPT при перегруженном worker. Нужны реальные A/B/A heap/GC/MSPT и throughput/latency измерения, включая ферму 8 пил + 8 активаторов/саженцы + portable storage interface. Прежние ~60 000 мкс/тик Observable не объявляются устранёнными.


## Дополнение: насыщение journal queue, 27.10.2026

[Исправлены удержание неполной пачки до таймера, отмена retry backoff обычными уведомлениями и lost wakeup sender](JOURNAL_PRESSURE_2.2.0.md). Проверка видимости больше не выбирает старейший файл из всего backlog. Подтверждены disk/replay, настоящая ClickHouse, четыре CI-профиля и GUI. Ограничения памяти и durable acknowledgement сохранены; длительная ресурсная перегрузка и production приёмка остаются открытыми.
