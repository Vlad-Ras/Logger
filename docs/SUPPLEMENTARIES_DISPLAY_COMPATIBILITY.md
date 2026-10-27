# Supplementaries: автоматизация текста, книги/карты доски и откат

Дата: 27.10.2026. Код и тесты: `2dea1797cce902cdefc84608cb133bd3ca976833`, дерево `39650ea0abc1622a35a474419020d10e5e9234fb`. База — `f7cb3ad3b7b2f4935ebbe9d1ddf709df512d737f`; ветка `work/2.2.0-modpack-text-batch-20261027`. Дополнение к [текстовому packet](SUPPLEMENTARIES_TEXT_COMPATIBILITY.md). Релизный JAR не создавался.

## Состав и границы

Отдельный disposable dedicated-server: Java 21 / NeoForge 21.1.247, предоставленные Supplementaries 1.21.1-4.0.2, Moonlight 1.21.1-3.7.2, Amendments 1.21-2.1.11 и Create 6.0.10. Точные хеши прежние и приведены в документе текстового packet. Дескрипторы чужих JAR не менялись. AE2/GuideME присутствуют как зависимости стенда. Это не полный запуск всех 267 JAR и не определение production-версии NeoForge.

Оба display-target mixin проходят отдельный raw preflight только для указанной пары Supplementaries/Create. Отсутствие Create или несовпадение его версии отключает только эти два перехвата; прежний текстовый packet остаётся отдельным контрактом. Удаление acceptText или нужного getter call site отвергается. В runtime подтверждено фактическое применение всех четырёх Supplementaries mixin и нового core persistent-data mixin.

## Захват и скорость

Перехватываются реальные TextHolderDisplayTarget.acceptText и NoticeBoardDisplayTarget.acceptText. Native getter цели выполняется один раз; его результат используется для блокировки зарезервированного откатом адреса, включая выключенную авторизацию. Место механизма берётся из того же DisplayLinkContext. Logger не повторяет native reservation/permission checks и не вызывает target/source повторно.

Before снимается при уже выполненном native getter holder/book, **до reserve и изменения held ItemStack**. Read-only сравнение holder raw/filter и страниц книги позволяет не снимать NBT при заведомо одинаковом содержимом, пустом или выходящем за диапазон обновлении. Учтены native сброс filtered страницы и resolved через withReplacedPages: совпадающие буквы не гарантируют одинаковый предмет. Для antique ink сравнение консервативно разрешает снимки, поскольку native setter переписывает font. Консервативные лишние снимки также возможны, если последующая native reservation остановит запись; дополнительного reservation lookup ради их исключения нет.

До снимка проверяются категории 1/2, AuthCore/logBlocks/restoring, server thread, загруженный чанк, точный provider и идентичность BE. Сохранены joint Create structural/deployer observers. После действия флаги/адрес проверяются повторно; исключение native вызова не объявляется успехом, observer failure не повторяет original.

Одна очищенная Frame может переиспользоваться на потоке; вложенный вызов возвращает прежнюю Frame. После close обнуляются target, origin, input, snapshots, state и previous. Нет owner registry, нового поля-владельца в TextHolder, обхода соседей/меню по тикам, дополнительного ticking, capability lookup или загрузки чанков со стороны нового адаптера. Player/BE/Input не уходят worker. Передаются detached full before/after; SNBT и очередь обрабатываются существующим pipeline. Отбраковка дельты уже отделённых before/after NBT пока выполняется синхронно; её перенос на worker остаётся отдельной оптимизацией. Это границы реализации, не численный heap/MSPT benchmark. Блокирующее противодавление очереди остаётся открытым.

## Что сохраняется

`BLOCK_ENTITY_NBT_CHANGE`, source=`mod:supplementaries:create_display_link`, actor UUID отсутствует, имя `SYSTEM[create:display_link]`. Координаты строки — изменённый блок; extra содержит отдельный адрес display link, измерение и completed. Сохраняются оба block state и полные BE snapshots: raw/filtered holder, книга с компонентами/страницами/названием/автором/поколением/resolved, карта с MapId, номер страницы и persistent data. Owner или случайный ambient player не объявляется стрелком/редактором автоматизации.

GUI дополнение показывает строки holder, предмет до/после, страницы книги и фильтрованные версии, название/автора/поколение, MapId. Читаются ограниченный SNBT и ванильные component codecs; чужие ItemStack deserializers не вызываются. Текст преобразуется в literal, click events из книги не исполняются. Записи source/типа других событий не переопределяются. storeRollbackDetails остаётся явной пользовательской настройкой.

Исходная механика Supplementaries при преобразовании writable→written создаёт новую книгу с названием Printed Book, автором Data Gatherer, generation=0 и не копирует произвольные компоненты исходного предмета. Logger записывает реальный результат. Откат возвращает исходную книгу с её компонентами, а не изменяет механику мода.

## Проверка мира и отката

| Сценарий | Проверенный результат |
| --- | --- |
| Коврик, доска, way_sign | Настоящий TextHolderDisplayTarget; raw/filter, повтор/пустой/выход за размер, upper-only native семантика way_sign и зарезервированная цель |
| Подписанная книга | Частичное изменение страницы, нетронутая filtered страница, название/автор/generation/custom name/custom data, resolved reset, повторный no-op |
| Книга с пером | Native подписание и страницы; точное возвращение исходного типа/компонентов при откате |
| Границы книги | 24 сочетания двух типов, offsets 0/1/3/49/50/60 и пустого/непустого ввода; read-only predictor не пропускает native mutation, лимит 50 и заполнение пустых страниц |
| Настоящее меню доски | PICKUP, QUICK_MOVE наружу и внутрь; written/writable/map, сохранность количества/компонентов и shared slot snapshot/apply |
| Карта | Настоящий MapItem с сохранённым MapSavedData; display-book target игнорирует карту, откат сохраняет MapId и ссылку на данные |
| Источник Create | Зарегистрированный EnchantPowerDisplaySource → реальный DisplayLinkBlockEntity.updateGatheredData → native NoticeBoardDisplayTarget; история и обратное применение |
| История/prepare | Gzip journal round trip → queryReverse/prepareBoxRange на worker → previewPrepared → applyPreparedBlockBatch или настоящий ActiveJob.tick kernel |
| Повреждённая история | Чужой BE ID, stone+BE, malformed NBT и off-thread вызов отвергаются без изменения доски и дополнительных item drops |
| Компенсация | Структурно допустимые слоты неверной ёмкости вызывают отказ после BE load; прежний полный BE возвращается под restore lock |
| Vanilla + Amendments | Реальные front/back raw/filtered updateSignText у standing/hanging sign; worker preparation и tick-kernel restore сохраняют обе стороны |

При испытании обнаружены и исправлены три общие проблемы: applyPreparedBlockBatch/applyPreparedEntry теперь выставляют restoring с возвратом прежнего флага в finally; несовместимый BE type проверяется до setBlock; при отсутствии NeoForgeData в full snapshot удаляются более поздние persistent data. Без последнего NeoForge loadAdditional оставлял reservation Create после отката книги. Новый core mixin только присваивает это поле при восстановлении; тиков и состояния на экземпляр он не добавляет. Optional attachments и произвольные другие данные модов этим тестом не объявляются полностью принятыми.

Получены SUPPLEMENTARIES_DISPLAY_NATIVE_OK, SUPPLEMENTARIES_BOARD_MENU_NATIVE_OK, SUPPLEMENTARIES_ROLLBACK_REFUSAL_NATIVE_OK, SUPPLEMENTARIES_AMENDMENTS_SIGN_ROLLBACK_NATIVE_OK, SUPPLEMENTARIES_DISPLAY_FAULT_PREFLIGHT_OK и общий SUPPLEMENTARIES_NATIVE_OK. Итоговый прогон без SUPPLEMENTARIES_NATIVE_FAILED/MixinApplyError/MixinTransformerError; accepted=0, adapterFailures=[]. Отдельный профиль без Create/Ponder/Flywheel прошёл три native packet provider и SUPPLEMENTARIES_WITHOUT_CREATE_NATIVE_OK; optional display classes не загружаются. В этом профиле исходники smoke ограничены двумя нужными классами, чтобы Create-only тестовые subscribers не исполнялись при проверке отсутствия Create.

Важная граница: AuthCore не обходился. Публичный startRollback в fixture действительно отказывает без разрешения. Положительные проверки используют native операции, detached evidence и внутренний серверный ActiveJob.tick **вне глобальной очереди заданий**. Это проверка реального ядра отката, но не production command → AuthCore → DB → GUI → rollback job.

## Хранение и повторение

[Серверная матрица и настоящая ClickHouse](https://github.com/Vlad-Ras/Logger/actions/runs/40478568114), [GUI](https://github.com/Vlad-Ras/Logger/actions/runs/40478568085) завершились успешно на `2dea1797cce902cdefc84608cb133bd3ca976833`: четыре серверных профиля, real ClickHouse и GUI. SUPPLEMENTARIES_DISPLAY_CLICKHOUSE_OK получен дважды — до и после reopen. Native Supplementaries с точными предоставленными JAR и профиль без Create выполнены локально на 21.1.247; эти приватные JAR в CI не загружаются.

Unit/HTTP проверяют system actor, точные target/mechanism, completed=false, оба full payload, отключённые категории, nested scope cleanup, no-op/filtered/conversion/page data и malformed fallback. Реальная ClickHouse проверяет полные книги/фильтр, system actor, адреса, completed и exact ID/feed в minimal до/после повторного открытия.

Локальные задачи: compileJava, compileCreateSmokeJava, testAsyncPipeline, testCreatePreflight, testExactModpackPreflight, verifyMixinSafety. Raw optional preflight — 163 mixin / 108 предоставленных target-классов; новый core shadow проверяется отдельно с injected missing-field fault. Полный transformed conflict pass всех модов этим не заменяется.

```bash
./gradlew runServer -PsupplementariesSmoke \
  -PsupplementariesSmokeMods="$SUPPLEMENTARIES_SMOKE_MODS" \
  -Pneo_version=21.1.247 -PcompatCreateVersion=6.0.10-281 \
  -PcompatAe2Version=19.2.18 -PcompatFrameworkVersion=1.21.1-0.13.11
```

Стенд, EULA и loopback-порт — как в документе текстового packet. Для отдельного native прогона без Create добавляется `-PsupplementariesCreateAbsent`; каталог optional JAR не должен содержать Create/Ponder/Flywheel. Успех определяется маркерами и отсутствием FAILED, не exit code Gradle.

## Оставшаяся приёмка

Другие меню/блоки и автоматизация Supplementaries, поворот/мимикрия/чернила/смывание, произвольные server-side setters вне двух display targets, изменения самого MapSavedData и другие сочетания модов открыты. Нет результата полного production authenticated capture/rollback, manual-stop Scguns 1.5, queue saturation redesign, клиентского heap профиля или A/B/A фермы 8 пил + 8 активаторов/саженцы + portable storage interface. Прежние ~60 000 мкс/тик Observable не объявляются устранёнными.
