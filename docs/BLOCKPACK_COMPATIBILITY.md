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

## Открытые границы

Не объявлены проверенными все 1200+ блоков/рецептов, все кластеры коробок, aquarium/lantern/flag/weather-vane, массовый wrench, join gifts/trader/автоматизация или весь модпак. Общий snapshot/штатное событие не заменяет отдельную native-приёмку. Нет новых MSPT/heap/allocations, клиентского профиля или подтверждения устранения 60 000 мкс/тик на ферме Create. Native-блокер manual stop Scguns остаётся открытым. Релизный JAR не подготовлен.
