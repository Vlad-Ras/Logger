# BlockPack 1.0.11: выбранный no-auto-glass-v2

Дата: 26.10.2026. Проверяется предоставленный `bf_blockpack-neoforge-1.21.1-1.0.11-RU-no-auto-glass-v2.jar`, Java 21, NeoForge 21.1.244. Исходный JAR с тем же mod ID в native-стенд не добавляется. Чужие JAR не публикуются и в Logger не встраиваются.

## Новый аудит настройки подарков

Переключение идёт через `SetGiftEnabled` и собственный queued callback, минуя обычное меню/DataSlot. Два optional-перехвата связывают серверный callback с фактической записью `GiftSavedData.setGiftEnabled`.

`GUI_CHANGE` сохраняет UUID/имя отправителя, его измерение/позицию, `giftEnabled` до/после, `Scope=server`, `StorageDimension=minecraft:overworld`. Место отправителя не объявляется местом глобальной настройки. Повторный запрос того же значения строку изменения не создаёт. Исходный callback, setter и рассылка выполняются прежним способом; Logger дополнительных пакетов не отправляет.

Категория контейнеров, серверный logContainers, AuthCore и режим восстановления проверяются до payload context/снимка. В очередь передаются только отделённые значения. Нет tick-опроса, capability lookup, обхода инвентаря или сериализации SavedData со всем множеством UUID получателей. Универсальный откат блока к этой аудиторской записи не применяется.

Версия и оба bytecode-контракта проверяются вместе до инициализации целей. Несовпадение выключает этот адаптер. Классы подарков побайтно одинаковы в предоставленных исходном и patched JAR; native-приёмка относится к выбранному patch.

## Сохранность настроек

В общем пути ClickHouse `GUI_CHANGE` с обеими сторонами настройки теперь сохраняет `be_before`/`be_after` при отключённых необязательных non-rollback деталях, включая minimal-режим. Эти значения являются содержимым события. Правило относится и к штатным DataSlot, и к подаркам BlockPack. Отключение категории по-прежнему прекращает захват целиком.

## Native-проверки

| Путь | Подтверждено |
| --- | --- |
| Gift packet | Настоящий callback с enqueueWork: две смены флага и один no-op; верный dirty-state, число исполнений и context reads |
| Верстак | Настоящий рецепт andesite_column, обычный и quick-move крафт: одно штатное crafting event с результатом ×2, точный расход ингредиентов, общий snapshot видит craft slots |
| Fillable crate | Пустой → 20 моркови → полный no-op → пустой; ровно 20 предметов возвращаются, лишнего расхода нет; immutable block states |
| Cardboard box | 9 видимых внешних слотов и 54 в BE; snapshot включает скрытый слот 53; native save/load и строгий restore сохраняют предметы |
| Пределы коробки | Native setter ограничивает стек до 64. Штатный ×37 восстанавливается; snapshot ×1024 отвергается с компенсацией и без порчи других слотов |
| no-auto-glass-v2 | Четыре настоящих tryUpgrade для glass/tinted/white/black stained glass возвращают false и сохраняют ванильные блоки |
| Optional-контракты | Оба перехвата реально применены; fault-fixture с удалённым полем/callback отвергается preflight |

Успех проверяется по `BLOCKPACK_NATIVE_OK` и отсутствию `BLOCKPACK_NATIVE_FAILED`, а не по одному exit code Gradle. Отдельные маркеры: `BLOCKPACK_GIFT_NATIVE_OK`, `BLOCKPACK_WORKBENCH_NATIVE_OK`, `BLOCKPACK_FILLABLE_NATIVE_OK`, `BLOCKPACK_BOX_NATIVE_OK`, `BLOCKPACK_NO_AUTO_GLASS_NATIVE_OK`.

AuthCore на стенде не включался и не обходился: production capture остался `accepted=0`, `adapterFailures=[]`. Native-операции и ядра формирования/хранения строк проверены раздельно. Авторизованная сессия → запись → GUI ещё требует production/staging приёмки.

## Поведение исходного пакета

Настоящий SetGiftEnabled callback предоставленной версии принимает переключение глобального флага от обычного ServerPlayer без OP2; серверной проверки OP в нём нет. Тест фиксирует это явно. Logger не добавляет это поведение и не заменяет разрешения BlockPack. Доступ к командам/GUI Logger остаётся под прежними AuthCore/OP-проверками.

## Проверки и повторение

Код/тесты: `ce0b340f38b8e9f8e1aee0df422d9c5c48986f78`, база `91c6bf3518e7eee91c2a5338b973a257aa823f54`, ветка `work/2.2.0-blockpack-20261026`.

Локально прошли compileJava, compileCreateSmokeJava, testAsyncPipeline, testExactModpackPreflight, verifyMixinSafety: **157 контрактов**, включая **2 BlockPack**, по **103 предоставленным target-классам**. Проверены no-op, actor/global scope, журнал, ранний отказ отключённой категории с poison context и запрет partial rollback. HTTP-тест сохраняет обе стороны DataSlot/gift setting при отключённых optional details, затем читает точный ID.

[Серверные профили и настоящая ClickHouse](https://github.com/Vlad-Ras/Logger/actions/runs/40244319896), [GUI](https://github.com/Vlad-Ras/Logger/actions/runs/40244319685). Все четыре серверных профиля, настоящая ClickHouse и GUI завершились успешно. На CI BlockPack отсутствует: это отдельная проверка optional-загрузки и регрессий, а native-контракт подтверждён локальным прогоном. После проверенной ревизии добавлена только документация. Реальная база дополнительно проверяет actor и обе стороны gift setting после повторного открытия хранилища.

```bash
./gradlew runServer -PblockpackSmoke -PblockpackSmokeMods="$BLOCKPACK_SMOKE_MODS" \
  -Pneo_version=21.1.244 -PcompatCreateVersion=6.0.10-281 \
  -PcompatAe2Version=19.2.18 -PcompatFrameworkVersion=1.21.1-0.13.11
```

Нужен отдельный `run-blockpack` с EULA, loopback-портом и тестовым миром. Каталог BLOCKPACK_SMOKE_MODS содержит только выбранный patched JAR и зависимости стенда. Fixture останавливает сервер после проверки; production-мир не используется.

## Группы коробок: адрес фактического хранилища

При клике по любому блоку группы native `CardboardBoxBlock.useWithoutItem` открывает `ChestMenu` с инвентарём корневого BE. Инвентари остальных BE остаются отдельными и в это меню не входят. Старый общий путь Logger сохранял адрес кликнутого блока и при клике не по корню снимал другое хранилище.

На подтверждённом открытии `ContainerSlotSnapshot.menuStoragePos` берёт позицию BE, уже переданного в `ChestMenu.getContainer()`. `OPEN_CONTAINER`, снимок слотов и BE до/после, координаты изменений и строгий restore относятся к этому адресу. Запись самого открытия по-прежнему обозначает место клика. Для перенаправленного меню начальный BE-снимок снимается с настоящего хранилища при открытии, до действий игрока. Составной контейнер, включая ванильный двойной сундук, и меню без собственного BE сохраняют прежнюю привязку.

Удалённый, заменённый или чужой BE, незагруженный чанк и чтение вне серверного потока отвергаются. Дополнительного `clusterAt`, обхода соседей, тиков, удержания Menu/Level в фоновых заданиях или принудительной загрузки чанка здесь нет; одна проверка фактического BE выполняется при открытии.

Native fixture открывает каждую коробку кубов 1×1×1, 2×2×2 и 3×3×3 во всех четырёх горизонтальных направлениях: 144 меню, из них 132 клика не по корню. Проверяет реальные 1/4/6 рядов, правильный backing container, изъятие четырёх алмазов и дельту −4, строгий restore всех 54 слотов, неприкосновенность отдельных инвентарей остальных коробок, native NBT save/load и сохранение скрытого слота 53 при уменьшении структуры. Маркер `BLOCKPACK_CLUSTER_NATIVE_OK`.

Отдельный `BlockMenuStorageSmoke` выполняется также в четырёх обычных CI-профилях без BlockPack. Он проверяет настоящий ванильный сундук, generic/compound fallback, отказ для заменённого/чужого BE и фонового потока. Маркер `BLOCK_MENU_STORAGE_NATIVE_OK`. Это не заменяет авторизованный production capture.

Локально код/тесты `1d6c521d1434a8e13fb589c729b782d6c18d0f96` прошли compileJava, compileCreateSmokeJava, testAsyncPipeline, testExactModpackPreflight (157 контрактов / 103 target-класса), verifyMixinSafety и native runServer. Получены оба новых маркера и все прежние BlockPack-маркеры; BLOCKPACK_NATIVE_FAILED/MixinApplyError/MixinTransformerError отсутствуют. AuthCore не обходился: accepted=0, adapterFailures=[]. Локальный подготовительный NeoForm tool использует отдельную копию с fallback java path для ограничения среды; production-исходники и CI этот инструмент не меняют.

[Повторная серверная матрица / ClickHouse](https://github.com/Vlad-Ras/Logger/actions/runs/40311641678), [GUI](https://github.com/Vlad-Ras/Logger/actions/runs/40311641991). Все четыре серверных профиля, настоящая ClickHouse и GUI завершились успешно на этой ревизии. После проверенного кода изменена только документация.

## Ключ и состояния блоков

В предоставленном WrenchItem хранится одна выбранная позиция, свойство и время использования. Отдельного массового выбора/операции в этой реализации нет. Проверяется серия штатных действий, а не выдуманный массовый packet.

Найдена общая ошибка ClickHouse: BLOCK_INTERACT с before/after поддерживается RollbackEngine, но отсутствовал в rollback-critical классификации. При штатном storeNonRollbackDetails=false снимки не попадали в detail table, а feed получал has_details=0. Теперь BLOCK_INTERACT с обеими сторонами блока хранится по правилу storeRollbackDetails, включая minimal. Одностороннее наблюдение остаётся optional; явное отключение rollback details учитывается. Проверка выполняется у фонового writer, новых захватов мира/сериализаторов/тиков нет. Старые строки, детали которых уже были отброшены, этим правилом не дополняются.

HTTP regression проверяет balanced с отключёнными optional details: insert обеих сторон, чтение по точному ID, показ конкретного свойства и отсутствие mandatory detail для after-only observation. Настоящая ClickHouse в minimal проверяет before/after, UUID, точные координаты, ID/feed, сведения о свойстве и повторное открытие хранилища. Маркеры BLOCK_INTERACTION_HTTP_OK и BLOCK_INTERACTION_CLICKHOUSE_OK.

Новый BlockStateEvidenceSmoke входит также в обычную CI-матрицу без BlockPack: отделённые состояния проходят SetBlockCapture.entry, compressed journal и BlockChangeDetails. Координата изменения проверяется отдельно от позиции игрока/клика; без actor сохраняется SYSTEM. Это тест ядра строк, не обход AuthCore.

На выбранном patched JAR новый native-прогон прошёл 69 переключений значений по 14 свойствам пяти представителей: oak_stairs (facing/half/shape), acacia_stacked_logs (facing/height), blaze_powder_sack (facing/open), neon_sign (facing/sign/x_bit/y_bit), paper_lantern_red_big (face/facing/open). Проверены boolean/integer/enum и исключение waterlogged; настоящий LeftClickBlock выбирает свойство и отменяет разрушение, повторный выбор учитывает 300 мс. Отказы: отсутствующий/просроченный выбор, другое измерение, дистанция, блок без свойств. Штатные useItem/useItemOn сохраняют player cause; отдельный useOn кликает по другому камню, а ядро строки сохраняет адрес выбранного блока. Настоящая публикация при этом выключена AuthCore.

Аквариум: настоящая установка/разрушение соседа во всех шести направлениях, двусторонние native-связи, точный расход предмета, вставка и извлечение воды через LiquidBlockContainer/BucketPickup. В этой реализации propFor(UP) выбирает поле DOWN, propFor(DOWN) — UP; тест учитывает эту native-семантику, Logger не переименовывает значения connect_*. Это не утверждение об ошибке внешнего мода.

Фонарь: настоящий клик переключает состояние при том же BE; kick и SwingStart сохраняются native NBT save/load. Flag/WeatherVane проходят BE round trip и не имеют серверного ticker. Отдельный поток журналирования косметических взмахов/опрос не добавлен; автоматическое соприкосновение сущностей с фонарём отдельно не принято.

Маркеры BLOCKPACK_WRENCH_NATIVE_OK, BLOCKPACK_AQUARIUM_NATIVE_OK, BLOCKPACK_DECORATION_NATIVE_OK, BLOCK_STATE_EVIDENCE_NATIVE_OK и все прежние BlockPack-маркеры получены, включая 144 открытия групп. BLOCKPACK_NATIVE_FAILED/MixinApplyError/MixinTransformerError отсутствуют. AuthCore не обходился: accepted=0, adapterFailures=[]. Native-механика, actor scope и ядра строк проверяются раздельно с авторизованной публикацией.

Исходный pre-fix ClickHouseLogStorage из 492165b5ba873d2483996cac7a786165f6084d24 отдельно скомпилирован в изолированный каталог: probe воспроизвёл неверную классификацию paired BLOCK_INTERACT; текущий код подтвердил исправленную и optional after-only. Основные compiled classes и чужие JAR при этом не заменялись.

Код/тесты: a955fd7bc1f33fe522d3b427a0bbdd3a70281137, дерево d2919384149c310268bc023e2625b7d2c9754d0b. Локально прошли compileJava, compileCreateSmokeJava, testAsyncPipeline, testExactModpackPreflight (157 контрактов / 103 предоставленных target-класса), verifyMixinSafety, native runServer и инструменты инвентаризации/сравнения профилей. [Итоговая матрица и настоящая ClickHouse](https://github.com/Vlad-Ras/Logger/actions/runs/40357477062), [GUI](https://github.com/Vlad-Ras/Logger/actions/runs/40357477018). Все четыре серверных профиля, настоящая ClickHouse и GUI завершились успешно на указанной ревизии. После неё изменена только документация.

## Открытые границы

Не объявлены проверенными все 1200+ блоков/рецептов, неоднозначные/перекрывающиеся группы коробок или смена/удаление корня при открытом меню, непринятые варианты/совместные действия блоков, join gifts/trader/автоматизация или весь модпак. Общий snapshot/штатное событие не заменяет отдельную native-приёмку. Нет новых MSPT/heap/allocations, клиентского профиля или подтверждения устранения 60 000 мкс/тик на ферме Create. Native-блокер manual stop Scguns остаётся открытым. Релизный JAR не подготовлен.
